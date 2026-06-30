# android2 — Native XBot (Java)

Standalone native Android app replicating the Flutter XBot core features without object recognition (YOLO). Uses **Java 17**, **View/XML + Fragment**, and shares assets from the repo root.

## Prerequisites

- Android SDK (compileSdk 36)
- JDK 17
- Node.js (to build virtual-pet HTML)

## Build

```bash
# 1. Build virtual-pet web assets (required once, or after HTML changes)
cd ../assets/html && npm install && npm run build

# 2. Assemble debug APK
cd ../../android2
./gradlew :app:assembleDebug
```

`syncAssets` runs automatically before each build and copies:

- `../assets/html/dist/` → `app/src/main/assets/html/dist/`
- `../assets/images/` → `app/src/main/assets/images/`
- `../assets/models/` → `app/src/main/assets/models/`
- `../android/app/src/main/assets/*.task` → `app/src/main/assets/models/`

Open `android2/` in Android Studio for on-device debugging. Application id: `com.xbot.xbot.native` (can coexist with the Flutter app).

## Architecture

| Module | Role |
|--------|------|
| `PerceptionPipeline` | CameraX analysis + ML Kit / MediaPipe / TFLite |
| `EmotionMapper` + `BehaviorStateTracker` | Attention FSM → virtual pet states |
| `VirtualPetWebView` | WebViewAssetLoader + JS bridge (`window.__face`) |
| `VoiceAssistant` | Wake / gaze / double-tap → Pophie API + streaming TTS |
| `BaseService` | Classic Bluetooth SPP base control |
| `PersonaLogger` + `PersonaLogServer` | Daily JSONL logs + NanoHTTPD browser (port 8765) |

## Notes

- **Camera**: Virtual-pet mode runs headless `ImageAnalysis` (~8 fps); debug mode shows CameraX preview + overlay.
- **Wake word**: Sherpa-onnx models are bundled; JNI integration uses an energy/VAD stub until AAR wiring is added.
- **Hand landmarker**: `hand_landmarker.task` is synced from `assets/models/` (download via MediaPipe if missing).

## Permissions

Camera, microphone, internet, Bluetooth (for base), foreground service — see `AndroidManifest.xml`.
