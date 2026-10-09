# Meta glasses camera (Ray-Ban Meta)

Branch `feature/meta-glasses`. Streams the Ray-Ban Meta camera into the existing reflex through the
[Meta Wearables Device Access Toolkit](https://wearables.developer.meta.com/) 1.0.0, in place of the
phone's camera.

## What it does and does not do

**Only the eyes move.** Glasses frames go to the same `processFrame(bitmap, timestamp)` the phone
camera used. The reflex still runs on the phone, on-device, and never waits on the network. Gemini,
navigation, and speech are unchanged.

**The toolkit gives device access, not Meta's AI.** It streams camera, microphone, and sensors to
our app. It does not let a third-party app call Meta AI, so Gemini stays as the reasoning tier.

## Using it

1. Glasses paired in the **Meta AI app**, with **Developer Mode** on in that app's settings.
2. Launcher, **Vision with Meta Glasses**.
3. First run opens the Meta AI app to approve the app, then returns through `guideglass://`.
4. Grant glasses camera access when prompted. That permission lives in the Meta AI app.

Developer Mode needs no credentials. To distribute to testers through a Meta release channel, set
`MWDAT_APPLICATION_ID` and `MWDAT_CLIENT_TOKEN` in `local.properties`.

## Safety behavior

Any loss of the glasses feed switches to the phone camera immediately, with an urgent spoken message
and a haptic pulse: glasses **taken off**, **disconnected**, **overheating**, or the stream ending.
Failing to start (not approved, no permission, no glasses, no Bluetooth) also falls back to the phone.
The user is never left walking with no obstacle detection.

## Reflex thresholds are rescaled for the glasses lens

Every size gate in `ObstacleDetector` is a fraction of the frame, so its meaning depends on the lens.
`ReflexScaling` maps them back to the phone's physical meaning, and the app applies the glasses gates
when the stream starts and restores the phone gates on fallback.

Without rescaling, the simulator (`paper/simulation/sim_glasses.py`) shows the first-alert distance
for a 0.9 m bollard falling from **3.71 m to 2.29 m**, because the looming detector's minimum-size gate
is no longer met at the time-to-contact radius. With rescaling it is restored to **3.71 m**, with the
false-alarm and miss rates matching the phone.

**The glasses field of view is unverified.** `ReflexScaling.GLASSES_HFOV_DEG` (60.6) and
`GLASSES_VFOV_DEG` (92.2) come from one review's 100 degree figure, assumed diagonal, on the toolkit's
9:16 portrait stream. Measure it on hardware (photograph a ruled board at a known distance) and update
those two constants. The unit test `ReflexScalingTest` will then flag the simulator comparison.

## Tests

| Test | Where | What it proves |
|---|---|---|
| `Yuv420Test` | JVM | Color conversion is right, and a wrong pixel layout gives wrong colors rather than passing silently |
| `ReflexScalingTest` | JVM | App thresholds match the simulator's rescaled thresholds exactly |
| `GlassesCameraSourceTest` | device | Color bars streamed through Meta's simulated glasses arrive with the right colors, taking the glasses off is reported, an unapproved app fails cleanly |

```bash
sh ./gradlew :app:testDebugUnitTest
sh ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.impairedvision.guideglass.glasses.GlassesCameraSourceTest
```

The device test pins the toolkit's raw frame layout (`Yuv420Layout`). **Run it on a real phone before
field use.** Phone hardware decoders can lay frames out differently from the emulator's software decoder.

## Known gaps

- **Not tested on real glasses.** Everything above uses Meta's simulated device.
- **Bluetooth latency is unmeasured.** Each 100 ms of transport delay costs 0.1 s and 0.14 m of
  warning at walking pace. Measure it before any trial.
- **Screen off stops the stream.** Meta's sample keeps streaming with a foreground service. Needed
  before real walks, since a blind user will not keep the screen on.
- **No preview in glasses mode.** The phone's preview stays blank, which only matters for sighted
  debugging.
- **Developer preview.** The toolkit cannot yet be published publicly. Release channels to testers work.

## Toolchain change

The toolkit is built with Kotlin 2.2 and needs androidx.core 1.16, so this branch moves to Gradle 8.13,
AGP 8.13.2, Kotlin 2.2.21, the Kotlin Compose compiler plugin, compileSdk 36, and **minSdk 29**
(Android 10, the toolkit's and Meta AI app's floor). targetSdk stays 34. Build with Android Studio's
bundled JDK 21; JDK 24 is too new for Gradle 8.13.
