// Port of ai/GeminiManager.kt onto the Gemini REST API (streaming).
//
// The system instruction is copied verbatim from the Android client. It is
// the contract between the reflex layer and the model: Gemini is told a
// separate system owns "STOP", and told the exact field names it will find in
// the navigation block. Change it here and on Android together or the two
// clients diverge in behaviour.
//
// Frames are downscaled to 320x320 JPEG q=0.7 before upload, as on Android -
// the model does not need more, and latency is dominated by upload on a phone.

const SYSTEM_INSTRUCTION = `
ROLE: Directional Guide for a blind user.
NOTE: A separate on-device system handles STOP warnings. You must NEVER say "STOP".
INPUT: Camera image plus a structured GPS/compass navigation block.

RULES:
1. PATH CLEAR: If the path ahead looks open AND isObstacleInFront is false, say
   "Walk forward" or "Path is clear." If isObstacleInFront is true, NEVER say "Walk forward"
   or "Path is clear" — instead give a directional avoidance instruction (e.g. "Veer left.").
2. DIRECTIONAL GUIDANCE: If obstacles are visible, guide around them.
   - "Veer left.", "Veer right.", "Step left.", "Step right."
3. TRAFFIC LIGHT PROTOCOL:
   - White Walking Person Icon = SAFE to cross.
   - Orange Hand = say "Wait to cross."
4. NAV CONTEXT: The navigation block contains these fields — act on them exactly:
   isObstacleInFront: when true, an on-device reflex already alerted the user.
   ROUTE: the compass direction the route requires.
   TRAVEL STATUS: pre-computed verdict on the user's movement direction.
   CAMERA STATUS: whether the phone camera is aligned with the route.
   NEXT STEP: current turn instruction and distance.
   ROUTE SUMMARY: overall distance and duration for the active route.

   When isObstacleInFront is true, prioritize calculating an immediate path adjustment
   or alternative trajectory to guide the user safely around the obstruction. Do not
   repeat "STOP" — the reflex layer already handled urgency.

   TRAVEL STATUS rules (highest priority — override visual scene if needed, unless
   isObstacleInFront is true, then obstacle avoidance guidance takes precedence):
   - Contains "WRONG WAY"    → say "Turn around, wrong direction."
   - Contains "HEADING AWAY" → say "Wrong way. Turn toward [ROUTE direction]."
   - Contains "OFF ROUTE"    → say "Bear [left/right] toward [ROUTE direction]."
   - Contains "SLIGHTLY OFF" → mention it only if no obstacle guidance is needed.
   - Contains "ON TRACK"     → ignore bearing, focus entirely on visual scene.

   CAMERA STATUS rules:
   - Contains "MISALIGNED"   → note it only if it contradicts the visual scene.
   - Otherwise               → ignore camera status.

5. LANDMARKS: Identify doors, signs, crosswalks, stairs, traffic lights.
6. CONSTRAINT: Under 12 words. Be direct and actionable. ONLY SAY STOP IF THE TRAFFIC LIGHT IS RED AND THE PEDESTRIAN IS AT THE INTERSECTION.
`.trim();

export const DEFAULT_MODEL_NAME = "gemini-3-flash-preview";
const FRAME_SIDE = 320;
const JPEG_QUALITY = 0.7;

export class GeminiManager {
  constructor(apiKey, modelName = DEFAULT_MODEL_NAME) {
    if (!apiKey) throw new Error("GEMINI_API_KEY missing - see config.example.js");
    this.apiKey = apiKey;
    this.modelName = modelName;
    this._canvas = document.createElement("canvas");
    this._canvas.width = this._canvas.height = FRAME_SIDE;
    this._ctx = this._canvas.getContext("2d", { willReadFrequently: true });
    this.inFlight = false;
    this.lastLatencyMs = 0;
  }

  _encodeFrame(source) {
    this._ctx.drawImage(source, 0, 0, FRAME_SIDE, FRAME_SIDE);
    return this._canvas.toDataURL("image/jpeg", JPEG_QUALITY).split(",")[1];
  }

  _prompt(navContext, activeUserGoal, isObstacleInFront) {
    if (activeUserGoal) {
      return [
        `OBJECTIVE: Guide the user to "${activeUserGoal}".`,
        `isObstacleInFront=${isObstacleInFront}`,
        `When they are directly in front of it, say 'You have reached the ${activeUserGoal}.' and end with [DONE].`,
        `Otherwise, provide directional steps to reach it.`,
      ].join("\n");
    }
    return [
      `REAL-TIME NAVIGATION METADATA:`,
      navContext,
      ``,
      `TASK: Use the metadata and image to provide ONLY the next directional instruction.`,
      `If isObstacleInFront is true, guide around the obstruction immediately.`,
      `Otherwise prioritize TRAVEL STATUS.`,
    ].join("\n");
  }

  /**
   * Stream a guidance instruction. `onChunk(fullTextSoFar)` fires as text
   * arrives; resolves with the final string. Concurrent calls are refused -
   * the caller's throttle is the only thing that should be pacing this.
   */
  async analyzeStream(source, { navContext, activeUserGoal = null, isObstacleInFront = false }, onChunk) {
    if (this.inFlight) return null;
    this.inFlight = true;
    const t0 = performance.now();
    try {
      const data = this._encodeFrame(source);
      const url =
        `https://generativelanguage.googleapis.com/v1beta/models/${this.modelName}` +
        `:streamGenerateContent?alt=sse&key=${encodeURIComponent(this.apiKey)}`;
      const body = {
        systemInstruction: { parts: [{ text: SYSTEM_INSTRUCTION }] },
        contents: [{
          role: "user",
          parts: [
            { inlineData: { mimeType: "image/jpeg", data } },
            { text: this._prompt(navContext, activeUserGoal, isObstacleInFront) },
          ],
        }],
        generationConfig: { maxOutputTokens: 60, temperature: 0.2 },
      };
      const res = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        const err = await res.text().catch(() => "");
        throw new Error(`Gemini ${res.status}: ${err.slice(0, 200)}`);
      }
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      let buf = "", full = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let nl;
        while ((nl = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, nl).trim();
          buf = buf.slice(nl + 1);
          if (!line.startsWith("data:")) continue;
          try {
            const j = JSON.parse(line.slice(5));
            const text = j.candidates?.[0]?.content?.parts?.map((p) => p.text ?? "").join("") ?? "";
            if (text) { full += text; onChunk?.(full); }
          } catch { /* partial JSON across chunks; wait for more */ }
        }
      }
      this.lastLatencyMs = performance.now() - t0;
      return full.trim();
    } finally {
      this.inFlight = false;
    }
  }
}
