// Port of maps/NavStateManager.kt plus the verdict logic from
// viewmodel/NavigationViewModel.kt (buildNavContext).
//
// One shared object, mutated by the maps/sensor layer and read by the Gemini
// layer - the same role the @Volatile singleton plays on Android. The verdict
// hysteresis is the part worth understanding: a WORSE verdict must persist for
// 2 s before it is shown, a BETTER one shows immediately. Without that, a
// user turning their head or a GPS jitter flips "ON TRACK" to "WRONG WAY" for
// a single frame and Gemini tells them to turn around.

import { bearingDescription, bearingDelta, formatNavigationBlock } from "./navContext.js";

export const STEP_ADVANCE_M = 20;          // NavigationViewModel: distanceToStepEnd < 20
export const VERDICT_COMMIT_MS = 2000;     // worse verdicts must hold this long

export const nav = {
  navigating: false,
  currentInstruction: "No active navigation.",
  remainingDistanceM: 0,
  routeBearing: 0,          // bearing of the current step, degrees
  currentStepIndex: 0,
  isObstacleInFront: false, // set by the reflex layer, consumed by Gemini
  routeSummary: "",
  steps: [],                // [{instruction, distanceText, durationText, start, end, path}]
  destinationName: "",
  activeUserGoal: null,     // free-form "find the door" style goal, or null

  // sensor inputs
  compassBearing: 0,
  gpsBearing: 0,
  hasValidGpsBearing: false,
  position: null,           // {lat, lng, accuracy}
  isMoving: false,

  // hysteresis state
  _committedLevel: 0,
  _pendingLevel: 0,
  _pendingSince: 0,
};

export function resetNavigation() {
  nav.navigating = false;
  nav.currentInstruction = "No active navigation.";
  nav.remainingDistanceM = 0;
  nav.routeBearing = 0;
  nav.currentStepIndex = 0;
  nav.routeSummary = "";
  nav.steps = [];
  nav.destinationName = "";
  nav._committedLevel = nav._pendingLevel = 0;
  nav._pendingSince = 0;
}

/**
 * Raw severity of the travel-direction error, 0..4. Thresholds are verbatim
 * from NavigationViewModel: >120 wrong way, >90 heading away, >45 off route,
 * >20 slightly off.
 */
function rawLevel(travelDelta) {
  if (travelDelta > 120) return 4;
  if (travelDelta > 90) return 3;
  if (travelDelta > 45) return 2;
  if (travelDelta > 20) return 1;
  return 0;
}

function committedLevel(raw, now) {
  if (raw > nav._committedLevel) {
    if (raw !== nav._pendingLevel) {
      nav._pendingLevel = raw;
      nav._pendingSince = now;
    }
    if (now - nav._pendingSince >= VERDICT_COMMIT_MS) {
      nav._committedLevel = raw;
      return raw;
    }
    return nav._committedLevel;
  }
  nav._committedLevel = raw;
  nav._pendingLevel = raw;
  nav._pendingSince = now;
  return raw;
}

/** Build the Gemini navigation block from current state. */
export function buildNavContext(now = Date.now()) {
  if (!nav.navigating) {
    return formatNavigationBlock({
      navigating: false,
      isObstacleInFront: nav.isObstacleInFront,
    });
  }

  const routeDir = bearingDescription(nav.routeBearing);
  const travelBearing = nav.hasValidGpsBearing ? nav.gpsBearing : nav.compassBearing;
  const travelDir = bearingDescription(travelBearing);
  const travelDelta = bearingDelta(travelBearing, nav.routeBearing);
  const level = committedLevel(rawLevel(travelDelta), now);
  const d = Math.trunc(travelDelta);

  const travelVerdict = {
    4: `⚠ WRONG WAY — heading ${travelDir}, nearly opposite to route (${d}° off). Turn around.`,
    3: `⚠ HEADING AWAY — travelling ${travelDir} (${d}° off-route). Turn toward ${routeDir}.`,
    2: `OFF ROUTE — heading ${travelDir} (${d}° off). Bear toward ${routeDir}.`,
    1: `SLIGHTLY OFF — heading ${travelDir} (${d}° off-route).`,
    0: `ON TRACK — heading ${travelDir}.`,
  }[level];

  const facingDelta = bearingDelta(nav.compassBearing, nav.routeBearing);
  const cameraDir = bearingDescription(nav.compassBearing);
  const f = Math.trunc(facingDelta);
  const cameraVerdict =
    facingDelta > 90 ? `MISALIGNED — camera facing ${cameraDir} (${f}° off route).`
    : facingDelta > 30 ? `SLIGHTLY OFF — camera facing ${cameraDir} (${f}° off route).`
    : `ALIGNED — camera facing ${cameraDir}.`;

  return formatNavigationBlock({
    navigating: true,
    routeBearing: nav.routeBearing,
    compassBearing: nav.compassBearing,
    gpsBearing: nav.gpsBearing,
    hasValidGpsBearing: nav.hasValidGpsBearing,
    instructionText: nav.currentInstruction,
    remainingDistanceText: `${Math.trunc(nav.remainingDistanceM)} meters`,
    routeSummary: nav.routeSummary,
    isObstacleInFront: nav.isObstacleInFront,
    travelVerdict,
    cameraVerdict,
  });
}

export function currentTravelLevel() {
  return nav._committedLevel;
}

// --------------------------------------------------------------------------
// Geometry helpers shared by maps + app. Haversine and initial bearing.

const R_EARTH = 6371000;
const toRad = (d) => (d * Math.PI) / 180;
const toDeg = (r) => (r * 180) / Math.PI;

export function distanceM(a, b) {
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a.lat)) * Math.cos(toRad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R_EARTH * Math.asin(Math.sqrt(s));
}

export function bearingDeg(a, b) {
  const φ1 = toRad(a.lat), φ2 = toRad(b.lat);
  const Δλ = toRad(b.lng - a.lng);
  const y = Math.sin(Δλ) * Math.cos(φ2);
  const x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ);
  return (toDeg(Math.atan2(y, x)) + 360) % 360;
}
