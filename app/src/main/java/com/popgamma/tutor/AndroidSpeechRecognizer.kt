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
 * One instance is created and reused for the app's lifetime. An earlier version of this class
 * created a fresh SpeechRecognizer and destroyed it on every single turn, which doesn't give the
 * underlying "Speech Recognition & Synthesis from Google" service (the same package that also
 * provides system TTS) time to release its session between calls -- hit live as a repeating
 * "cannot record right now" system message during a run of several voice turns in a row. Reusing
 * one instance and calling startListening() again per turn, which is the pattern Android's own
 * docs describe, is what actually avoids that conflict; recreating the object was the bug, not a
 * timing value to tune.
 *
 * No word-level timestamps are available from this API (Android added word timing only on API 33+
 * and it's inconsistent across OEMs), so the caller synthesizes per-word timing from total elapsed
 * time -- exactly what the web app's own Web Speech path already does (see App.tsx's
 * live-transcription branch) rather than something new invented for this file.
 */
class AndroidSpeechRecognizer(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var onFinalResult: ((String) -> Unit)? = null
    private var onFailure: (() -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** [onFinalResult] fires with the recognized text (possibly blank) once the engine decides
     *  the student is done speaking, or after [finish] is called early. [onError] fires only for a
     *  real failure (engine unavailable, permission, service busy) -- a plain "didn't hear
     *  anything" is reported as a blank result, not an error, so the caller can treat both no-
     *  speech paths the same way it already treats an empty transcript. */
    fun start(onFinalResult: (String) -> Unit, onError: () -> Unit) {
        if (!isAvailable()) {
            onError()
            return
        }
        this.onFinalResult = onFinalResult
        this.onFailure = onError

        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            this.onFinalResult = null
            this.onFailure = null
            onError()
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            val finalResult = onFinalResult
            val failure = onFailure
            onFinalResult = null
            onFailure = null
            // No speech / a natural timeout isn't a failure -- it's the same "didn't catch an
            // actual answer" case the transcript-content guard already handles downstream.
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                finalResult?.invoke("")
            } else {
                failure?.invoke()
            }
        }

        override fun onResults(results: Bundle?) {
            val finalResult = onFinalResult
            onFinalResult = null
            onFailure = null
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            finalResult?.invoke(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Asks the engine to finish now with whatever it has heard so far -- it still reports back
     *  through the [start] callbacks asynchronously, same as reaching natural silence on its own.
     *  Does not tear down the recognizer -- see class kdoc for why reuse matters. */
    fun finish() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {}
    }

    /** Releases the underlying connection entirely. Call only when the app is shutting down, not
     *  between turns. */
    fun release() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = null
        onFinalResult = null
        onFailure = null
    }
}
