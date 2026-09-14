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
        encouragementText = 'Acknowledge correct effort with genuine praise.';
        break;
      case 'HIGH':
        encouragementText = 'Praise effort often and specifically -- this student needs to feel supported, not just corrected.';
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
    prompt += `\n\nInstruction: Reply directly in 1 to 2 concise sentences suitable for spoken conversation. Do not use markdown, bullets, or asterisks.`;
    return prompt;
  }
};
