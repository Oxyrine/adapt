# Adaptive Tutor Tone — Android Demo

A demo harness for PopGamma's Prof. Albert tutor: session-level tone that adapts to performance
and regularity, plus per-answer follow-up depth that adapts to how confidently a spoken answer was
given. Full spec and design rationale: see the plan at
`C:\Users\aakas\.claude\plans\adaptive-tutor-tone-flickering-kahn.md`.

The whole point of this app is a contrast a reviewer can see in a few minutes, not a feature list:
structure tightens for a struggling student, encouragement never drops, and a confident-wrong
answer gets a visibly deeper follow-up than a hesitant-correct one at the same warmth.

## Before you build

**I have not compiled this.** No JDK, Gradle, or Android SDK was available on the machine this was
written on. The pure-Kotlin core (`Tone.kt`, `Confidence.kt`, `Routing.kt`, `Metrics.kt`,
`QuestionBank.kt`) has no Android imports and is logically self-consistent with `CoreTest.kt`, but
"logically consistent on paper" and "compiles" are different claims. **Open this in Android Studio
and run the test suite before building anything on top of it:**

```bash
./gradlew :app:testDebugUnitTest
```

Then:

1. Copy `local.properties.example` to `local.properties` and set `GEMINI_API_KEY` (gitignored,
   never commit it).
2. Sync Gradle, run on a device or emulator.

## What the tests actually check

`CoreTest.kt` isn't incidental coverage — it's the spec's two non-negotiable caveats turned into
assertions that fail the build if broken:

- **Caveat 2** (structure can tighten, encouragement can't drop): walks every cell of the tone
  table and asserts encouragement never decreases as structure gets tighter.
- **Caveat 3** (confidence changes depth, never warmth): builds the per-turn directive for all four
  `(correct, confidence band)` combinations and asserts the encouragement half is byte-identical
  across all four — only the follow-up varies.

## Demo script

1. Pick **Strong/Consistent**, chat a few turns (text mode), Snapshot.
2. Pick **Struggling/Gapped**, chat a few turns, Snapshot. Structure should visibly tighten;
   encouragement should not.
3. Open **Compare snapshots** — both cards side by side, aligned rows.
4. Switch to **Voice mode**, answer a bank question hesitantly-but-correctly (e.g. mumble "um, I
   think it's twelve" to "What is seven plus five?") → full praise + "how did you get that?" Then
   answer confidently-and-wrongly ("it's eleven", clearly) → same warmth, visibly deeper
   explanation.
5. Read the confidence readout on the metrics strip — the raw transcript, the three signal
   numbers, the resulting band. It's there so the band is auditable, not asserted.

**If step 4 misbehaves**, check in this order — see the plan's Finding 6 for why:

1. Read the raw transcript. Is it what was actually said? (Word-level timestamps can degrade
   Gemini's transcription accuracy, and correctness is a substring match — one mistranscribed
   answer word flips `correct?` and silently picks a different routing-table row.)
2. If the transcript is right, check the three numbers against the thresholds in
   `ConfidenceThresholds` (`Confidence.kt`) — they're hand-picked, not calibrated, and are the
   first knob to turn.
3. Only then suspect the scoring logic itself.

An **offline sample** toggle sits next to voice mode — it swaps live transcription for two canned
fixtures (the same ones `CoreTest.kt` uses) so the demo survives bad wifi. A failed live call falls
back to it automatically.

## What's deliberately out of scope

Persistence, auth, real student data, a DI framework, a navigation library, silence-detection
auto-stop (the student taps Done). Also: the API key is embedded in the built APK, which is fine
for a demo and not for a shipped build — a real version needs a backend proxy holding the key.
