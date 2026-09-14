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

    let body = '';
    switch (profile.structure) {
      case 'LOOSE':
        body = "By the way, that tracks. Let's keep going at your own pace -- no need to rush this one.";
        break;
      case 'MEDIUM':
        body = "First, that's on the right track. Next, let's build on it a little before moving on.";
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
