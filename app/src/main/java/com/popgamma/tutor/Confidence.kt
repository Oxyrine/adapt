package com.popgamma.tutor

// Pure Kotlin -- no Android imports. Implements spec Section 3a: the confidence signal is a
// measurement over a transcript, never a model judgment, and never an emotion label (Caveat 1).

data class Word(val text: String, val startMs: Long, val endMs: Long)

data class ConfidenceSignals(
    val latencyMs: Long,
    val hedgeRate: Float,
    val selfCorrections: Int
)

enum class ConfidenceBand { HIGH, LOW }

object ConfidenceThresholds {
    // ponytail: these are hand-picked, not calibrated against real student speech -- what reads
    // as "hesitant" varies by speaker, accent, second-language status and room noise. Exposed on
    // the metrics strip's debug row so they're the first knob to turn if a demo answer scores
    // wrong, not a value buried in code. Upgrade path: calibrate against a real sample if this
    // ever leaves the demo.
    const val LATENCY_MS = 1500L
    const val HEDGE_RATE = 0.12f
    const val SELF_CORRECTIONS = 1
}

object ConfidenceScorer {

    // "like" is deliberately excluded -- ordinary filler for the target age group, not a
    // hesitation signal. Including it would penalise how a kid talks, not how sure they are.
    private val HEDGE_WORDS = setOf(
        "um", "uh", "erm", "hmm", "ah", "er", "well", "maybe", "probably"
    )
    private val HEDGE_BIGRAMS = setOf(
        "i think", "is it", "not sure", "kind of", "sort of", "i guess", "maybe it", "or maybe",
        "could be", "might be", "not certain", "i believe"
    )
    private val RESTART_UNIGRAMS = setOf("actually")
    private val RESTART_BIGRAMS = setOf("no wait", "i mean", "wait no", "or rather")

    private fun normalizeToken(raw: String): String {
        val cleaned = raw.lowercase().trim { it in ",.?!…-;:\"'() " }
        // Collapse elongated fillers: "umm" -> "um", "uhh" -> "uh", "err" -> "er", "hmmm" -> "hmm", "ahh" -> "ah"
        return when {
            cleaned.matches(Regex("^u+m+$")) -> "um"
            cleaned.matches(Regex("^u+h+$")) -> "uh"
            cleaned.matches(Regex("^e+r+m*$")) -> "erm"
            cleaned.matches(Regex("^h+m+$")) -> "hmm"
            cleaned.matches(Regex("^a+h+$")) -> "ah"
            cleaned.matches(Regex("^e+r+$")) -> "er"
            else -> cleaned
        }
    }

    fun score(words: List<Word>): ConfidenceSignals {
        if (words.isEmpty()) return ConfidenceSignals(latencyMs = 0L, hedgeRate = 0f, selfCorrections = 0)

        val latencyMs = words.first().startMs
        val tokens = words.map { normalizeToken(it.text) }.filter { it.isNotBlank() }

        var hedgeHits = 0
        for (i in tokens.indices) {
            if (tokens[i] in HEDGE_WORDS) hedgeHits++
            if (i + 1 < tokens.size && "${tokens[i]} ${tokens[i + 1]}" in HEDGE_BIGRAMS) hedgeHits++
        }
        val hedgeRate = if (tokens.isNotEmpty()) hedgeHits.toFloat() / tokens.size else 0f

        var selfCorrections = 0
        // adjacent repeated tokens ("the the")
        for (i in 1 until tokens.size) {
            if (tokens[i] == tokens[i - 1]) selfCorrections++
        }
        // repeated bigram within a short window (student restarts a partial phrase)
        for (i in tokens.indices) {
            if (i + 1 >= tokens.size) continue
            val bigram = "${tokens[i]} ${tokens[i + 1]}"
            val windowEnd = minOf(i + 6, tokens.size - 1)
            for (j in (i + 2)..windowEnd) {
                if (j + 1 >= tokens.size) continue
                if (bigram == "${tokens[j]} ${tokens[j + 1]}") {
                    selfCorrections++
                    break
                }
            }
        }
        // explicit restart cues (both single-word like "actually" and two-word phrases like "no wait")
        for (i in tokens.indices) {
            if (tokens[i] in RESTART_UNIGRAMS) selfCorrections++
            if (i + 1 < tokens.size && "${tokens[i]} ${tokens[i + 1]}" in RESTART_BIGRAMS) selfCorrections++
        }

        return ConfidenceSignals(latencyMs, hedgeRate, selfCorrections)
    }

    /** LOW when at least two of the three signals cross their threshold -- a threshold, not a
     *  model judgment. This is what keeps Caveat 1 true: it never asks the model to rate the
     *  student, only measures the transcript it already produced. */
    fun band(signals: ConfidenceSignals): ConfidenceBand {
        var crossed = 0
        if (signals.latencyMs > ConfidenceThresholds.LATENCY_MS) crossed++
        if (signals.hedgeRate > ConfidenceThresholds.HEDGE_RATE) crossed++
        if (signals.selfCorrections > ConfidenceThresholds.SELF_CORRECTIONS) crossed++
        return if (crossed >= 2) ConfidenceBand.LOW else ConfidenceBand.HIGH
    }
}

// Finding 6 (plan): word-level timestamps can degrade Gemini's transcription accuracy, and there
// is no mitigation available at the API level (custom_vocabulary can't combine with word
// timestamps either). These two fixtures double as the offline/network-failure fallback (see
// OfflineFixtures usage in TutorViewModel) and as CoreTest's scorer test data -- one source, not
// two code paths that could quietly drift apart.
object OfflineFixtures {
    /** Hesitant but correct: leading pause, a hedge word, a hedge bigram -> LOW band. Answers the
     *  "twelve" bank question correctly. */
    val hesitantCorrect = listOf(
        Word("um", 1800, 2000),
        Word("I", 2050, 2150),
        Word("think", 2150, 2300),
        Word("it's", 2300, 2400),
        Word("twelve", 2450, 2700),
    )

    /** Confident but wrong: no leading pause, no hedges, no restarts -> HIGH band. Answers the
     *  "twelve" bank question incorrectly. */
    val confidentWrong = listOf(
        Word("it's", 300, 450),
        Word("eleven", 450, 750),
    )
}
