import type { Performance, Regularity, StructureLevel, EncouragementLevel, ToneProfile } from '../types';

export const ToneTable = {
  cells: [
    { performance: 'STRONG' as Performance, regularity: 'CONSISTENT' as Regularity, structure: 'LOOSE' as StructureLevel, encouragement: 'STANDARD' as EncouragementLevel },
    { performance: 'STRONG' as Performance, regularity: 'GAPPED' as Regularity, structure: 'MEDIUM' as StructureLevel, encouragement: 'STANDARD' as EncouragementLevel },
    { performance: 'STRUGGLING' as Performance, regularity: 'CONSISTENT' as Regularity, structure: 'TIGHT' as StructureLevel, encouragement: 'STANDARD' as EncouragementLevel },
    { performance: 'STRUGGLING' as Performance, regularity: 'GAPPED' as Regularity, structure: 'TIGHT' as StructureLevel, encouragement: 'HIGH' as EncouragementLevel },
  ] as ToneProfile[],

  lookup(performance: Performance, regularity: Regularity): ToneProfile {
    const found = this.cells.find(c => c.performance === performance && c.regularity === regularity);
    if (!found) throw new Error(`Unknown profile ${performance} / ${regularity}`);
    return found;
  },

  all(): ToneProfile[] {
    return this.cells;
  }
};

export const PromptBuilder = {
  systemPrompt(profile: ToneProfile): string {
    let structureText = '';
    switch (profile.structure) {
      case 'LOOSE':
        structureText = 'Turns can run long. Jokes and tangents are welcome. Keep scaffolding light.';
        break;
      case 'MEDIUM':
        structureText = 'Keep turns a moderate length. Use some scaffolding steps. Occasional light tangents are fine.';
        break;
      case 'TIGHT':
        structureText = 'Keep turns short. Break explanations into clear numbered steps. No jokes or tangents -- stay on task.';
        break;
    }

    let encouragementText = '';
    switch (profile.encouragement) {
      case 'STANDARD':
        encouragementText = 'Acknowledge correct effort with genuine praise, using a recognizable phrase like "good job", "nice work", or "well done".';
        break;
      case 'HIGH':
        encouragementText = 'Praise effort often and specifically -- this student needs to feel supported, not just corrected. Use warm, explicit praise like "great job" or "you got it".';
        break;
    }

    return `You are Prof. Albert, a tutor. ${structureText} ${encouragementText}\nA struggling student gets a more focused tutor, never a colder one: encouragement never drops because structure has tightened.`;
  },

  buildPrompt(profile: ToneProfile, addendum: string | null, history: string, userText: string): string {
    let prompt = this.systemPrompt(profile);
    if (addendum) {
      prompt += `\n\n${addendum}`;
    }
    if (history.trim().length > 0) {
      prompt += `\n\n${history}`;
    }
    prompt += `\nStudent: ${userText}`;
    // A flat "1 to 2 sentences" cap here used to override whatever the routing addendum above
    // asked for -- a confident-wrong answer's "walk through the misconception directly" got
    // squashed to the same length as a plain move-on, so every reply read identically regardless
    // of band/correctness. Length now follows what was actually asked for instead of a fixed cap.
    prompt += `\n\nInstruction: Reply directly, suitable for spoken conversation -- no markdown, bullets, or asterisks. Match the length to what was actually asked: a quick move-on or gentle correction is one short sentence, but when asked to explain a misconception or walk through reasoning, take three or four sentences and actually do it -- do not compress a real explanation into one line.`;
    return prompt;
  }
};
