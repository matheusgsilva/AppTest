# CameraFrameLab

Android diagnostic app created to isolate dropped-frame behavior from the main SteadyVault app.

## What it measures

- Camera2 `SENSOR_TIMESTAMP` for every completed capture result.
- Camera frame number and wall-clock callback timing.
- Every encoded H.264 sample PTS emitted by `MediaCodec`.
- Camera and encoder interval distribution (median/p95/p99/max).
- Gaps larger than 1.5 expected frame intervals and estimated missing-frame count.
- Repeated encoder PTS values.
- Android thermal status and battery temperature during the test.
- Camera hardware level, timestamp source, supported regular FPS ranges and constrained high-speed modes.

No interpolation, frame repair, OpenCV, vault, background service, audio, or post-processing is included. The purpose is to disturb the camera pipeline as little as possible.

## Recommended first run on the Galaxy S25 Ultra

1. Grant camera permission.
2. Tap **Refresh capabilities**.
3. Run the automatic suite once with the phone cool and unplugged.
4. Repeat the suite after 5-10 minutes of continuous camera use if you want to compare thermal behavior.
5. The MP4 plus CSV/JSON reports are all written together under `DCIM/CameraFrameLab`.

The suite only starts configurations the device reports as supported. It focuses on 1080p30, 1080p60, 4K30 and 4K60, with preview ON/OFF variants for 60 fps when possible.

## Build

Use Android Studio with Android SDK 36 installed. This project is pinned to Android Gradle Plugin 9.4.0 and Gradle 9.6.0.

The Gradle wrapper properties are included. If your checkout does not have `gradle-wrapper.jar`, Android Studio can still sync/build the project, or run `gradle wrapper --gradle-version 9.6` once from a machine with Gradle installed.

## Output interpretation

- **Camera gap**: the Camera2 sensor timestamps themselves skipped one or more expected frame periods. That means the application never received a real capture cadence for those missing periods.
- **Encoder-only gap**: Camera2 cadence is healthy but encoded PTS has a gap. This points downstream toward the encoder/muxer path.
- **Callback-only delay**: sensor timestamps are healthy while callback wall-clock timing is late. This is app/thread scheduling latency, not necessarily a missing sensor frame.
- **Repeated PTS**: encoded timestamps repeated. This is not a pixel-content duplicate detector; it only detects duplicate timestamps.

## Important limitation

Camera2 `CaptureResult` cadence is the least invasive practical proxy for capture cadence, but it is not a direct dump of every pixel buffer consumed by the encoder. The comparison is designed to localize the problem without adding an expensive analysis stream that could itself create dropped frames.

## Windows: collect everything with ADB

With USB debugging enabled and `adb` on PATH, run:

```powershell
.\tools\pull_reports.ps1
```

It copies visible reports and videos into the local `captures/` folder.

## CI

`.github/workflows/android.yml` runs the pure Java gap-analysis tests and builds a debug APK on every push/PR. This is especially useful because this repository intentionally has no dependency on the SteadyVault build.
