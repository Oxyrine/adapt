package com.popgamma.tutor

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Records raw 16-bit mono PCM at 16kHz until [stopAndGetPcm] is called.
 *
 * Finding 3 (plan): the caller must call [start] the moment Albert's turn ends, not when the
 * student taps a mic button. Latency (spec Section 3a) is defined as "time between Albert
 * finishing and the student starting to speak" -- under push-to-talk, the student's thinking time
 * all happens before recording starts, so the first word always lands near t=0 and the signal
 * reads as maximally confident regardless of reality. Auto-arming means the clip's leading
 * silence *is* the measurement; the student taps Done only to stop.
 */
class MicRecorder(private val onSilenceDetected: (() -> Unit)? = null) {
    private val sampleRate = 16000
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    @Volatile
    private var recording = false

    /** True once any chunk of this recording crossed [speechRmsThreshold]. Lets the caller tell a
     *  clip that's mostly/entirely silence apart from what it actually captured -- sending a
     *  near-silent clip to Whisper is exactly what makes it hallucinate boilerplate ("you",
     *  "Thank you.") instead of failing honestly. */
    @Volatile
    var didDetectSpeech: Boolean = false
        private set

    // Voice Activity Detection (VAD) parameters
    private var speechDetected = false
    private var lastSpeechTimeMs = 0L
    private val speechRmsThreshold = 500.0 // Speech detection amplitude threshold
    private val silenceDurationMs = 900L // 900ms of silence after speaking triggers prompt auto-stop
    private val minSpeechDurationMs = 300L
    private var speechStartTimeMs = 0L

    // Caller must have already checked RECORD_AUDIO permission before constructing/starting this
    // (see MainActivity's permission gate) -- that's the actual guard, this annotation just
    // silences the lint warning about the unchecked call site.
    @Suppress("MissingPermission")
    fun start() {
        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBufSize > 0) minBufSize else sampleRate // fallback if the device query fails
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )
        audioRecord?.startRecording()
        recording = true
        speechDetected = false
        didDetectSpeech = false
        lastSpeechTimeMs = 0L
        speechStartTimeMs = 0L

        recordingThread = Thread {
            val chunk = ByteArray(bufSize)
            while (recording) {
                val read = audioRecord?.read(chunk, 0, chunk.size) ?: -1
                if (read > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, read) }

                    if (onSilenceDetected != null) {
                        val rms = calculateRms(chunk, read)
                        val now = System.currentTimeMillis()
                        if (rms > speechRmsThreshold) {
                            didDetectSpeech = true
                            if (!speechDetected) {
                                speechDetected = true
                                speechStartTimeMs = now
                            }
                            lastSpeechTimeMs = now
                        } else if (speechDetected && (now - speechStartTimeMs > minSpeechDurationMs)) {
                            if (now - lastSpeechTimeMs > silenceDurationMs) {
                                // Silence detected after speech -- auto-stop turn!
                                recording = false
                                Thread {
                                    onSilenceDetected.invoke()
                                }.start()
                                break
                            }
                        }
                    }
                }
            }
        }.also { it.start() }
    }

    private fun calculateRms(pcm: ByteArray, len: Int): Double {
        var sum = 0.0
        val sampleCount = len / 2
        for (i in 0 until len step 2) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val shortVal = sample.toShort().toDouble()
            sum += shortVal * shortVal
        }
        return if (sampleCount > 0) Math.sqrt(sum / sampleCount) else 0.0
    }

    fun stopAndGetPcm(): ByteArray {
        recording = false
        if (recordingThread != null && Thread.currentThread() != recordingThread) {
            try {
                recordingThread?.join(500)
            } catch (_: InterruptedException) {}
        }
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        return synchronized(buffer) { buffer.toByteArray() }
    }
}
