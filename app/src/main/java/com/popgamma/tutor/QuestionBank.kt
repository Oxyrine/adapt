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
//
// Verified live against the API: Gemini's transcription normalizes spoken numbers to digits even
// in verbatim mode -- saying "twelve" comes back as "12." in the transcript. isCorrect() below
// accepts both forms for numeric answers so a correct answer never fails on formatting alone.
data class BankQuestion(val prompt: String, val expectedAnswer: String)

object QuestionBank {
    val questions = listOf(
        BankQuestion("What is seven plus five?", "twelve"),
        BankQuestion("What is nine times three?", "twenty seven"),
        BankQuestion("What is the capital of France?", "paris"),
        BankQuestion("What color do you get mixing blue and yellow?", "green"),
    )

    fun isCorrect(question: BankQuestion, transcript: String): Boolean {
        fun normalize(s: String) = s.lowercase()
            .replace('-', ' ')
            .replace(Regex("""\b(\d+)(st|nd|rd|th)\b"""), "$1")
            .replace("twelfth", "twelve")
            .replace(Regex("[^a-z0-9 ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        val normalizedTranscript = normalize(transcript)
        val normalizedExpected = normalize(question.expectedAnswer)
        if (Regex("\\b" + Regex.escape(normalizedExpected) + "\\b").containsMatchIn(normalizedTranscript)) return true

        // Verified against the live API: Gemini's transcription normalizes spoken numbers to
        // digits even in verbatim mode ("twelve" -> "12."), so a correct answer to a numeric
        // question would otherwise fail this match on formatting alone, not accuracy. Also accept
        // the digit form.
        val digitForm = wordsToDigits(question.expectedAnswer) ?: return false
        return Regex("\\b" + Regex.escape(digitForm) + "\\b").containsMatchIn(normalizedTranscript)
    }

    // 0-99 only, hardcoded -- the bank never needs more than this, not a general number parser.
    private val ONES = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    private fun wordsToDigits(phrase: String): String? {
        val words = phrase.lowercase().trim().split(Regex("\\s+"))
        return when {
            words.size == 1 && ONES.containsKey(words[0]) -> ONES.getValue(words[0]).toString()
            words.size == 1 && TENS.containsKey(words[0]) -> TENS.getValue(words[0]).toString()
            words.size == 2 && TENS.containsKey(words[0]) && ONES[words[1]]?.let { it in 1..9 } == true ->
                (TENS.getValue(words[0]) + ONES.getValue(words[1])).toString()
            else -> null
        }
    }
}
