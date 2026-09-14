export type Performance = 'STRONG' | 'STRUGGLING';
export type Regularity = 'CONSISTENT' | 'GAPPED';
export type StructureLevel = 'LOOSE' | 'MEDIUM' | 'TIGHT';
export type EncouragementLevel = 'STANDARD' | 'HIGH';
export type ConfidenceBand = 'HIGH' | 'LOW';

export interface ToneProfile {
  performance: Performance;
  regularity: Regularity;
  structure: StructureLevel;
  encouragement: EncouragementLevel;
}

export interface Word {
  text: string;
  startMs: number;
  endMs: number;
}

export interface ConfidenceSignals {
  latencyMs: number;
  hedgeRate: number;
  selfCorrections: number;
}

export interface ConfidenceReadout {
  transcript: string;
  signals: ConfidenceSignals;
  band: ConfidenceBand;
  correct: boolean;
  triggeredFollowUp: boolean;
}

export interface TurnMetrics {
  wordCount: number;
  scaffoldingSteps: number;
  jokeTangentCount: number;
  praiseMarkers: number;
}

export interface SessionMetrics {
  turns: number;
  totalWords: number;
  totalScaffolding: number;
  totalJokes: number;
  totalPraise: number;
}

export interface MetricsSnapshot {
  label: string;
  metrics: SessionMetrics;
}

export interface BankQuestion {
  prompt: string;
  expectedAnswer: string;
}

export interface ChatMessage {
  fromAlbert: boolean;
  text: string;
}

export type MicState = 'IDLE' | 'ARMED' | 'RECORDING' | 'PROCESSING';
export type Screen = 'PROFILE' | 'CHAT' | 'COMPARE';
