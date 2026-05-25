# Guide Glass - Vision Assistance App

A comprehensive Android application that combines Google Maps navigation with Gemini AI-powered scene recognition and text-to-speech guidance for visually impaired users.

## Features

### 🔍 Vision Mode
- Real-time camera analysis using Google Gemini AI
- Automatic scene recognition and obstacle detection
- Voice guidance for navigation assistance
- Configurable analysis frequency (default: every 3 seconds)
- Urgent alerts for immediate dangers (e.g., "STOP")

### 🗺️ Navigation Mode
- Google Maps integration with turn-by-turn directions
- Place search with autocomplete
- Walking directions with voice guidance
- Real-time location tracking during navigation
- Support for different map types (Normal, Satellite, Terrain, Hybrid)

### 🚶 Combined Mode
- Use Vision and Navigation together
- Camera analysis while navigating
- Seamless switching between features

## Project Structure

```
com.impairedvision.guideglass/
├── MainActivity.kt              # Main launcher with mode selection
├── maps/
│   ├── MapsActivity.kt         # Google Maps navigation
│   ├── DirectionsService.kt    # Retrofit API for directions
│   ├── RetrofitClients.kt      # API client configuration
│   └── PolylineDecoder.kt      # Decode route polylines
├── vision/
│   ├── VisionActivity.kt       # Gemini AI camera analysis
│   └── OverlayView.kt          # Detection visualization overlay
├── tts/
│   └── SpeechHelper.kt         # Shared text-to-speech helper
└── ui/theme/
    ├── Color.kt                # Theme colors
    ├── Theme.kt                # Material 3 theme
    └── Type.kt                 # Typography styles
```

## Setup Instructions

### 1. API Keys Configuration

You need to configure two API keys:

#### Google Maps API Key
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing one
3. Enable these APIs:
   - Maps SDK for Android
   - Directions API
   - Places API
4. Create an API key and restrict it to your app
5. Copy `local.properties.example` to `local.properties` (gitignored) and set:
   - `GOOGLE_MAPS_KEY` — Maps SDK + Places (manifest)
   - `GOOGLE_DIRECTIONS_KEY` — Directions REST API
   - `GEMINI_API_KEY` — Gemini generative API

#### Gemini AI API Key
1. Go to [Google AI Studio](https://makersuite.google.com/app/apikey)
2. Create an API key
3. Add it as `GEMINI_API_KEY` in `local.properties` (injected into `BuildConfig` at compile time)

### 2. Build Configuration

The project uses:
- Kotlin 1.9.22
- Jetpack Compose with Material 3
- CameraX 1.3.1
- Google Maps SDK 18.2.0
- Gemini AI SDK 0.2.2
- Retrofit 2.9.0 with Moshi

Minimum SDK: 26 (Android 8.0)
Target SDK: 34 (Android 14)

### 3. Permissions

The app requires these permissions (handled in AndroidManifest.xml):
- `INTERNET` - For API calls
- `ACCESS_FINE_LOCATION` - For GPS navigation
- `ACCESS_COARSE_LOCATION` - For approximate location
- `CAMERA` - For vision mode

### 4. Building the Project

```bash
# Open in Android Studio
# File > Open > Select the GuideGlass directory

# Or build from command line
./gradlew assembleDebug
```

## Usage

### Main Menu
- **Start Vision Mode**: Opens camera for AI-powered scene analysis
- **Start Navigation**: Opens Google Maps for turn-by-turn navigation
- **Vision + Navigation**: Combined mode with both features active

### Vision Mode
- Point the camera forward
- The AI will analyze the scene and provide voice guidance
- Tap "Pause Analysis" to temporarily stop analysis
- Instructions appear on screen and are spoken aloud

### Navigation Mode
- Tap the search bar to enter a destination
- Or long-press on the map to set origin/destination manually
- Tap "Start Navigation" when route is shown
- Voice guidance will announce each turn

## Voice Commands Reference

### Vision Mode Responses
- "Walk forward" - Path is clear
- "Veer [direction] for [object]" - Obstacle ahead, adjust course
- "STOP. [Object]. [Action]" - Immediate danger

### Navigation Mode
- Route summary (distance, duration)
- Turn-by-turn instructions
- Arrival announcement

## Customization

### Adjust Analysis Frequency
In `VisionActivity.kt`, modify:
```kotlin
private const val THROTTLE_MS = 3000L // Change to desired interval
```

### Modify AI Instructions
In `VisionActivity.kt`, edit the `systemInstruction` block to change how Gemini responds.

### Speech Settings
Use `SpeechHelper` methods:
```kotlin
speechHelper.setSpeechRate(1.2f)  // Faster speech
speechHelper.setPitch(0.9f)       // Lower pitch
```

## Troubleshooting

### Maps not loading
- Verify Google Maps API key is correct
- Check that Maps SDK is enabled in Cloud Console
- Ensure the key has Android app restrictions set correctly

### Camera not working
- Grant camera permission when prompted
- Check if another app is using the camera

### No voice output
- Check device volume
- Ensure TTS is installed on device
- Verify language pack is available

### Gemini errors
- Verify API key is valid
- Check internet connection
- Review usage quotas in Google AI Studio

## License

This project is for educational and accessibility purposes.

## Acknowledgments

- Google Maps Platform
- Google Gemini AI
- Android Jetpack libraries
- Material Design 3
# guideglass
