# Cactus Compute × GuideGlass — Technical Evaluation

**Date:** May 2026  
**Product reviewed:** [Cactus — On-device AI with cloud fallback](https://www.cactuscompute.com/)  
**App context:** GuideGlass (`README.md`, `guideglass_technical_deep_dive.md`)

---

## 1. Executive summary

**Cactus is not a drop-in replacement for your current models.** GuideGlass today depends on **Google’s stack end-to-end for “intelligence”**:

| Layer | What GuideGlass uses today | Cactus overlap |
|-------|---------------------------|----------------|
| Urgent reflex (Tier 1) | **ML Kit** object detection + heuristics | Possible *alternative* on-device vision path, not the same API |
| Scene / nav reasoning (Tier 2) | **Gemini** multimodal (JPEG + structured nav block) | Hybrid *could* reduce cloud calls; on-device model ≠ Gemini 1.5 Flash |
| Maps / routing / places | **Google Maps, Directions, Places** | **No** — Cactus does not replace these |
| Speech output | Android **TTS** | Cactus focuses on **STT / LLM inference**, not TTS |

**Verdict:** Cactus is **worth a structured pilot**, mainly for **hybrid routing** (cheaper/faster/offline paths) and possibly **on-device speech** for voice commands—not as a single swap for Gemini + ML Kit without significant architecture work.

---

## 2. What Cactus is

From [cactuscompute.com](https://www.cactuscompute.com/) and their positioning:

### 2.1 Core product

- **On-device inference runtime** (“Cactus Engine”) with optional **cloud fallback** (“Cactus Hybrid Cloud”).
- **Automatic routing** between device and cloud based on signals such as:
  - Audio quality (clear → on-device, noisy → cloud) for transcription.
  - Task **complexity** for agent / tool-calling style commands.
- Claimed properties:
  - **&lt;120 ms** on-device latency (domain-dependent).
  - **~5× cost savings** when most traffic stays on-device.
  - Quantization, hardware-specific acceleration, zero-copy model mapping for RAM/battery.

### 2.2 Surfaces they advertise

- **SDKs:** Python, React Native, **Swift, Kotlin**, Flutter, C++.
- **Use cases:** mobile voice assistants, desktop notetakers, wearables, “vision” listed at a high level.
- **CLI / open engine:** GitHub-oriented workflow (`cactus build`, `cactus run`) for running models like small LLMs on device.

### 2.3 What it is *not*

- Not Google Gemini, ML Kit, or Maps.
- Not a navigation SDK.
- Not a turnkey “blind pedestrian assistant”—it is **infrastructure** for running/routing models.

---

## 3. GuideGlass architecture (baseline)

Your app is a **two-tier safety + guidance** system:

```
CameraX (KEEP_ONLY_LATEST)
    → reflexExecutor → ObstacleDetector (ML Kit) → edge-triggered STOP + haptic
    → geminiInFlight gate → GeminiManager (cloud) → TTS guidance
```

Parallel concerns:

- **Navigation:** Directions API, Places, compass/GPS, `NavStateManager`, step instructions.
- **Concurrency:** `cameraExecutor`, `reflexExecutor`, ML Kit internal pool, `Dispatchers.IO` for network.
- **History:** On-device **MiDaS depth** was removed (~200 ms/frame, thermal issues, weak depth proxy). Reflex moved to **ML Kit bounding-box heuristics**.

Dependencies today (`app/build.gradle.kts`):

- `com.google.mlkit:object-detection`
- `com.google.ai.client.generativeai:generativeai`
- CameraX, Play Services Maps/Location/Places
- MediaPipe / TFLite present but **not** on the hot path for reflex

---

## 4. Compatibility with GuideGlass

### 4.1 Platform & project fit

| Requirement | GuideGlass | Cactus (public info) | Fit |
|-------------|------------|----------------------|-----|
| Android | minSdk **26**, target 34 | Kotlin SDK advertised | **Likely yes** — confirm min SDK + ABI in Cactus Android docs |
| Camera pipeline | CameraX `ImageAnalysis` | Vision mentioned, details in docs | **Verify** frame format, rotation, max resolution |
| Kotlin / coroutines | Primary language | Kotlin sample on site | **Good** |
| Offline | Partial (ML Kit yes, Gemini no) | Core value proposition | **Strong fit** for Tier 2 *if* on-device VLM is good enough |
| APK size / models | Already ships native libs (MediaPipe, TFLite) | Quantized models add MB–GB | **Risk** — model download strategy needed |

**Action before any commit:** Request or read Cactus’s **Android integration guide**, model catalog, and license for commercial accessibility apps.

### 4.2 “Would it work for our models?”

Interpret “our models” as what actually runs in production:

| Current “model” | Cactus equivalent? |
|-----------------|-------------------|
| **ML Kit Object Detection** (STREAM_MODE) | Cactus may run **custom or bundled vision models**, but **not ML Kit binaries**. You would **re-implement** `ObstacleDetector` against Cactus vision APIs or keep ML Kit. |
| **Gemini 1.5 Flash** (multimodal + long system prompt) | Cactus hybrid might run a **small on-device LLM/VLM** for simple prompts and **cloud** for hard scenes—**not the same weights or behavior**. Expect **regression testing** on: traffic lights, “veer left”, `isObstacleInFront`, nav metadata obedience. |
| **Google Directions / Places** | **No Cactus replacement** |

So: **partial compatibility**. Cactus is a **runtime/router**, not your existing Google models.

### 4.3 Best-fit integration points in GuideGlass

1. **Tier 2 — Gemini hybrid routing (`GeminiManager` / `maybeLaunchGemini`)**  
   - Route “simple” frames + rich `NavContextBuilder` prompts on-device when connectivity is poor or `geminiInFlight` budget is tight.  
   - Cloud fallback for ambiguous scenes, heavy OCR, rare objects.

2. **Voice destination parsing (`VoiceCommandManager` + STT)**  
   - If you add **continuous or push-to-talk** command capture, Cactus transcription claims (&lt;120 ms, noise-aware routing) align with hands-free use.

3. **Optional replacement of dormant MediaPipe/TFLite**  
   - You already pay binary size for unused stacks; either **remove** them or **consolidate** on one runtime (Cactus vs TFLite) to avoid triple-stacking runtimes.

4. **Poor fit: Tier 1 reflex**  
   - ML Kit is already async, Play Services–distributed, and tuned for your bounding-box heuristic. Replacing it with Cactus vision is **high risk** unless benchmarks show better latency/accuracy on **your** street tests.

---

## 5. What Cactus could help with

### 5.1 Cost and scale

- Every Gemini call sends a **320×320 JPEG** plus a large text context block. Walking + combined mode can produce **many calls/minute** even with throttling.
- Hybrid routing could move **routine** guidance (“path clear”, “bear left toward North”) on-device and reserve Gemini for **hard** frames → lower **API spend**.

### 5.2 Latency and perceived responsiveness

- Cloud Gemini commonly **1.5–3 s** per invocation in your deep dive.
- On-device path targeting **&lt;150 ms** (Cactus marketing) could make **follow-up** guidance feel snappier *after* the reflex STOP—if quality holds.

### 5.3 Offline and privacy modes

- Accessibility users may be in **low connectivity** (tunnels, rural sidewalks).
- “Lock to on-device only” (Cactus privacy story) could support a **privacy mode** where camera frames never leave the phone for reasoning—**HIPAA/GDPR-friendly positioning** on their site; validate legally for your deployment.

### 5.4 Noise-aware speech (future)

- README emphasizes **voice** for navigation; today much parsing is regex + Places/geocoder.
- Cactus’s **audio quality router** is a natural match if you expand **hands-free destination entry** in Vision/Combined mode.

### 5.5 Consolidating on-device ML story

- You removed MiDaS due to **blocking + thermal** issues.
- Cactus advertises **battery-efficient, zero-copy** loading—*might* avoid repeating that failure mode, but **only with profiling on target phones**, not marketing claims alone.

---

## 6. Benefits (summary)

| Benefit | Relevance to GuideGlass |
|---------|-------------------------|
| Lower cloud bill | **High** — heavy Gemini usage |
| Sub-150 ms on-device path | **Medium–high** — after reflex, before next TTS |
| Automatic hybrid routing | **High** — matches your `geminiInFlight` / throttle design philosophically |
| Kotlin / Android SDK | **High** — matches stack |
| Offline reasoning | **Medium** — maps still need network; vision guidance partial |
| Open-source engine narrative | **Medium** — auditability for safety-critical apps |

---

## 7. Negatives, risks, and side effects

### 7.1 Technical risks

| Risk | Impact |
|------|--------|
| **Guidance quality drop** vs Gemini multimodal | Wrong turn advice is **safety-critical**; extensive field tests required |
| **Second inference stack** | ML Kit + Cactus + maybe Gemini = **RAM, APK, cold start** |
| **Behavior drift** | System prompts (`isObstacleInFront`, travel verdicts) tuned for Gemini may not transfer |
| **Integration cost** | New abstraction over `GeminiManager`, testing matrix for 3 modes × 2 tiers |
| **Vendor dependency** | Startup product; longevity, SLA, pricing vs Google |
| **Model licensing** | Confirm commercial use, redistribution, attribution |

### 7.2 Operational side effects

- **Model updates:** On-device weights versioned separately from app releases.
- **Support burden:** “Works on Pixel but not Samsung” class issues common with on-device ML.
- **Debugging:** Hybrid routing can be opaque (“why did it go cloud this frame?”).
- **Keys:** You already manage `local.properties`; Cactus adds **`CACTUS_CLOUD_KEY`**-style secrets for fallback paths.

### 7.3 Architectural conflicts with recent GuideGlass work

Recent refactors assume:

- **Edge-triggered reflex** in `ObstacleDetector` (ML Kit).
- **`NavStateManager.isObstacleInFront`** feeding **Gemini** prompts.
- **`geminiInFlight`** as single Tier-2 gate.

Cactus hybrid would need a **parallel policy layer**:

```
if (cactusPolicy.shouldRunOnDevice(context, bitmap, navBlock)) {
    cactusComplete(...)
} else {
    geminiManager.analyzeWithGeminiStream(...)
}
```

Without careful design, you **reintroduce duplicate pipelines** (the code-auditor concern about two detectors).

### 7.4 What Cactus does *not* fix

- Combined mode **startup** (CameraX + Maps + Places) — unrelated.
- **Compass / step progression / wrong-way** logic — stay in your Kotlin nav layer.
- **ML Kit false positives** on pavement tilt — unless Cactus vision model is provably better on your heuristic task.

---

## 8. Comparison matrix

| Criterion | ML Kit (current Tier 1) | Gemini (current Tier 2) | Cactus hybrid (proposed) |
|-----------|-------------------------|-------------------------|---------------------------|
| Latency | Async, sub-100 ms feel | 1.5–3 s | Target &lt;150 ms on-device path |
| Offline | Yes | No | Partial |
| Scene understanding | Boxes only | Strong multimodal | Depends on bundled VLM |
| Nav prompt compliance | N/A | Good (with tuning) | Unknown — must test |
| Cost per active user | Low (on-device GS) | API $ | Lower if routed well |
| Integration effort | Done | Done | **Large** |
| Safety reflex suitability | **Proven path** | Too slow | **Not ideal for STOP** |

---

## 9. Recommended adoption path (if you proceed)

### Phase 0 — Due diligence (1–2 days)

- [ ] Android Kotlin SDK docs, minSdk, ABI, APK size per model.
- [ ] List **on-device vision-language** models (if any) vs speech-only.
- [ ] Pricing, cloud fallback terms, data retention.
- [ ] Pilot build on **same devices** you use for ML Kit street tests.

### Phase 1 — Shadow mode (low risk)

- Log **what Cactus would have chosen** (on-device vs cloud) without changing TTS.
- Compare outputs to Gemini offline on recorded frame + `NavContextBuilder` fixtures.

### Phase 2 — Hybrid Tier 2 only

- Keep **ML Kit reflex unchanged**.
- Route only **non-urgent** guidance through Cactus→Gemini fallback.
- Preserve `geminiInFlight` semantics end-to-end.

### Phase 3 — Optional speech

- Prototype voice destination with Cactus STT in Combined mode.

**Do not** replace ML Kit reflex in Phase 1–2.

---

## 10. Direct answers to common questions

### “Should we add Cactus to GuideGlass?”

**Yes, as an evaluated optional layer—not an immediate rewrite.** It aligns with cost, offline, and hybrid goals; it does **not** align with replacing Google Maps or ML Kit reflex without proof.

### “Will our Gemini API key / ML Kit still work?”

**Yes.** Cactus sits *beside* or *above* inference choices. Maps/Places/Directions unchanged.

### “Is it worth it for a student / accessibility project?”

**High learning value** (hybrid edge AI). **Production value** depends on test time and whether Gemini bills or offline gaps hurt real users.

---

## 11. References

- [Cactus Compute — homepage](https://www.cactuscompute.com/) — hybrid cloud, latency, Kotlin, privacy claims.
- Internal: `README.md`, `guideglass_technical_deep_dive.md`, `app/src/main/java/.../GeminiManager.kt`, `ObstacleDetector.kt`.

---

## 12. Document changelog

| Version | Notes |
|---------|--------|
| 1.0 | Initial evaluation against GuideGlass README + architecture deep dive |
