import type { TurnMetrics, SessionMetrics } from '../types';

const PRAISE_MARKERS = [
  "that's right", "nicely done", "great work", "you got it", "nice work",
  "well done", "great job", "good job", "exactly", "awesome", "perfect", "good"
];

function escapeRegex(string: string) {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const PRAISE_PATTERN = new RegExp(
  '\\b(' + PRAISE_MARKERS.map(escapeRegex).join('|') + ')\\b',
  'gi'
);
const SCAFFOLD_MARKERS = ["first,", "first ", "next,", "next ", "then,", "then "];
const NUMBERED_LINE = /\n\s*\d+[.)]\s/g;
const JOKE_MARKERS = ["haha", "lol", "by the way", "fun fact", "speaking of"];

export const MetricsScanner = {
  scan(reply: string): TurnMetrics {
    const lower = reply.toLowerCase();
    const words = reply.trim().split(/\s+/).filter(w => w.length > 0);
    const wordCount = words.length;

    let scaffoldingSteps = SCAFFOLD_MARKERS.filter(m => lower.includes(m)).length;
    const numberedMatches = reply.match(NUMBERED_LINE);
    if (numberedMatches) {
      scaffoldingSteps += numberedMatches.length;
    }

    const jokeTangentCount = JOKE_MARKERS.filter(m => lower.includes(m)).length;

    const praiseMatches = reply.match(PRAISE_PATTERN);
    const praiseMarkers = praiseMatches ? praiseMatches.length : 0;

    return {
      wordCount,
      scaffoldingSteps,
      jokeTangentCount,
      praiseMarkers
    };
  }
};

export function createInitialSessionMetrics(): SessionMetrics {
  return {
    turns: 0,
    totalWords: 0,
    totalScaffolding: 0,
    totalJokes: 0,
    totalPraise: 0
  };
}

export function addTurnToMetrics(session: SessionMetrics, turn: TurnMetrics): SessionMetrics {
  return {
    turns: session.turns + 1,
    totalWords: session.totalWords + turn.wordCount,
    totalScaffolding: session.totalScaffolding + turn.scaffoldingSteps,
    totalJokes: session.totalJokes + turn.jokeTangentCount,
    totalPraise: session.totalPraise + turn.praiseMarkers
  };
}

export function getAvgWords(session: SessionMetrics): number {
  return session.turns === 0 ? 0 : session.totalWords / session.turns;
}

export function getAvgPraise(session: SessionMetrics): number {
  return session.turns === 0 ? 0 : session.totalPraise / session.turns;
}
