package com.popgamma.tutor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val fromAlbert: Boolean, val text: String)

/** What the metrics strip shows for the last voice answer -- the raw transcript is included
 *  deliberately (see plan's diagnostic order): if a demo answer routes wrong, the transcript is
 *  what tells you whether to suspect Gemini's transcription or the scorer's thresholds. */
data class ConfidenceReadout(
    val transcript: String,
    val signals: ConfidenceSignals,
    val band: ConfidenceBand,
    val correct: Boolean,
    val triggeredFollowUp: Boolean
)

enum class MicState { IDLE, ARMED, RECORDING, PROCESSING }

data class TutorUiState(
    val performance: Performance? = null,
    val regularity: Regularity? = null,
    val messages: List<ChatMessage> = emptyList(),
    val sessionMetrics: SessionMetrics = SessionMetrics(),
    val snapshots: List<MetricsSnapshot> = emptyList(),
    val voiceMode: Boolean = false,
    val micState: MicState = MicState.IDLE,
    val currentBankQuestion: BankQuestion? = null,
    val lastConfidenceReadout: ConfidenceReadout? = null,
    val offlineMode: Boolean = false,
    val error: String? = null,
    val busy: Boolean = false
) {
    val toneProfile: ToneProfile?
        get() {
            val perf = performance ?: return null
            val reg = regularity ?: return null
            return ToneTable.lookup(perf, reg)
        }
}

class TutorViewModel(initialApiKey: String) : ViewModel() {
    // var, not val -- a downloaded APK has no build-time local.properties key baked in, so this
    // needs to be settable at runtime from a UI the user can actually reach after install.
    // ApiKeyStore.save() persists it; this field is just what GroqClient calls actually use.
    var apiKey: String = initialApiKey
    private val _state = MutableStateFlow(TutorUiState())
    val state: StateFlow<TutorUiState> = _state

    private var recorder: MicRecorder? = null
    var voiceEngine: AlbertVoice? = null
    var speechRecognizer: AndroidSpeechRecognizer? = null

    // Which capture path the current turn is using -- set in armMic(), read by
    // stopVoiceTurnAndScore() so the "Done" button drives whichever one is actually running.
    private var usingNativeRecognition = false
    private var recordingStartTimeMs = 0L

    fun selectProfile(performance: Performance, regularity: Regularity) {
        // A profile switch can happen mid voice-turn (e.g. navigating back to Profiles without
        // finishing one) -- leaving mic/recognizer state pointing at the old turn means a result
        // that arrives after the switch gets silently dropped, or worse, scored against whatever
        // question happens to still be set. Tear down anything in flight before starting clean.
        cleanupActiveVoiceTurn()
        _state.update {
            it.copy(
                performance = performance,
                regularity = regularity,
                messages = emptyList(),
                sessionMetrics = SessionMetrics(),
                lastConfidenceReadout = null,
                currentBankQuestion = null,
                micState = MicState.IDLE,
                error = null
            )
        }
    }

    private fun cleanupActiveVoiceTurn() {
        voiceEngine?.stop()
        usingNativeRecognition = false
        speechRecognizer?.finish()
        recorder?.stopAndGetPcm()
        recorder = null
    }

