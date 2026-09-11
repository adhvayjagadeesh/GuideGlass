// Copy this file to config.js (gitignored) and fill in your keys.
//
// Same keys as the Android client's local.properties. Both keys end up in
// the browser, so restrict them in Google Cloud Console:
//   GOOGLE_MAPS_KEY  -> "HTTP referrers", allow http://localhost:*/* and your host.
//                       Enable: Maps JavaScript API, Places API, Directions API.
//   GEMINI_API_KEY   -> from https://aistudio.google.com/app/apikey
//                       Restrict to the Generative Language API.
//
// For a research prototype run on your own machine this is fine. Do not ship
// a public deployment with keys in a static file - put a proxy in front.

window.GUIDEGLASS_CONFIG = {
  GOOGLE_MAPS_KEY: "your_google_maps_key",
  GEMINI_API_KEY: "your_gemini_api_key",
  // Model name used by the Android client. Override if that preview is retired.
  GEMINI_MODEL: "gemini-3-flash-preview",
};
