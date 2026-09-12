package com.popgamma.tutor

// Pure Kotlin -- no Android imports. Canned replies used only when the live Gemini chat call
// fails (quota exhausted, network down, timeout). Mirrors the "canned but honest" approach the
// plan already applies to voice transcription (OfflineFixtures) -- rather than a dead-ended
// transcript with an error banner and no reply, the demo degrades to fixed text and says so.
//
// The text still has to respect the two things this whole demo argues for, or a fallback reply
// during a live pitch would undercut the argument instead of just working around an outage:
//   - structure/encouragement follow the active ToneProfile (Caveat 2)
//   - encouragement never changes with confidence routing, only follow-up depth does (Caveat 3)
object FallbackReplies {

    private const val ENCOURAGEMENT_STANDARD = "Good effort."
    private const val ENCOURAGEMENT_HIGH = "Good, and great work -- you're doing well, keep it up."

    private const val OFFLINE_NOTE = "\n\n(Offline reply -- live tutor unavailable right now.)"

    fun reply(profile: ToneProfile, correct: Boolean? = null, band: ConfidenceBand? = null): String {
        val encouragement = when (profile.encouragement) {
            EncouragementLevel.STANDARD -> ENCOURAGEMENT_STANDARD
            EncouragementLevel.HIGH -> ENCOURAGEMENT_HIGH
        }
        val body = when (profile.structure) {
            StructureLevel.LOOSE ->
                "By the way, that tracks. Let's keep going at your own pace -- no need to rush this one."
            StructureLevel.MEDIUM ->
                "First, that's on the right track. Next, let's build on it a little before moving on."
            StructureLevel.TIGHT ->
                "First, note the key point. Next, check it against the question. Then, move to the next step."
        }
        val followUp = if (correct == null || band == null) "" else when {
            correct && band == ConfidenceBand.HIGH ->
                " Let's move to the next question."
            correct && band == ConfidenceBand.LOW ->
                " Walk me through how you got that, just to check."
            !correct && band == ConfidenceBand.LOW ->
                " That's a small slip, not a big deal -- here's the fix."
            else -> // !correct && band == ConfidenceBand.HIGH -- confident wrong, the strongest signal
                " Let's slow down and walk through this one directly, step by step, since it's worth understanding fully."
        }
        return "$encouragement $body$followUp$OFFLINE_NOTE"
    }
}
