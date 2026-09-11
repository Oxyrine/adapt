package com.popgamma.tutor

// Pure Kotlin -- no Android imports.
//
// Finding 5 (plan): the spec's routing table crosses confidence with correctness but never says
// where correctness comes from. A hardcoded bank with deterministic string matching is the only
// way to force "confident wrong" on cue in front of a reviewer -- asking the model to judge
// correctness reintroduces exactly the variance a live demo can't afford.
//
// Finding 6 (plan): word-level timestamps can degrade transcription accuracy, and correctness is
// a substring match against the transcript -- so a single mistranscribed answer word flips
// `correct?` and silently selects a different routing-table row. The mitigation is here, in the
// bank content, not in code: every expected answer is short and phonetically distinct (a number
// or a single common word), never jargon or a near-homophone.
data class BankQuestion(val prompt: String, val expectedAnswer: String)

object QuestionBank {
    val questions = listOf(
        BankQuestion("What is seven plus five?", "twelve"),
        BankQuestion("What is nine times three?", "twenty seven"),
        BankQuestion("What is the capital of France?", "paris"),
        BankQuestion("What color do you get mixing blue and yellow?", "green"),
    )

    fun isCorrect(question: BankQuestion, transcript: String): Boolean {
        fun normalize(s: String) = s.lowercase().trim()
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
        return normalize(transcript).contains(normalize(question.expectedAnswer))
    }
}
