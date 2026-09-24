# Adaptive Tutor Tone

A demo tutor (Prof. Albert) for PopGamma that adjusts to a student two ways: how they've been
doing over time, and how confident they sounded on the specific answer they just gave. The point
is a contrast a reviewer can see in a few minutes, not a feature list -- structure tightens for a
struggling student, encouragement never drops, and a confident-wrong answer gets a visibly deeper
follow-up than a hesitant-correct one at the same warmth.

**Live demo:** [adapt-three-mu.vercel.app](https://adapt-three-mu.vercel.app) -- the site also has
a "Get Android App" download link for the native build, so one URL covers both platforms.

## What's here

Two apps sharing the same design, each with its own implementation:

| | Web (`web/`) | Android (`app/`) |
|---|---|---|
| UI | React 19 + TypeScript + Vite | Kotlin + Jetpack Compose |
| Voice input | Browser Web Speech API, with a raw-audio + Whisper fallback | Android's own `SpeechRecognizer`, same fallback |
| Voice output | Browser `speechSynthesis` | Android `TextToSpeech` |
| Tutor replies | Groq (`openai/gpt-oss-20b`) | Groq (`openai/gpt-oss-20b`) |

The decision-making logic -- the tone table, the confidence scorer, the routing rules, the metrics
scanner, the question bank -- is written twice, once per platform, with zero framework imports in
either copy. That's what lets `CoreTest.kt` run as a plain JVM test with no emulator.

## The two rules that can't break

1. Structure can tighten for a student who needs more scaffolding. Encouragement can only stay the
   same or increase -- it never drops.
2. A student's overall profile sets the tutor's session-level style. How confident they sounded on
   one answer only changes how deep the follow-up goes, never how warm the reply is.

Both are checked automatically -- see [What the tests check](#what-the-tests-check) -- not just
asserted in this paragraph.

## Running it

### Web

```bash
cd web
npm install
npm run dev
```

Needs a Groq API key to call the live tutor: either set `VITE_GROQ_API_KEY` in `web/.env.local`,
or just enter it in the app's own Settings screen at runtime (stored in `localStorage`). Without a
key, chat and voice scoring still work end-to-end against a network/API failure gracefully -- see
[Known limitations](#known-limitations).

### Android

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

1. Copy `local.properties.example` to `local.properties` and set `GROQ_API_KEY` (gitignored, never
   commit it) -- or skip this and enter a key at runtime from the app's own settings screen once
   it's installed, same as the web app.
2. Sync Gradle, run on a device or emulator. Grant the microphone permission when prompted.

Both verified on this machine: `testDebugUnitTest` and `assembleDebug` both pass on a JDK 17 +
Android SDK toolchain, and the built APK has been run and tested on a real device (not just an
emulator) -- including the voice path, which is the part most worth verifying for real, since a
speech-recognition engine is very hard to reason about from source alone.

## What the tests check

`CoreTest.kt` isn't incidental coverage -- it's the two rules above turned into assertions that
fail the build if either one breaks:

- **Rule 1**: walks every cell of the tone table and asserts encouragement never decreases as
  structure gets tighter, and separately asserts no cell is encouraged less than the loosest one.
- **Rule 2**: builds the per-turn directive for all four `(correct, confidence band)` combinations
  and asserts the encouragement text is byte-identical across all four -- only the follow-up
  varies. Also asserts the routing prompt states ground truth explicitly (the correct answer, not
  left for the model to re-derive) -- see the note on that below.

## Demo script

1. Pick **Strong / Consistent**, chat a few turns, Snapshot.
2. Pick **Struggling / Gapped**, chat a few turns. Structure should visibly tighten; encouragement
   should not.
3. Open **Compare snapshots** -- both cards side by side, aligned rows.
4. Answer a bank question hesitantly-but-correctly (e.g. "um, I think it's twelve" to "What is
   seven plus five?") -> full praise + "how did you get that?" Then answer confidently-and-wrongly
   ("it's eleven", clearly) -> same warmth, visibly deeper explanation.
5. Read the confidence readout on the metrics strip -- the raw transcript and the three signal
   numbers behind the band, not just the band itself. It's there so the result is auditable, not
   asserted.

**If step 4 misbehaves**, check in this order:

1. Read the raw transcript on the metrics strip. Is it what was actually said? A near-silent
   recording is guarded against before it reaches scoring (see below), but a real mis-transcription
   can still happen.
2. If the transcript is right, check the three numbers against the thresholds in
   `ConfidenceThresholds` (`Confidence.kt`) -- hand-picked, not calibrated, and the first knob to
   turn if something reads as more or less confident than expected.
3. Only then suspect the scoring or routing logic itself.

## Known limitations

- **Confidence is measured from the transcript, not the actual voice.** The scorer reads pause
  length, hedge words, and self-corrections from what was said -- not pitch, tone, or delivery.
  Reading the voice itself directly would need an audio-native model, and the word-level timing
  data this scorer depends on is documented to reduce transcription accuracy on top of that. A
  deliberate scope decision for this phase, not an oversight.
- **The question bank is small and fixed on purpose.** Four questions, matched by exact string, not
  judged by the model -- asking the model to judge correctness would reintroduce exactly the
  variance a live demo can't afford.
- **The routing prompt states correctness as a fact, not a question.** Earlier versions left the
  model to re-derive whether an answer was right from the transcript, which a fast, low-effort
  model is not reliable at for arithmetic. The prompt now states the correct answer explicitly.
- The API key ships inside a downloaded APK if one is baked in at build time, or is entered by the
  user at runtime otherwise; a real (non-demo) build needs a backend proxy holding the key instead.
- No persistence, no auth, no real student data, no DI framework, no navigation library.
