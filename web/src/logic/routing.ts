import type { ConfidenceBand } from '../types';

export interface TurnDirective {
  encouragement: string;
  followUp: string;
}

export const Routing = {
  // Caveat 3: warmth cannot drift with confidence
  ENCOURAGEMENT: "Acknowledge the student's effort warmly, at the same level regardless of this answer's correctness or confidence.",

  directive(correct: boolean, band: ConfidenceBand): TurnDirective {
    let followUp = '';
    if (correct && band === 'HIGH') {
      followUp = 'Move on to the next question.';
    } else if (correct && band === 'LOW') {
      followUp = 'Ask the student to walk through how they got the answer, to check understanding -- not because you doubt them.';
    } else if (!correct && band === 'LOW') {
      followUp = 'Give a gentle, expected correction -- this looks like a careless slip, not a misconception.';
    } else {
      // !correct && band === 'HIGH'
      followUp = 'Flag this for deeper explanation and walk through the misconception directly -- a confident wrong answer is the strongest available signal of a real misconception, not a careless slip.';
    }
    return {
      encouragement: this.ENCOURAGEMENT,
      followUp
    };
  },

  promptAddendum(correct: boolean, band: ConfidenceBand): string {
    const d = this.directive(correct, band);
    return `${d.encouragement} ${d.followUp}`;
  }
};
