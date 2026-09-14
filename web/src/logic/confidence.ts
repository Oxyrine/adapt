import type { Word, ConfidenceSignals, ConfidenceBand } from '../types';

export const ConfidenceThresholds = {
  LATENCY_MS: 1500,
  HEDGE_RATE: 0.12,
  SELF_CORRECTIONS: 1,
};

export const ConfidenceScorer = {
  HEDGE_WORDS: new Set([
    'um', 'uh', 'erm', 'hmm', 'ah', 'er', 'well', 'maybe', 'probably'
  ]),
  HEDGE_BIGRAMS: new Set([
    'i think', 'is it', 'not sure', 'kind of', 'sort of', 'i guess', 'maybe it', 'or maybe',
    'could be', 'might be', 'not certain', 'i believe'
  ]),
  RESTART_UNIGRAMS: new Set(['actually']),
  RESTART_BIGRAMS: new Set(['no wait', 'i mean', 'wait no', 'or rather']),

  normalizeToken(raw: string): string {
    const cleaned = raw.toLowerCase().replace(/^[,\.?!…\-;:\"'()]+|[,\.?!…\-;:\"'()]+$/g, '');
    if (/^u+m+$/.test(cleaned)) return 'um';
    if (/^u+h+$/.test(cleaned)) return 'uh';
    if (/^e+r+m*$/.test(cleaned)) return 'erm';
    if (/^h+m+$/.test(cleaned)) return 'hmm';
    if (/^a+h+$/.test(cleaned)) return 'ah';
    if (/^e+r+$/.test(cleaned)) return 'er';
    return cleaned;
  },

  score(words: Word[]): ConfidenceSignals {
    if (words.length === 0) {
      return { latencyMs: 0, hedgeRate: 0, selfCorrections: 0 };
    }

    const latencyMs = words[0].startMs;
    const tokens = words
      .map(w => this.normalizeToken(w.text))
      .filter(t => t.length > 0);

    let hedgeHits = 0;
    for (let i = 0; i < tokens.length; i++) {
      if (this.HEDGE_WORDS.has(tokens[i])) hedgeHits++;
      if (i + 1 < tokens.length && this.HEDGE_BIGRAMS.has(`${tokens[i]} ${tokens[i + 1]}`)) {
        hedgeHits++;
      }
    }
    const hedgeRate = tokens.length > 0 ? hedgeHits / tokens.length : 0;

    let selfCorrections = 0;
    // adjacent repeated tokens ("the the")
    for (let i = 1; i < tokens.length; i++) {
      if (tokens[i] === tokens[i - 1]) selfCorrections++;
    }

    // repeated bigram within a short window
    for (let i = 0; i < tokens.length; i++) {
      if (i + 1 >= tokens.length) continue;
      const bigram = `${tokens[i]} ${tokens[i + 1]}`;
      const windowEnd = Math.min(i + 6, tokens.length - 1);
      for (let j = i + 2; j <= windowEnd; j++) {
        if (j + 1 >= tokens.length) continue;
        if (bigram === `${tokens[j]} ${tokens[j + 1]}`) {
          selfCorrections++;
          break;
        }
      }
    }

    // explicit restart cues
    for (let i = 0; i < tokens.length; i++) {
      if (this.RESTART_UNIGRAMS.has(tokens[i])) selfCorrections++;
      if (i + 1 < tokens.length && this.RESTART_BIGRAMS.has(`${tokens[i]} ${tokens[i + 1]}`)) {
        selfCorrections++;
      }
    }

    return { latencyMs, hedgeRate, selfCorrections };
  },

  band(signals: ConfidenceSignals): ConfidenceBand {
    let crossed = 0;
    if (signals.latencyMs > ConfidenceThresholds.LATENCY_MS) crossed++;
    if (signals.hedgeRate > ConfidenceThresholds.HEDGE_RATE) crossed++;
    if (signals.selfCorrections > ConfidenceThresholds.SELF_CORRECTIONS) crossed++;
    return crossed >= 2 ? 'LOW' : 'HIGH';
  }
};

export const OfflineFixtures = {
  hesitantCorrect: [
    { text: 'um', startMs: 1800, endMs: 2000 },
    { text: 'I', startMs: 2050, endMs: 2150 },
    { text: 'think', startMs: 2150, endMs: 2300 },
    { text: "it's", startMs: 2300, endMs: 2400 },
    { text: 'twelve', startMs: 2450, endMs: 2700 },
  ] as Word[],

  confidentWrong: [
    { text: "it's", startMs: 300, endMs: 450 },
    { text: 'eleven', startMs: 450, endMs: 750 },
  ] as Word[],
};
