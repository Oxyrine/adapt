import type { BankQuestion } from '../types';

const ONES: Record<string, number> = {
  zero: 0, one: 1, two: 2, three: 3, four: 4, five: 5,
  six: 6, seven: 7, eight: 8, nine: 9, ten: 10,
  eleven: 11, twelve: 12, thirteen: 13, fourteen: 14, fifteen: 15,
  sixteen: 16, seventeen: 17, eighteen: 18, nineteen: 19
};

const TENS: Record<string, number> = {
  twenty: 20, thirty: 30, forty: 40, fifty: 50,
  sixty: 60, seventy: 70, eighty: 80, ninety: 90
};

function wordsToDigits(phrase: string): string | null {
  const words = phrase.toLowerCase().trim().split(/\s+/);
  if (words.length === 1 && ONES[words[0]] !== undefined) {
    return ONES[words[0]].toString();
  }
  if (words.length === 1 && TENS[words[0]] !== undefined) {
    return TENS[words[0]].toString();
  }
  if (words.length === 2 && TENS[words[0]] !== undefined && ONES[words[1]] && ONES[words[1]] >= 1 && ONES[words[1]] <= 9) {
    return (TENS[words[0]] + ONES[words[1]]).toString();
  }
  return null;
}

export const QuestionBank = {
  questions: [
    { prompt: 'What is seven plus five?', expectedAnswer: 'twelve' },
    { prompt: 'What is nine times three?', expectedAnswer: 'twenty seven' },
    { prompt: 'What is the capital of France?', expectedAnswer: 'paris' },
    { prompt: 'What color do you get mixing blue and yellow?', expectedAnswer: 'green' },
  ] as BankQuestion[],

  isCorrect(question: BankQuestion, transcript: string): boolean {
    const normalize = (s: string) =>
      s.toLowerCase()
        .replace(/-/g, ' ')
        .replace(/\b(\d+)(st|nd|rd|th)\b/g, '$1')
        .replace(/\btwelfth\b/g, 'twelve')
        .replace(/[^a-z0-9 ]/g, '')
        .replace(/\s+/g, ' ')
        .trim();

    const normalizedTranscript = normalize(transcript);
    const normalizedExpected = normalize(question.expectedAnswer);

    const expectedRegex = new RegExp(`\\b${normalizedExpected}\\b`, 'i');
    if (expectedRegex.test(normalizedTranscript)) {
      return true;
    }

    // Accept digit form (e.g., "12." for "twelve")
    const digitForm = wordsToDigits(question.expectedAnswer);
    if (digitForm) {
      const digitRegex = new RegExp(`\\b${digitForm}\\b`);
      if (digitRegex.test(normalizedTranscript)) {
        return true;
      }
    }

    return false;
  }
};
