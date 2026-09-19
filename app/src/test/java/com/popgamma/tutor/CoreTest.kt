package com.popgamma.tutor

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Plain JVM unit tests over the pure core (Tone.kt, Confidence.kt, Routing.kt, Metrics.kt,
 * QuestionBank.kt) -- no Android imports anywhere in this dependency chain, so this runs with
 * `./gradlew :app:testDebugUnitTest` and no emulator.
 *
 * The first two groups below are not incidental coverage -- they are the spec's Caveat 2 and
 * Caveat 3 turned into assertions. If a future change to the tone table or the routing directives
 * breaks either promise, this file fails the build instead of a README quietly going stale.
 */
class CoreTest {

    // ---- Caveat 2: structure can tighten, encouragement must never drop ----

    @Test
    fun `encouragement never decreases as structure tightens`() {
        val cells = ToneTable.all()
        for (looser in cells) {
            for (tighter in cells) {
                if (looser.structure.ordinal < tighter.structure.ordinal) {
                    assertTrue(
                        tighter.encouragement.ordinal >= looser.encouragement.ordinal,
                        "Tightening structure from ${looser.structure} (${looser.performance}/${looser.regularity}) " +
                            "to ${tighter.structure} (${tighter.performance}/${tighter.regularity}) " +
                            "must not lower encouragement (${looser.encouragement} -> ${tighter.encouragement})"
                    )
                }
            }
        }
    }

    @Test
    fun `no cell is encouraged less than the loosest cell`() {
        val cells = ToneTable.all()
        val loosest = cells.minByOrNull { it.structure.ordinal }!!
        cells.forEach { cell ->
            assertTrue(
                cell.encouragement.ordinal >= loosest.encouragement.ordinal,
                "${cell.performance}/${cell.regularity} encouragement is below the loosest cell's"
            )
        }
    }

    // ---- Caveat 3: confidence band changes the follow-up, never the warmth ----

    @Test
    fun `encouragement directive is byte-identical across all four correctness-confidence combinations`() {
        val combos = listOf(
            true to ConfidenceBand.HIGH,
            true to ConfidenceBand.LOW,
            false to ConfidenceBand.LOW,
            false to ConfidenceBand.HIGH
        )
        val directives = combos.map { (correct, band) -> Routing.directive(correct, band) }

        val encouragements = directives.map { it.encouragement }.toSet()
        assertEquals(1, encouragements.size, "Encouragement directive varies with correctness/confidence: $encouragements")

        // The follow-up must genuinely vary -- otherwise the identical-encouragement assertion
        // above would be trivially true because the whole directive never changes.
        val followUps = directives.map { it.followUp }.toSet()
        assertEquals(4, followUps.size, "Expected four distinct follow-up directives, got: $followUps")
    }

    @Test
    fun `confident wrong is the only combination flagged for deeper explanation`() {
        val confidentWrong = Routing.directive(correct = false, band = ConfidenceBand.HIGH)
        assertTrue(confidentWrong.followUp.contains("misconception", ignoreCase = true))
        assertTrue(confidentWrong.followUp.contains("deeper", ignoreCase = true))

        // Note: "misconception" alone isn't a safe marker -- the low-confidence-wrong branch
        // deliberately says "not a misconception" to distinguish a careless slip from a real one,
        // so it contains the substring too. "flag" is unique to the confident-wrong routing.
        val others = listOf(
            Routing.directive(correct = true, band = ConfidenceBand.HIGH),
            Routing.directive(correct = true, band = ConfidenceBand.LOW),
            Routing.directive(correct = false, band = ConfidenceBand.LOW)
        )
        others.forEach { assertFalse(it.followUp.contains("flag", ignoreCase = true)) }
    }

    // ---- Confidence scorer ----

    @Test
    fun `hesitant correct fixture scores LOW band`() {
        val signals = ConfidenceScorer.score(OfflineFixtures.hesitantCorrect)
        assertEquals(ConfidenceBand.LOW, ConfidenceScorer.band(signals))
    }

    @Test
    fun `confident wrong fixture scores HIGH band`() {
        val signals = ConfidenceScorer.score(OfflineFixtures.confidentWrong)
        assertEquals(ConfidenceBand.HIGH, ConfidenceScorer.band(signals))
    }

    @Test
    fun `empty transcript reads as HIGH confidence, not a false LOW`() {
        val signals = ConfidenceScorer.score(emptyList())
        assertEquals(ConfidenceBand.HIGH, ConfidenceScorer.band(signals))
    }

    @Test
    fun `like is not counted as a hedge`() {
        val words = listOf(Word("I", 0, 100), Word("like", 100, 200), Word("twelve", 200, 400))
        val signals = ConfidenceScorer.score(words)
        assertEquals(0f, signals.hedgeRate)
    }

