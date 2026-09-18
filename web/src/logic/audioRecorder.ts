/**
 * AudioRecorder captures microphone audio via Web Audio API,
 * performs adaptive RMS-based Voice Activity Detection (silence auto-stop),
 * and encodes raw PCM into a standard 16kHz mono WAV Base64 string for Gemini.
 *
 * This completely eliminates dependency on Google Chrome's proprietary
 * webkitSpeechRecognition backend, making speech capture work seamlessly in
 * Brave, Edge, Firefox, Chrome, Safari, and across VPNs/ad-blockers.
 */

export interface AudioRecordingResult {
  wavBase64: string;
  durationMs: number;
  /** True if any frame crossed the speech-energy threshold during this recording. A clip that
   *  never does is mostly/entirely silence -- sending it to Whisper anyway is exactly what makes
   *  it hallucinate boilerplate ("you", "Thank you.") instead of failing honestly. */
  speechDetected: boolean;
}

export class AudioRecorder {
  private audioContext: AudioContext | null = null;
  private mediaStream: MediaStream | null = null;
  private processorNode: ScriptProcessorNode | null = null;
  private pcmChunks: Float32Array[] = [];
  private isRecording = false;
  private silenceTimer: any = null;
  private speechDetected = false;
  private targetSampleRate = 16000;

  async start(options: {
    onSilenceDetected?: () => void;
    onLevelChange?: (level: number) => void;
    onSpeechDetected?: () => void;
    silenceDelayMs?: number;
  }): Promise<void> {
    this.stop(); // cleanup any existing session
    this.pcmChunks = [];
    this.speechDetected = false;

    const AudioCtx = window.AudioContext || (window as any).webkitAudioContext;
    const ctx = new AudioCtx({ sampleRate: this.targetSampleRate });
    this.audioContext = ctx;

    const stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true
      }
    });
    this.mediaStream = stream;

    const source = ctx.createMediaStreamSource(stream);
    // 2048 samples = ~128ms per buffer at 16kHz
    const processor = ctx.createScriptProcessor(2048, 1, 1);
    this.processorNode = processor;

    const silenceDelay = options.silenceDelayMs || 900;
    let noiseFloor = 0.015;
    let frameCount = 0;

    processor.onaudioprocess = (event) => {
      if (!this.isRecording) return;
      const input = event.inputBuffer.getChannelData(0);
      this.pcmChunks.push(new Float32Array(input));

      // Calculate RMS amplitude
      let sum = 0;
      for (let i = 0; i < input.length; i++) {
        sum += input[i] * input[i];
      }
      const rms = Math.sqrt(sum / input.length);

      // Report audio level for UI (0 - 100 scale)
      if (options.onLevelChange) {
        options.onLevelChange(Math.min(100, Math.round(rms * 450)));
      }

      // Initial ambient noise floor calibration (first 5 frames)
      if (frameCount < 5) {
        noiseFloor = Math.max(0.008, Math.min(0.04, (noiseFloor + rms) / 2));
        frameCount++;
        return;
      }

      const speechThreshold = Math.max(0.022, noiseFloor * 2.2);

      if (rms > speechThreshold) {
        if (!this.speechDetected) {
          this.speechDetected = true;
          if (options.onSpeechDetected) {
            options.onSpeechDetected();
          }
        }
        // Reset silence timer while speech energy is active
        if (this.silenceTimer) {
          clearTimeout(this.silenceTimer);
          this.silenceTimer = null;
        }
      } else if (this.speechDetected) {
        // Speech was previously heard; now quiet
        if (!this.silenceTimer) {
          this.silenceTimer = setTimeout(() => {
            if (this.isRecording && options.onSilenceDetected) {
              options.onSilenceDetected();
            }
          }, silenceDelay);
        }
      }
    };

    source.connect(processor);
    // Connect to destination via silent gain to keep audio process alive in Chromium
    const silentGain = ctx.createGain();
    silentGain.gain.value = 0;
    processor.connect(silentGain);
    silentGain.connect(ctx.destination);

    this.isRecording = true;
  }

  stop(): AudioRecordingResult | null {
    this.isRecording = false;
    if (this.silenceTimer) {
      clearTimeout(this.silenceTimer);
      this.silenceTimer = null;
    }

    if (this.processorNode) {
      try {
        this.processorNode.disconnect();
      } catch {}
      this.processorNode = null;
    }

    if (this.mediaStream) {
      try {
        this.mediaStream.getTracks().forEach((t) => t.stop());
      } catch {}
      this.mediaStream = null;
    }

    if (this.audioContext) {
      try {
        this.audioContext.close();
      } catch {}
      this.audioContext = null;
    }

    if (this.pcmChunks.length === 0) {
      return null;
    }

    // Merge all PCM chunks
    let totalLength = 0;
    for (const chunk of this.pcmChunks) {
      totalLength += chunk.length;
    }
    const merged = new Float32Array(totalLength);
    let offset = 0;
    for (const chunk of this.pcmChunks) {
      merged.set(chunk, offset);
      offset += chunk.length;
    }
    this.pcmChunks = [];

    const durationMs = Math.round((totalLength / this.targetSampleRate) * 1000);
    const wavBase64 = this.pcmToWav(merged, this.targetSampleRate);

    return { wavBase64, durationMs, speechDetected: this.speechDetected };
  }

  private pcmToWav(pcmData: Float32Array, sampleRate: number): string {
    const numChannels = 1;
    const bytesPerSample = 2;
    const blockAlign = numChannels * bytesPerSample;
    const byteRate = sampleRate * blockAlign;
    const dataSize = pcmData.length * bytesPerSample;
    const buffer = new ArrayBuffer(44 + dataSize);
    const view = new DataView(buffer);

    // RIFF chunk descriptor
    this.writeString(view, 0, 'RIFF');
    view.setUint32(4, 36 + dataSize, true);
    this.writeString(view, 8, 'WAVE');

    // fmt sub-chunk
    this.writeString(view, 12, 'fmt ');
    view.setUint32(16, 16, true); // Subchunk1Size (16 for PCM)
    view.setUint16(20, 1, true); // AudioFormat (1 for PCM)
    view.setUint16(22, numChannels, true);
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, byteRate, true);
    view.setUint16(32, blockAlign, true);
    view.setUint16(34, 16, true); // BitsPerSample (16)

    // data sub-chunk
    this.writeString(view, 36, 'data');
    view.setUint32(40, dataSize, true);

    // Write PCM samples (float32 to 16-bit signed integer)
    let byteOffset = 44;
    for (let i = 0; i < pcmData.length; i++, byteOffset += 2) {
      const s = Math.max(-1, Math.min(1, pcmData[i]));
      view.setInt16(byteOffset, s < 0 ? s * 0x8000 : s * 0x7fff, true);
    }

    // Convert to Base64
    const bytes = new Uint8Array(buffer);
    let binary = '';
    const chunkSize = 8192;
    for (let i = 0; i < bytes.length; i += chunkSize) {
      const sub = bytes.subarray(i, i + chunkSize);
      binary += String.fromCharCode.apply(null, sub as unknown as number[]);
    }
    return btoa(binary);
  }

  private writeString(view: DataView, offset: number, str: string) {
    for (let i = 0; i < str.length; i++) {
      view.setUint8(offset + i, str.charCodeAt(i));
    }
  }
}
