# GuideGlass — web client

A browser port of the Android app. Same three tiers, same thresholds, same
Gemini prompt, so behaviour matches across the two clients:

| Tier | Android | Web |
|---|---|---|
| Reflex (STOP, on-device) | ML Kit Object Detection | TensorFlow.js COCO-SSD, WebGL |
| Guidance (cloud, ~1.5–3 s) | Gemini SDK | Gemini REST, streaming |
| Navigation | Maps SDK + Directions REST | Maps JavaScript API |
| Speech | Android TTS | Web Speech API |
| Compass | SensorManager | DeviceOrientation (phone) / slider (desktop) |

Runs on a laptop with the webcam standing in for the phone camera, and on a
phone in Chrome or Safari with the real camera, GPS and compass.

## Run

```bash
cp web/config.example.js web/config.js     # then fill in the two keys
python3 -m http.server 8765 --directory web
```

Open `http://localhost:8765`. Camera and geolocation need `localhost` or
HTTPS; a plain `file://` open will not get either.

**Keys** — `GOOGLE_MAPS_KEY` needs Maps JavaScript API, Places API and
Directions API enabled, with an HTTP-referrer restriction that includes
`http://localhost:*/*`. `GEMINI_API_KEY` from AI Studio. Both keys are
visible in the browser; restrict them and do not deploy this file publicly.

A bad Maps key does **not** fail the script load — Google rejects it a beat
later through a separate callback. The app hooks that and says so aloud;
without the hook it announced "navigation ready" over a blank map.

## Modes

**Vision** — camera → reflex → Gemini → speech. Needs no Maps key.
**Navigation** — map, search, walking route, turn-by-turn. Needs no camera.
**Combined** — both, with the navigation block injected into every Gemini
prompt. This is the product; the other two exist to test each tier alone.

## Desktop testing

A laptop has no compass and cannot walk. Open **Desktop testing** on the map
panel and tick **Simulate**: clicking the map moves you (GPS bearing is
derived from the last two clicks), and the slider sets compass heading. That
is enough to drive the whole nav state machine — route, step advance at 20 m,
the WRONG WAY / HEADING AWAY verdicts with their 2 s commit hysteresis — from
a desk.

## Commands

Type in the command box or press 🎤 (Chrome/Edge). Grammar is the Android one:

- `navigate to X`, `take me to X`, `directions to X`
- `nearest coffee`, `find me a pharmacy`, `where is the bathroom`
- `stop`, `cancel`, `never mind`
- anything else is a free-form goal for Gemini: `the door`, `the crosswalk`

## What matches Android exactly

Reflex tuning (`reflex.js`): corridor 32–68 %, near at 34 % height or 12 %
area, looming at 0.6/s growth or ≤ 2 s time-to-contact, 2 hits to enter and
4 misses to clear. Verdict thresholds (`navState.js`): > 120° wrong way,
> 90° heading away, > 45° off route, > 20° slightly off, with a worse verdict
needing 2 s before it shows. Timing (`app.js`): Gemini every 1.5 s moving /
3 s idle, STOP cooldown 3.5 s, re-arm 1.2 s, wrong-direction alert every 10 s,
step advance under 20 m. The Gemini system instruction is byte-identical.

The one thing the platform forced: COCO-SSD has no tracking IDs, so
`reflex.js` carries a small IoU tracker to feed the looming heuristic.

## Measured here

COCO-SSD lite_mobilenet_v2 on WebGL: ~15 s first load (cached after),
~117 ms per frame on a laptop. Close to the sub-100 ms reflex target; on a
phone expect the model to be the bottleneck and lower the detection interval
in `app.js` if it falls behind — frames arriving during inference are
dropped, not queued.
