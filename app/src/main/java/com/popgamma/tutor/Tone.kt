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
        val structureText = when (profile.structure) {
            StructureLevel.LOOSE ->
                "Turns can run long and conversational, like chatting with a laid-back mentor. " +
                    "Work a light joke, fun fact, or friendly tangent into almost every reply -- " +
                    "this student thrives on a relaxed vibe, not a formal lesson. Skip numbered " +
                    "steps unless the question truly needs them."
            StructureLevel.MEDIUM ->
                "Keep turns a moderate length -- a couple of sentences, friendly but focused. Use " +
                    "scaffolding steps when explaining something, and toss in an occasional light " +
                    "aside, but don't force one into every single reply."
            StructureLevel.TIGHT ->
                "Keep every reply short and strictly businesslike -- no small talk, no jokes, no " +
                    "tangents, ever. Always break any explanation into clearly numbered steps " +
                    "(1, 2, 3...). Get straight to the point in as few words as possible."
        }
        val encouragementText = when (profile.encouragement) {
            EncouragementLevel.STANDARD ->
                "Acknowledge correct effort with a brief, genuine phrase like \"good job\" or " +
                    "\"nice work\" -- warm, but matter-of-fact, not gushing."
            EncouragementLevel.HIGH ->
                "This student needs to feel strongly supported, every single turn. Open or close " +
                    "nearly every reply with enthusiastic, specific praise -- phrases like " +
                    "\"Great job!\", \"You're doing awesome!\", or \"You've got this!\" -- and make " +
                    "the encouragement impossible to miss, even when correcting a mistake."
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
            // more" got answered with unrelated travel trivia instead of short numbered steps --
            // the depth instruction and the structure rule were fighting, and depth won. Depth now
            // has to stay inside whatever the structure rule already said.
            val depthWithinStructure = if (profile.structure == StructureLevel.TIGHT) {
                "Depth is never an excuse to break the TIGHT rule above -- more explanation means " +
                    "more short numbered steps, not tangents, trivia, or padding."
            } else {
                "Depth means actually working through the reasoning, not padding with unrelated " +
                    "trivia either."
            }
            append(
                "\n\nInstruction: Reply directly, suitable for spoken conversation -- no markdown, " +
                    "bullets, or asterisks. Match the length to what was actually asked: a quick " +
                    "move-on or gentle correction is one short sentence; when asked to explain a " +
                    "misconception or walk through reasoning, actually do it. $depthWithinStructure"
            )
        }
}
