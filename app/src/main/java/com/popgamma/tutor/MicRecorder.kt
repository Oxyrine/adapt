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
class MicRecorder {
    private val sampleRate = 16000
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    @Volatile
    private var recording = false

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
        recordingThread = Thread {
            val chunk = ByteArray(bufSize)
            while (recording) {
                val read = audioRecord?.read(chunk, 0, chunk.size) ?: -1
                if (read > 0) {
                    synchronized(buffer) { buffer.write(chunk, 0, read) }
                }
            }
        }.also { it.start() }
    }

    fun stopAndGetPcm(): ByteArray {
        recording = false
        recordingThread?.join(500)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        return synchronized(buffer) { buffer.toByteArray() }
    }
}
