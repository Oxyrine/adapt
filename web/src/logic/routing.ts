import type { ConfidenceBand } from '../types';

export interface TurnDirective {
  encouragement: string;
  followUp: string;
}

export const Routing = {
  // Caveat 3: warmth cannot drift with confidence. Hit live: "The capital of France is Paris,
  // not Tokyo. Good job." -- warmth stayed constant, but "good job" landed right after the wrong
  // answer with nothing telling the model WHAT it's praising, so it read as praising the wrong
  // fact itself, not the attempt. Fixed by naming the target of the praise explicitly.
  ENCOURAGEMENT: "Acknowledge the student's effort warmly, at the same level regardless of this answer's correctness or confidence. If the answer is wrong, state the correction first and put the encouragement after it, phrased so it clearly targets the attempt, not the answer -- never open a wrong-answer reply with the praise phrase, and never let it read like you're endorsing a wrong answer as correct.",

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
