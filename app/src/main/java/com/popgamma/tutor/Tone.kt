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
        val structureText = when (profile.structure) {
            StructureLevel.LOOSE ->
                "Turns can run long. Jokes and tangents are welcome. Keep scaffolding light."
            StructureLevel.MEDIUM ->
                "Keep turns a moderate length. Use some scaffolding steps. Occasional light tangents are fine."
            StructureLevel.TIGHT ->
                "Keep turns short. Break explanations into clear numbered steps. No jokes or tangents -- stay on task."
        }
        val encouragementText = when (profile.encouragement) {
            EncouragementLevel.STANDARD ->
                "Acknowledge correct effort with genuine praise, using a recognizable phrase like \"good job\", \"nice work\", or \"well done\"."
            EncouragementLevel.HIGH ->
                "Praise effort often and specifically -- this student needs to feel supported, not just corrected. Use warm, explicit praise like \"great job\" or \"you got it\"."
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
            // above asked for -- a confident-wrong answer's "walk through the misconception
            // directly" got squashed to the same length as a plain move-on, so every reply read
            // identically regardless of band/correctness. Length now follows what was actually
            // asked for instead of a fixed cap.
            append(
                "\n\nInstruction: Reply directly, suitable for spoken conversation -- no markdown, " +
                    "bullets, or asterisks. Match the length to what was actually asked: a quick " +
                    "move-on or gentle correction is one short sentence, but when asked to explain " +
                    "a misconception or walk through reasoning, take three or four sentences and " +
                    "actually do it -- do not compress a real explanation into one line."
            )
        }
}
