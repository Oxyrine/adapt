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
    // ponytail: "welcome"/"fine" are permissions, not instructions -- a model asked to *permit* a
    // joke usually just doesn't bother. Live testing showed every profile landing on nearly the
    // same length and register. Rewritten as explicit requirements per level so the contrast is
    // something a reviewer can hear, not just something the metrics count.
    let structureText = '';
    switch (profile.structure) {
      case 'LOOSE':
        structureText = 'Turns can run long and conversational, like chatting with a laid-back mentor. Work a light joke, fun fact, or friendly tangent into almost every reply -- this student thrives on a relaxed vibe, not a formal lesson. Skip numbered steps unless the question truly needs them.';
        break;
      case 'MEDIUM':
        structureText = "Keep turns a moderate length -- a couple of sentences, friendly but focused. Use scaffolding steps when explaining something, and toss in an occasional light aside, but don't force one into every single reply.";
        break;
      case 'TIGHT':
        structureText = 'Keep every reply short and strictly businesslike -- no small talk, no jokes, no tangents, ever. Always break any explanation into clearly numbered steps (1, 2, 3...). Get straight to the point in as few words as possible.';
        break;
    }

    let encouragementText = '';
    switch (profile.encouragement) {
      case 'STANDARD':
        encouragementText = 'Acknowledge correct effort with a brief, genuine phrase like "good job" or "nice work" -- warm, but matter-of-fact, not gushing.';
        break;
      case 'HIGH':
        encouragementText = 'This student needs to feel strongly supported, every single turn. Open or close nearly every reply with enthusiastic, specific praise -- phrases like "Great job!", "You\'re doing awesome!", or "You\'ve got this!" -- and make the encouragement impossible to miss, even when correcting a mistake.';
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
    // asked for. Fixing that (allowing more depth on the "explain the misconception" branch)
    // then broke a different thing live: on a TIGHT-structure profile, "explain more" got
    // answered with unrelated travel trivia (Eiffel Tower, Notre-Dame) instead of short numbered
    // steps -- the depth instruction and the structure rule above were fighting, and depth won.
    // Depth now has to stay inside whatever the structure rule already said.
    const depthWithinStructure = profile.structure === 'TIGHT'
      ? 'Depth is never an excuse to break the TIGHT rule above -- more explanation means more short numbered steps, not tangents, trivia, or padding.'
      : 'Depth means actually working through the reasoning, not padding with unrelated trivia either.';
    prompt += `\n\nInstruction: Reply directly, suitable for spoken conversation -- no markdown, bullets, or asterisks. Match the length to what was actually asked: a quick move-on or gentle correction is one short sentence; when asked to explain a misconception or walk through reasoning, actually do it. ${depthWithinStructure}`;
    return prompt;
  }
};
