package com.popgamma.tutor

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Provides Text-To-Speech (TTS) voice modulation for Prof. Albert.
 *
 * Tone Modulation:
 * - Struggling students receive a slower (0.88x), patient, reassuring (0.95x pitch) voice.
 * - Strong students receive a brisk (1.10x), energetic, conversational voice.
 * - High encouragement lifts vocal pitch slightly (+5%) for audible warmth.
 */
class AlbertVoice(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            try {
                val voices = tts?.voices
                val bestVoice = voices?.firstOrNull { 
                    it.locale.language == "en" && (it.name.contains("network", ignoreCase = true) || it.name.contains("natural", ignoreCase = true) || it.name.contains("en-us-x", ignoreCase = true)) 
                } ?: voices?.firstOrNull { it.locale == Locale.US }
                if (bestVoice != null) {
                    tts?.voice = bestVoice
                }
            } catch (_: Exception) {}
            ready = true
        }
    }

    fun speak(text: String, profile: ToneProfile?, onDone: (() -> Unit)? = null) {
        if (!ready || tts == null) {
            onDone?.invoke()
            return
        }

        // Strip markdown and parenthetical meta-text
        val cleanText = text
            .replace(Regex("""\*\*|\*|#|`"""), "")
            .replace(Regex("""\([^)]*offline[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (cleanText.isEmpty()) {
            onDone?.invoke()
            return
        }

        val (rate, pitch) = when (profile?.structure) {
            StructureLevel.TIGHT -> 0.90f to 0.97f // Patient, measured pace
            StructureLevel.MEDIUM -> 1.0f to 1.0f
            StructureLevel.LOOSE, null -> 1.12f to 1.04f // Brisk, lively pace
        }

        // Higher encouragement adds warmth to the pitch
        val finalPitch = if (profile?.encouragement == EncouragementLevel.HIGH) pitch * 1.05f else pitch

        val utteranceId = "albert_speech_${System.currentTimeMillis()}"
        if (onDone != null) {
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) onDone.invoke()
                }
                override fun onError(id: String?) {
                    if (id == utteranceId) onDone.invoke()
                }
            })
        }

        tts?.setSpeechRate(rate)
        tts?.setPitch(finalPitch)
        tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
