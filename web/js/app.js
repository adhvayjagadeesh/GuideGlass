// The orchestrator. Plays the role of vision/VisionActivity.kt and
// viewmodel/NavigationViewModel.kt: wires camera -> reflex -> Gemini -> TTS,
// and maps/sensors -> nav state -> Gemini. Timing constants are the Android
// ones.
//
// Three modes, as on the launcher: Vision, Navigation, Combined. Combined is
// the product; the other two exist so each tier can be tested alone.

import { Camera } from "./camera.js";
import { ObstacleDetector, AvoidanceHint } from "./reflex.js";
import { GeminiManager } from "./gemini.js";
import { SpeechHelper } from "./speech.js";
import { nav, buildNavContext, currentTravelLevel } from "./navState.js";
import * as maps from "./maps.js";
import { startCompass, setSimulatedHeading, clearSimulatedHeading, sensors, haptic } from "./sensors.js";
import { VoiceListener, parseCommand } from "./voice.js";

// VisionActivity constants
const THROTTLE_MOVING_MS = 1500;
const THROTTLE_IDLE_MS = 3000;
const STOP_COOLDOWN_MS = 3500;
const REARM_CLEAR_MS = 1200;
const WRONG_DIRECTION_THROTTLE_MS = 10_000;
const REFLEX_INTERVAL_MS = 120;      // ~8 fps of detection; model latency gates it anyway

const $ = (id) => document.getElementById(id);
const ui = {
  video: $("video"), overlay: $("overlay"), map: $("map"), search: $("search"),
  status: $("status"), guidance: $("guidance"), transcript: $("transcript"),
  reflexState: $("reflexState"), navState: $("navState"), latency: $("latency"),
  heading: $("heading"), headingVal: $("headingVal"), simPos: $("simPos"),
  voiceStatus: $("voiceStatus"), goal: $("goal"), modeBtns: document.querySelectorAll("[data-mode]"),
};

const cfg = window.GUIDEGLASS_CONFIG || {};
const speech = new SpeechHelper({ rate: 1.5 });
const camera = new Camera(ui.video);
const reflex = new ObstacleDetector();
let gemini = null;
let voice = null;

const state = {
  mode: null, running: false,
  lastGeminiAt: 0, lastStopAt: 0, clearedAt: 0, lastWrongDirAt: 0,
  reflexTimer: null, geminiTimer: null,
  paused: false,
};

// ------------------------------------------------------------------ log ---

function log(kind, text) {
  const li = document.createElement("li");
  li.className = kind;
  const t = new Date().toLocaleTimeString([], { hour12: false });
  li.innerHTML = `<time>${t}</time><span>${text}</span>`;
  ui.transcript.prepend(li);
  while (ui.transcript.children.length > 60) ui.transcript.lastChild.remove();
}
speech.onSpeak = (text, _) => log("tts", `🔊 ${text}`);

function setStatus(text, cls = "") { ui.status.textContent = text; ui.status.className = `status ${cls}`; }

// ------------------------------------------------------------- overlay ---

function drawOverlay(decisionActive) {
  const c = ui.overlay, v = ui.video;
  if (!v.videoWidth) return;
  if (c.width !== v.videoWidth || c.height !== v.videoHeight) { c.width = v.videoWidth; c.height = v.videoHeight; }
  const g = c.getContext("2d");
  g.clearRect(0, 0, c.width, c.height);
  // corridor gate
  g.strokeStyle = "rgba(255,255,255,0.35)"; g.setLineDash([8, 8]); g.lineWidth = 2;
  for (const r of [0.32, 0.68]) { g.beginPath(); g.moveTo(c.width * r, 0); g.lineTo(c.width * r, c.height); g.stroke(); }
  g.setLineDash([]);
  for (const d of reflex.lastDetections) {
    const near = d.h / c.height >= 0.34 || (d.w * d.h) / (c.width * c.height) >= 0.12;
    g.strokeStyle = near ? "#ff4d4d" : "#2fd08c"; g.lineWidth = near ? 4 : 2;
    g.strokeRect(d.x, d.y, d.w, d.h);
    g.fillStyle = g.strokeStyle; g.font = "bold 16px system-ui";
    g.fillText(`${d.cls} ${(d.score * 100) | 0}% #${d.id}`, d.x + 4, Math.max(16, d.y - 6));
  }
  if (decisionActive) { g.strokeStyle = "#ff4d4d"; g.lineWidth = 12; g.strokeRect(0, 0, c.width, c.height); }
}

// -------------------------------------------------------------- reflex ---