    @Test
    fun `two of three crossed thresholds is enough for LOW, one alone is not`() {
        // Only latency crosses (hedge rate 0, no self-corrections) -> should stay HIGH.
        val onlyLatency = listOf(Word("twelve", 2000, 2200))
        assertEquals(ConfidenceBand.HIGH, ConfidenceScorer.band(ConfidenceScorer.score(onlyLatency)))

        // Latency crosses and hedge rate crosses -> LOW.
        val latencyAndHedge = listOf(Word("um", 2000, 2200), Word("twelve", 2250, 2400))
        assertEquals(ConfidenceBand.LOW, ConfidenceScorer.band(ConfidenceScorer.score(latencyAndHedge)))
    }

    // ---- Question bank correctness (Finding 5) ----

    @Test
    fun `correctness matches on the expected answer substring`() {
        val q = QuestionBank.questions.first { it.expectedAnswer == "twelve" }
        assertTrue(QuestionBank.isCorrect(q, "um I think it's twelve"))
        assertFalse(QuestionBank.isCorrect(q, "it's eleven"))
    }

    @Test
    fun `correctness is case and punctuation insensitive`() {
        val q = QuestionBank.questions.first { it.expectedAnswer == "paris" }
        assertTrue(QuestionBank.isCorrect(q, "Paris!"))
    }

    @Test
    fun `correctness accepts the digit form of a numeric answer`() {
        // Verified live: Gemini's transcription returns "12." for a spoken "twelve" even in
        // verbatim mode. A correct answer must not fail on digit-vs-word formatting alone.
        val twelve = QuestionBank.questions.first { it.expectedAnswer == "twelve" }
        assertTrue(QuestionBank.isCorrect(twelve, "Um, I think it's 12."))

        val twentySeven = QuestionBank.questions.first { it.expectedAnswer == "twenty seven" }
        assertTrue(QuestionBank.isCorrect(twentySeven, "it's 27"))
        assertFalse(QuestionBank.isCorrect(twentySeven, "it's 26"))
    }

    // ---- Metrics scanner ----

    @Test
    fun `praise markers are counted`() {
        val metrics = MetricsScanner.scan("Great job! That's right, nicely done.")
        assertTrue(metrics.praiseMarkers >= 2)
    }

    @Test
    fun `scaffolding steps are counted from numbered lines`() {
        val reply = "First, write the equation.\n1. Add the numbers.\n2. Check your work."
        val metrics = MetricsScanner.scan(reply)
        assertTrue(metrics.scaffoldingSteps >= 2)
    }

    // ---- Prompt builder ----

    @Test
    fun `prompt addendum for confident wrong appears verbatim in the built prompt`() {
        val profile = ToneTable.lookup(Performance.STRUGGLING, Regularity.GAPPED)
        val addendum = Routing.promptAddendum(correct = false, band = ConfidenceBand.HIGH, expectedAnswer = "twelve")
        val prompt = PromptBuilder.buildPrompt(profile, addendum, history = "", userText = "it's eleven")
        assertTrue(prompt.contains(addendum))
    }

    @Test
    fun `prompt addendum states ground truth explicitly instead of leaving the model to judge it`() {
        // Hit live: without this, a small/low-reasoning-effort model would sometimes say
        // "correct, good job" to a wrong arithmetic answer, or skip the misconception explanation
        // for a TIGHT-structure profile because it hadn't independently noticed anything was wrong.
        val wrong = Routing.promptAddendum(correct = false, band = ConfidenceBand.HIGH, expectedAnswer = "twenty seven")
        assertTrue(wrong.contains("incorrect", ignoreCase = true))
        assertTrue(wrong.contains("twenty seven"))

        val right = Routing.promptAddendum(correct = true, band = ConfidenceBand.HIGH, expectedAnswer = "twenty seven")
        assertFalse(right.contains("incorrect", ignoreCase = true))
    }

    @Test
    fun `tight structure prompt bans jokes and tangents outright`() {
        val profile = ToneTable.lookup(Performance.STRUGGLING, Regularity.CONSISTENT)
        val prompt = PromptBuilder.systemPrompt(profile)
        assertTrue(prompt.contains("no jokes", ignoreCase = true))
        // "welcome"/"fine" are permissions, not instructions -- rewritten as a requirement
        // (work a joke into ALMOST EVERY reply) after live testing showed every profile
        // landing on the same flat register when jokes were merely permitted, not required.
        assertFalse(prompt.contains("almost every reply", ignoreCase = true))
    }

    @Test
    fun `loose structure prompt requires jokes or tangents, not just permits them`() {
        val profile = ToneTable.lookup(Performance.STRONG, Regularity.CONSISTENT)
        val prompt = PromptBuilder.systemPrompt(profile)
        assertTrue(prompt.contains("joke", ignoreCase = true))
        assertTrue(prompt.contains("almost every reply", ignoreCase = true))
    }

