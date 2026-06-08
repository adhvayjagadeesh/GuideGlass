# GuideGlass — Project Status Report

**Application:** GuideGlass (`com.impairedvision.guideglass`) — vision-assistance Android app for visually impaired users
**Last updated:** May 28, 2026
**Build status:** `compileDebugKotlin` green in Android Studio. CLI Gradle currently blocked by a Windows `R.jar` file lock (environmental, not code).
**Maturity:** Pre-field-testing. Core pipelines implemented; several recent safety-critical fixes are **not yet validated on-device**.

> This document is a living snapshot of where the app stands: what works, what's broken, what's untested, what's risky, and the forward roadmap. For architecture internals see [`guideglass_technical_deep_dive.md`](../guideglass_technical_deep_dive.md). For the on-device AI migration see the Cactus docs linked in the [Roadmap](#roadmap-viewer).

---

## 1. Executive Summary

GuideGlass fuses three subsystems behind a simple three-button launcher:

1. **Tier 1 — ML Kit obstacle reflex** (on-device, sub-100ms target): immediate "STOP" + haptic when something blocks the walking corridor.
2. **Tier 2 — Gemini scene/navigation guidance** (cloud, ~1.5–3s): directional, under-12-word spoken guidance using a structured navigation context.
3. **Google Maps navigation**: walking routes, Places search, voice destinations, compass/GPS bearing, turn-by-turn.

All output is spoken through a shared `SpeechHelper` (TTS). The two vision tiers are coordinated through `NavStateManager.isObstacleInFront`.

**Recent trajectory:** The last two work sessions resolved a Combined-Mode startup hang, refactored the reflex→Gemini handoff, migrated secrets out of source, fixed the Gemini model name, repaired a TTS failure, and overhauled reflex reliability. The dominant **open risk** is that Gemini (Tier 2) is **slow and unreliable**, and the newest reflex changes are **not yet field-tested**.

---

## 2. Current Architecture Snapshot

| Layer | Key files | State |
|-------|-----------|-------|
| Launcher (Compose) | `MainActivity.kt` | Stable |
| Vision + Combined hub | `vision/VisionActivity.kt` (~1.3k lines) | Functional, oversized ("God Activity") |
| Maps-only nav | `maps/MapsActivity.kt` | Stable |
| MVVM nav path | `viewmodel/NavigationViewModel.kt` | Partially wired (parallel to Activity path) |
| Reflex (Tier 1) | `vision/ObstacleDetector.kt` | Recently rewritten — **untested** |
| Cloud AI (Tier 2) | `ai/GeminiManager.kt`, `ai/NavContextBuilder.kt` | Functional; model preview-tier |
| Repositories | `data/{Vision,Location,Sensor,Route,Places}Repository.kt` | Stable |
| Voice | `voice/VoiceCommandManager.kt` | Functional |
| Speech (TTS) | `tts/SpeechHelper.kt` | Recently hardened — **untested on-device** |
| Shared nav state | `maps/NavStateManager.kt` | Stable |

**Threading model:** Main (UI/Map/TTS) · `cameraExecutor` (frame→bitmap) · `reflexExecutor` MAX_PRIORITY (`processFrame`) · ML Kit internal pool (async detection) · `Dispatchers.IO` (Directions, Geocoder, Gemini).

---

## 3. Successes / Completed Work

### 3.1 Combined Mode startup hang — FIXED
- **Root cause:** camera bound before the combined `PreviewView` had layout dimensions; `Places.initialize()` ran synchronously on the main thread in `onCreate`.
- **Fix:** `scheduleCameraStart()` posts camera start after layout; `Places.initialize()` moved to `Dispatchers.Default`; removed a debug `FileWriter` on the hot frame path that could block the reflex thread.

### 3.2 Two-tier reflex/Gemini semantics — REFACTORED
- Reflex is **edge-triggered** (fires once on enter, clears after consecutive empty frames) rather than level-triggered STOP spam.
- `NavContextBuilder` provides a single structured nav payload (obstacle flag, compass facing, travel direction, route summary, travel/camera verdicts, next step) to Gemini.
- Gemini runs gated by `geminiInFlight` (Activity) / `isAnalyzing` (ViewModel) so frames can't interrupt an active reasoning window.