const reflexListener = {
  onObstacleEntered(d) {
    nav.isObstacleInFront = true;
    const now = Date.now();
    ui.reflexState.textContent = `OBSTACLE: ${d.label} h=${(d.heightFraction * 100) | 0}% ${d.avoidance}`;
    ui.reflexState.className = "pill danger";
    if (now - state.lastStopAt >= STOP_COOLDOWN_MS) {
      state.lastStopAt = now;
      const hint = d.avoidance === AvoidanceHint.VEER_LEFT ? " Veer left." : d.avoidance === AvoidanceHint.VEER_RIGHT ? " Veer right." : "";
      speech.speakUrgent(`STOP.${hint}`);
      haptic([120, 60, 120]);
      log("stop", `⛔ STOP — ${d.label} (${(d.heightFraction * 100) | 0}% of frame)${hint}`);
    }
  },
  onObstacleCleared() {
    state.clearedAt = Date.now();
    setTimeout(() => { if (Date.now() - state.clearedAt >= REARM_CLEAR_MS - 5) nav.isObstacleInFront = false; }, REARM_CLEAR_MS);
    ui.reflexState.textContent = "path clear"; ui.reflexState.className = "pill ok";
    log("info", "path clear");
  },
};

async function reflexTick() {
  if (!state.running || state.paused || !camera.ready) return;
  await reflex.processFrame(ui.video, reflexListener);
  drawOverlay(reflex.pathObstaclePresent);
}

// -------------------------------------------------------------- gemini ---

async function geminiTick() {
  if (!state.running || state.paused || !camera.ready || !gemini || gemini.inFlight) return;
  if (state.mode === "navigation") return;   // maps-only mode has no camera tier
  const now = Date.now();
  const throttle = nav.isMoving || nav.activeUserGoal ? THROTTLE_MOVING_MS : THROTTLE_IDLE_MS;
  if (now - state.lastGeminiAt < throttle) return;
  state.lastGeminiAt = now;

  const navContext = buildNavContext(now);
  ui.navState.textContent = navContext;
  try {
    const text = await gemini.analyzeStream(
      ui.video,
      { navContext, activeUserGoal: nav.activeUserGoal, isObstacleInFront: nav.isObstacleInFront },
      (partial) => { ui.guidance.textContent = partial; },
    );
    if (text == null) return;
    ui.latency.textContent = `${gemini.lastLatencyMs | 0} ms`;
    ui.guidance.textContent = text;
    // Gemini must never say STOP; strip it defensively as VisionActivity does.
    const spoken = text.replace(/\bSTOP\b\.?/gi, "").replace(/\[DONE\]/g, "").trim();
    if (spoken) speech.speak(spoken);
    log("gemini", `🧠 ${text}`);
    if (/\[DONE\]/.test(text) && nav.activeUserGoal) { log("info", `goal reached: ${nav.activeUserGoal}`); nav.activeUserGoal = null; ui.goal.value = ""; }
  } catch (e) {
    ui.guidance.textContent = `Gemini error: ${e.message}`;
    log("error", `Gemini: ${e.message}`);
    state.lastGeminiAt = now + 4000;   // back off
  }
}

// ---------------------------------------------------------- navigation ---

function wrongDirectionCheck() {
  if (!nav.navigating) return;
  const lvl = currentTravelLevel();
  const now = Date.now();
  if (lvl >= 3 && now - state.lastWrongDirAt >= WRONG_DIRECTION_THROTTLE_MS) {
    state.lastWrongDirAt = now;
    speech.speakNavigation(lvl === 4 ? "Turn around, wrong direction." : "Wrong way. Turn toward the route.");
  }
}

const mapCallbacks = {
  onPosition(p) {
    ui.simPos.textContent = `${p.lat.toFixed(5)}, ${p.lng.toFixed(5)} ±${p.accuracy | 0}m`;
    if (nav.navigating) { ui.navState.textContent = buildNavContext(); wrongDirectionCheck(); }
  },
  onRoute({ summary, steps, dest }) {
    speech.speakNavigation(`Route to ${dest}. ${summary}. ${steps[0]?.instruction ?? ""}`);
    log("nav", `🗺 route to ${dest}: ${summary}, ${steps.length} steps`);
  },
  onStep(i, s) {
    if (i > 0) speech.speakNavigation(`${s.instruction}. ${s.distanceText}.`);
    log("nav", `➡ step ${i + 1}: ${s.instruction} (${s.distanceText})`);
  },
  onArrive(dest) {
    speech.speakNavigation(`You have arrived at ${dest}.`);
    log("nav", `🏁 arrived: ${dest}`);
    ui.navState.textContent = "arrived";
  },
};

async function handleCommand(intent, raw) {
  try {
    switch (intent.type) {
      case "cancel":
        maps.stopNavigation(); nav.activeUserGoal = null; ui.goal.value = "";
        speech.speakNavigation("Navigation cancelled."); log("nav", "cancelled"); break;
      case "navigate": {
        const p = await maps.findPlace(intent.canonical !== intent.query ? intent.canonical : intent.query);
        await maps.routeTo(p, p.name); break;
      }
      case "nearest": {
        const p = await maps.findNearest(intent.canonical !== intent.query ? intent.canonical : intent.query);
        speech.speakNavigation(`Nearest ${intent.canonical} is ${p.name}. Routing.`);
        await maps.routeTo(p, p.name); break;
      }
      case "goal":
        nav.activeUserGoal = intent.goal; ui.goal.value = intent.goal;
        speech.speakNavigation(`Looking for ${intent.goal}.`); log("info", `goal: ${intent.goal}`); break;
    }
  } catch (e) { speech.speakNavigation(e.message); log("error", e.message); }
}

