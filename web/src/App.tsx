import React, { useState, useEffect, useRef } from 'react';
import type {
  Performance,
  Regularity,
  Screen,
  ChatMessage,
  SessionMetrics,
  MetricsSnapshot,
  ConfidenceReadout,
  BankQuestion,
  MicState,
  Word
} from './types';
import { ToneTable, PromptBuilder } from './logic/tone';
import { Routing } from './logic/routing';
import { ConfidenceScorer, OfflineFixtures, ConfidenceThresholds } from './logic/confidence';
import { QuestionBank } from './logic/questionBank';
import { MetricsScanner, createInitialSessionMetrics, addTurnToMetrics, getAvgWords, getAvgPraise } from './logic/metrics';
import { FallbackReplies } from './logic/fallbackReplies';
import { GroqClient } from './logic/groq';
import { AudioRecorder } from './logic/audioRecorder';
import {
  Settings,
  Send,
  ArrowLeft,
  Camera,
  Smartphone,
  Monitor,
  AlertCircle,
  Volume2,
  VolumeX,
  Mic,
  Download
} from 'lucide-react';
import './App.css';

export const App: React.FC = () => {
  // Navigation & Settings
  const [screen, setScreen] = useState<Screen>('PROFILE');
  const [phoneFrame, setPhoneFrame] = useState<boolean>(true);
  const [showSettings, setShowSettings] = useState<boolean>(false);
  const [apiKey, setApiKey] = useState<string>(() => localStorage.getItem('groq_api_key') || (import.meta as any).env?.VITE_GROQ_API_KEY || '');

  // App State matching TutorViewModel
  const [performance, setPerformance] = useState<Performance | null>(null);
  const [regularity, setRegularity] = useState<Regularity | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [sessionMetrics, setSessionMetrics] = useState<SessionMetrics>(createInitialSessionMetrics());
  const [snapshots, setSnapshots] = useState<MetricsSnapshot[]>([]);
  const [offlineMode, setOfflineMode] = useState<boolean>(false);
  const [micState, setMicState] = useState<MicState>('IDLE');
  const [currentBankQuestion, setCurrentBankQuestion] = useState<BankQuestion | null>(null);
  const [lastConfidenceReadout, setLastConfidenceReadout] = useState<ConfidenceReadout | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<boolean>(false);
  const [textInput, setTextInput] = useState<string>('');
  const [ttsEnabled, setTtsEnabled] = useState<boolean>(true);
  const [voices, setVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [selectedVoice, setSelectedVoice] = useState<string>(() => localStorage.getItem('albert_voice_uri') || '');
  const [vadStatus, setVadStatus] = useState<string>('');
  const [liveTranscript, setLiveTranscript] = useState<string>('');
  const [vadSilenceDelayMs, setVadSilenceDelayMs] = useState<number>(900); // 900ms silence auto-stop
  const [isSpeaking, setIsSpeaking] = useState<boolean>(false);
  const [isAlbertSpeaking, setIsAlbertSpeaking] = useState<boolean>(false);
  const [speechEngine, setSpeechEngine] = useState<'AUTO' | 'GROQ_AUDIO' | 'WEB_SPEECH'>('AUTO');
  const [audioLevel, setAudioLevel] = useState<number>(0);

  // Speech Recognition & Audio Recorder Refs
  const recognitionRef = useRef<any>(null);
  const audioRecorderRef = useRef<AudioRecorder>(new AudioRecorder());
  const recordingStartTimeRef = useRef<number>(0);
  const transcriptBufferRef = useRef<string>('');
  const chatScrollRef = useRef<HTMLDivElement>(null);
  const silenceTimerRef = useRef<any>(null);
  const inactivityTimerRef = useRef<any>(null);
  const isListeningRef = useRef<boolean>(false);
  // Mirrors currentBankQuestion for the same reason isListeningRef/transcriptBufferRef exist:
  // the SpeechRecognition handlers set up in startActiveListening are long-lived closures created
  // once per Ask click, from the render BEFORE setCurrentBankQuestion(question) takes effect. Hit
  // live: handleStopVoiceTurn, called from those closures, read the stale (still-null)
  // currentBankQuestion state, hit its `if (!question) return` guard, and silently no-opped --
  // setMicState('PROCESSING') never ran, so the UI stayed frozen on "Done Speaking" forever even
  // though the "Silence detected" status text (a plain setState call, unaffected by the stale
  // closure) had already updated. Reading from this ref instead of the state gives the current
  // value regardless of which render's closure is calling it.
  const currentBankQuestionRef = useRef<BankQuestion | null>(null);
  // Cycles through the question bank so one mic button can drive voice mode end to end --
  // no picking a specific question first. Wraps back to the start once the bank is exhausted.
  const nextQuestionIndexRef = useRef(0);

  // Discover and configure system voices on mount
  useEffect(() => {
    const updateVoices = () => {
      if (!('speechSynthesis' in window)) return;
      const allVoices = window.speechSynthesis.getVoices();
      const enVoices = allVoices.filter(v => v.lang.startsWith('en'));
      const list = enVoices.length > 0 ? enVoices : allVoices;
      setVoices(list);

      const saved = localStorage.getItem('albert_voice_uri');
      if (saved && list.some(v => v.voiceURI === saved)) {
        setSelectedVoice(saved);
      } else {
        // Auto-select the highest quality natural/neural English voice
        const naturalVoice =
          list.find(v => /natural|neural|online|guy|christopher|google/i.test(v.name)) ||
          list.find(v => !/david/i.test(v.name)) ||
          list[0];
        if (naturalVoice) {
          setSelectedVoice(naturalVoice.voiceURI);
          localStorage.setItem('albert_voice_uri', naturalVoice.voiceURI);
        }
      }
    };

    updateVoices();
    if ('speechSynthesis' in window) {
      window.speechSynthesis.onvoiceschanged = updateVoices;
    }

    return () => {
      cleanupAudioCapture();
    };
  }, []);

  // Auto-scroll chat to bottom
  useEffect(() => {
    if (chatScrollRef.current) {
      chatScrollRef.current.scrollTop = chatScrollRef.current.scrollHeight;
    }
  }, [messages, busy]);

  const saveApiKey = (key: string) => {
    setApiKey(key);
    localStorage.setItem('groq_api_key', key.trim());
  };

  const handleVoiceSelect = (uri: string) => {
    setSelectedVoice(uri);
    localStorage.setItem('albert_voice_uri', uri);
  };

  const testVoicePreview = (voiceUri?: string) => {
    if (!('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();
    setTimeout(() => {
      const utterance = new SpeechSynthesisUtterance("Greetings! I am Professor Albert. Let's explore this problem step by step.");
      const all = window.speechSynthesis.getVoices();
      const target = voiceUri || selectedVoice;
      const v = all.find(x => x.voiceURI === target);
      if (v) {
        utterance.voice = v;
        utterance.lang = v.lang || 'en-US';
      }
      utterance.rate = 1.0;
      utterance.pitch = 1.0;
      window.speechSynthesis.speak(utterance);
    }, 40);
  };

  const cleanupAudioCapture = () => {
    isListeningRef.current = false;
    if (silenceTimerRef.current) {
      clearTimeout(silenceTimerRef.current);
      silenceTimerRef.current = null;
    }
    if (inactivityTimerRef.current) {
      clearTimeout(inactivityTimerRef.current);
      inactivityTimerRef.current = null;
    }
    if (recognitionRef.current) {
      try {
        recognitionRef.current.abort();
      } catch {}
      recognitionRef.current = null;
    }
    try {
      audioRecorderRef.current.stop();
    } catch {}
    setIsSpeaking(false);
    setAudioLevel(0);
  };

  const getToneProfile = () => {
    if (!performance || !regularity) return null;
    return ToneTable.lookup(performance, regularity);
  };

  const conversationContext = () => {
    const recent = messages.slice(-6);
    if (recent.length === 0) return '';
    return recent.map(m => (m.fromAlbert ? 'Albert: ' : 'Student: ') + m.text).join('\n');
  };

  const speakAlbert = (text: string, onEnd?: () => void) => {
    if (!('speechSynthesis' in window)) {
      if (onEnd) onEnd();
      return;
    }
    try {
      window.speechSynthesis.cancel();
      const cleanText = text
        .replace(/[*#`_]/g, '')
        .replace(/\([^)]*offline[^)]*\)/gi, '')
        .replace(/\s+/g, ' ')
        .trim();

      if (!cleanText) {
        if (onEnd) onEnd();
        return;
      }

      // Small delay prevents Chromium bug where cancel() suppresses immediate speak()
      setTimeout(() => {
        const utterance = new SpeechSynthesisUtterance(cleanText);
        const profile = getToneProfile();

        // Use chosen natural voice
        const allVoices = window.speechSynthesis.getVoices();
        let voiceObj = allVoices.find(v => v.voiceURI === selectedVoice);
        if (!voiceObj) {
          voiceObj =
            allVoices.find(v => v.lang.startsWith('en') && /natural|neural|online|guy|christopher|google/i.test(v.name)) ||
            allVoices.find(v => v.lang.startsWith('en') && !/david/i.test(v.name)) ||
            allVoices.find(v => v.lang.startsWith('en'));
        }
        if (voiceObj) {
          utterance.voice = voiceObj;
          utterance.lang = voiceObj.lang || 'en-US';
        }

        // Voice modulation:
        // Struggling: patient pace (0.92x), calm & reassuring lower pitch (0.98x)
        // Strong: brisk pace (1.10x), energetic higher pitch (1.02x)
        if (profile?.structure === 'TIGHT') {
          utterance.rate = 0.92;
          utterance.pitch = 0.98;
        } else if (profile?.structure === 'MEDIUM') {
          utterance.rate = 1.0;
          utterance.pitch = 1.0;
        } else {
          utterance.rate = 1.10;
          utterance.pitch = 1.02;
        }

        if (profile?.encouragement === 'HIGH') {
          utterance.pitch *= 1.04; // extra audible warmth
        }

        let hasFinished = false;
        const completeUtterance = () => {
          if (!hasFinished) {
            hasFinished = true;
            if (onEnd) onEnd();
          }
        };

        utterance.onend = completeUtterance;
        utterance.onerror = completeUtterance;

        // Chrome timeout guard
        setTimeout(() => {
          completeUtterance();
        }, Math.max(2500, cleanText.length * 110));

        // Chrome keep-alive bugfix
        const resumeInterval = setInterval(() => {
          if (!window.speechSynthesis.speaking) {
            clearInterval(resumeInterval);
          } else {
            window.speechSynthesis.resume();
          }
        }, 4000);

        window.speechSynthesis.speak(utterance);
      }, 40);
    } catch {
      if (onEnd) onEnd();
    }
  };

  // Profile Selection
  const handleSelectProfile = (perf: Performance, reg: Regularity) => {
    setPerformance(perf);
    setRegularity(reg);
    setMessages([]);
    setSessionMetrics(createInitialSessionMetrics());
    setLastConfidenceReadout(null);
    setError(null);
    setScreen('CHAT');
  };

  // Text Send (Instant with zero artificial delays)
  const handleSendText = async (textToSend?: string) => {
    const text = (textToSend !== undefined ? textToSend : textInput).trim();
    if (!text || busy) return;

    const profile = getToneProfile();
    if (!profile) return;

    setTextInput('');
    setMessages(prev => [...prev, { fromAlbert: false, text }]);
    setBusy(true);
    setError(null);

    const prompt = PromptBuilder.buildPrompt(profile, null, conversationContext(), text);

    if (offlineMode || !apiKey.trim()) {
      const reply = FallbackReplies.reply(profile);
      applyReply(reply);
      return;
    }

    const result = await GroqClient.chat(apiKey, prompt);
    if (result.success && result.value) {
      applyReply(result.value);
    } else {
      setError(`${result.error || 'Network error'} -- showing offline fallback`);
      applyReply(FallbackReplies.reply(profile));
    }
  };

  const applyReply = (reply: string) => {
    const turnMetrics = MetricsScanner.scan(reply);
    setMessages(prev => [...prev, { fromAlbert: true, text: reply }]);
    setSessionMetrics(prev => addTurnToMetrics(prev, turnMetrics));
    setBusy(false);
    setMicState('IDLE');
    setCurrentBankQuestion(null);
    currentBankQuestionRef.current = null;
    if (ttsEnabled) {
      speakAlbert(reply);
    }
  };

  // Voice Mode Handling
  const handleStartVoiceTurn = (question: BankQuestion) => {
    if (micState !== 'IDLE' || busy) return;

    cleanupAudioCapture();
    setMessages(prev => [...prev, { fromAlbert: true, text: question.prompt }]);
    setCurrentBankQuestion(question);
    currentBankQuestionRef.current = question;
    setMicState('RECORDING');
    setError(null);
    transcriptBufferRef.current = '';
    setLiveTranscript('');
    setIsSpeaking(false);

    if (ttsEnabled) {
      setVadStatus('Albert is speaking question... (Click "Skip & Speak" to talk now)');
      setIsAlbertSpeaking(true);
      // Albert speaks FIRST; microphone arms ONLY AFTER Albert finishes speaking!
      speakAlbert(question.prompt, () => {
        setIsAlbertSpeaking(false);
        startActiveListening(question);
      });
    } else {
      setIsAlbertSpeaking(false);
      startActiveListening(question);
    }
  };

  // One-tap entry point for the big mic button -- no separate "enable voice mode" step and no
  // picking a specific question first. Auto-advances through the bank each tap.
  const handleMicButtonTap = () => {
    if (micState !== 'IDLE' || busy) return;
    const question = QuestionBank.questions[nextQuestionIndexRef.current % QuestionBank.questions.length];
    nextQuestionIndexRef.current += 1;
    handleStartVoiceTurn(question);
  };

  const handleSkipQuestionAndSpeak = () => {
    if (!currentBankQuestion) return;
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
    setIsAlbertSpeaking(false);
    startActiveListening(currentBankQuestion);
  };

  const startDirectAudioRecording = async (_question: BankQuestion) => {
    cleanupAudioCapture();
    setVadStatus('🎙️ Listening (Direct Groq Audio)... speak now!');
    recordingStartTimeRef.current = Date.now();
    isListeningRef.current = true;
    transcriptBufferRef.current = '';
    setLiveTranscript('');
    setIsSpeaking(false);

    try {
      await audioRecorderRef.current.start({
        silenceDelayMs: vadSilenceDelayMs,
        onLevelChange: (level) => {
          setAudioLevel(level);
        },
        onSpeechDetected: () => {
          setIsSpeaking(true);
          setVadStatus('🎙️ Speech detected... listening!');
        },
        onSilenceDetected: () => {
          setVadStatus('Silence detected, transcribing via Groq...');
          handleStopVoiceTurn();
        }
      });
    } catch (err: any) {
      setError(`Microphone error: ${err?.message || 'Could not access mic'}. Check browser permissions.`);
      cleanupAudioCapture();
      setMicState('IDLE');
    }
  };

  const startActiveListening = (question: BankQuestion) => {
    cleanupAudioCapture();
    recordingStartTimeRef.current = Date.now();
    isListeningRef.current = true;
    transcriptBufferRef.current = '';
    setLiveTranscript('');
    setIsSpeaking(false);

    // If user selected Direct Groq Audio or if Web Speech previously failed:
    if (speechEngine === 'GROQ_AUDIO') {
      startDirectAudioRecording(question);
      return;
    }

    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognition) {
      console.log('Web Speech API not available; using Direct Groq Audio');
      setSpeechEngine('GROQ_AUDIO');
      startDirectAudioRecording(question);
      return;
    }

    try {
      const recognition = new SpeechRecognition();
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.lang = 'en-US';
      recognition.maxAlternatives = 1;

      recognition.onstart = () => {
        isListeningRef.current = true;
        setVadStatus('🎙️ Microphone active, say your answer!');
      };

      recognition.onspeechstart = () => {
        setIsSpeaking(true);
        setVadStatus('🎙️ Speech detected... listening!');
        if (silenceTimerRef.current) {
          clearTimeout(silenceTimerRef.current);
          silenceTimerRef.current = null;
        }
      };

      recognition.onresult = (event: any) => {
        let interimText = '';
        let finalText = '';
        for (let i = 0; i < event.results.length; i++) {
          const res = event.results[i];
          if (res.isFinal) {
            finalText += res[0].transcript + ' ';
          } else {
            interimText += res[0].transcript;
          }
        }
        const fullTranscript = (finalText + interimText).trim();
        if (fullTranscript) {
          transcriptBufferRef.current = fullTranscript;
          setLiveTranscript(fullTranscript);
          setIsSpeaking(true);
          setVadStatus(`🎙️ Heard: "${fullTranscript}"`);

          // Reset silence auto-stop timer on every detected word/syllable!
          if (silenceTimerRef.current) {
            clearTimeout(silenceTimerRef.current);
          }
          silenceTimerRef.current = setTimeout(() => {
            if (isListeningRef.current && transcriptBufferRef.current.trim().length > 0) {
              setVadStatus('Silence detected, submitting answer!');
              handleStopVoiceTurn();
            }
          }, vadSilenceDelayMs);
        }
      };

      recognition.onspeechend = () => {
        setIsSpeaking(false);
        // Browser's internal acoustic endpoint fired!
        if (isListeningRef.current && transcriptBufferRef.current.trim().length > 0) {
          setVadStatus('Silence detected, submitting answer...');
          if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
          silenceTimerRef.current = setTimeout(() => {
            if (isListeningRef.current) {
              handleStopVoiceTurn();
            }
          }, 350);
        }
      };

      recognition.onerror = (event: any) => {
        console.warn('SpeechRecognition error:', event.error);
        if (event.error === 'network') {
          // Automatic seamless failover to Direct Groq Audio!
          console.warn('Browser Speech API network blocked, switching to Direct Groq Audio');
          setVadStatus('Browser speech service blocked by network/browser, switched to Direct Groq Audio!');
          setSpeechEngine('GROQ_AUDIO');
          startDirectAudioRecording(question);
          return;
        }
        if (event.error === 'not-allowed' || event.error === 'service-not-allowed') {
          setError('Microphone permission blocked. Please allow mic access in your browser address bar.');
          cleanupAudioCapture();
          setMicState('IDLE');
        } else if (event.error === 'no-speech') {
          setIsSpeaking(false);
        }
      };

      recognition.onend = () => {
        setIsSpeaking(false);
        if (isListeningRef.current) {
          if (transcriptBufferRef.current.trim().length > 0) {
            handleStopVoiceTurn();
          } else {
            try {
              recognition.start();
            } catch {}
          }
        }
      };

      // Inactivity timeout: if 12s pass with no speech at all, gracefully stop
      inactivityTimerRef.current = setTimeout(() => {
        if (isListeningRef.current && !transcriptBufferRef.current.trim()) {
          setVadStatus('No speech detected. Click Ask to try again or click a sample button.');
          cleanupAudioCapture();
          setMicState('IDLE');
        }
      }, 12000);

      recognition.start();
      recognitionRef.current = recognition;
    } catch (err: any) {
      console.warn('SpeechRecognition error; switching to Direct Audio:', err);
      setSpeechEngine('GROQ_AUDIO');
      startDirectAudioRecording(question);
    }
  };

  const handleStopVoiceTurn = async (forcedWords?: Word[]) => {
    // Read from the ref, not the currentBankQuestion state var -- this function is called from
    // long-lived SpeechRecognition closures (see currentBankQuestionRef's declaration comment)
    // that can hold a stale, pre-update render's value of the state.
    const question = currentBankQuestionRef.current;
    if (!question) return;

    // Stop direct audio recorder if active
    const directResult = audioRecorderRef.current.stop();
    cleanupAudioCapture();

    setMicState('PROCESSING');
    setBusy(true);

    if (forcedWords) {
      scoreAndRespond(question, forcedWords);
      return;
    }

    // If Direct Groq Audio recorded audio and we don't have a transcript yet:
    if (directResult && directResult.wavBase64 && !transcriptBufferRef.current.trim()) {
      setVadStatus('Transcribing speech with Groq...');
      let transcribed = '';
      if (apiKey.trim() && !offlineMode) {
        const tr = await GroqClient.transcribeAudio(apiKey, directResult.wavBase64);
        if (tr.success && tr.value) {
          transcribed = tr.value;
        }
      }
      if (transcribed.trim()) {
        transcriptBufferRef.current = transcribed;
        setLiveTranscript(transcribed);
      }
    }

    // A live transcript that's pure punctuation/noise ("." from misheard silence) has no real
    // content to score or reply to -- without this guard it still reaches the scorer (whose
    // signals all read as zero, i.e. falsely "confident") and the LLM, which then improvises a
    // reply disconnected from what was actually asked. Only applies to the live-transcription
    // branch below; offline/fixture words always have real content.
    const liveTranscriptHasContent = /[a-z0-9]/i.test(transcriptBufferRef.current);

    if (!offlineMode && transcriptBufferRef.current.trim() !== '' && !liveTranscriptHasContent) {
      setError("Didn't catch an actual answer -- try again.");
      setBusy(false);
      setMicState('IDLE');
      setCurrentBankQuestion(null);
      currentBankQuestionRef.current = null;
      return;
    }

    let words: Word[];
    if (offlineMode || transcriptBufferRef.current.trim() === '') {
      words = question.expectedAnswer === 'twelve'
        ? OfflineFixtures.hesitantCorrect
        : OfflineFixtures.confidentWrong;
    } else {
      const elapsed = Math.max(300, Date.now() - recordingStartTimeRef.current);
      const tokens = transcriptBufferRef.current.trim().split(/\s+/).filter(Boolean);
      const wordDuration = Math.round(Math.min(600, Math.max(150, elapsed / Math.max(1, tokens.length))));
      const latencyMs = Math.max(200, elapsed - tokens.length * wordDuration);
      words = tokens.map((tok, idx) => ({
        text: tok,
        startMs: latencyMs + idx * wordDuration,
        endMs: latencyMs + (idx + 1) * wordDuration
      }));
    }

    // Immediately score and answer with ZERO delay!
    scoreAndRespond(question, words);
  };

  const scoreAndRespond = async (question: BankQuestion, words: Word[]) => {
    const profile = getToneProfile();
    if (!profile) {
      setBusy(false);
      setMicState('IDLE');
      return;
    }

    const transcript = words.map(w => w.text).join(' ');
    const signals = ConfidenceScorer.score(words);
    const band = ConfidenceScorer.band(signals);
    const correct = QuestionBank.isCorrect(question, transcript);
    const readout: ConfidenceReadout = {
      transcript,
      signals,
      band,
      correct,
      triggeredFollowUp: band === 'LOW' || !correct
    };

    setMessages(prev => [...prev, { fromAlbert: false, text: transcript }]);
    setLastConfidenceReadout(readout);

    const addendum = Routing.promptAddendum(correct, band);
    const prompt = PromptBuilder.buildPrompt(profile, addendum, conversationContext(), transcript);

    if (offlineMode || !apiKey.trim()) {
      applyReply(FallbackReplies.reply(profile, correct, band));
      return;
    }

    const result = await GroqClient.chat(apiKey, prompt);
    if (result.success && result.value) {
      applyReply(result.value);
    } else {
      setError(`${result.error || 'Network error'} -- using offline reply`);
      applyReply(FallbackReplies.reply(profile, correct, band));
    }
  };

  // Snapshot active profile's metrics
  const handleSnapshot = () => {
    if (!performance || !regularity) return;
    const label = `${performance.charAt(0) + performance.slice(1).toLowerCase()} / ${
      regularity.charAt(0) + regularity.slice(1).toLowerCase()
    }`;
    setSnapshots(prev => [...prev.filter(s => s.label !== label), { label, metrics: sessionMetrics }]);
  };

  return (
    <div className="app-container">
      {/* Top Navbar */}
      <header className="top-nav">
        <div className="nav-brand">
          <span className="nav-logo">🎓</span>
          <span className="nav-title">Adaptive Tutor Tone</span>
          <span className="badge">Prof. Albert Demo</span>
        </div>
        <div className="nav-actions">
          <button
            className={`btn-icon ${ttsEnabled ? 'active' : ''}`}
            onClick={() => setTtsEnabled(!ttsEnabled)}
            title={ttsEnabled ? 'Voice output enabled (click to mute)' : 'Voice output muted (click to enable)'}
          >
            {ttsEnabled ? <Volume2 size={18} /> : <VolumeX size={18} />}
            <span className="btn-label">{ttsEnabled ? 'Voice: On' : 'Voice: Muted'}</span>
          </button>
          <button
            className={`btn-icon ${phoneFrame ? 'active' : ''}`}
            onClick={() => setPhoneFrame(!phoneFrame)}
            title={phoneFrame ? 'Switch to Full Screen View' : 'Switch to Android Phone Mockup'}
          >
            {phoneFrame ? <Monitor size={18} /> : <Smartphone size={18} />}
            <span className="btn-label">{phoneFrame ? 'Wide View' : 'Phone Frame'}</span>
          </button>
          <button
            className="btn-icon"
            onClick={() => setShowSettings(true)}
            title="Configure Groq API Key"
          >
            <Settings size={18} />
            <span className="btn-label">{apiKey ? 'API Key Set' : 'Set API Key'}</span>
          </button>
          {/* Direct APK download -- this is the actual native Android app (the Kotlin/Compose
              project this web version was ported from), not a repackaged copy of this site. No
              Play Store listing, so Android will warn about installing from an unknown source --
              that's expected for a sideloaded demo build, not a bug. */}
          <a
            className="btn-icon"
            href="/adaptive-tutor-tone.apk"
            download
            title="Download the native Android app (.apk, ~9MB)"
          >
            <Download size={18} />
            <span className="btn-label">Get Android App</span>
          </a>
        </div>
      </header>

      {/* Main Viewport */}
      <main className="main-viewport">
        <div className={phoneFrame ? 'device-wrapper' : 'desktop-wrapper'}>
          {phoneFrame && <div className="speaker-earpiece" />}

          <div className="screen-frame">
            {/* Top Bar inside the app */}
            <div className="app-top-bar">
              {screen !== 'PROFILE' ? (
                <button
                  className="top-bar-back-btn"
                  onClick={() => setScreen(screen === 'COMPARE' ? 'PROFILE' : 'PROFILE')}
                >
                  <ArrowLeft size={18} />
                  <span>{screen === 'COMPARE' ? 'Profiles' : 'Profiles'}</span>
                </button>
              ) : (
                <span className="top-bar-title">Adaptive Tutor Tone</span>
              )}

              {screen === 'CHAT' && performance && regularity && (
                <span className="profile-pill">
                  {performance} / {regularity}
                </span>
              )}
            </div>

            {/* Error Banner */}
            {error && (
              <div className="error-banner">
                <AlertCircle size={16} />
                <span>{error}</span>
                <button onClick={() => setError(null)} className="dismiss-btn">
                  Dismiss
                </button>
              </div>
            )}

            {/* SCREEN 1: PROFILE SELECTION */}
            {screen === 'PROFILE' && (
              <div className="screen-content profile-screen">
                <div className="profile-header">
                  <h2>Pick a simulated student</h2>
                  <p className="subtitle">
                    Select a student profile to test how Prof. Albert adapts structure and encouragement.
                  </p>
                </div>

                <div className="profile-list">
                  <div className="profile-card" onClick={() => handleSelectProfile('STRONG', 'CONSISTENT')}>
                    <div className="profile-card-top">
                      <span className="profile-name">STRONG / CONSISTENT</span>
                      <span className="badge badge-success">Loose Structure</span>
                    </div>
                    <p className="profile-desc">
                      High performers with regular habits: jokes and tangents are welcome, scaffolding stays light.
                    </p>
                  </div>

                  <div className="profile-card" onClick={() => handleSelectProfile('STRONG', 'GAPPED')}>
                    <div className="profile-card-top">
                      <span className="profile-name">STRONG / GAPPED</span>
                      <span className="badge badge-warning">Medium Structure</span>
                    </div>
                    <p className="profile-desc">
                      Strong capability but study gaps: moderate turns, partial scaffolding, standard praise.
                    </p>
                  </div>

                  <div className="profile-card" onClick={() => handleSelectProfile('STRUGGLING', 'CONSISTENT')}>
                    <div className="profile-card-top">
                      <span className="profile-name">STRUGGLING / CONSISTENT</span>
                      <span className="badge badge-info">Tight Structure</span>
                    </div>
                    <p className="profile-desc">
                      Working hard but having trouble: concise numbered steps, zero tangents, standard praise.
                    </p>
                  </div>

                  <div className="profile-card" onClick={() => handleSelectProfile('STRUGGLING', 'GAPPED')}>
                    <div className="profile-card-top">
                      <span className="profile-name">STRUGGLING / GAPPED</span>
                      <span className="badge badge-danger">Tight + High Praise</span>
                    </div>
                    <p className="profile-desc">
                      Maximum assistance: tight scaffolding combined with elevated, specific encouragement!
                    </p>
                  </div>
                </div>

                {snapshots.length > 0 && (
                  <button className="btn-secondary full-width mt-16" onClick={() => setScreen('COMPARE')}>
                    Compare {snapshots.length} Snapshot{snapshots.length > 1 ? 's' : ''}
                  </button>
                )}

                <div className="info-box mt-16">
                  <strong>Non-negotiable Rules (Caveats):</strong>
                  <ul>
                    <li>
                      <strong>Caveat 2:</strong> Structure can tighten, but encouragement never drops.
                    </li>
                    <li>
                      <strong>Caveat 3:</strong> Confidence signals change follow-up depth, never warmth.
                    </li>
                  </ul>
                </div>
              </div>
            )}

            {/* SCREEN 2: CHAT SCREEN */}
            {screen === 'CHAT' && (
              <div className="screen-content chat-screen">
                {/* Live Metrics Strip */}
                <div className="metrics-strip">
                  <div className="metrics-header">
                    <span className="metrics-title">📊 Live Session Metrics</span>
                    <button className="snapshot-btn-small" onClick={handleSnapshot} title="Snapshot current metrics">
                      <Camera size={14} /> Snapshot
                    </button>
                  </div>

                  <div className="metrics-grid">
                    <div className="metric-item">
                      <span className="metric-label">Turns</span>
                      <span className="metric-value">{sessionMetrics.turns}</span>
                    </div>
                    <div className="metric-item">
                      <span className="metric-label">Avg Words/Turn</span>
                      <span className="metric-value">{getAvgWords(sessionMetrics).toFixed(1)}</span>
                    </div>
                    <div className="metric-item">
                      <span className="metric-label">Scaffolding</span>
                      <span className="metric-value">{sessionMetrics.totalScaffolding}</span>
                    </div>
                    <div className="metric-item">
                      <span className="metric-label">Jokes / Tangents</span>
                      <span className="metric-value">{sessionMetrics.totalJokes}</span>
                    </div>
                    <div className="metric-item">
                      <span className="metric-label">Praise Markers</span>
                      <span className="metric-value">{sessionMetrics.totalPraise}</span>
                    </div>
                    <div className="metric-item">
                      <span className="metric-label">Avg Praise/Turn</span>
                      <span className="metric-value">{getAvgPraise(sessionMetrics).toFixed(2)}</span>
                    </div>
                  </div>

                  {/* Last Confidence Readout (From Voice/Spoken Turn) */}
                  {lastConfidenceReadout && (
                    <div className="confidence-box">
                      <div className="confidence-title">
                        <span>🎙️ Last Spoken Answer: "{lastConfidenceReadout.transcript}"</span>
                      </div>
                      <div className="confidence-stats">
                        <span>Latency: <strong>{lastConfidenceReadout.signals.latencyMs}ms</strong></span>
                        <span>Hedges: <strong>{(lastConfidenceReadout.signals.hedgeRate * 100).toFixed(0)}%</strong></span>
                        <span>Corrections: <strong>{lastConfidenceReadout.signals.selfCorrections}</strong></span>
                      </div>
                      <div className="confidence-footer">
                        <span className={`badge ${lastConfidenceReadout.band === 'HIGH' ? 'badge-success' : 'badge-warning'}`}>
                          Band: {lastConfidenceReadout.band}
                        </span>
                        <span className={`badge ${lastConfidenceReadout.correct ? 'badge-success' : 'badge-danger'}`}>
                          {lastConfidenceReadout.correct ? '✓ Correct' : '✗ Incorrect'}
                        </span>
                        <span className="badge badge-info">
                          Follow-up: {lastConfidenceReadout.triggeredFollowUp ? 'Deep / Check' : 'Standard'}
                        </span>
                      </div>
                      <div style={{ marginTop: '4px', fontSize: '10px', color: '#78350f' }}>
                        Thresholds -- latency &gt; {ConfidenceThresholds.LATENCY_MS}ms, hedge &gt; {ConfidenceThresholds.HEDGE_RATE}, corrections &gt; {ConfidenceThresholds.SELF_CORRECTIONS}
                      </div>
                    </div>
                  )}
                </div>

                {/* Messages List */}
                <div className="messages-container" ref={chatScrollRef}>
                  {messages.length === 0 ? (
                    <div className="empty-chat">
                      <p>Start a conversation with Albert or select a question below!</p>
                    </div>
                  ) : (
                    messages.map((msg, idx) => (
                      <div
                        key={idx}
                        className={`message-bubble ${msg.fromAlbert ? 'albert-bubble' : 'student-bubble'}`}
                      >
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '3px' }}>
                          <span className="message-sender">{msg.fromAlbert ? 'Prof. Albert' : 'Student'}</span>
                          {msg.fromAlbert && (
                            <button
                              onClick={() => speakAlbert(msg.text)}
                              title="Listen to Albert's modulated voice"
                              style={{ background: 'none', border: 'none', cursor: 'pointer', opacity: 0.7, padding: '2px' }}
                            >
                              <Volume2 size={13} />
                            </button>
                          )}
                        </div>
                        <div className="message-text">{msg.text}</div>
                      </div>
                    ))
                  )}

                  {busy && (
                    <div className="thinking-row">
                      <div className="linear-loader" />
                      <span>Albert is thinking...</span>
                    </div>
                  )}
                </div>

                {/* Toggles Strip */}
                <div className="toggles-strip">
                  <label className="toggle-label">
                    <input
                      type="checkbox"
                      checked={offlineMode}
                      onChange={e => setOfflineMode(e.target.checked)}
                    />
                    <span>Offline Sample</span>
                  </label>
                </div>

                {/* Voice is always available now -- no separate "enable voice mode" toggle. One
                    tap on the mic starts a turn with the next question in the bank; the list
                    below still lets you deliberately pick a specific one when a demo needs it. */}
                <button
                  className="btn-mic-main"
                  onClick={handleMicButtonTap}
                  disabled={busy || micState !== 'IDLE'}
                >
                  <Mic size={18} />
                  {micState === 'IDLE' ? 'Tap to talk to Albert' : 'Listening…'}
                </button>

                <div className="voice-panel">
                    <div className="voice-panel-header">
                      <span>Or pick a specific question</span>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <button
                          onClick={() => setSpeechEngine(prev => prev === 'GROQ_AUDIO' ? 'WEB_SPEECH' : 'GROQ_AUDIO')}
                          style={{
                            fontSize: '10px',
                            fontWeight: 600,
                            padding: '2px 8px',
                            background: speechEngine === 'GROQ_AUDIO' ? '#dbeafe' : '#f1f5f9',
                            color: speechEngine === 'GROQ_AUDIO' ? '#1d4ed8' : '#475569',
                            border: '1px solid #cbd5e1',
                            borderRadius: '4px',
                            cursor: 'pointer'
                          }}
                          title="Click to toggle between Direct Groq Audio (works on Brave/VPNs) and Browser Web Speech"
                        >
                          {speechEngine === 'GROQ_AUDIO' ? '🎙️ Groq Audio' : '⚡ Web Speech'}
                        </button>
                        <small>Auto-detects silence</small>
                      </div>
                    </div>

                    <div className="question-list">
                      {QuestionBank.questions.map((q, idx) => {
                        const isRecordingThis =
                          micState === 'RECORDING' && currentBankQuestion?.prompt === q.prompt;

                        return (
                          <div key={idx} className="question-row">
                            <span className="q-prompt">{q.prompt}</span>

                            {isRecordingThis ? (
                              <button
                                className="btn-done-recording"
                                onClick={() => handleStopVoiceTurn()}
                              >
                                ● Done Speaking
                              </button>
                            ) : (
                              <button
                                className="btn-ask"
                                disabled={busy || micState !== 'IDLE'}
                                onClick={() => handleStartVoiceTurn(q)}
                              >
                                Ask
                              </button>
                            )}

                            {isRecordingThis && (
                              <div className="vad-status-box">
                                <div className="vad-status-text">
                                  <span className={`vad-dot ${isSpeaking ? 'vad-dot-speaking' : ''}`} />
                                  <span>{vadStatus || 'Listening... speak your answer now!'}</span>
                                </div>

                                {isAlbertSpeaking && (
                                  <button
                                    className="btn-skip-audio"
                                    onClick={handleSkipQuestionAndSpeak}
                                    style={{
                                      margin: '6px 0',
                                      padding: '5px 10px',
                                      fontSize: '11px',
                                      fontWeight: 600,
                                      background: '#2563eb',
                                      color: '#ffffff',
                                      border: 'none',
                                      borderRadius: '6px',
                                      cursor: 'pointer',
                                      width: '100%',
                                      textAlign: 'center'
                                    }}
                                  >
                                    ⚡ Skip question audio & speak now
                                  </button>
                                )}

                                <div className="vad-meter-container" title={`Audio Activity: ${audioLevel}%`}>
                                  <div
                                    className={`vad-meter-bar ${isSpeaking ? 'vad-meter-active' : ''}`}
                                    style={{ width: `${Math.max(15, Math.max(audioLevel, isSpeaking ? 100 : (liveTranscript ? 65 : 20)))}%` }}
                                  />
                                </div>

                                {liveTranscript && (
                                  <div className="vad-live-transcript">
                                    Heard: <em>"{liveTranscript}"</em>
                                  </div>
                                )}

                                <div className="fixture-buttons" style={{ marginTop: '8px' }}>
                                  <span style={{ fontSize: '11px', color: '#94a3b8', display: 'block', width: '100%', marginBottom: '4px' }}>
                                    ⚡ Stops automatically ~{vadSilenceDelayMs / 1000}s after you stop talking, or test preset:
                                  </span>
                                  <button
                                    className="fixture-btn"
                                    onClick={() => handleStopVoiceTurn(OfflineFixtures.hesitantCorrect)}
                                  >
                                    Hesitant-Correct ("um, 12")
                                  </button>
                                  <button
                                    className="fixture-btn"
                                    onClick={() => handleStopVoiceTurn(OfflineFixtures.confidentWrong)}
                                  >
                                    Confident-Wrong ("11")
                                  </button>
                                </div>
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                </div>

                <div className="text-input-row">
                  <input
                    type="text"
                    className="chat-input"
                    placeholder="Or type your answer..."
                    value={textInput}
                    onChange={e => setTextInput(e.target.value)}
                    onKeyDown={e => e.key === 'Enter' && handleSendText()}
                    disabled={busy}
                  />
                  <button
                    className="btn-send"
                    onClick={() => handleSendText()}
                    disabled={!textInput.trim() || busy}
                  >
                    <Send size={16} />
                  </button>
                </div>
              </div>
            )}

            {/* SCREEN 3: COMPARE SNAPSHOTS */}
            {screen === 'COMPARE' && (
              <div className="screen-content compare-screen">
                <h2>Compare Profile Snapshots</h2>
                <p className="subtitle">
                  Verify how structure tightens while encouragement stays high across student profiles.
                </p>

                <div className="snapshots-grid">
                  {snapshots.map((snap, idx) => (
                    <div key={idx} className="snapshot-card">
                      <h3>{snap.label}</h3>
                      <div className="snapshot-stat-list">
                        <div className="snap-row">
                          <span>Turns:</span>
                          <strong>{snap.metrics.turns}</strong>
                        </div>
                        <div className="snap-row">
                          <span>Avg Words / Turn:</span>
                          <strong>{getAvgWords(snap.metrics).toFixed(1)}</strong>
                        </div>
                        <div className="snap-row">
                          <span>Scaffolding Steps:</span>
                          <strong>{snap.metrics.totalScaffolding}</strong>
                        </div>
                        <div className="snap-row">
                          <span>Jokes & Tangents:</span>
                          <strong>{snap.metrics.totalJokes}</strong>
                        </div>
                        <div className="snap-row">
                          <span>Praise Markers:</span>
                          <strong>{snap.metrics.totalPraise}</strong>
                        </div>
                        <div className="snap-row">
                          <span>Avg Praise / Turn:</span>
                          <strong>{getAvgPraise(snap.metrics).toFixed(2)}</strong>
                        </div>
                      </div>
                    </div>
                  ))}
                </div>

                <div className="compare-insights">
                  <h4>Key Insights Verified:</h4>
                  <ul>
                    <li>
                      <strong>Structure Tightening:</strong> Struggling students receive significantly higher
                      scaffolding counts and fewer tangents to prevent cognitive overload.
                    </li>
                    <li>
                      <strong>Encouragement Parity:</strong> Praise markers remain flat or increase for struggling
                      students, never decreasing despite the tighter discipline!
                    </li>
                  </ul>
                </div>
              </div>
            )}
          </div>
        </div>
      </main>

      {/* Settings Modal */}
      {showSettings && (
        <div className="modal-overlay" onClick={() => setShowSettings(false)}>
          <div className="modal-card" onClick={e => e.stopPropagation()} style={{ maxWidth: '500px' }}>
            <div className="modal-header">
              <h3>Settings & Voice Controls</h3>
              <button className="close-btn" onClick={() => setShowSettings(false)}>
                ✕
              </button>
            </div>
            <div className="modal-body" style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              {/* API Key */}
              <div>
                <label style={{ display: 'block', fontSize: '13px', fontWeight: 600, marginBottom: '4px' }}>
                  Groq API Key
                </label>
                <input
                  type="password"
                  className="modal-input"
                  placeholder="gsk_..."
                  value={apiKey}
                  onChange={e => saveApiKey(e.target.value)}
                />
                <p className="hint" style={{ marginTop: '4px' }}>
                  Uses ultra-fast <code>gemini-3.5-flash-lite</code> with zero thinking overhead.
                </p>
              </div>

              {/* Speech Recognition Engine */}
              <div>
                <label style={{ display: 'block', fontSize: '13px', fontWeight: 600, marginBottom: '4px' }}>
                  Microphone Speech Recognition Engine
                </label>
                <select
                  value={speechEngine}
                  onChange={e => setSpeechEngine(e.target.value as any)}
                  style={{
                    width: '100%',
                    padding: '8px 12px',
                    borderRadius: '6px',
                    background: '#232838',
                    color: '#f1f5f9',
                    border: '1px solid #3b82f6',
                    fontSize: '13px'
                  }}
                >
                  <option value="AUTO">Auto (Web Speech with automatic Groq failover)</option>
                  <option value="GROQ_AUDIO">Direct Groq Audio (Works everywhere, Brave & VPNs)</option>
                  <option value="WEB_SPEECH">Browser Web Speech API (Chrome/Edge only)</option>
                </select>
                <p className="hint" style={{ marginTop: '4px' }}>
                  If your browser gives a network error, <strong>Direct Groq Audio</strong> bypasses browser cloud limits and transcribes using your API key.
                </p>
              </div>

              {/* TTS Voice Picker */}
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
                  <label style={{ fontSize: '13px', fontWeight: 600 }}>
                    Prof. Albert TTS Voice
                  </label>
                  <button
                    className="fixture-btn"
                    style={{ padding: '3px 10px', fontSize: '12px' }}
                    onClick={() => testVoicePreview()}
                  >
                    🔊 Test Voice
                  </button>
                </div>
                <select
                  value={selectedVoice}
                  onChange={e => handleVoiceSelect(e.target.value)}
                  style={{
                    width: '100%',
                    padding: '8px 12px',
                    borderRadius: '6px',
                    background: '#232838',
                    color: '#f1f5f9',
                    border: '1px solid #3b82f6',
                    fontSize: '13px'
                  }}
                >
                  {voices.map(v => (
                    <option key={v.voiceURI} value={v.voiceURI}>
                      {v.name} ({v.lang}) {/natural|neural|online|google/i.test(v.name) ? '★ Natural' : ''}
                    </option>
                  ))}
                </select>
                <p className="hint" style={{ marginTop: '4px' }}>
                  Tip: Voices with <strong>Natural</strong> or <strong>Google</strong> sound human and expressive.
                </p>
              </div>

              {/* VAD Sensitivity Slider */}
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '4px', fontSize: '13px' }}>
                  <span style={{ fontWeight: 600 }}>Silence Auto-Stop Delay:</span>
                  <strong style={{ color: '#60a5fa' }}>{vadSilenceDelayMs} ms ({vadSilenceDelayMs < 800 ? 'Ultra Fast' : vadSilenceDelayMs <= 1100 ? 'Recommended' : 'Relaxed'})</strong>
                </div>
                <input
                  type="range"
                  min="500"
                  max="1600"
                  step="50"
                  value={vadSilenceDelayMs}
                  onChange={e => setVadSilenceDelayMs(Number(e.target.value))}
                  style={{ width: '100%', cursor: 'pointer' }}
                />
                <p className="hint" style={{ marginTop: '2px' }}>
                  How long to wait after you stop speaking before Albert automatically answers.
                </p>
              </div>

              {/* TTS Read Aloud Toggle */}
              <div>
                <label style={{ display: 'flex', alignItems: 'center', gap: '8px', cursor: 'pointer', fontSize: '13px' }}>
                  <input
                    type="checkbox"
                    checked={ttsEnabled}
                    onChange={e => setTtsEnabled(e.target.checked)}
                  />
                  <span>Automatically speak Prof. Albert's replies out loud</span>
                </label>
              </div>
            </div>
            <div className="modal-footer">
              <button className="btn-primary" onClick={() => setShowSettings(false)}>
                Save & Close
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default App;