    fun sendText(userText: String) {
        if (userText.isBlank()) return
        val profile = _state.value.toneProfile ?: return
        _state.update {
            it.copy(messages = it.messages + ChatMessage(fromAlbert = false, text = userText), busy = true, error = null)
        }
        viewModelScope.launch {
            try {
                val prompt = PromptBuilder.buildPrompt(profile, addendum = null, history = conversationContext(), userText = userText)
                when (val result = GroqClient.chat(apiKey, prompt)) {
                    is GroqResult.Success -> applyReply(result.value)
                    is GroqResult.Failure -> {
                        _state.update { it.copy(error = "${result.message} -- showing an offline reply") }
                        applyReply(FallbackReplies.reply(profile))
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = "${e.message ?: "Unexpected error"} -- showing an offline reply") }
                applyReply(FallbackReplies.reply(profile))
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** Auto-arms the mic immediately -- see MicRecorder's kdoc and plan Finding 3. Must be called
     *  the moment Albert's turn (the question) finishes, not on a later user tap. */
    fun startVoiceTurn(question: BankQuestion) {
        if (_state.value.micState != MicState.IDLE) return
        // Put Albert's question into the transcript -- previously the question bank was just a
        // silent picker and Albert never actually "asked" anything, which is most of why voice
        // mode didn't read as a conversation. Not scored/scanned as a real turn (it's a fixed
        // prompt, not a model reply), so it doesn't skew sessionMetrics.
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(fromAlbert = true, text = question.prompt),
                currentBankQuestion = question,
                micState = MicState.RECORDING,
                error = null
            )
        }
        val voice = voiceEngine
        if (voice != null) {
            voice.speak(question.prompt, _state.value.toneProfile) {
                // Android's TTS onDone can fire slightly before the speaker actually finishes
                // playing, and the mic has no echo cancellation (plain MIC source, not
                // VOICE_COMMUNICATION). A short buffer keeps Albert's own voice tail from bleeding
                // into the start of the recording -- small enough (well under the 1500ms latency
                // threshold in Confidence.kt) that it doesn't skew the confidence measurement
                // Finding 3 depends on.
                viewModelScope.launch {
                    delay(MIC_ARM_DELAY_MS)
                    armMic()
                }
            }
        } else {
            armMic()
        }
    }

    /** Prefers Android's own SpeechRecognizer (the live engine behind Chrome's Web Speech API on
     *  this platform, and the reason answers transcribe far better there than through a raw-audio
     *  upload to Whisper). Falls back to the raw-audio MicRecorder + Groq Whisper path only when
     *  the device genuinely has no speech recognition service available. */
    private fun armMic() {
        val recognizer = speechRecognizer
        if (recognizer != null && recognizer.isAvailable()) {
            usingNativeRecognition = true
            recordingStartTimeMs = System.currentTimeMillis()
            recognizer.start(
                onFinalResult = { transcript -> onNativeSpeechResult(transcript) },
                onError = {
                    // A real engine failure for this turn only -- fall back to raw-audio capture
                    // rather than leaving the student stuck with an armed mic that never responds.
                    usingNativeRecognition = false
                    recorder = MicRecorder(onSilenceDetected = { stopVoiceTurnAndScore() }).also { it.start() }
                }
            )
        } else {
            usingNativeRecognition = false
            recorder = MicRecorder(onSilenceDetected = { stopVoiceTurnAndScore() }).also { it.start() }
        }
    }

    /** Bound to the "Done" button. Dispatches to whichever capture path [armMic] actually started. */
    fun stopVoiceTurnAndScore() {
        if (usingNativeRecognition) {
            // Asks the recognizer to finish with whatever it heard so far; onNativeSpeechResult
            // carries the turn the rest of the way through, asynchronously, same as if the
            // recognizer had reached natural silence on its own.
            speechRecognizer?.finish()
            return
        }
        val question = _state.value.currentBankQuestion ?: return
        val pcm = recorder?.stopAndGetPcm()
        val hadDetectedSpeech = recorder?.didDetectSpeech ?: false
        recorder = null
        // Clear any stale error from an earlier failed attempt -- otherwise a leftover "timeout"
        // banner can sit on screen indefinitely even after this turn succeeds.
        _state.update { it.copy(micState = MicState.PROCESSING, busy = true, error = null) }
        viewModelScope.launch {
            try {
                // If the mic never picked up anything loud enough to register as speech, don't
                // even send the clip to Whisper -- a near-silent clip is exactly what makes it
                // hallucinate boilerplate ("you", "Thank you.") instead of failing honestly, and
                // no phrase blocklist can keep up with every phrase it might invent. Skipped when
                // offline (there's no live mic clip to judge) or the sample toggle is on.
                val skipTranscription = !_state.value.offlineMode && pcm != null && pcm.isNotEmpty() && !hadDetectedSpeech
                if (skipTranscription) {
                    _state.update { it.copy(error = "Didn't catch an actual answer -- try again.") }
                    return@launch
                }
                val words = resolveWords(question, pcm)
                completeVoiceTurn(question, words)
            } catch (e: Exception) {
                val profile = _state.value.toneProfile
                if (profile != null) {
                    _state.update { it.copy(error = "${e.message ?: "Voice processing error"} -- showing an offline reply") }
                    applyReply(FallbackReplies.reply(profile))
                }
            } finally {
                _state.update { it.copy(busy = false, micState = MicState.IDLE, currentBankQuestion = null) }
            }
        }
    }

    /** Callback from [AndroidSpeechRecognizer] -- fires once it decides the student is done
     *  speaking (naturally, or because [stopVoiceTurnAndScore] asked it to finish early). */
    private fun onNativeSpeechResult(transcript: String) {
        val question = _state.value.currentBankQuestion ?: return
        usingNativeRecognition = false
        _state.update { it.copy(micState = MicState.PROCESSING, busy = true, error = null) }
        viewModelScope.launch {
            try {
                // A genuinely blank result (mic heard nothing) is not the same as the student
                // asking for the offline sample -- silently substituting a canned "it's eleven"
                // for it means an answer nobody gave gets scored and replied to as if it were
                // real. Only the explicit toggle should ever reach for the fixture.
                if (!_state.value.offlineMode && transcript.isBlank()) {
                    _state.update { it.copy(error = "Didn't catch an actual answer -- try again.") }
                    return@launch
                }
                // No word-level timestamps from this API (see AndroidSpeechRecognizer's kdoc) --
                // synthesize per-word timing from total elapsed time, identical to the web app's
                // own Web Speech path (App.tsx's live-transcription branch).
                val elapsedMs = (System.currentTimeMillis() - recordingStartTimeMs).coerceAtLeast(300L)
                val words = if (_state.value.offlineMode) {
                    offlineFixtureFor(question)
                } else {
                    wordsFromTranscript(transcript, elapsedMs)
                }
                completeVoiceTurn(question, words)
            } catch (e: Exception) {
                val profile = _state.value.toneProfile
                if (profile != null) {
                    _state.update { it.copy(error = "${e.message ?: "Voice processing error"} -- showing an offline reply") }
                    applyReply(FallbackReplies.reply(profile))
                }
            } finally {
                _state.update { it.copy(busy = false, micState = MicState.IDLE, currentBankQuestion = null) }
            }
        }
    }

    private fun wordsFromTranscript(transcript: String, elapsedMs: Long): List<Word> {
        val tokens = transcript.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val wordDuration = (elapsedMs / tokens.size.coerceAtLeast(1)).coerceIn(150L, 600L)
        val latencyMs = (elapsedMs - tokens.size * wordDuration).coerceAtLeast(200L)
        return tokens.mapIndexed { idx, tok ->
            Word(tok, latencyMs + idx * wordDuration, latencyMs + (idx + 1) * wordDuration)
        }
    }

    /** Shared tail of both capture paths: the punctuation/hallucination guard, then scoring. */
    private suspend fun completeVoiceTurn(question: BankQuestion, words: List<Word>) {
        // A transcript that's pure punctuation/noise (misheard silence coming back as ".") or a
        // hallucination that slipped past the speech-detection gate has no real content to score
        // or reply to. Without this guard it still reaches the scorer (whose signals all read as
        // zero, i.e. falsely "confident") and the LLM, which then improvises a reply disconnected
        // from what was actually asked.
        if (!looksLikeRealAnswer(words)) {
            // resolveWords may already have set a more specific message (e.g. a transcription API
            // failure) -- keep that instead of overwriting it with the generic one.
            _state.update { it.copy(error = it.error ?: "Didn't catch an actual answer -- try again.") }
            return
        }
        scoreAndRespond(question, words)
    }

    private suspend fun resolveWords(question: BankQuestion, pcm: ByteArray?): List<Word> {
        if (_state.value.offlineMode) {
            return offlineFixtureFor(question)
        }
        if (pcm == null || pcm.isEmpty()) {
            // No audio was actually captured -- this is "no answer", not a fabricated one. Only
            // the explicit Offline Sample toggle above should ever reach for the fixture.
            return emptyList()
        }
        val wavBytes = WavEncoder.pcmToWav(pcm)
        return when (val result = GroqClient.transcribeWithTimestamps(apiKey, wavBytes)) {
            is GroqResult.Success -> result.value
            is GroqResult.Failure -> {
                // A failed transcription call is a real error worth surfacing, not something to
                // silently paper over by answering on the student's behalf with a canned wrong
                // answer ("it's eleven") that has nothing to do with what they actually said.
                _state.update { it.copy(error = result.message) }
                emptyList()
            }
        }
    }

    private fun offlineFixtureFor(question: BankQuestion): List<Word> =
        if (question.expectedAnswer == "twelve") OfflineFixtures.hesitantCorrect else OfflineFixtures.confidentWrong

    private suspend fun scoreAndRespond(question: BankQuestion, words: List<Word>) {
        val profile = _state.value.toneProfile
        if (profile == null) {
            _state.update { it.copy(busy = false, micState = MicState.IDLE) }
            return
        }

        val transcript = words.joinToString(" ") { it.text }
        val signals = ConfidenceScorer.score(words)
        val band = ConfidenceScorer.band(signals)
        val correct = QuestionBank.isCorrect(question, transcript)
        val readout = ConfidenceReadout(
            transcript = transcript,
            signals = signals,
            band = band,
            correct = correct,
            triggeredFollowUp = band == ConfidenceBand.LOW || !correct
        )

        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(fromAlbert = false, text = transcript),
                lastConfidenceReadout = readout
            )
        }

