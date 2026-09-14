package com.popgamma.tutor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

// Groq's OpenAI-compatible API, replacing Gemini. Chat: POST /openai/v1/chat/completions.
// Transcription: POST /openai/v1/audio/transcriptions (multipart, Whisper) with word-level
// timestamps via response_format=verbose_json + timestamp_granularities[]=word -- the same signal
// Confidence.kt needs, returned as plain float seconds instead of Gemini's "0.100s" strings.
//
// ponytail: not yet live-tested against a real key. Gemini's actual response shape differed from
// its docs (plan Finding 2) and only got caught by real curl calls -- verify this one the same way
// before trusting it in a demo.

@Serializable
data class GroqMessage(val role: String, val content: String)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<GroqMessage>,
    val temperature: Float = 0.7f,
    @SerialName("max_tokens") val maxTokens: Int = 250
)

@Serializable
data class ChatResponseMessage(val content: String? = null)

@Serializable
data class ChatChoice(val message: ChatResponseMessage? = null)

@Serializable
data class ChatCompletionResponse(val choices: List<ChatChoice>? = null) {
    fun text(): String? = choices?.firstOrNull()?.message?.content?.trim()?.ifBlank { null }
}

@Serializable
data class TranscriptionWord(val word: String, val start: Double, val end: Double)

@Serializable
data class TranscriptionResponse(val words: List<TranscriptionWord>? = null) {
    fun wordList(): List<Word> =
        words.orEmpty().map { Word(it.word.trim(), (it.start * 1000).toLong(), (it.end * 1000).toLong()) }
}

sealed class GroqResult<out T> {
    data class Success<T>(val value: T) : GroqResult<T>()
    data class Failure(val message: String) : GroqResult<Nothing>()
}

object GroqClient {
    private const val CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
    private const val TRANSCRIBE_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    // Two guessed llama-3.x model ids both 404'd (model_not_found) -- this key's account has no
    // llama-3.x models at all. Confirmed via GET /openai/v1/models against the real key: this is
    // one of the ids that actually came back, not another guess.
    private const val CHAT_MODEL = "openai/gpt-oss-20b"
    private const val TRANSCRIBE_MODEL = "whisper-large-v3" // word timestamps need the full model, not -turbo
    // ponytail: no live latency measurement for Groq yet (Gemini's 60s came from a real measured
    // 8.5-15.7s + emulator jitter). 30s is a conservative placeholder -- tighten once measured.
    private const val TIMEOUT_SECONDS = 30L

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun chat(apiKey: String, prompt: String): GroqResult<String> = withContext(Dispatchers.IO) {
        try {
            val body = ChatCompletionRequest(model = CHAT_MODEL, messages = listOf(GroqMessage("user", prompt)))
            val payload = json.encodeToString(ChatCompletionRequest.serializer(), body)
            val responseBody = postWithRetry(apiKey, CHAT_URL, payload.toRequestBody("application/json".toMediaType()))
            val text = json.decodeFromString<ChatCompletionResponse>(responseBody).text()
            if (text == null) GroqResult.Failure("Empty response from tutor model") else GroqResult.Success(text)
        } catch (e: Exception) {
            GroqResult.Failure(e.message ?: "Network error")
        }
    }

    suspend fun transcribeWithTimestamps(apiKey: String, wavBytes: ByteArray): GroqResult<List<Word>> =
        withContext(Dispatchers.IO) {
            try {
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", TRANSCRIBE_MODEL)
                    .addFormDataPart("response_format", "verbose_json")
                    .addFormDataPart("timestamp_granularities[]", "word")
                    .addFormDataPart("file", "audio.wav", wavBytes.toRequestBody("audio/wav".toMediaType()))
                    .build()
                val responseBody = postWithRetry(apiKey, TRANSCRIBE_URL, multipart)
                GroqResult.Success(json.decodeFromString<TranscriptionResponse>(responseBody).wordList())
            } catch (e: Exception) {
                GroqResult.Failure(e.message ?: "Network error")
            }
        }

    // Carries the HTTP status code so postWithRetry can tell a transient server error (5xx,
    // worth retrying once) from a client error (4xx, retrying won't help).
    private class HttpException(val code: Int, message: String) : IOException(message)

    private fun postWithRetry(apiKey: String, url: String, body: RequestBody): String =
        try {
            post(apiKey, url, body)
        } catch (e: HttpException) {
            if (e.code in 500..599) post(apiKey, url, body) else throw e
        }

    private fun post(apiKey: String, url: String, body: RequestBody): String {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val responseText = resp.body?.string()
            if (!resp.isSuccessful) {
                if (resp.code == 429) {
                    // Same class of issue hit live against Gemini's free tier -- retrying wastes
                    // another call, so report plainly and point at the offline fixtures.
                    throw HttpException(
                        429,
                        "API quota exceeded for this key. Wait for it to reset or check your plan, " +
                            "or use the \"Offline sample\" toggle to keep testing without live calls."
                    )
                }
                throw HttpException(resp.code, "HTTP ${resp.code}: ${responseText?.take(300) ?: "(no body)"}")
            }
            return responseText ?: throw IOException("Empty response body")
        }
    }
}

/** Prepends a 44-byte WAV header to raw 16-bit mono PCM -- inline multipart upload (Groq's
 *  transcription endpoint takes a file, not base64-in-JSON like Gemini did), so a ~10s answer
 *  needs no separate upload step either way. */
object WavEncoder {
    fun pcmToWav(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
        return header + pcm
    }
}
