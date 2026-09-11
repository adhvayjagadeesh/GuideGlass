// Port of ai/NavContextBuilder.kt.
//
// The structured navigation block handed to Gemini alongside each camera
// frame. Field names and wording are load-bearing: the system prompt tells
// the model to act on "TRAVEL STATUS", "CAMERA STATUS", "NEXT STEP" etc. by
// name, and the verdict strings ("WRONG WAY", "ON TRACK", ...) are matched by
// substring in the prompt's rules. Keep this byte-compatible with the Android
// version so the two clients get the same behaviour from the same prompt.

export function bearingDescription(bearing) {
  const b = ((bearing % 360) + 360) % 360;
  if (b < 22.5 || b >= 337.5) return "North";
  if (b < 67.5) return "Northeast";
  if (b < 112.5) return "East";
  if (b < 157.5) return "Southeast";
  if (b < 202.5) return "South";
  if (b < 247.5) return "Southwest";
  if (b < 292.5) return "West";
  return "Northwest";
}

/** Smallest angle between two bearings, 0..180. */
export function bearingDelta(a, b) {
  const diff = Math.abs(a - b) % 360;
  return diff > 180 ? 360 - diff : diff;
}

export function formatNavigationBlock({
  navigating,
  routeBearing,
  compassBearing,
  gpsBearing,
  hasValidGpsBearing,
  instructionText,
  remainingDistanceText,
  routeSummary,
  isObstacleInFront,
  travelVerdict,
  cameraVerdict,
}) {
  if (!navigating) {
    return `NAVIGATION: inactive.\nisObstacleInFront=${isObstacleInFront}\n`;
  }

  const routeDir = bearingDescription(routeBearing);
  const compassDir = bearingDescription(compassBearing);
  const travelBearing = hasValidGpsBearing ? gpsBearing : compassBearing;
  const travelSource = hasValidGpsBearing ? "GPS" : "compass";
  const travelDir = bearingDescription(travelBearing);

  return [
    `isObstacleInFront=${isObstacleInFront}`,
    `COMPASS FACING: ${compassDir} (${Math.trunc(compassBearing)}°).`,
    `DIRECTION OF TRAVEL (${travelSource}): ${travelDir} (${Math.trunc(travelBearing)}°).`,
    `ROUTE: Go ${routeDir} (${Math.trunc(routeBearing)}°).`,
    `ROUTE SUMMARY: ${routeSummary}`,
    `TRAVEL STATUS: ${travelVerdict}`,
    `CAMERA STATUS: ${cameraVerdict}`,
    `NEXT STEP: "${instructionText}" in ${remainingDistanceText}.`,
    "",
  ].join("\n");
}