    // ---- Fallback replies (chat call failed -- quota/network) still honor Caveats 2 & 3 ----

    @Test
    fun `fallback reply structure scaffolds more as structure tightens, never fewer jokes than scaffolding steps demands`() {
        val loose = MetricsScanner.scan(FallbackReplies.reply(ToneTable.lookup(Performance.STRONG, Regularity.CONSISTENT)))
        val medium = MetricsScanner.scan(FallbackReplies.reply(ToneTable.lookup(Performance.STRONG, Regularity.GAPPED)))
        val tight = MetricsScanner.scan(FallbackReplies.reply(ToneTable.lookup(Performance.STRUGGLING, Regularity.CONSISTENT)))

        assertTrue(loose.jokeTangentCount >= 1, "Loose fallback should read as relaxed, not scaffolded")
        assertEquals(0, loose.scaffoldingSteps)
        assertTrue(medium.scaffoldingSteps > loose.scaffoldingSteps)
        assertTrue(tight.scaffoldingSteps > medium.scaffoldingSteps)
        assertEquals(0, tight.jokeTangentCount, "Tight structure fallback must not read as jokey")
    }

    @Test
    fun `fallback reply encouragement does not drop between STANDARD and HIGH cells`() {
        val standard = MetricsScanner.scan(FallbackReplies.reply(ToneTable.lookup(Performance.STRONG, Regularity.CONSISTENT)))
        val high = MetricsScanner.scan(FallbackReplies.reply(ToneTable.lookup(Performance.STRUGGLING, Regularity.GAPPED)))
        assertTrue(high.praiseMarkers >= standard.praiseMarkers)
    }

    @Test
    fun `fallback reply follow-up varies with confidence routing but stays labeled offline`() {
        val profile = ToneTable.lookup(Performance.STRUGGLING, Regularity.GAPPED)
        val confidentWrong = FallbackReplies.reply(profile, correct = false, band = ConfidenceBand.HIGH)
        val confidentRight = FallbackReplies.reply(profile, correct = true, band = ConfidenceBand.HIGH)

        assertTrue(confidentWrong.contains("Offline reply"))
        assertTrue(confidentRight.contains("Offline reply"))
        // Confident wrong is the strongest misconception signal -- its fallback follow-up must
        // still read as the deepest one, same as the live routing directive does.
        assertTrue(confidentWrong.length > confidentRight.length)
        assertNotEquals(confidentWrong, confidentRight)
    }

    // ---- Groq response parsing -- payloads below follow Groq's documented OpenAI-compatible
    // shape, NOT a live-captured response (unlike the Gemini contract, which only got locked down
    // after real curl calls exposed the docs were wrong -- see plan Finding 2). ponytail: treat
    // this contract as unverified until it's been checked against a real key, same lesson. ----

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `text reads the first choice's message content`() {
        val body = """
            {"choices":[
                {"message":{"role":"assistant","content":"OK"},"finish_reason":"stop"}
            ]}
        """.trimIndent()
        val response = json.decodeFromString<ChatCompletionResponse>(body)
        assertEquals("OK", response.text())
    }

    @Test
    fun `wordList parses float-second word timestamps into milliseconds`() {
        // Groq's verbose_json + timestamp_granularities=word returns start/end as plain float
        // seconds, not Gemini's "0.100s" strings -- for a spoken "um, I think it's twelve".
        val body = """
            {"text":"Um, I think it's twelve.","words":[
                {"word":"Um,","start":0.1,"end":0.6},
                {"word":"I","start":0.8,"end":1.0},
                {"word":"think","start":1.0,"end":1.2},
                {"word":"it's","start":1.2,"end":1.4},
                {"word":"twelve.","start":1.4,"end":1.9}
            ]}
        """.trimIndent()
        val words = json.decodeFromString<TranscriptionResponse>(body).wordList()

        assertEquals(5, words.size)
        assertEquals("Um,", words[0].text)
        assertEquals(100L, words[0].startMs)
        assertEquals(600L, words[0].endMs)
        assertEquals("I", words[1].text)
        assertEquals(800L, words[1].startMs)
        assertEquals(1000L, words[1].endMs)

        // End-to-end check that this response shape flows into a real band -- same fixture timing
        // as the Gemini version had: fast start (100ms) so only the hedge signal crosses its
        // threshold, landing HIGH under the "2 of 3" rule, not LOW.
        val signals = ConfidenceScorer.score(words)
        assertTrue(signals.hedgeRate > ConfidenceThresholds.HEDGE_RATE)
        assertTrue(signals.latencyMs <= ConfidenceThresholds.LATENCY_MS)
        assertEquals(ConfidenceBand.HIGH, ConfidenceScorer.band(signals))
    }
}
