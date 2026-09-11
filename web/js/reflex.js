// Port of vision/ObstacleDetector.kt - the Tier 1 reflex.
//
// Runs on-device (in the browser, via TensorFlow.js COCO-SSD) and owns the
// "STOP" decision. Gemini is explicitly told never to say STOP; this layer is
// the only thing that does, because it is the only one fast enough to matter.
//
// The proximity model is the Android one, verbatim:
//   * corridor gate  - only objects in the centre 32%..68% of the frame count
//   * near by size   - height >= 34% of frame, or area >= 12%
//   * looming        - tracked height growing >= 60%/s, or time-to-contact <= 2 s
//   * edge triggers  - 2 consecutive hits to ENTER, 4 consecutive misses to CLEAR
//
// One difference forced by the platform: ML Kit STREAM_MODE hands back a
// trackingId per object, COCO-SSD does not. `Tracker` below assigns stable
// ids by greedy IoU matching between frames so the looming logic has
// something to work on. It is deliberately simple - it only needs to survive
// the ~1 s window the loom detector looks at.

export const ReflexTuning = {
  CENTER_PATH_MIN_X_RATIO: 0.32,
  CENTER_PATH_MAX_X_RATIO: 0.68,
  MIN_CORRIDOR_OVERLAP_RATIO: 0.45,
  MAX_BOX_WIDTH_RATIO: 0.90,
  NEAR_HEIGHT_RATIO: 0.34,
  NEAR_AREA_RATIO: 0.12,
  ENTER_STREAK_REQUIRED: 2,
  CLEAR_STREAK_REQUIRED: 4,
};

const LOOM_WINDOW_MS = 900;
const TRACK_TTL_MS = 1200;
const MAX_SAMPLES_PER_TRACK = 6;
const MIN_LOOM_HEIGHT_RATIO = 0.16;
const MIN_LOOM_AREA_RATIO = 0.03;
const LOOM_GROWTH_RATE_PER_SEC = 0.6;
const TTC_IMMINENT_SEC = 2.0;

const HINT_SIDE_MIN_HEIGHT = 0.15;
const HINT_OCC_DIFF = 0.20;
const HINT_CLEAR_OCC = 0.35;
const CENTER_NEUTRAL_MIN = 0.46;
const CENTER_NEUTRAL_MAX = 0.54;

const MIN_SCORE = 0.45;        // COCO-SSD confidence floor
const IOU_MATCH = 0.3;

export const AvoidanceHint = Object.freeze({ NONE: "NONE", VEER_LEFT: "VEER_LEFT", VEER_RIGHT: "VEER_RIGHT" });

function iou(a, b) {
  const x0 = Math.max(a.x, b.x), y0 = Math.max(a.y, b.y);
  const x1 = Math.min(a.x + a.w, b.x + b.w), y1 = Math.min(a.y + a.h, b.y + b.h);
  const inter = Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
  const union = a.w * a.h + b.w * b.h - inter;
  return union > 0 ? inter / union : 0;
}

/** Greedy IoU tracker: gives each detection a persistent id across frames. */
class Tracker {
  constructor() { this.prev = []; this.nextId = 1; }
  assign(dets) {
    const pairs = [];
    dets.forEach((d, i) => this.prev.forEach((p) => {
      const s = iou(d, p);
      if (s >= IOU_MATCH) pairs.push([s, i, p.id]);
    }));
    pairs.sort((a, b) => b[0] - a[0]);
    const usedDet = new Set(), usedId = new Set();
    for (const [, i, id] of pairs) {
      if (usedDet.has(i) || usedId.has(id)) continue;
      dets[i].id = id; usedDet.add(i); usedId.add(id);
    }
    for (const d of dets) if (d.id == null) d.id = this.nextId++;
    this.prev = dets.map((d) => ({ ...d }));
    return dets;
  }
}

export class ObstacleDetector {
  constructor(tuning = {}) {
    this.t = { ...ReflexTuning, ...tuning };
    this.model = null;
    this.tracker = new Tracker();
    this.trackHistory = new Map();   // id -> [{tMs, heightFrac}]
    this.trackLastSeen = new Map();
    this.pathObstaclePresent = false;
    this.enterStreak = 0;
    this.clearStreak = 0;
    this.busy = false;
    this.lastDetections = [];        // for the overlay
  }

  async load() {
    if (this.model) return;
    if (!window.cocoSsd) throw new Error("coco-ssd script not loaded");
    this.model = await window.cocoSsd.load({ base: "lite_mobilenet_v2" });
  }

  /**
   * Analyse one frame. `listener` gets onObstacleEntered(decision) /
   * onObstacleCleared(). Frames arriving while inference is in flight are
   * dropped - the KEEP_ONLY_LATEST backpressure policy from CameraX.
   */
  async processFrame(source, listener) {
    if (!this.model || this.busy) return;
    this.busy = true;
    try {
      const w = source.videoWidth || source.width;
      const h = source.videoHeight || source.height;
      const raw = await this.model.detect(source);
      const dets = raw
        .filter((r) => r.score >= MIN_SCORE)
        .map((r) => ({ x: r.bbox[0], y: r.bbox[1], w: r.bbox[2], h: r.bbox[3], cls: r.class, score: r.score }));
      this.tracker.assign(dets);
      this.lastDetections = dets;

      const now = Date.now();
      const decision = this._evaluate(dets, w, h, now);

      if (decision) {
        this.clearStreak = 0;
        if (!this.pathObstaclePresent) {
          this.enterStreak++;
          if (this.enterStreak >= this.t.ENTER_STREAK_REQUIRED) {
            this.pathObstaclePresent = true;
            this.enterStreak = 0;
            listener.onObstacleEntered(decision);
          }
        }
      } else {
        this.enterStreak = 0;
        if (this.pathObstaclePresent) {
          this.clearStreak++;
          if (this.clearStreak >= this.t.CLEAR_STREAK_REQUIRED) {
            this.pathObstaclePresent = false;
            this.clearStreak = 0;
            listener.onObstacleCleared();
          }
        }
      }
    } finally {
      this.busy = false;
    }
  }