        val addendum = Routing.promptAddendum(correct, band)
        val prompt = PromptBuilder.buildPrompt(profile, addendum, conversationContext(), transcript)
        when (val result = GroqClient.chat(apiKey, prompt)) {
            is GroqResult.Success -> applyReply(result.value)
            is GroqResult.Failure -> {
                _state.update { it.copy(error = "${result.message} -- showing an offline reply") }
                applyReply(FallbackReplies.reply(profile, correct, band))
            }
        }
    }

    private fun applyReply(reply: String) {
        val turnMetrics = MetricsScanner.scan(reply)
        _state.update {
            it.copy(
                messages = it.messages + ChatMessage(fromAlbert = true, text = reply),
                sessionMetrics = it.sessionMetrics.plus(turnMetrics),
                busy = false,
                micState = MicState.IDLE,
                currentBankQuestion = null
            )
        }
        try {
            voiceEngine?.speak(reply, _state.value.toneProfile)
        } catch (_: Exception) {
            // TTS failure should never crash the session
        }
    }

    /** Freezes the active profile's running metrics into a labeled card so it can sit alongside
     *  another profile's on the Compare screen -- snapshot-and-compare, not two live chats. */
    fun snapshotCurrentProfile() {
        val s = _state.value
        val perf = s.performance ?: return
        val reg = s.regularity ?: return
        val label = "${perf.name.lowercase().replaceFirstChar(Char::uppercase)} / " +
            reg.name.lowercase().replaceFirstChar(Char::uppercase)
        _state.update {
            it.copy(snapshots = it.snapshots.filterNot { snap -> snap.label == label } + MetricsSnapshot(label, it.sessionMetrics))
        }
    }

    fun toggleVoiceMode(enabled: Boolean) = _state.update { it.copy(voiceMode = enabled) }
    fun toggleOfflineMode(enabled: Boolean) = _state.update { it.copy(offlineMode = enabled) }
    fun dismissError() = _state.update { it.copy(error = null) }

    fun updateApiKey(newKey: String) {
        apiKey = newKey.trim()
        _state.update { it.copy(error = null) }
    }

    private fun conversationContext(): String {
        val recent = _state.value.messages.takeLast(6)
        if (recent.isEmpty()) return ""
        return recent.joinToString("\n") { (if (it.fromAlbert) "Albert: " else "Student: ") + it.text }
    }

    companion object {
        // TTS and speech recognition share the same underlying "Speech Recognition & Synthesis
        // from Google" service on many devices -- this buffer also gives that shared session time
        // to actually release before the recognizer tries to grab it, not just the speaker.
        private const val MIC_ARM_DELAY_MS = 400L

        // Whisper hallucinates boilerplate outro phrases on short/quiet clips with little real
        // signal -- it was trained on huge amounts of video data and falls back on things like
        // this when it has nothing real to transcribe. None of these are plausible answers to any
        // question in QuestionBank, so treating them as "no answer" rather than a real transcript
        // is safe for this demo.
        private val HALLUCINATED_PHRASES = setOf(
            "you", "thank you", "thanks for watching", "thank you for watching", "thanks",
            "please subscribe", "subscribe to my channel", "bye", "bye bye", "see you next time",
            "okay", "yeah"
        )

        /** True only for a transcript worth scoring -- not empty/punctuation-only (see the
         *  earlier "." bug) and not a known Whisper hallucination. */
        fun looksLikeRealAnswer(words: List<Word>): Boolean {
            if (!words.any { w -> w.text.any { c -> c.isLetterOrDigit() } }) return false
            val transcript = words.joinToString(" ") { it.text }
                .lowercase()
                .trim { it in ",.?!… " }
            return transcript !in HALLUCINATED_PHRASES
        }
    }
}

class TutorViewModelFactory(private val apiKey: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TutorViewModel(apiKey) as T
}
