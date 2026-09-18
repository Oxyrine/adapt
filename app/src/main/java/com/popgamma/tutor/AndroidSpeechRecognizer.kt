package com.popgamma.tutor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Wraps Android's built-in SpeechRecognizer -- the same on-device/cloud recognition backend
 * Chrome's Web Speech API calls into on Android, and the actual reason voice answers transcribe
 * far better there than through this app's raw-audio-upload-to-Whisper path (Groq.kt). This isn't
 * a tuning difference; it's a different, purpose-built live speech engine.
 *
 * No word-level timestamps are available from this API (Android added word timing only on API 33+
 * and it's inconsistent across OEMs), so the caller synthesizes per-word timing from total elapsed
 * time -- exactly what the web app's own Web Speech path already does (see App.tsx's
 * live-transcription branch) rather than something new invented for this file.
 */
class AndroidSpeechRecognizer(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** [onFinalResult] fires with the recognized text (possibly blank) once the engine decides
     *  the student is done speaking, or after [stop] is called early. [onError] fires only for a
     *  real failure (engine unavailable, permission, network) -- a plain "didn't hear anything" is
     *  reported as a blank result, not an error, so the caller can treat both no-speech paths the
     *  same way it already treats an empty transcript. */
    fun start(onFinalResult: (String) -> Unit, onError: () -> Unit) {
        stop()
        if (!isAvailable()) {
            onError()
            return
        }
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // No speech / a natural timeout isn't a failure -- it's the same "didn't catch an
                // actual answer" case the transcript-content guard already handles downstream.
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    onFinalResult("")
                } else {
                    onError()
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                onFinalResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        rec.startListening(intent)
    }

    /** Asks the engine to finish now with whatever it has heard so far -- it still reports back
     *  through the [start] callbacks asynchronously, same as reaching natural silence on its own. */
    fun stop() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
    }
}
