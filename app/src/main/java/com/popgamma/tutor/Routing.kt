package com.popgamma.tutor

// Pure Kotlin -- no Android imports. Per-turn confidence routing (spec Section 3a/8). This never
// touches ToneProfile.structure or .encouragement for the rest of the session -- it only ever
// appends one directive to a single turn's prompt (see PromptBuilder.buildPrompt's addendum).

data class TurnDirective(val encouragement: String, val followUp: String)

object Routing {

    // Caveat 3: this string is used for all four (correct, band) combinations. CoreTest asserts
    // it is byte-identical across all four -- warmth cannot drift with confidence because the
    // test won't allow the code to make it drift.
    // Hit live: "The capital of France is Paris, not Tokyo. Good job." -- warmth stayed constant
    // per Caveat 3, but "good job" landed right after the wrong answer with nothing telling the
    // model WHAT it's praising, so it read as praising the wrong fact itself, not the attempt.
    // Fixed by naming the target of the praise explicitly, not just requiring warmth to persist.
    private const val ENCOURAGEMENT =
        "Acknowledge the student's effort warmly, at the same level regardless of this answer's " +
            "correctness or confidence. If the answer is wrong, state the correction first and " +
            "put the encouragement after it, phrased so it clearly targets the attempt, not the " +
            "answer -- never open a wrong-answer reply with the praise phrase, and never let it " +
            "read like you're endorsing a wrong answer as correct."

    fun directive(correct: Boolean, band: ConfidenceBand): TurnDirective {
        val followUp = when {
            correct && band == ConfidenceBand.HIGH ->
                "Move on to the next question."
            correct && band == ConfidenceBand.LOW ->
                "Ask the student to walk through how they got the answer, to check understanding -- not because you doubt them."
            !correct && band == ConfidenceBand.LOW ->
                "Give a gentle, expected correction -- this looks like a careless slip, not a misconception."
            else -> // !correct && band == ConfidenceBand.HIGH
                "Flag this for deeper explanation and walk through the misconception directly -- a confident wrong answer is the strongest available signal of a real misconception, not a careless slip."
        }
        return TurnDirective(ENCOURAGEMENT, followUp)
    }

    fun promptAddendum(correct: Boolean, band: ConfidenceBand): String {
        val d = directive(correct, band)
        return "${d.encouragement} ${d.followUp}"
    }
}
