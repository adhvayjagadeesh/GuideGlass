// Port of data/SensorRepository.kt - the compass.
//
// On a phone, DeviceOrientationEvent gives a magnetic heading (iOS exposes
// webkitCompassHeading directly; Android needs `absolute` events and a
// conversion from alpha). On a laptop there is no compass at all. Rather than
// leave nav verdicts undefined on desktop, a slider lets you set the heading
// by hand - which is also the only sane way to test the WRONG WAY /
// HEADING AWAY hysteresis without walking in circles outside.

import { nav } from "./navState.js";

export const sensors = { source: "none", available: false, simulated: false };
let onChange = null;

function setBearing(deg) {
  nav.compassBearing = ((deg % 360) + 360) % 360;
  onChange?.(nav.compassBearing);
}

function handleOrientation(e) {
  if (sensors.simulated) return;
  let heading = null;
  if (typeof e.webkitCompassHeading === "number") {          // iOS Safari
    heading = e.webkitCompassHeading; sensors.source = "iOS compass";
  } else if (e.absolute && typeof e.alpha === "number") {    // Android Chrome
    heading = (360 - e.alpha) % 360; sensors.source = "device orientation";
  }
  if (heading == null) return;
  sensors.available = true;
  setBearing(heading);
}

/** Must be called from a user gesture (iOS requires a permission prompt). */
export async function startCompass(cb) {
  onChange = cb;
  const DOE = window.DeviceOrientationEvent;
  if (!DOE) { sensors.source = "unavailable"; return false; }
  try {
    if (typeof DOE.requestPermission === "function") {
      const r = await DOE.requestPermission();
      if (r !== "granted") { sensors.source = "denied"; return false; }
    }
  } catch { sensors.source = "denied"; return false; }
  window.addEventListener("deviceorientationabsolute", handleOrientation, true);
  window.addEventListener("deviceorientation", handleOrientation, true);
  // If nothing arrives in 2 s we are on a machine with no compass.
  setTimeout(() => { if (!sensors.available && !sensors.simulated) sensors.source = "no compass on this device"; onChange?.(nav.compassBearing); }, 2000);
  return true;
}

export function setSimulatedHeading(deg) {
  sensors.simulated = true;
  sensors.source = "simulated";
  setBearing(deg);
}

export function clearSimulatedHeading() { sensors.simulated = false; }

/** navigator.vibrate is a no-op on desktop and iOS; that is fine. */
export function haptic(pattern = [80]) { try { navigator.vibrate?.(pattern); } catch { /* ignore */ } }
