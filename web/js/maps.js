// Port of maps/MapsActivity.kt + data/RouteRepository.kt + data/LocationRepository.kt
// onto the Google Maps JavaScript API.
//
// The Android client hits the Directions REST endpoint through Retrofit and
// decodes the polyline by hand. In the browser the Maps JS SDK does both, so
// this file is shorter than its Kotlin counterparts - but the step model it
// produces is the same shape, and NavStateManager consumes it identically.
//
// Desktop testing: a laptop has geolocation (coarse, network-based) but no
// compass and no way to walk. `simulate` mode lets you click the map to
// place yourself, so the whole nav state machine can be exercised at a desk.

import { nav, resetNavigation, distanceM, bearingDeg, STEP_ADVANCE_M } from "./navState.js";

const GPS_BEARING_MIN_SPEED = 0.5;   // m/s below which GPS heading is noise
const MOVING_SPEED = 0.3;

let map, directionsService, directionsRenderer, userMarker, headingLine, placesAutocomplete;
let watchId = null;
let lastFix = null;
let listeners = { onPosition: null, onRoute: null, onStep: null, onArrive: null };

export const mapsState = { ready: false, simulate: false, authFailed: false };

export function loadMapsApi(key, onAuthError) {
  if (!key) return Promise.reject(new Error("GOOGLE_MAPS_KEY missing - see config.example.js"));
  // A bad key does NOT fail the script load. The SDK loads, resolves, and
  // only then calls this global - so without hooking it the app would report
  // "navigation ready" over a map that never renders and routes that never
  // resolve. That is the worst kind of failure for a user who cannot see the
  // blank map.
  window.gm_authFailure = () => {
    mapsState.authFailed = true;
    onAuthError?.(new Error("Google Maps rejected the API key (InvalidKeyMapError). Check GOOGLE_MAPS_KEY, enabled APIs, and referrer restrictions."));
  };
  if (window.google?.maps) return Promise.resolve();
  return new Promise((resolve, reject) => {
    window.__gg_maps_cb = () => resolve();
    const s = document.createElement("script");
    s.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(key)}&libraries=places,geometry&callback=__gg_maps_cb&loading=async`;
    s.onerror = () => reject(new Error("Maps JS failed to load - check key + referrer restrictions"));
    document.head.appendChild(s);
  });
}

export function initMap(el, searchInput, cbs) {
  listeners = { ...listeners, ...cbs };
  map = new google.maps.Map(el, {
    center: { lat: 37.3382, lng: -121.8863 },   // San Jose; replaced by the first fix
    zoom: 16,
    mapTypeId: "roadmap",
    disableDefaultUI: true,
    zoomControl: true,
    gestureHandling: "greedy",
    styles: [{ featureType: "poi", elementType: "labels", stylers: [{ visibility: "off" }] }],
  });
  directionsService = new google.maps.DirectionsService();
  directionsRenderer = new google.maps.DirectionsRenderer({
    map, suppressMarkers: false, preserveViewport: false,
    polylineOptions: { strokeColor: "#2fd08c", strokeWeight: 6, strokeOpacity: 0.9 },
  });
  userMarker = new google.maps.Marker({
    map, zIndex: 999,
    icon: { path: google.maps.SymbolPath.CIRCLE, scale: 8, fillColor: "#3d8bff", fillOpacity: 1, strokeColor: "#fff", strokeWeight: 2 },
  });
  headingLine = new google.maps.Polyline({ map, strokeColor: "#3d8bff", strokeWeight: 3, strokeOpacity: 0.8 });

  placesAutocomplete = new google.maps.places.Autocomplete(searchInput, { fields: ["geometry", "name", "formatted_address"] });
  placesAutocomplete.addListener("place_changed", () => {
    const p = placesAutocomplete.getPlace();
    if (!p.geometry?.location) return;
    routeTo({ lat: p.geometry.location.lat(), lng: p.geometry.location.lng() }, p.name || p.formatted_address);
  });

  map.addListener("click", (e) => {
    if (!mapsState.simulate) return;
    setPosition({ lat: e.latLng.lat(), lng: e.latLng.lng(), accuracy: 3, speed: 1.2, heading: null }, true);
  });

  mapsState.ready = true;
  return map;
}

// -------------------------------------------------------------- location --

export function startLocation() {
  if (!navigator.geolocation) return false;
  if (watchId != null) return true;
  watchId = navigator.geolocation.watchPosition(
    (pos) => {
      if (mapsState.simulate) return;   // clicked position wins on desktop
      const c = pos.coords;
      setPosition({ lat: c.latitude, lng: c.longitude, accuracy: c.accuracy, speed: c.speed, heading: c.heading }, false);
    },
    (err) => console.warn("geolocation:", err.message),
    { enableHighAccuracy: true, maximumAge: 1000, timeout: 15000 },
  );
  return true;
}

export function stopLocation() {
  if (watchId != null) navigator.geolocation.clearWatch(watchId);
  watchId = null;
}

/** Feed one fix into nav state. `simulated` fixes derive bearing from the last fix. */
export function setPosition(fix, simulated) {
  const prev = nav.position;
  nav.position = { lat: fix.lat, lng: fix.lng, accuracy: fix.accuracy ?? 10 };

  // GPS bearing: only trust it when moving; on a click-simulated fix, derive it.
  if (simulated && prev) {
    const d = distanceM(prev, fix);
    if (d > 0.5) { nav.gpsBearing = bearingDeg(prev, fix); nav.hasValidGpsBearing = true; }
    nav.isMoving = true;
  } else if (fix.heading != null && !Number.isNaN(fix.heading) && (fix.speed ?? 0) >= GPS_BEARING_MIN_SPEED) {
    nav.gpsBearing = fix.heading; nav.hasValidGpsBearing = true;
    nav.isMoving = true;
  } else {
    nav.hasValidGpsBearing = false;
    nav.isMoving = (fix.speed ?? 0) >= MOVING_SPEED;
  }
  lastFix = fix;

  if (mapsState.ready) {
    userMarker.setPosition(nav.position);
    if (!prev) map.panTo(nav.position);
    drawHeading();
  }
  if (nav.navigating) advanceSteps();
  listeners.onPosition?.(nav.position);
}

export function drawHeading() {
  if (!mapsState.ready || !nav.position) return;
  const b = nav.hasValidGpsBearing ? nav.gpsBearing : nav.compassBearing;
  const end = google.maps.geometry.spherical.computeOffset(new google.maps.LatLng(nav.position), 25, b);
  headingLine.setPath([nav.position, end]);
}

export function panToUser() { if (mapsState.ready && nav.position) map.panTo(nav.position); }

// ---------------------------------------------------------------- routing --

function stripHtml(s) { return s.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim(); }

export async function routeTo(dest, name = "") {
  if (mapsState.authFailed) throw new Error("Maps API key was rejected - fix GOOGLE_MAPS_KEY before routing.");
  if (!nav.position) throw new Error("No current position yet - allow location, or enable Simulate and click the map.");
  const req = {
    origin: nav.position, destination: dest,
    travelMode: google.maps.TravelMode.WALKING,
  };
  const res = await directionsService.route(req);
  const route = res.routes?.[0];
  const leg = route?.legs?.[0];
  if (!leg) throw new Error("No walking route found.");

  directionsRenderer.setDirections(res);
  const steps = leg.steps.map((s) => ({
    instruction: stripHtml(s.instructions || ""),
    distanceText: s.distance?.text ?? "",
    durationText: s.duration?.text ?? "",
    start: { lat: s.start_location.lat(), lng: s.start_location.lng() },
    end: { lat: s.end_location.lat(), lng: s.end_location.lng() },
    path: (s.path || []).map((p) => ({ lat: p.lat(), lng: p.lng() })),
  }));

  resetNavigation();
  nav.navigating = true;
  nav.steps = steps;
  nav.destinationName = name || leg.end_address || "destination";
  nav.routeSummary = `${leg.distance?.text} • ${leg.duration?.text}`;
  nav.currentStepIndex = 0;
  applyStep(0);
  listeners.onRoute?.({ summary: nav.routeSummary, steps, dest: nav.destinationName });
  return nav.routeSummary;
}

function applyStep(i) {
  const s = nav.steps[i];
  if (!s) return;
  nav.currentStepIndex = i;
  nav.currentInstruction = s.instruction || (i === nav.steps.length - 1 ? "Continue to destination" : "Continue");
  nav.routeBearing = bearingDeg(s.start, s.end);
  nav.remainingDistanceM = nav.position ? distanceM(nav.position, s.end) : distanceM(s.start, s.end);
  listeners.onStep?.(i, s);
}

/** NavigationViewModel: when within 20 m of the step end, advance. */
function advanceSteps() {
  const s = nav.steps[nav.currentStepIndex];
  if (!s || !nav.position) return;
  nav.remainingDistanceM = distanceM(nav.position, s.end);
  // route bearing tracks the user: point from HERE to the step end, not from step start
  nav.routeBearing = bearingDeg(nav.position, s.end);
  if (nav.remainingDistanceM < STEP_ADVANCE_M) {
    if (nav.currentStepIndex + 1 < nav.steps.length) applyStep(nav.currentStepIndex + 1);
    else { const dest = nav.destinationName; resetNavigation(); directionsRenderer.setDirections({ routes: [] }); listeners.onArrive?.(dest); }
  }
}

export function stopNavigation() {
  resetNavigation();
  if (mapsState.ready) directionsRenderer.setDirections({ routes: [] });
}

/** Text search fallback for voice commands ("navigate to X"). */
export function findPlace(query) {
  return new Promise((resolve, reject) => {
    const svc = new google.maps.places.PlacesService(map);
    const req = { query, fields: ["name", "geometry", "formatted_address"] };
    if (nav.position) req.locationBias = { center: nav.position, radius: 3000 };
    svc.findPlaceFromQuery(req, (results, status) => {
      if (status !== google.maps.places.PlacesServiceStatus.OK || !results?.[0]?.geometry) return reject(new Error(`No place found for "${query}"`));
      const r = results[0];
      resolve({ name: r.name, lat: r.geometry.location.lat(), lng: r.geometry.location.lng() });
    });
  });
}

/** "nearest coffee" style query: nearby search ranked by distance. */
export function findNearest(query) {
  return new Promise((resolve, reject) => {
    if (!nav.position) return reject(new Error("No current position."));
    const svc = new google.maps.places.PlacesService(map);
    svc.nearbySearch({ location: nav.position, rankBy: google.maps.places.RankBy.DISTANCE, keyword: query }, (results, status) => {
      if (status !== google.maps.places.PlacesServiceStatus.OK || !results?.[0]?.geometry) return reject(new Error(`No "${query}" nearby`));
      const r = results[0];
      resolve({ name: r.name, lat: r.geometry.location.lat(), lng: r.geometry.location.lng() });
    });
  });
}
