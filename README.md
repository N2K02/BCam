# BCam

[![Latest release](https://img.shields.io/github/v/release/N2K02/BCam?label=download)](https://github.com/N2K02/BCam/releases/latest) ![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Background video recorder for Android. No screen, no preview — tap the icon and it records.

*Screen-off background video recorder for Android: hidden camera recording with no preview, H.265/H.264, front and back camera.*

**[⬇ Download the latest APK](https://github.com/N2K02/BCam/releases/latest)** · Android 8.0 or newer · Free and open source

## How it works

| Action | What happens |
|---|---|
| Tap the **BCam** icon | Recording starts in the background (screen closes immediately) |
| Notification → **Stop** | Stops and saves the current video |
| Notification → **Switch to front / back** | Saves the current part and continues with the other camera |
| Notification → **⚙ Settings** | Opens the video settings (see below) |
| Every **30 minutes** (adjustable) | The current file is saved and a new one starts automatically, until you press Stop |

## Video settings

Tap **⚙ Settings** in the recording notification to choose:

| Setting | Options | Default |
|---|---|---|
| Resolution | 720p · 1080p · 4K (only what your camera supports) | 1080p |
| Frame rate | 24 · 30 · 60 fps (60 only if supported) | 30 fps |
| Quality | Low · Standard · High (bitrate) | Standard |
| Codec | H.265 (smaller) · H.264 (plays everywhere) | H.265 |
| Sound | On · Off | On |
| New file every | 10 · 15 · 30 · 60 min | 30 min |

The dialog shows the estimated size per file. If you save while recording, the current file is kept and a new one starts with the new settings. Settings are remembered for next time.

- **Format:** MP4 with **H.265 / HEVC** video (about half the size of H.264 at the same quality) + AAC audio. If the phone has no hardware HEVC encoder, it falls back to H.264.
- **Quality (default):** up to 1080p at 30 fps, ~5 Mbps → roughly **1.1 GB per 30-minute part** (H.265). Change it in ⚙ Settings.
- **Where:** `Movies/BCam` (visible in Gallery) on Android 10+. On Android 8–9: `Android/data/com.bcam.app/files/Movies/BCam`.
- Files are named `BCam_YYYYMMDD_HHMMSS_back.mp4` / `_front.mp4`.
- If another app takes the camera, BCam waits and resumes automatically.
- If you block BCam's notifications, tapping the icon again stops recording.

Video orientation assumes the phone is held upright (portrait).

## Install

1. Download `BCam-<version>.apk` (e.g. `BCam-1.0.apk`) from the latest **Release** of this repo and install it (allow "install unknown apps").
2. Tap the BCam icon once and allow **Camera**, **Microphone** and **Notifications**.
3. On Xiaomi / Samsung / Huawei etc. set BCam battery usage to **Unrestricted** so long recordings are not killed.

## Build

Every push to `main` builds the APK with GitHub Actions and publishes it as a release named "BCam <version>" (1.0, 1.1, 1.2 …, one decimal place) with release notes listing the changes since the previous release. To jump to a new number (e.g. 2.0), run the workflow by hand from the Actions tab and type the version. Locally: `./gradlew assembleRelease` (shrunk APK) (JDK 17 + Android SDK).

## License

MIT. See [LICENSE](LICENSE).