  _evaluate(dets, frameW, frameH, now) {
    if (frameW <= 0 || frameH <= 0) return null;
    this._cleanupTracks(now);
    const t = this.t;
    const corridorMinX = frameW * t.CENTER_PATH_MIN_X_RATIO;
    const corridorMaxX = frameW * t.CENTER_PATH_MAX_X_RATIO;
    const corridorW = corridorMaxX - corridorMinX;
    const frameArea = frameW * frameH;
    const midX = frameW / 2;

    let leftOcc = 0, rightOcc = 0;
    let danger = null, dangerH = 0;

    for (const d of dets) {
      if (d.w <= 0 || d.h <= 0) continue;
      if (d.w >= frameW * t.MAX_BOX_WIDTH_RATIO) continue;      // background span

      const heightFrac = d.h / frameH;
      const areaFrac = (d.w * d.h) / frameArea;
      const centerX = d.x + d.w / 2;

      if (heightFrac >= HINT_SIDE_MIN_HEIGHT) {
        if (centerX < midX) leftOcc = Math.max(leftOcc, heightFrac);
        else rightOcc = Math.max(rightOcc, heightFrac);
      }

      const centerIn = centerX >= corridorMinX && centerX <= corridorMaxX;
      const overlap = Math.min(d.x + d.w, corridorMaxX) - Math.max(d.x, corridorMinX);
      const overlapFrac = corridorW > 0 ? overlap / corridorW : 0;
      if (!centerIn && overlapFrac < t.MIN_CORRIDOR_OVERLAP_RATIO) continue;

      this._updateTrack(d.id, now, heightFrac);
      const looming = this._isLooming(this.trackHistory.get(d.id), heightFrac, areaFrac);
      const nearBySize = heightFrac >= t.NEAR_HEIGHT_RATIO || areaFrac >= t.NEAR_AREA_RATIO;
      if (!nearBySize && !looming) continue;

      if (heightFrac > dangerH) { dangerH = heightFrac; danger = d; }
    }

    if (!danger) return null;
    const centerRatio = (danger.x + danger.w / 2) / frameW;
    return {
      avoidance: this._hint(centerRatio, leftOcc, rightOcc),
      obstacleCenterXRatio: centerRatio,
      heightFraction: dangerH,
      label: danger.cls,
    };
  }

  _updateTrack(id, now, heightFrac) {
    let hist = this.trackHistory.get(id);
    if (!hist) { hist = []; this.trackHistory.set(id, hist); }
    hist.push({ tMs: now, heightFrac });
    while (hist.length > 1 && now - hist[0].tMs > LOOM_WINDOW_MS) hist.shift();
    while (hist.length > MAX_SAMPLES_PER_TRACK) hist.shift();
    this.trackLastSeen.set(id, now);
  }

  _isLooming(hist, currH, currA) {
    if (!hist || hist.length < 2) return false;
    if (currH < MIN_LOOM_HEIGHT_RATIO && currA < MIN_LOOM_AREA_RATIO) return false;
    const first = hist[0], last = hist[hist.length - 1];
    const dt = (last.tMs - first.tMs) / 1000;
    if (dt <= 0 || first.heightFrac <= 0) return false;
    const dh = last.heightFrac - first.heightFrac;
    if (dh <= 0) return false;
    if ((dh / first.heightFrac) / dt >= LOOM_GROWTH_RATE_PER_SEC) return true;
    const ttc = (dt * first.heightFrac) / dh;
    return ttc >= 0 && ttc <= TTC_IMMINENT_SEC;
  }

  _hint(centerRatio, leftOcc, rightOcc) {
    if (centerRatio >= CENTER_NEUTRAL_MIN && centerRatio <= CENTER_NEUTRAL_MAX) return AvoidanceHint.NONE;
    if (Math.abs(leftOcc - rightOcc) < HINT_OCC_DIFF) return AvoidanceHint.NONE;
    if (centerRatio < 0.5) return rightOcc <= HINT_CLEAR_OCC ? AvoidanceHint.VEER_RIGHT : AvoidanceHint.NONE;
    return leftOcc <= HINT_CLEAR_OCC ? AvoidanceHint.VEER_LEFT : AvoidanceHint.NONE;
  }

  _cleanupTracks(now) {
    for (const [id, seen] of this.trackLastSeen) {
      if (now - seen > TRACK_TTL_MS) { this.trackHistory.delete(id); this.trackLastSeen.delete(id); }
    }
  }

  close() {
    this.model?.dispose?.();
    this.model = null;
    this.trackHistory.clear();
    this.trackLastSeen.clear();
  }
}
