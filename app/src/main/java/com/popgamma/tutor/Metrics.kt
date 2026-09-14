package com.popgamma.tutor

// Pure Kotlin -- no Android imports. Spec Section 4.3/5: a lightweight keyword/regex scan of
// Albert's reply, not a rigorous NLP pass -- "sufficient" per spec Section 5 as long as it visibly
// tracks the right direction. Encouragement counting is the reliable half (a small closed set of
// praise markers); joke/tangent detection is the weakest heuristic here and will be roughly right
// at best.

data class TurnMetrics(
    val wordCount: Int,
    val scaffoldingSteps: Int,
    val jokeTangentCount: Int,
    val praiseMarkers: Int
)

object MetricsScanner {
    // Sorted by descending length so multi-word phrases match before single words
    private val PRAISE_MARKERS = listOf(
        "that's right", "nicely done", "great work", "you got it", "nice work",
        "well done", "great job", "good job", "exactly", "awesome", "perfect", "good"
    )
    private val PRAISE_PATTERN = Regex(
        "\\b(" + PRAISE_MARKERS.joinToString("|") { Regex.escape(it) } + ")\\b",
        RegexOption.IGNORE_CASE
    )
    private val SCAFFOLD_MARKERS = listOf("first,", "first ", "next,", "next ", "then,", "then ")
    private val NUMBERED_LINE = Regex("""\n\s*\d+[.)]\s""")
    private val JOKE_MARKERS = listOf("haha", "lol", "by the way", "fun fact", "speaking of")

    fun scan(reply: String): TurnMetrics {
        val lower = reply.lowercase()
        val wordCount = reply.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val scaffoldingSteps = SCAFFOLD_MARKERS.count { lower.contains(it) } + NUMBERED_LINE.findAll(reply).count()
        val jokeTangentCount = JOKE_MARKERS.count { lower.contains(it) }
        val praiseMarkers = PRAISE_PATTERN.findAll(reply).count()
        return TurnMetrics(wordCount, scaffoldingSteps, jokeTangentCount, praiseMarkers)
    }
}

data class SessionMetrics(
    val turns: Int = 0,
    val totalWords: Int = 0,
    val totalScaffolding: Int = 0,
    val totalJokes: Int = 0,
    val totalPraise: Int = 0
) {
    val avgWordsPerTurn: Float get() = if (turns == 0) 0f else totalWords.toFloat() / turns
    val avgPraisePerTurn: Float get() = if (turns == 0) 0f else totalPraise.toFloat() / turns

    fun plus(turn: TurnMetrics): SessionMetrics = copy(
        turns = turns + 1,
        totalWords = totalWords + turn.wordCount,
        totalScaffolding = totalScaffolding + turn.scaffoldingSteps,
        totalJokes = totalJokes + turn.jokeTangentCount,
        totalPraise = totalPraise + turn.praiseMarkers
    )
}

/** A frozen copy of one profile's metrics, per Section 4.3's "run both profiles side by side" --
 *  implemented as snapshot-and-compare rather than two live chats (your call, see plan). */
data class MetricsSnapshot(val label: String, val metrics: SessionMetrics)
