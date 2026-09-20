import type { ToneProfile, ConfidenceBand } from '../types';

export const FallbackReplies = {
  ENCOURAGEMENT_STANDARD: 'Good effort.',
  ENCOURAGEMENT_HIGH: "Good, and great work -- you're doing well, keep it up.",
  OFFLINE_NOTE: '\n\n(Offline reply -- live tutor unavailable right now.)',

  reply(profile: ToneProfile, correct?: boolean | null, band?: ConfidenceBand | null): string {
    const encouragement =
      profile.encouragement === 'HIGH'
        ? this.ENCOURAGEMENT_HIGH
        : this.ENCOURAGEMENT_STANDARD;

    // "That tracks" / "on the right track" claim the answer was fine -- said unconditionally,
    // that's exactly the false "correct/good job for a wrong answer" bug this whole demo exists
    // to prevent, just baked into the canned offline text instead of the live prompt.
    // Encouragement (above) can still praise the effort when wrong; the body must not imply the
    // answer itself was right.
    let body = '';
    switch (profile.structure) {
      case 'LOOSE':
        body = correct === false
          ? "By the way, this one needs another look. Let's keep going at your own pace -- no need to rush it."
          : "By the way, that tracks. Let's keep going at your own pace -- no need to rush this one.";
        break;
      case 'MEDIUM':
        body = correct === false
          ? "First, let's take a closer look at this one. Next, let's build on it a little before moving on."
          : "First, that's on the right track. Next, let's build on it a little before moving on.";
        break;
      case 'TIGHT':
        body = "First, note the key point. Next, check it against the question. Then, move to the next step.";
        break;
    }

    let followUp = '';
    if (correct !== undefined && correct !== null && band !== undefined && band !== null) {
      if (correct && band === 'HIGH') {
        followUp = " Let's move to the next question.";
      } else if (correct && band === 'LOW') {
        followUp = ' Walk me through how you got that, just to check.';
      } else if (!correct && band === 'LOW') {
        followUp = " That's a small slip, not a big deal -- here's the fix.";
      } else {
        // !correct && band === 'HIGH' -- confident wrong, the strongest signal
        followUp = " Let's slow down and walk through this one directly, step by step, since it's worth understanding fully.";
      }
    }

    return `${encouragement} ${body}${followUp}${this.OFFLINE_NOTE}`;
  }
};