### 3.3 Code-audit follow-ups — IMPLEMENTED
- Collapsed duplicate `isProcessing` + `geminiInFlight` into a single authoritative gate.
- Documented `ObstacleDetector` tuning (`ReflexTuning` + runtime `applyTuning()`).
- **Secrets migration:** API keys moved from hardcoded source to `local.properties` → `BuildConfig` / `resValue`; `local.properties.example` added; README updated.
- Deprecated APIs modernized: `VibrationHelper` (`VibratorManager` on API 31+), `GeocoderHelper` (`GeocodeListener` on API 33+).

### 3.4 Gemini model name — FIXED
- `DEFAULT_MODEL_NAME` was `gemini-1.5-flash` (treated as nonexistent in this project's context) → now `gemini-3-flash-preview`. Single source of truth in `GeminiManager`; both call sites use it.

### 3.5 TTS "no voice / error -1" — FIXED (untested on-device)
- **Cause 1:** `VisionActivity` spoke **every** streaming chunk; since Gemini emits cumulative text, TTS got rapid `QUEUE_FLUSH` calls → engine error `-1`. Now accumulates and speaks once at stream end (matching `NavigationViewModel`).
- **Cause 2:** `SpeechHelper` set `isSpeaking = true` even when `speak()` returned an error, stalling the queue. Now checks the return code, recovers, logs `onError(code)`, queues speech until `onInit` succeeds, uses `applicationContext`, and `@Volatile`s its flags.

### 3.6 Reflex reliability overhaul — IMPLEMENTED (untested on-device)
- **Proximity by apparent size + looming**, not bottom-edge alone: near = large box (`heightFraction ≥ 0.34` or `areaFraction ≥ 0.12`) OR looming via STREAM_MODE `trackingId` size-growth / time-to-contact.
- **`enableMultipleObjects()`** so a close obstacle the tracker didn't lock onto is still evaluated.
- **Corridor center test** (center-in-corridor or ≥45% overlap) + background-box rejection (>90% width) to kill far-left/right and full-frame false positives.
- **STOP anti-spam:** `STOP_COOLDOWN_MS = 3500` + re-arm gate in `VisionActivity` → no more "STOP STOP STOP".
- **Local, network-free veer hint** ("Stop. Obstacle ahead. Veer left/right.") computed conservatively from box geometry, so guidance doesn't depend on slow Gemini.
- **Gemini suppression narrowed:** only blocks duplicate STOP and "path clear/walk forward" while an obstacle is active; directional guidance passes through. System prompt forbids "walk forward" when `isObstacleInFront=true`.

---

## 4. Known Problems / Open Issues

| # | Issue | Severity | Notes |
|---|-------|----------|-------|
| P1 | **Gemini is slow & unreliable** | High | 1.5–3s latency; preview-tier model. The app cannot lean on it for time-critical guidance — motivation for the local veer hint and the Cactus roadmap. |
| P2 | **`gemini-3-flash-preview` availability unverified** | High | If the model name doesn't resolve for the API key, every Gemini call silently fails and all Tier-2 guidance disappears. Must confirm in Google AI Studio. |
| P3 | **Dual `ObstacleDetector` ownership** | Medium | Both `VisionActivity` and `VisionRepository` instantiate a detector. Only the Activity path is active today; if both run, `isObstacleInFront` can desync and alerts duplicate. |
| P4 | **`VisionActivity` is a ~1.3k-line "God Activity"** | Medium | Owns camera, map fragment, sensors, voice, and both vision tiers. Hard to maintain/test; flagged for extraction to ViewModel/repository. |
| P5 | **Two parallel Gemini call sites** | Medium | `VisionActivity.analyzeWithGemini` vs `NavigationViewModel.triggerGeminiAnalysis` duplicate prompt/stream logic; risk of divergent behavior. |
| P6 | **Unused heavy native deps** | Low/Medium | MediaPipe `tasks-vision` and TensorFlow Lite remain in `build.gradle.kts` though reflex is ML-Kit-only → APK bloat, multi-ABI packaging, slower installs. |
| P7 | **CLI Gradle `R.jar` file lock** | Low (env) | `processDebugResources` fails to delete `R.jar` when Android Studio holds the build dir. Build in Studio or `./gradlew --stop` first. |
| P8 | **SDK XML version warning** | Low | "SDK XML version 4 vs up to 3" — Android Studio / cmdline-tools version skew; cosmetic. |

---

## 5. Untested / Unvalidated Features

These are implemented but **have not been verified on a device or in the field**. Treat as provisional until a walk test.

- **Reflex reliability rewrite (§3.6)** — size/looming thresholds, `trackingId` history, cooldown/re-arm, and the veer hint are all **unverified on real sidewalks**. Thresholds are first-pass estimates.
- **Local veer-hint accuracy** — the "which side is clear" heuristic is conservative but could still point toward a hazard; needs safety validation.
- **TTS fix (§3.5)** — verified by compile + logic review only; not yet confirmed audibly on-device.
- **Combined Mode after recent changes** — startup hang fix predates the reflex/TTS edits; re-confirm camera + map appear and voice works together.
- **Gemini directional guidance during an active obstacle** — the relaxed suppression path hasn't been observed end-to-end on-device.
- **Model `gemini-3-flash-preview`** — live request/response not confirmed.
- **MVVM (`NavigationViewModel`) path** — parallel to the Activity path; unclear which is exercised in shipped flows.

---

## 6. Possible Issues / Risks to Watch

- **Reflex false negatives from raised size gate:** `NEAR_HEIGHT_RATIO = 0.34` may miss genuinely close low/thin obstacles (curbs, poles seen edge-on). Mitigation: looming path + lower the ratio after field data.
- **Reflex false positives persist:** if street objects (bins, foliage, people) loom large in-corridor, expect occasional STOPs. One-knob-at-a-time tuning recommended.
- **Safety of spoken hints:** any wrong "veer left/right" is a real-world hazard for a blind user. Keep hints conservative; prefer silence when ambiguous.
- **Battery/thermal:** continuous CameraX + ML Kit + periodic Gemini uploads are power-hungry; not yet profiled.
- **Network dependence:** with Gemini down/slow and reflex being the only on-device intelligence, guidance degrades to "STOP + veer hint" only.
- **TTS engine variance:** behavior of queue/flush and error `-1` differs across OEM TTS engines; validate on target hardware.
- **Bitmap rotation assumption:** frames are pre-rotated in `imageProxyToBitmap` and passed to ML Kit with rotation `0`; re-verify if boxes look skewed on some devices.

---

## 7. Roadmap Viewer

### 7.1 Near-term (correctness & validation)
- [ ] **Field-test the reflex overhaul** (§3.6) and tune `NEAR_HEIGHT_RATIO`, looming thresholds, `STOP_COOLDOWN_MS`, corridor width.
- [ ] **Verify `gemini-3-flash-preview`** resolves for the API key; add explicit error surfacing when a Gemini call fails.
- [ ] **On-device TTS confirmation** across vision, navigation, and combined modes.
- [ ] **Re-validate Combined Mode** startup + concurrent voice after recent edits.

### 7.2 Mid-term (architecture hygiene)
- [ ] **Consolidate to one `ObstacleDetector`** (resolve P3) when MVVM wiring lands.
- [ ] **Extract `VisionActivity`** responsibilities into ViewModel/repositories (P4).
- [ ] **Unify the two Gemini call sites** behind one prompt/stream helper (P5).
- [ ] **Dependency cleanup** — remove MediaPipe/TFLite if profiling confirms no use (P6).

### 7.3 Long-term — Cactus on-device AI migration (DEFERRED, planned)
A phased plan to add [Cactus Compute](https://www.cactuscompute.com/) as a hybrid on-device/cloud layer for Tier-2 guidance (and optionally speech), keeping ML Kit reflex and Gemini fallback intact.

- **Goal:** route non-urgent scene guidance through on-device Cactus when latency/quality wins; fall back to Gemini otherwise. ML Kit reflex stays the only STOP path.
- **Status:** Planned / not started. All phases `pending`.

| Phase | Description |
|-------|-------------|
| 0 | Due diligence, go/no-go, implementation log |
| 1 | Add Cactus SDK, secrets, init, arm64 spike + cold-start benchmark |
| 2 | Shadow-mode logging — Cactus vs Gemini without changing TTS |
| 3 | `GuidanceRouter` abstraction; refactor VisionActivity/ViewModel seams |
| 4 | Enable hybrid Tier 2 with Gemini fallback + field tests |
| 5 | (Optional) Cactus STT for voice destination commands |
| 6 | Feature flags, README, dependency cleanup, thermal profiling |
| 7 | Staged production rollout and monitoring |

**Reference docs:**
- Roadmap plan: [`.cursor/plans/cactus_integration_roadmap_5f258d85.plan.md`](../.cursor/plans/cactus_integration_roadmap_5f258d85.plan.md)
- Evaluation: [`docs/CACTUS_EVALUATION.md`](CACTUS_EVALUATION.md)
- Team lesson plan: [`docs/CACTUS_GROUP_LESSON_PLAN.md`](CACTUS_GROUP_LESSON_PLAN.md)

---

## 8. Suggested Device Test Plan

1. **Combined Mode launch** — map + camera preview appear within a few seconds; no indefinite hang; welcome voice plays.
2. **Reflex (single):** walk toward a pole/wall → exactly one "Stop. Obstacle ahead." + haptic; hold position → no repeat STOPs.
3. **Reflex (re-arm):** step away (clears) → approach again after a moment → a second STOP fires.
4. **Reflex false-positive check:** stand on open sidewalk, pan across distant objects → no STOP; phone pitched down → no ground-plane STOP.
5. **Veer hint:** obstacle clearly to one side with the other open → correct "Veer left/right"; ambiguous clutter → STOP with no direction.
6. **Gemini guidance:** while an obstacle is present, hear directional guidance (not "walk forward"); after clearing, normal guidance resumes; one spoken phrase per response (no chunk spam).
7. **Navigation:** start a route by voice and by search; confirm turn-by-turn speaks; verify Gemini context includes compass, travel direction, step text, route summary.
8. **TTS resilience:** trigger many guidance updates rapidly → no stuck silence; check Logcat `SpeechHelper` for `speak() failed` / `onError` recovery.

**Logcat tags to watch:** `ObstacleDetector` (enter/clear, hint, height), `VisionActivity` (Gemini responses, suppression), `SpeechHelper` (init, errors).

---

## 9. Change Log (recent sessions)

- **Session A (May 17):** Resolved `VisionCoordinator` unresolved reference; fixed Combined Mode init hang; refactored edge-triggered reflex + `NavContextBuilder`; added `geminiInFlight`/`isAnalyzing` gating; implemented all code-audit medium/style follow-ups (flag collapse, tuning docs, secrets→`local.properties`/`BuildConfig`, deprecated-API modernization); fixed `BuildConfig` unresolved reference (use `getString(R.string.gemini_api_key)`); produced Cactus evaluation, roadmap plan, and group lesson plan.
- **Session B (May 28):** Fixed Gemini model name (`gemini-1.5-flash` → `gemini-3-flash-preview`); fixed TTS failure (cumulative-chunk flooding + `speak()` error recovery + init-race queue); overhauled reflex reliability (size + looming/TTC proximity, multiple-objects, corridor center test, STOP cooldown/re-arm, local veer hint, narrowed Gemini suppression).

---

## 10. Quick Facts

- **Min SDK:** 26 (Android 8.0) · **Target SDK:** 34 · **JVM:** 17 · **Compose** + Material 3
- **Secrets:** `local.properties` → `GEMINI_API_KEY`, `GOOGLE_MAPS_KEY`, `GOOGLE_DIRECTIONS_KEY` (never commit real keys)
- **Permissions:** INTERNET, ACCESS_FINE/COARSE_LOCATION, CAMERA, RECORD_AUDIO, VIBRATE
- **Specialist agents:** `gemini-pipeline`, `mlkit-reflex`, `google-maps-navigation`, `android-performance`, `codebase-teacher`, `code-auditor` (see `.cursor/agents/`)
