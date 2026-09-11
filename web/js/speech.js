// Port of tts/SpeechHelper.kt onto the Web Speech API.
//
// Two queues. Urgent messages (the reflex "STOP") flush everything and speak
// immediately; normal messages wait their turn. A message identical to the
// last one spoken within DUPLICATE_WINDOW_MS is dropped, because Gemini
// returning "Walk forward" every 1.5 s should not be read aloud every 1.5 s.
//
// Browser quirk: speechSynthesis will not speak until the page has had a user
// gesture, and Chrome silently drops utterances if the voices list has not
// loaded yet. Both are handled by `prime()`, called from the first click.

const DUPLICATE_WINDOW_MS = 4000;

export class SpeechHelper {
  constructor({ rate = 1.5, pitch = 1.0, lang = "en-US" } = {}) {
    this.rate = rate;
    this.pitch = pitch;
    this.lang = lang;
    this.urgentQueue = [];
    this.normalQueue = [];
    this.speaking = false;
    this.lastText = "";
    this.lastAt = 0;
    this.enabled = "speechSynthesis" in window;
    this.voice = null;
    this.onSpeak = null;           // UI hook: (text, urgent) => void
    if (this.enabled) {
      const pick = () => {
        const voices = speechSynthesis.getVoices();
        this.voice =
          voices.find((v) => v.lang === lang && v.localService) ||
          voices.find((v) => v.lang === lang) ||
          voices.find((v) => v.lang.startsWith("en")) ||
          null;
      };
      pick();
      speechSynthesis.onvoiceschanged = pick;
    }
  }

  /** Call from a user gesture so the browser allows audio. */
  prime() {
    if (!this.enabled) return;
    const u = new SpeechSynthesisUtterance("");
    u.volume = 0;
    speechSynthesis.speak(u);
  }

  speak(text, { force = false, skipDuplicateCheck = false } = {}) {
    if (!this.enabled || !text) return;
    const t = text.trim();
    if (!t) return;
    const now = Date.now();
    if (!skipDuplicateCheck && t === this.lastText && now - this.lastAt < DUPLICATE_WINDOW_MS) {
      return;
    }
    if (force) {
      this.urgentQueue.length = 0;
      this.normalQueue.length = 0;
      speechSynthesis.cancel();
      this.speaking = false;
      this.urgentQueue.push(t);
    } else {
      this.normalQueue.push(t);
    }
    this._next();
  }

  speakNavigation(instruction) {
    this.speak(instruction, { skipDuplicateCheck: true });
  }

  speakUrgent(warning) {
    this.speak(warning, { force: true, skipDuplicateCheck: true });
  }

  stop() {
    this.urgentQueue.length = 0;
    this.normalQueue.length = 0;
    if (this.enabled) speechSynthesis.cancel();
    this.speaking = false;
  }

  _next() {
    if (this.speaking) return;
    const text = this.urgentQueue.shift() ?? this.normalQueue.shift();
    if (!text) return;
    this.speaking = true;
    this.lastText = text;
    this.lastAt = Date.now();
    const u = new SpeechSynthesisUtterance(text);
    u.rate = this.rate;
    u.pitch = this.pitch;
    u.lang = this.lang;
    if (this.voice) u.voice = this.voice;
    const done = () => {
      this.speaking = false;
      this._next();
    };
    u.onend = done;
    u.onerror = done;
    this.onSpeak?.(text, this.urgentQueue.length === 0 && this.normalQueue.length === 0);
    speechSynthesis.speak(u);
    // Chrome bug: long utterances can stall; a watchdog keeps the queue alive.
    setTimeout(() => { if (this.speaking && !speechSynthesis.speaking) done(); }, 15000);
  }
}
