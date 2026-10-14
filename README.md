# BCam

[![Latest release](https://img.shields.io/github/v/release/N2K02/BCam?label=download)](https://github.com/N2K02/BCam/releases/latest) ![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white) ![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Background video recorder for Android. No screen, no preview — tap the icon and it records.

*Screen-off background video recorder for Android: hidden camera recording with no preview, H.265/H.264, front and back camera.*

**[⬇ Download the latest APK](https://github.com/N2K02/BCam/releases/latest)** · Android 8.0 or newer · Free and open source

## How it works

| Action | What happens |
|---|---|
| Tap the **BCam** icon | Recording starts in the background (screen closes immediately) |
| Notification → **❚❚ Pause / ▶ Resume** | Pauses recording and continues later in the same video file |
| Notification → **■ Stop** | Stops and saves the current video |
| Notification → **⚙ Settings** (or tap the notification) | Video settings, **Camera: back / front**, the Updates card and **Exit BCam** |
| Every **30 minutes** (adjustable) | The current file is saved and a new one starts automatically, until you press Stop |

## Video settings

- **Camera**: back or front. While recording, the video so far is saved and recording continues with the other camera in a new file (the two cameras record at different angles and sizes, so they can't share one file).
- **Exit BCam** (bottom of Settings): stops and saves the recording and closes BCam.

Tap **⚙ Settings** in the recording notification (or the notification itself) to choose:

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

## Updates

BCam updates itself from this repo's Releases:

- When you tap the icon it checks for a newer release in the background (at most every 12 hours). If there is one, an **"Update available"** notification appears; tap it to open Settings with the update shown in its **Updates** card.
- To check by hand: ⚙ **Settings** in the recording notification → **Updates** card → **Check for updates**. What's new, the download progress and **Install** all show in that card; the Settings dialog stays open.
- The APK is downloaded inside the app, checked (size, SHA-256 checksum from GitHub, same app, newer version, same signing key) and installed with Android's own installer. If BCam is recording, the recording is stopped and saved first.
- The first time, Android asks you to allow installs from BCam ("Install unknown apps"); after that you just confirm the update.

## Signing

Releases are signed with a private key that is kept encrypted in `signing/keys.tar.enc` and unlocked during the build with the `SIGNING_PASSPHRASE` repository secret (see [signing/README.md](signing/README.md)). Earlier builds used a public test key; Android's key rotation moves installed apps to the private key on the next update, without reinstalling.

## Build

Every push to `main` builds the APK with GitHub Actions and publishes it as a release named "BCam <version>" (1.0, 1.1, 1.2 …, one decimal place) with release notes listing the changes since the previous release. To jump to a new number (e.g. 2.0), run the workflow by hand from the Actions tab and type the version. Locally: `./gradlew assembleRelease` (shrunk APK) (JDK 17 + Android SDK).

## License

MIT. See [LICENSE](LICENSE).
