# GuideGlass: Technical Architecture Deep Dive
**A Senior Engineer's Guide to the Codebase**

This document provides a highly technical, nitty-gritty breakdown of the GuideGlass application. Use this guide to explain the architecture, concurrency model, and data pipelines to other senior engineers.

---

## 1. Architectural Pattern
GuideGlass follows a modernized **MVVM (Model-View-ViewModel)** architecture built heavily on **Kotlin Coroutines and StateFlows**. 
*   **The View (`VisionActivity.kt`)**: A single "God Activity" that manages the Android camera lifecycle, Google Maps UI, and Android hardware sensors.
*   **The ViewModels (`NavigationViewModel.kt`)**: Handles the reactive state of the user's journey. It fuses disparate data streams (GPS, Compass, Route) into actionable states.
*   **The Data/Domain Layer**: Handled by dedicated repositories and managers (`VisionRepository`, `RouteRepository`, `GeminiManager`, `ObstacleDetector`).

---

## 2. The Vision Pipeline (Sub-100ms Safety Reflex)

The primary goal of the vision pipeline is collision avoidance. This must operate independently of the UI thread and network latency.

### 2.1 CameraX Configuration
We use AndroidX `CameraX` bound to the Activity lifecycle. 
*   **Use Case**: `ImageAnalysis`.
*   **Backpressure Strategy**: `STRATEGY_KEEP_ONLY_LATEST`. This is critical. If the inference engine falls behind, we drop frames rather than queueing them up, which would result in analyzing stale data and delayed warnings.
*   **Execution**: Frames are pushed to a dedicated background `ExecutorService` inside `VisionRepository`.

### 2.2 Obstacle Detection (ML Kit)
Frames (`ImageProxy`) are converted to Bitmaps and fed into `ObstacleDetector.kt`.
*   **The Engine**: We use Google Play Services ML Kit Object Detection. This runs asynchronously on an internal ML Kit thread pool, completely freeing our local executors.
*   **The Proximity Heuristic**: Because standard mono-cameras lack absolute depth, we calculate proximity using a spatial bounding-box heuristic:
    1.  **Center Path Masking**: We ignore objects outside the `[0.3f, 0.7f]` X-coordinate bounds to prevent false positives from walls/parked cars beside the user.
    2.  **Bottom-Edge Y-Coordinate Check**: We evaluate `boundingBox.bottom.toFloat() / frameHeight`. If the bottom edge of an object exceeds `0.8f` (the bottom 20% of the screen), the object is physically near the user's feet.
*   **The Callback**: If the threshold is breached, the detector fires the `onDangerDetected()` callback. `VisionActivity` catches this on the Main thread, immediately flushing the TTS queue to shout "STOP" and triggering a heavy haptic pulse.

---

## 3. The AI Guidance Pipeline (Asynchronous Contextual Navigation)

While ML Kit handles the immediate reflex, the Google Gemini API handles contextual routing.

### 3.1 Reactive Triggers (No Polling)
We do not use dumb `while(true)` polling loops. `NavigationViewModel` acts as a reactive state machine.
*   **Inputs**: It observes `LocationRepository.locationFlow` and `SensorRepository.compassBearingFlow`.
*   **The Trigger**: When the user's location changes by $X$ meters, or their heading changes by $Y$ degrees, the ViewModel combines the current NavStep, the GPS vector, and the compass heading.
*   **Concurrency Control**: It uses Flow operators (like `throttleFirst` / `sample` / atomic flags) to ensure we do not queue up multiple Gemini requests if the user turns rapidly. If an API call is in flight (which takes 1.5s - 3s), new triggers are ignored.

### 3.2 Gemini Interaction (`GeminiManager.kt`)
*   **Execution**: Triggered via `viewModelScope.launch(Dispatchers.IO)`.
*   **Payload**: We capture the latest camera Bitmap (scaled down to reduce latency and bandwidth) and inject the routing metadata (e.g., "User is facing North, next step is turn Right in 50ft") into the system prompt.
*   **Streaming**: We use `generativeModel.generateContentStream()`. As chunks arrive, they are pushed to the UI, and the final concatenated string is sent to the `SpeechHelper` for Text-To-Speech.

---

## 4. Navigation & State Management

### 4.1 Routing (`RouteRepository.kt` & Retrofit)
*   When a user initiates a voice command, Regex parses the intent.
*   We use a Retrofit client to hit the **Google Directions REST API**.
*   The API returns an encoded Polyline string and a list of `Steps` (JSON).

### 4.2 State Synchronization (`NavStateManager.kt`)
*   The `PolylineDecoder` decrypts the route into a `List<LatLng>`.
*   `VisionActivity` observes this StateFlow. **Critical UI Optimization:** To prevent massive GPU overdraw, the Activity explicitly removes the `currentPolyline` from the `GoogleMap` object before calling `googleMap.addPolyline()` with the new state.
*   The `NavStateManager` tracks the user's distance to the next `LatLng` node and advances the routing instructions sequentially.

---

## 5. Summary of the Threading Model

To explain this to another engineer, draw this mental map of our concurrency:

1.  **Main Thread (UI)**: Owns `GoogleMap` rendering, Android `SpeechRecognizer`, `TextToSpeech` engine, and Haptic feedback. It *only* executes fast callbacks.
2.  **Camera Background Thread**: A single-thread executor that continuously pumps `ImageProxy` frames out of CameraX.
3.  **ML Kit Thread Pool (Internal)**: Google's proprietary threads that crunch the TFLite models for object bounding boxes.
4.  **Dispatchers.IO (Coroutines)**: Manages all Retrofit network calls for Google Directions and all gRPC/REST calls for the Gemini API.

### *Historical Context: Why we ripped out MiDaS V2*
*If a senior engineer asks why we use ML Kit instead of a depth-estimation model:*
Previously, we ran MiDaS V2 on-device. It was a synchronous bottleneck. Because it took ~200ms per frame to calculate depth, it occupied a dedicated `MAX_PRIORITY` thread. Furthermore, MiDaS only provided *relative* depth (min/max scaling), meaning a flat wall 10 meters away looked identical to a truck 2 meters away if they filled the frame. Moving to ML Kit Object Detection decoupled our threads (making it asynchronous) and gave us metric-proxy bounding boxes, resolving our thermal throttling and false-positive issues.
