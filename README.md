# BCam

Background video recorder for Android. No screen, no preview — tap the icon and it records.

## How it works

| Action | What happens |
|---|---|
| Tap the **BCam** icon | Recording starts in the background (screen closes immediately) |
| Notification → **Stop** | Stops and saves the current video |
| Notification → **Switch to front / back** | Saves the current part and continues with the other camera |
| Every **30 minutes** | The current file is saved and a new one starts automatically, until you press Stop |

- **Format:** MP4 with **H.265 / HEVC** video (about half the size of H.264 at the same quality) + AAC audio. If the phone has no hardware HEVC encoder, it falls back to H.264.
- **Quality:** up to 1080p at 30 fps, ~5 Mbps → roughly **1.1 GB per 30-minute part** (H.265).
- **Where:** `Movies/BCam` (visible in Gallery) on Android 10+. On Android 8–9: `Android/data/com.bcam.app/files/Movies/BCam`.
- Files are named `BCam_YYYYMMDD_HHMMSS_back.mp4` / `_front.mp4`.
- If another app takes the camera, BCam waits and resumes automatically.
- If you block BCam's notifications, tapping the icon again stops recording.

Video orientation assumes the phone is held upright (portrait).

## Install

1. Download `BCam.apk` from the latest **Release** of this repo and install it (allow "install unknown apps").
2. Tap the BCam icon once and allow **Camera**, **Microphone** and **Notifications**.
3. On Xiaomi / Samsung / Huawei etc. set BCam battery usage to **Unrestricted** so long recordings are not killed.

## Build

Every push to `main` builds the APK with GitHub Actions and publishes it as a release. Locally: `./gradlew assembleRelease` (shrunk APK) (JDK 17 + Android SDK).
