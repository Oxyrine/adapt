import type { Word } from '../types';

export interface GeminiResult<T> {
  success: boolean;
  value?: T;
  error?: string;
}

// ponytail: Groq's contract here follows their published OpenAI-compatible docs, not a live
// capture. The Gemini version of this file only got its response shape right after real curl
// calls proved the docs wrong -- verify this one against a real key before trusting it in a demo.
export const GroqClient = {
  // Two guessed llama-3.x model ids both 404'd (model_not_found) -- this key's account has no
  // llama-3.x models at all. Confirmed via GET /openai/v1/models against the real key: this is
  // one of the ids that actually came back, not another guess.
  CHAT_MODEL: 'openai/gpt-oss-20b',
  TRANSCRIBE_MODEL: 'whisper-large-v3', // word timestamps need the full model, not -turbo

  async chat(apiKey: string, prompt: string): Promise<GeminiResult<string>> {
    if (!apiKey || apiKey.trim() === '') {
      return { success: false, error: 'No API key configured (using offline reply)' };
    }

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 15000);

      const response = await fetch('/groq-api/openai/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${apiKey.trim()}`
        },
        body: JSON.stringify({
          model: this.CHAT_MODEL,
          messages: [{ role: 'user', content: prompt }],
          temperature: 0.7,
          // gpt-oss-20b is a reasoning model -- it can spend part of max_tokens on hidden
          // reasoning before writing the final answer. Hit live: a 250-token cap plus reasoning
          // overhead returned HTTP 200 with an empty content field once replies were asked to
          // actually explain something, not just move on. reasoning_effort "low" keeps that
          // overhead small; the raised cap gives the real answer room either way.
          max_tokens: 500,
          reasoning_effort: 'low'
        }),
        signal: controller.signal
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        const err = await response.text();
        return { success: false, error: `HTTP ${response.status}: ${err.slice(0, 200)}` };
      }

      const data = await response.json();
      const text = data.choices?.[0]?.message?.content?.trim();
      if (text) {
        return { success: true, value: text };
      }
      return { success: false, error: 'Empty response from tutor model' };
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Network error';
      return { success: false, error: msg };
    }
  },

  async transcribeWithTimestamps(apiKey: string, wavBase64: string): Promise<GeminiResult<Word[]>> {
    if (!apiKey || apiKey.trim() === '') {
      return { success: false, error: 'No API key configured' };
    }

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 20000);

      const form = new FormData();
      form.append('model', this.TRANSCRIBE_MODEL);
      form.append('response_format', 'verbose_json');
      form.append('timestamp_granularities[]', 'word');
      form.append('file', base64ToBlob(wavBase64, 'audio/wav'), 'audio.wav');

      const response = await fetch('/groq-api/openai/v1/audio/transcriptions', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${apiKey.trim()}` },
        body: form,
        signal: controller.signal
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        const err = await response.text();
        return { success: false, error: `HTTP ${response.status}: ${err.slice(0, 200)}` };
      }

      const data = await response.json();
      const words: Word[] = (data.words || []).map((w: { word: string; start: number; end: number }) => ({
        text: w.word.trim(),
        startMs: Math.round(w.start * 1000),
        endMs: Math.round(w.end * 1000)
      }));

      return { success: true, value: words };
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Transcription network error';
      return { success: false, error: msg };
    }
  },

  /** Plain transcript, no word timestamps -- for the "direct audio" browser fallback, which only
   *  needs the text, not the confidence-scoring signal. Same endpoint, timestamps just omitted. */
  async transcribeAudio(apiKey: string, wavBase64: string): Promise<GeminiResult<string>> {
    if (!apiKey || apiKey.trim() === '') {
      return { success: false, error: 'No API key configured' };
    }

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 15000);

      const form = new FormData();
      form.append('model', this.TRANSCRIBE_MODEL);
      form.append('response_format', 'json');
      form.append('file', base64ToBlob(wavBase64, 'audio/wav'), 'audio.wav');

      const response = await fetch('/groq-api/openai/v1/audio/transcriptions', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${apiKey.trim()}` },
        body: form,
        signal: controller.signal
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        return { success: false, error: 'Audio transcription failed' };
      }

      const data = await response.json();
      const text = (data.text || '').trim();
      return { success: true, value: text };
    } catch {
      return { success: false, error: 'Audio transcription failed' };
    }
  }
};

function base64ToBlob(base64: string, mimeType: string): Blob {
  const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
  return new Blob([bytes], { type: mimeType });
}
