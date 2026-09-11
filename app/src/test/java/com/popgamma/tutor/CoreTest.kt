package com.popgamma.tutor

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val addendum = Routing.promptAddendum(correct = false, band = ConfidenceBand.HIGH)
        val prompt = PromptBuilder.buildPrompt(profile, addendum, history = "", userText = "it's eleven")
        assertTrue(prompt.contains(addendum))
    }

    @Test
    fun `tight structure prompt never encourages jokes or tangents`() {
        val profile = ToneTable.lookup(Performance.STRUGGLING, Regularity.CONSISTENT)
        val prompt = PromptBuilder.systemPrompt(profile)
        assertFalse(prompt.contains("Jokes and tangents are welcome"))
    }

    @Test
    fun `loose structure prompt welcomes jokes and tangents`() {
        val profile = ToneTable.lookup(Performance.STRONG, Regularity.CONSISTENT)
        val prompt = PromptBuilder.systemPrompt(profile)
        assertTrue(prompt.contains("Jokes and tangents are welcome"))
    }

    // ---- Gemini response parsing -- payloads below are trimmed but otherwise verbatim from real
    // curl calls made against the live API during setup, not hand-guessed from docs. The docs never
    // showed a full response body; this is what actually locks the parsing contract in place. ----

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `modelOutputText skips thought steps and reads the model_output step`() {
        // Captured from a real gemini-3.8-flash chat call.
        val body = """
            {"id":"v1_test","status":"completed","steps":[
                {"signature":"EosDCogDARFNMg...","type":"thought"},
                {"content":[{"text":"OK","type":"text"}],"type":"model_output"}
            ],"object":"interaction","model":"gemini-3.8-flash"}
        """.trimIndent()
        val response = json.decodeFromString<InteractionResponse>(body)
        assertEquals("OK", response.modelOutputText())
    }

    @Test
    fun `wordList parses second-based offset strings into milliseconds`() {
        // Captured from a real gemini-3.5-transcribe call with word timestamps enabled, for a
        // spoken "um, I think it's twelve" -- note the model already normalized "twelve" to "12."
        // (see QuestionBank's digit-form handling) and offsets arrive as strings like "0.100s" and
        // "1s", not integers.
        val body = """
            {"id":"v1_test2","status":"completed","steps":[{"content":[{
                "text":"Um, I think it's 12.",
                "annotations":[
                    {"start_index":0,"end_index":3,"text":"Um,","start_offset":"0.100s","end_offset":"0.600s","type":"word_info"},
                    {"start_index":4,"end_index":5,"text":"I","start_offset":"0.800s","end_offset":"1s","type":"word_info"},
                    {"start_index":6,"end_index":11,"text":"think","start_offset":"1s","end_offset":"1.200s","type":"word_info"},
                    {"start_index":12,"end_index":16,"text":"it's","start_offset":"1.200s","end_offset":"1.400s","type":"word_info"},
                    {"start_index":17,"end_index":20,"text":"12.","start_offset":"1.400s","end_offset":"1.900s","type":"word_info"}
                ],"type":"text"
            }],"type":"model_output"}],"object":"interaction","model":"gemini-3.5-transcribe"}
        """.trimIndent()
        val words = json.decodeFromString<InteractionResponse>(body).wordList()

        assertEquals(5, words.size)
        assertEquals("Um,", words[0].text)
        assertEquals(100L, words[0].startMs)
        assertEquals(600L, words[0].endMs)
        assertEquals("I", words[1].text)
        assertEquals(800L, words[1].startMs)
        assertEquals(1000L, words[1].endMs) // "1s" -> 1000ms, not a parse failure defaulting to 0

        // End-to-end check that the live response shape flows into a real band. This sample was
        // synthesized speech that started almost instantly (100ms), so only the hedge signal
        // crosses its threshold -- latency doesn't -- landing HIGH under the "2 of 3" rule, not
        // LOW. That's not a bug: a fast hedge alone isn't enough on its own, which is exactly the
        // kind of threshold sensitivity the plan already flagged as needing in-room calibration
        // (ConfidenceThresholds), not something to hardcode an assumption about here.
        val signals = ConfidenceScorer.score(words)
        assertTrue(signals.hedgeRate > ConfidenceThresholds.HEDGE_RATE)
        assertTrue(signals.latencyMs <= ConfidenceThresholds.LATENCY_MS)
        assertEquals(ConfidenceBand.HIGH, ConfidenceScorer.band(signals))
    }
}
