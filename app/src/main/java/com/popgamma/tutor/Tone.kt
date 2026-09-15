package com.popgamma.tutor

// Pure Kotlin -- no Android imports. This is what CoreTest.kt exercises directly as a JVM test,
// with no emulator required. See spec Section 5 and Caveat 2.

enum class Performance { STRONG, STRUGGLING }
enum class Regularity { CONSISTENT, GAPPED }

// Declaration order matters: CoreTest compares these by ordinal to check tightness/level ranking.
enum class StructureLevel { LOOSE, MEDIUM, TIGHT }
enum class EncouragementLevel { STANDARD, HIGH }

data class ToneProfile(
    val performance: Performance,
    val regularity: Regularity,
    val structure: StructureLevel,
    val encouragement: EncouragementLevel
)

object ToneTable {
    // Caveat 2: two independent dials. Structure tightens as performance/regularity worsen.
    // Encouragement is held flat or RISES as structure tightens -- it must never drop.
    // This is enforced by CoreTest, not just asserted in this comment.
    private val cells = listOf(
        ToneProfile(Performance.STRONG, Regularity.CONSISTENT, StructureLevel.LOOSE, EncouragementLevel.STANDARD),
        ToneProfile(Performance.STRONG, Regularity.GAPPED, StructureLevel.MEDIUM, EncouragementLevel.STANDARD),
        ToneProfile(Performance.STRUGGLING, Regularity.CONSISTENT, StructureLevel.TIGHT, EncouragementLevel.STANDARD),
        ToneProfile(Performance.STRUGGLING, Regularity.GAPPED, StructureLevel.TIGHT, EncouragementLevel.HIGH),
    )

    fun lookup(performance: Performance, regularity: Regularity): ToneProfile =
        cells.first { it.performance == performance && it.regularity == regularity }

    fun all(): List<ToneProfile> = cells
}

object PromptBuilder {

    fun systemPrompt(profile: ToneProfile): String {
        // ponytail: "welcome"/"fine" are permissions, not instructions -- a model asked to
        // *permit* a joke usually just doesn't bother. Live testing showed every profile landing
        // on nearly the same length and register. Rewritten as explicit requirements per level so
        // the contrast is something a reviewer can hear, not just something the metrics count.
        // ponytail: a second round of live testing still read too similar across profiles even
        // after switching from permissions to requirements. Escalating with an explicit persona
        // plus one worked example per extreme -- a concrete example changes model output far more
        // reliably than another paragraph describing the desired vibe in the abstract.
        val structureText = when (profile.structure) {
            StructureLevel.LOOSE ->
                "You're basically this student's chill study buddy right now, not a formal " +
                    "teacher. Work a joke, fun fact, or friendly tangent into almost every reply, " +
                    "and use relaxed, casual language freely. Example cadence: \"Ha, close! It's " +
                    "actually 12 -- fun fact, that's basically a dozen eggs. Wanna try a trickier " +
                    "one?\" Skip numbered steps entirely unless the question truly demands them."
            StructureLevel.MEDIUM ->
                "Keep turns a moderate length -- a couple of sentences, friendly but focused. Use " +
                    "scaffolding steps when explaining something, and toss in an occasional light " +
                    "aside, but don't force one into every single reply."
            StructureLevel.TIGHT ->
                "You're in strict, no-nonsense mode right now -- almost like a drill instructor " +
                    "giving orders. Keep every reply short and strictly businesslike: no small " +
                    "talk, no jokes, no tangents, ever, and under 25 words outside of any numbered " +
                    "steps. Always number every step, even a one-step answer. Example cadence: " +
                    "\"Incorrect. 1) 7 plus 5 is 12. Try the next one.\" The delivery is brisk -- " +
                    "but the encouragement phrase itself must still land warmly, per the " +
                    "instruction below."
        }
        val encouragementText = when (profile.encouragement) {
            EncouragementLevel.STANDARD ->
                "Acknowledge correct effort with one brief, plain phrase like \"good job\" or " +
                    "\"nice work\" -- no exclamation marks, no extra warmth beyond that single " +
                    "phrase."
            EncouragementLevel.HIGH ->
                "This student needs big, obvious support every single turn -- think enthusiastic " +
                    "cheerleader, not a quiet nod. Open or close nearly every reply with a bold, " +
                    "exclamation-mark praise phrase -- \"Great job!!\", \"You're crushing it!\", or " +
                    "\"You've totally got this!\" -- and don't hold back, even when correcting a " +
                    "mistake."
        }
        return """
            You are Prof. Albert, a tutor. $structureText $encouragementText
            A struggling student gets a more focused tutor, never a colder one: encouragement never
            drops because structure has tightened.
        """.trimIndent()
    }

    /**
     * Builds the full per-turn prompt: session-level tone (from [profile]) plus, when present, a
     * per-turn confidence-routing [addendum] (spec Section 3a) -- which never changes the tone
     * itself, only what gets appended for this one turn. [history] is a short rolling transcript
     * so the chat feels conversational rather than stateless Q&A.
     */
    fun buildPrompt(profile: ToneProfile, addendum: String?, history: String, userText: String): String =
        buildString {
            append(systemPrompt(profile))
            if (addendum != null) {
                append("\n\n")
                append(addendum)
            }
            if (history.isNotEmpty()) {
                append("\n\n")
                append(history)
            }
            append("\nStudent: ")
            append(userText)
            // A flat "1 to 2 sentences" cap here used to override whatever the routing addendum
            // above asked for. Fixing that (allowing more depth on the "explain the misconception"
            // branch) then broke a different thing live: on a TIGHT-structure profile, "explain
            // more" got answered with unrelated travel trivia instead of short numbered steps. The
            // blanket "not padding with unrelated trivia either" fix for that then broke LOOSE --
            // it directly contradicts LOOSE's own persona instruction to work a joke/fun fact/
            // tangent into almost every reply, and live testing showed that contradiction just
            // killing the LOOSE persona outright rather than resolving it. Each structure level
            // needs its own compatible depth guidance, not one rule applied to all three.
            val depthGuidance = when (profile.structure) {
                StructureLevel.TIGHT ->
                    "Depth is never an excuse to break the TIGHT rule above -- more explanation " +
                        "means more short numbered steps, not tangents, trivia, or padding."
                StructureLevel.LOOSE ->
                    "The joke, fun fact, or tangent from the persona above still has to sit " +
                        "alongside a real, clear answer -- work it in, don't let it replace " +
                        "actually explaining."
                StructureLevel.MEDIUM ->
                    "Depth means actually working through the reasoning -- an occasional light " +
                        "aside is fine, but don't let it replace the actual explanation."
            }
            append(
                "\n\nInstruction: Reply directly, suitable for spoken conversation -- no markdown, " +
                    "bullets, or asterisks. Match the length to what was actually asked: a quick " +
                    "move-on or gentle correction is one short sentence; when asked to explain a " +
                    "misconception or walk through reasoning, actually do it. $depthGuidance"
            )
        }
}
