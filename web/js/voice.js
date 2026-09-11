// Port of voice/VoiceCommandManager.kt onto the Web Speech API.
//
// Intent grammar is verbatim from Android so the same phrases work on both:
//   cancel   : stop | cancel | never mind | quit | exit
//   nearest  : "nearest X" | "closest X" | "find (me) (a|an|the) X" | "where is (the) X"
//   navigate : "(navigate|directions|take me|go|head) to X"
//   otherwise: a free-form goal handed to Gemini ("find the door")
//
// SpeechRecognition is Chrome/Edge only. Everything degrades to the text box.

const COMMON_POI_ALIASES = {
  "Coffee shop": ["coffee", "cafe", "café", "starbucks", "espresso"],
  "Grocery store": ["grocery", "groceries", "supermarket", "market", "safeway", "trader joe"],
  "Pharmacy": ["pharmacy", "drugstore", "cvs", "walgreens", "chemist"],
  "Bus stop": ["bus stop", "bus", "transit"],
  "Restroom": ["restroom", "bathroom", "toilet", "washroom"],
  "Hospital": ["hospital", "emergency", "er", "urgent care"],
  "ATM": ["atm", "cash machine", "bank"],
  "Restaurant": ["restaurant", "food", "eat", "dinner", "lunch"],
  "McDonald's": ["mcdonald", "mcdonalds", "macdonalds", "fast food"],
};

export function resolvePoiAlias(query) {
  const lq = query.toLowerCase().trim();
  for (const [canonical, aliases] of Object.entries(COMMON_POI_ALIASES)) {
    if (canonical.toLowerCase() === lq) return canonical;
    if (aliases.some((a) => lq.includes(a) || a.includes(lq))) return canonical;
  }
  return query;
}

export function parseCommand(command) {
  const raw = command.trim();
  const lc = raw.toLowerCase();

  if (/\b(stop|cancel|never\s*mind|quit|exit)\b/.test(lc)) return { type: "cancel" };

  const nearest = /\b(nearest|closest|find\s+(?:me\s+)?(?:a|an|the)?|where\s+is\s+(?:the)?)\s+(.+)/i.exec(lc);
  if (nearest && nearest[2].trim()) {
    const q = nearest[2].trim();
    return { type: "nearest", query: q, canonical: resolvePoiAlias(q) };
  }

  const navi = /\b(navigate|directions?|take\s+me|go|head)\s+to\s+(.+)/i.exec(lc);
  if (navi && navi[2].trim()) {
    const q = navi[2].trim();
    return { type: "navigate", query: q, canonical: resolvePoiAlias(q) };
  }

  return { type: "goal", goal: raw };
}

export class VoiceListener {
  constructor(onCommand, onStatus) {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    this.supported = !!SR;
    this.onCommand = onCommand;
    this.onStatus = onStatus;
    if (!SR) return;
    this.rec = new SR();
    this.rec.lang = "en-US";
    this.rec.interimResults = false;
    this.rec.maxAlternatives = 1;
    this.rec.onresult = (e) => {
      const t = e.results[e.results.length - 1][0].transcript;
      onStatus?.(`heard: "${t}"`);
      onCommand(parseCommand(t), t);
    };
    this.rec.onerror = (e) => onStatus?.(`voice error: ${e.error}`);
    this.rec.onend = () => { this.listening = false; onStatus?.("idle"); };
    this.listening = false;
  }
  listenOnce() {
    if (!this.supported || this.listening) return;
    this.listening = true;
    this.onStatus?.("listening…");
    try { this.rec.start(); } catch { this.listening = false; }
  }
}
