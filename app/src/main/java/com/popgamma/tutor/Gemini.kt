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

// Response shape confirmed against the live API (not the docs, which never showed a full response
// body) with real curl calls during setup -- both a chat call and a transcription call with word
// timestamps enabled. There is no output_text or output field at all: everything lives under
// steps[], each step typed "thought" (skip) or "model_output" (keep), each content item carrying
// plain text and, for transcription, a word_info annotation per word. Word timing comes as a
// STRING like "0.100s" or "1s" -- not an integer millisecond count as the request-shape docs would
// suggest by analogy -- see parseOffsetToMs below.
@Serializable
data class WordAnnotation(
    val text: String,
    @SerialName("start_offset") val startOffset: String? = null,
    @SerialName("end_offset") val endOffset: String? = null,
    val type: String? = null
)

@Serializable
data class InteractionStepContent(
    val type: String? = null,
    val text: String? = null,
    val annotations: List<WordAnnotation>? = null
)

@Serializable
data class InteractionStep(
    val type: String? = null,
    val content: List<InteractionStepContent>? = null
)

@Serializable
data class InteractionResponse(val steps: List<InteractionStep>? = null) {
    /** Concatenated text from every "model_output" step -- "thought" steps are reasoning, not the
     *  reply, and are deliberately skipped. */
    fun modelOutputText(): String? =
        steps?.filter { it.type == "model_output" }
            ?.flatMap { it.content.orEmpty() }
            ?.mapNotNull { it.text }
            ?.joinToString("")
            ?.ifBlank { null }

    /** Word-level timestamps for transcription calls -- lives inside content[].annotations[],
     *  filtered to type "word_info" (guards against other annotation kinds appearing later). */
    fun wordList(): List<Word> =
        steps?.filter { it.type == "model_output" }
            ?.flatMap { it.content.orEmpty() }
            ?.flatMap { it.annotations.orEmpty() }
            ?.filter { it.type == "word_info" }
            ?.map { Word(it.text, parseOffsetToMs(it.startOffset), parseOffsetToMs(it.endOffset)) }
            ?: emptyList()
}

/** "0.100s" / "1s" / "1.900s" -> milliseconds. Malformed or missing offsets fall back to 0 rather
 *  than crashing the whole transcript on one bad field. */
private fun parseOffsetToMs(offset: String?): Long {
    val seconds = offset?.removeSuffix("s")?.toDoubleOrNull() ?: return 0L
    return (seconds * 1000).toLong()
}

sealed class GeminiResult<out T> {
    data class Success<T>(val value: T) : GeminiResult<T>()
    data class Failure(val message: String) : GeminiResult<Nothing>()
}

object GeminiClient {
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/interactions"
    private const val CHAT_MODEL = "gemini-3.8-flash"
    private const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe"
    // Verified live: this model spends real time on internal "thinking" tokens before replying
    // (90 thought tokens for a one-word reply in testing) -- 12s was cutting off normal model
    // latency, not just genuine network failures, causing spurious timeouts mid-demo.
    private const val TIMEOUT_SECONDS = 30L // build-now network-failure path: never hang forever

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun chat(apiKey: String, prompt: String): GeminiResult<String> = withContext(Dispatchers.IO) {
        try {
            val body = InteractionRequest(model = CHAT_MODEL, input = listOf(ContentPart(type = "text", text = prompt)))
            val responseBody = postWithRetry(apiKey, body)
            val text = json.decodeFromString<InteractionResponse>(responseBody).modelOutputText()
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
                val responseBody = postWithRetry(apiKey, body)
                val words = json.decodeFromString<InteractionResponse>(responseBody).wordList()
                GeminiResult.Success(words)
            } catch (e: Exception) {
                GeminiResult.Failure(e.message ?: "Network error")
            }
        }

    // Carries the HTTP status code so postWithRetry can tell a transient server error (5xx,
    // worth retrying once) from a client error (4xx, retrying won't help).
    private class HttpException(val code: Int, message: String) : IOException(message)

    /** One retry for a transient 5xx only -- found necessary after a live HTTP 500 that could not
     *  be reproduced (a healthy re-send of the same prompt shape succeeded), suggesting a one-off
     *  server-side blip rather than a request problem. Not retried for 4xx: a bad request stays
     *  bad on a second try. */
    private fun postWithRetry(apiKey: String, body: InteractionRequest): String =
        try {
            post(apiKey, body)
        } catch (e: HttpException) {
            if (e.code in 500..599) post(apiKey, body) else throw e
        }

    private fun post(apiKey: String, body: InteractionRequest): String {
        val payload = json.encodeToString(InteractionRequest.serializer(), body)
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            val responseText = resp.body?.string()
            if (!resp.isSuccessful) {
                // Previously discarded the response body entirely -- an error surfaced as just
                // "HTTP 500:" with nothing after the colon (OkHttp's reason phrase is often empty
                // for HTTP/2 responses) and no way to tell why. Truncated to keep the error banner
                // readable.
                throw HttpException(resp.code, "HTTP ${resp.code}: ${responseText?.take(300) ?: "(no body)"}")
            }
            return responseText ?: throw IOException("Empty response body")
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
