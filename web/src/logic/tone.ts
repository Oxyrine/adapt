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
    // ponytail: a second round of live testing still read too similar across profiles even after
    // switching from permissions to requirements. Escalating with an explicit persona plus one
    // worked example per extreme -- a concrete example changes model output far more reliably than
    // another paragraph describing the desired vibe in the abstract.
    let structureText = '';
    switch (profile.structure) {
      case 'LOOSE':
        structureText = 'You\'re basically this student\'s chill study buddy right now, not a formal teacher. Work a joke, fun fact, or friendly tangent into almost every reply, and use relaxed, casual language freely. Example cadence: "Ha, close! It\'s actually 12 -- fun fact, that\'s basically a dozen eggs. Wanna try a trickier one?" Skip numbered steps entirely unless the question truly demands them.';
        break;
      case 'MEDIUM':
        structureText = "Keep turns a moderate length -- a couple of sentences, friendly but focused. Use scaffolding steps when explaining something, and toss in an occasional light aside, but don't force one into every single reply.";
        break;
      case 'TIGHT':
        structureText = 'You\'re in strict, no-nonsense mode right now -- almost like a drill instructor giving orders. Keep every reply short and strictly businesslike: no small talk, no jokes, no tangents, ever, and under 25 words outside of any numbered steps. Always number every step, even a one-step answer. Example cadence: "Incorrect. 1) 7 plus 5 is 12. Try the next one." The delivery is brisk -- but the encouragement phrase itself must still land warmly, per the instruction below.';
        break;
    }

    let encouragementText = '';
    switch (profile.encouragement) {
      case 'STANDARD':
        encouragementText = 'Acknowledge correct effort with one brief, plain phrase like "good job" or "nice work" -- no exclamation marks, no extra warmth beyond that single phrase.';
        break;
      case 'HIGH':
        encouragementText = 'This student needs big, obvious support every single turn -- think enthusiastic cheerleader, not a quiet nod. Open or close nearly every reply with a bold, exclamation-mark praise phrase -- "Great job!!", "You\'re crushing it!", or "You\'ve totally got this!" -- and don\'t hold back, even when correcting a mistake.';
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
    // answered with unrelated travel trivia instead of short numbered steps. The blanket
    // "not padding with unrelated trivia either" fix for that then broke LOOSE -- it directly
    // contradicts LOOSE's own persona instruction to work a joke/fun fact/tangent into almost
    // every reply, and live testing showed that contradiction just killing the LOOSE persona
    // outright (the model dropped the tangent entirely rather than resolve the conflict). Each
    // structure level needs its own compatible depth guidance, not one rule applied to all three.
    let depthGuidance = '';
    switch (profile.structure) {
      case 'TIGHT':
        depthGuidance = 'Depth is never an excuse to break the TIGHT rule above -- more explanation means more short numbered steps, not tangents, trivia, or padding.';
        break;
      case 'LOOSE':
        depthGuidance = "The joke, fun fact, or tangent from the persona above still has to sit alongside a real, clear answer -- work it in, don't let it replace actually explaining.";
        break;
      case 'MEDIUM':
        depthGuidance = "Depth means actually working through the reasoning -- an occasional light aside is fine, but don't let it replace the actual explanation.";
        break;
    }
    prompt += `\n\nInstruction: Reply directly, suitable for spoken conversation -- no markdown, bullets, or asterisks. Match the length to what was actually asked: a quick move-on or gentle correction is one short sentence; when asked to explain a misconception or walk through reasoning, actually do it. ${depthGuidance}`;
    return prompt;
  }
};
