package com.popgamma.tutor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

class TutorViewModel(private val apiKey: String) : ViewModel() {
    private val _state = MutableStateFlow(TutorUiState())
    val state: StateFlow<TutorUiState> = _state

    private var recorder: MicRecorder? = null
    var voiceEngine: AlbertVoice? = null

    fun selectProfile(performance: Performance, regularity: Regularity) {
        _state.update {
            it.copy(
                performance = performance,
                regularity = regularity,
                messages = emptyList(),
                sessionMetrics = SessionMetrics(),
                lastConfidenceReadout = null,
                error = null
            )
        }
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
                recorder = MicRecorder(onSilenceDetected = { stopVoiceTurnAndScore() }).also { it.start() }
            }
        } else {
            recorder = MicRecorder(onSilenceDetected = { stopVoiceTurnAndScore() }).also { it.start() }
        }
    }

    fun stopVoiceTurnAndScore() {
        val question = _state.value.currentBankQuestion ?: return
        val pcm = recorder?.stopAndGetPcm()
        recorder = null
        // Clear any stale error from an earlier failed attempt -- otherwise a leftover "timeout"
        // banner can sit on screen indefinitely even after this turn succeeds.
        _state.update { it.copy(micState = MicState.PROCESSING, busy = true, error = null) }
        viewModelScope.launch {
            try {
                val words = resolveWords(question, pcm)
                scoreAndRespond(question, words)
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

    private suspend fun resolveWords(question: BankQuestion, pcm: ByteArray?): List<Word> {
        if (_state.value.offlineMode || pcm == null || pcm.isEmpty()) {
            return offlineFixtureFor(question)
        }
        val wavBytes = WavEncoder.pcmToWav(pcm)
        return when (val result = GroqClient.transcribeWithTimestamps(apiKey, wavBytes)) {
            is GroqResult.Success -> result.value
            is GroqResult.Failure -> {
                // Build-now network-failure path: degrade to "canned but honest" rather than
                // stalling or crashing a live demo -- see plan's promoted section.
                _state.update { it.copy(error = "${result.message} -- using offline sample", offlineMode = true) }
                offlineFixtureFor(question)
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

    private fun conversationContext(): String {
        val recent = _state.value.messages.takeLast(6)
        if (recent.isEmpty()) return ""
        return recent.joinToString("\n") { (if (it.fromAlbert) "Albert: " else "Student: ") + it.text }
    }
}

class TutorViewModelFactory(private val apiKey: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = TutorViewModel(apiKey) as T
}
