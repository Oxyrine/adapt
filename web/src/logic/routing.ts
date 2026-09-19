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

  // expectedAnswer is stated as ground truth up front, not left for the model to re-derive from
  // the transcript. QuestionBank.isCorrect() already computed `correct` deterministically; leaving
  // the model to independently judge correctness for its own reply is exactly what let it say
  // "correct, good job" to a wrong arithmetic answer in testing, and skip the misconception
  // explanation entirely for a TIGHT-structure profile because it didn't think anything was wrong
  // to begin with. A small, low-reasoning-effort model is not a reliable arithmetic checker and
  // shouldn't need to be one here -- the fact is already known.
  promptAddendum(correct: boolean, band: ConfidenceBand, expectedAnswer: string): string {
    const truth = correct
      ? "Fact: the student's answer is correct."
      : `Fact: the student's answer is incorrect. The correct answer is "${expectedAnswer}". State plainly that it's incorrect -- never imply the student was right.`;
    const d = this.directive(correct, band);
    return `${truth} ${d.encouragement} ${d.followUp}`;
  }
};
