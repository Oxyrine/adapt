package com.popgamma.tutor

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

// -- Request/response DTOs for POST https://generativelanguage.googleapis.com/v1beta/interactions.
// Both the chat call and the transcription call hit this one endpoint with different `model`
// values -- the spec's "audio understanding endpoint" phrasing (Section 7) suggested a separate
// endpoint; it isn't one. See plan Finding 2.

@Serializable
data class ContentPart(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    @SerialName("mime_type") val mimeType: String? = null
)

@Serializable
data class TranscriptionMode(
    val type: String = "verbatim", // must stay verbatim -- see plan Finding 1
    @SerialName("timestamp_granularities") val timestampGranularities: List<String> = listOf("word")
)

@Serializable
data class TranscriptionConfig(
    @SerialName("mode") val mode: TranscriptionMode = TranscriptionMode()
    // Deliberately no custom_vocabulary field here. Docs disagree with each other about whether
    // custom_vocabulary can combine with word timestamps (one page says no, the endpoint reference
    // lists them as coexisting); a live API bug report confirms the API enforces the stricter
    // reading and rejects the combination at request time. If vocabulary biasing is ever added,
    // expect that rejection -- it's a known API constraint, not a bug in this code.
)

@Serializable
data class GenerationConfig(
    @SerialName("transcription_config") val transcriptionConfig: TranscriptionConfig? = null
)

@Serializable
data class InteractionRequest(
    val model: String,
    val input: List<ContentPart>,
    @SerialName("generation_config") val generationConfig: GenerationConfig? = null
)

// NOTE: the exact response shape for word-level timestamps wasn't in the docs surfaced during
// planning (only the request shape was confirmed). This mapping is best-effort and unverified
// against a live response -- if transcribeWithTimestamps() comes back with an empty word list
// where the docs' request shape looks right, check this DTO against the actual response body
// first, per the diagnostic order in the plan's verification section.
@Serializable
data class InteractionResponse(
    @SerialName("output_text") val outputText: String? = null,
    val output: List<TranscriptOutputItem>? = null
)

@Serializable
data class TranscriptOutputItem(val words: List<TranscriptWord>? = null)

@Serializable
data class TranscriptWord(
    val text: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long
)

sealed class GeminiResult<out T> {
    data class Success<T>(val value: T) : GeminiResult<T>()
    data class Failure(val message: String) : GeminiResult<Nothing>()
}

object GeminiClient {
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
    private const val CHAT_MODEL = "gemini-3.8-flash"
    private const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe"
    private const val TIMEOUT_SECONDS = 12L // build-now network-failure path: never hang the demo

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun chat(apiKey: String, prompt: String): GeminiResult<String> = withContext(Dispatchers.IO) {
        try {
            val body = InteractionRequest(model = CHAT_MODEL, input = listOf(ContentPart(type = "text", text = prompt)))
            val responseBody = post(apiKey, body)
            val text = json.decodeFromString<InteractionResponse>(responseBody).outputText
            if (text.isNullOrBlank()) GeminiResult.Failure("Empty response from tutor model") else GeminiResult.Success(text)
        } catch (e: Exception) {
            GeminiResult.Failure(e.message ?: "Network error")
        }
    }

    suspend fun transcribeWithTimestamps(apiKey: String, wavBase64: String): GeminiResult<List<Word>> =
        withContext(Dispatchers.IO) {
            try {
                val body = InteractionRequest(
                    model = TRANSCRIBE_MODEL,
                    input = listOf(ContentPart(type = "audio", data = wavBase64, mimeType = "audio/wav")),
                    generationConfig = GenerationConfig(transcriptionConfig = TranscriptionConfig())
                )
                val responseBody = post(apiKey, body)
                val words = json.decodeFromString<InteractionResponse>(responseBody)
                    .output?.firstOrNull()?.words
                    ?.map { Word(it.text, it.startMs, it.endMs) }
                    ?: emptyList()
                GeminiResult.Success(words)
            } catch (e: Exception) {
                GeminiResult.Failure(e.message ?: "Network error")
            }
        }

    private fun post(apiKey: String, body: InteractionRequest): String {
        val payload = json.encodeToString(InteractionRequest.serializer(), body)
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: ${resp.message}")
            return resp.body?.string() ?: throw IOException("Empty response body")
        }
    }
}

/** Prepends a 44-byte WAV header to raw 16-bit mono PCM and base64-encodes it -- inline audio
 *  (spec Section 7 / plan Finding 2), so a ~10s answer needs no Files API upload step. */
object WavEncoder {
    fun pcmToWavBase64(pcm: ByteArray, sampleRate: Int = 16000, channels: Int = 1, bitsPerSample: Int = 16): String {
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
        return Base64.encodeToString(header + pcm, Base64.NO_WRAP)
    }
}