// --------------------------------------------------------------- modes ---

async function startMode(mode) {
  speech.prime();
  stopMode();
  state.mode = mode; state.running = true;
  ui.modeBtns.forEach((b) => b.classList.toggle("active", b.dataset.mode === mode));
  setStatus(`starting ${mode}…`, "busy");
  log("info", `mode: ${mode}`);

  const needsCamera = mode !== "navigation";
  const needsMaps = mode !== "vision";

  try {
    if (needsCamera) {
      const { width, height } = await camera.start();
      log("info", `camera ${width}×${height}`);
      setStatus("loading reflex model…", "busy");
      await reflex.load();
      if (!gemini) gemini = new GeminiManager(cfg.GEMINI_API_KEY, cfg.GEMINI_MODEL || undefined);
      state.reflexTimer = setInterval(reflexTick, REFLEX_INTERVAL_MS);
      state.geminiTimer = setInterval(geminiTick, 250);
      ui.reflexState.textContent = "path clear"; ui.reflexState.className = "pill ok";
    }
    if (needsMaps) {
      if (!maps.mapsState.ready) {
        setStatus("loading maps…", "busy");
        await maps.loadMapsApi(cfg.GOOGLE_MAPS_KEY, (err) => {
          // arrives AFTER the script resolves; must override the "running" status
          setStatus(err.message, "error"); log("error", err.message);
          speech.speakUrgent("Maps key rejected. Navigation unavailable.");
        });
        maps.initMap(ui.map, ui.search, mapCallbacks);
      }
      maps.startLocation();
      await startCompass((b) => { ui.headingVal.textContent = `${b | 0}° (${sensors.source})`; if (!sensors.simulated) ui.heading.value = b | 0; maps.drawHeading(); });
    }
    document.body.dataset.mode = mode;
    setStatus(`${mode} running`, "ok");
    speech.speak(mode === "combined" ? "Vision and navigation ready." : `${mode} ready.`, { skipDuplicateCheck: true });
  } catch (e) {
    setStatus(e.message, "error"); log("error", e.message); state.running = false;
  }
}

function stopMode() {
  state.running = false;
  clearInterval(state.reflexTimer); clearInterval(state.geminiTimer);
  camera.stop(); maps.stopLocation(); speech.stop();
  nav.isObstacleInFront = false;
  ui.overlay.getContext("2d")?.clearRect(0, 0, ui.overlay.width, ui.overlay.height);
  ui.modeBtns.forEach((b) => b.classList.remove("active"));
  document.body.dataset.mode = "";
}

// ------------------------------------------------------------------ ui ---

function wireUi() {
  ui.modeBtns.forEach((b) => b.addEventListener("click", () => startMode(b.dataset.mode)));
  $("stopBtn").addEventListener("click", () => { stopMode(); setStatus("stopped"); log("info", "stopped"); });
  $("pauseBtn").addEventListener("click", (e) => { state.paused = !state.paused; e.target.textContent = state.paused ? "Resume analysis" : "Pause analysis"; });
  $("flipBtn").addEventListener("click", () => camera.flip().catch((e) => log("error", e.message)));
  $("simToggle").addEventListener("change", (e) => {
    maps.mapsState.simulate = e.target.checked;
    log("info", e.target.checked ? "SIMULATE: click the map to move; slider sets heading" : "live sensors");
    if (!e.target.checked) clearSimulatedHeading();
  });
  ui.heading.addEventListener("input", (e) => { if ($("simToggle").checked) setSimulatedHeading(+e.target.value); });
  $("goBtn").addEventListener("click", () => { const q = ui.search.value.trim(); if (q) handleCommand(parseCommand(`navigate to ${q}`), q); });
  $("cancelNavBtn").addEventListener("click", () => handleCommand({ type: "cancel" }));
  $("centerBtn").addEventListener("click", () => maps.panToUser());
  ui.goal.addEventListener("change", (e) => { nav.activeUserGoal = e.target.value.trim() || null; log("info", nav.activeUserGoal ? `goal: ${nav.activeUserGoal}` : "goal cleared"); });
  $("cmdForm").addEventListener("submit", (e) => { e.preventDefault(); const t = $("cmd").value.trim(); if (t) { handleCommand(parseCommand(t), t); $("cmd").value = ""; } });
  voice = new VoiceListener(handleCommand, (s) => (ui.voiceStatus.textContent = s));
  $("micBtn").addEventListener("click", () => voice.listenOnce());
  if (!voice.supported) { $("micBtn").disabled = true; ui.voiceStatus.textContent = "voice: not supported in this browser"; }

  // key check up front so the failure is legible
  const missing = [];
  if (!cfg.GEMINI_API_KEY || /your_/.test(cfg.GEMINI_API_KEY)) missing.push("GEMINI_API_KEY");
  if (!cfg.GOOGLE_MAPS_KEY || /your_/.test(cfg.GOOGLE_MAPS_KEY)) missing.push("GOOGLE_MAPS_KEY");
  if (missing.length) setStatus(`config.js missing: ${missing.join(", ")} — copy config.example.js`, "error");
  else setStatus("ready — pick a mode");
}

wireUi();
