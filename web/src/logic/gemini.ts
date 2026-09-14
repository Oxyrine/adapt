import type { Word } from '../types';

export interface GeminiResult<T> {
  success: boolean;
  value?: T;
  error?: string;
}

export const GeminiClient = {
  // Ultra-fast model specifically optimized for low-latency tutoring conversations
  FAST_MODEL: 'gemini-3.5-flash-lite',
  TRANSCRIBE_MODEL: 'gemini-3.5-transcribe',

  async chat(apiKey: string, prompt: string): Promise<GeminiResult<string>> {
    if (!apiKey || apiKey.trim() === '') {
      return { success: false, error: 'No API key configured (using offline reply)' };
    }

    const trimmedKey = apiKey.trim();

    // 1. Try ultra-fast generateContent (1.3s response time, zero thinking latency)
    // Try via Vite proxy first, fallback to direct URL
    const urls = [
      `/google-api/v1beta/models/${this.FAST_MODEL}:generateContent?key=${encodeURIComponent(trimmedKey)}`,
      `https://generativelanguage.googleapis.com/v1beta/models/${this.FAST_MODEL}:generateContent?key=${encodeURIComponent(trimmedKey)}`,
      `https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=${encodeURIComponent(trimmedKey)}`
    ];

    const bodyPayload = JSON.stringify({
      contents: [{ parts: [{ text: prompt }] }],
      generationConfig: {
        temperature: 0.7,
        maxOutputTokens: 250 // Keep explanations focused and fast
      }
    });

    for (const url of urls) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 7000); // 7s strict timeout

        const response = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: bodyPayload,
          signal: controller.signal
        });

        clearTimeout(timeoutId);

        if (response.ok) {
          const data = await response.json();
          const text = data.candidates?.[0]?.content?.parts?.[0]?.text;
          if (text && text.trim().length > 0) {
            return { success: true, value: text.trim() };
          }
        }
      } catch {
        // Try next endpoint/model immediately without stalling
      }
    }

    // 2. Try Interactions endpoint with gemini-3.5-flash-lite as secondary
    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 7000);

      const response = await fetch('https://generativelanguage.googleapis.com/v1beta/interactions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-goog-api-key': trimmedKey
        },
        body: JSON.stringify({
          model: this.FAST_MODEL,
          input: [{ type: 'text', text: prompt }]
        }),
        signal: controller.signal
      });

      clearTimeout(timeoutId);

      if (response.ok) {
        const data = await response.json();
        const steps = data.steps || [];
        const output = steps
          .filter((s: { type: string }) => s.type === 'model_output')
          .flatMap((s: { content?: Array<{ text?: string }> }) => s.content || [])
          .map((c: { text?: string }) => c.text || '')
          .join('');

        if (output && output.trim().length > 0) {
          return { success: true, value: output.trim() };
        }
      }
    } catch {
      // Return clear offline fallback
    }

    return {
      success: false,
      error: 'Network timeout or API limit reached'
    };
  },

  async transcribeWithTimestamps(apiKey: string, audioBase64: string): Promise<GeminiResult<Word[]>> {
    if (!apiKey || apiKey.trim() === '') {
      return { success: false, error: 'No API key configured' };
    }

    try {
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 10000);

      const response = await fetch('https://generativelanguage.googleapis.com/v1beta/interactions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'x-goog-api-key': apiKey.trim()
        },
        body: JSON.stringify({
          model: this.TRANSCRIBE_MODEL,
          input: [{ type: 'audio', data: audioBase64, mime_type: 'audio/wav' }],
          generation_config: {
            transcription_config: {
              mode: {
                type: 'verbatim',
                timestamp_granularities: ['word']
              }
            }
          }
        }),
        signal: controller.signal
      });

      clearTimeout(timeoutId);

      if (!response.ok) {
        const err = await response.text();
        return { success: false, error: `HTTP ${response.status}: ${err.slice(0, 200)}` };
      }

      const data = await response.json();
      const words: Word[] = [];
      const steps = data.steps || [];
      for (const step of steps) {
        if (step.type === 'model_output' && step.content) {
          for (const content of step.content) {
            if (content.annotations) {
              for (const ann of content.annotations) {
                if (ann.type === 'word_info') {
                  const startMs = parseOffsetToMs(ann.start_offset);
                  const endMs = parseOffsetToMs(ann.end_offset);
                  words.push({ text: ann.text, startMs, endMs });
                }
              }
            }
          }
        }
      }

      return { success: true, value: words };
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Transcription network error';
      return { success: false, error: msg };
    }
  },

  /**
   * Directly transcribes a WAV Base64 audio clip using Gemini 3.5 Flash Lite multimodal audio input.
   * Completely independent of browser speech recognition engines, works in Brave, Edge, Firefox, Chrome, and over VPNs.
   */
  async transcribeAudio(apiKey: string, audioBase64: string): Promise<GeminiResult<string>> {
    if (!apiKey || apiKey.trim() === '') {
      return { success: false, error: 'No API key configured' };
    }

    const trimmedKey = apiKey.trim();
    const urls = [
      `/google-api/v1beta/models/${this.FAST_MODEL}:generateContent?key=${encodeURIComponent(trimmedKey)}`,
      `https://generativelanguage.googleapis.com/v1beta/models/${this.FAST_MODEL}:generateContent?key=${encodeURIComponent(trimmedKey)}`
    ];

    for (const url of urls) {
      try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 8000);

        const response = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            contents: [{
              parts: [
                { text: 'Transcribe what the student said in this audio clip. Output ONLY the verbatim spoken words. If there is no audible speech, output [NO_SPEECH].' },
                { inline_data: { mime_type: 'audio/wav', data: audioBase64 } }
              ]
            }],
            generationConfig: {
              maxOutputTokens: 60,
              temperature: 0.0
            }
          }),
          signal: controller.signal
        });

        clearTimeout(timeoutId);

        if (response.ok) {
          const data = await response.json();
          const text = data.candidates?.[0]?.content?.parts?.[0]?.text?.trim() || '';
          if (text && text !== '[NO_SPEECH]') {
            return { success: true, value: text };
          }
          return { success: true, value: '' };
        }
      } catch (err) {
        console.warn('Direct transcribe attempt failed:', err);
      }
    }

    return { success: false, error: 'Audio transcription failed' };
  }
};

function parseOffsetToMs(offset?: string): number {
  if (!offset) return 0;
  const num = parseFloat(offset.replace('s', ''));
  return isNaN(num) ? 0 : Math.round(num * 1000);
}
