# PhairPlay

PhairPlay is a free, open-source, ad-free AirPlay 2 receiver for Google TV. It lets your macOS or iOS/iPadOS device mirror its screen and audio directly to your TV — no Apple TV required.

---

## Download the latest APK

**[Download PhairPlay for Google TV](https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk)**

The link always points to the latest successful build from `main`. The APK keeps one simple filename; the [current GitHub release](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest) shows the exact version embedded in the APK and explains what's new.

- [Read the current update notes](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest)
- [Verify the APK checksum](https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/SHA256SUMS.txt)
- Runs on Google TV / Android TV OS 10+ (tested on Google TV 4K, Android TV OS 14)
- Optional [download page](https://2archiver.github.io/phairplay-archiver-fork-/) — the APK link works without Pages

**Install:** open *Downloader* on the TV, paste the download link above, then choose **Install**. From a computer, use `adb install -r PhairPlay-googletv.apk`. See the [installation guide](docs/guides/INSTALLATION.md).

**Update:** on the TV, choose **Settings → Updates → Check for updates**. PhairPlay checks the latest release, verifies its checksum and signing certificate, then offers to install it. Builds signed with this repository's key install over the existing app; older or differently signed builds may need a one-time reinstall. See [keeping PhairPlay up to date](docs/UPDATES.md).

**Help:** [Troubleshooting](docs/guides/TROUBLESHOOTING.md) · [Google Cast](docs/guides/CAST.md) · [Miracast](docs/guides/MIRACAST.md).

**No APK yet?** GitHub Actions publishes one after a successful build from `main`. For a temporary test build, use **Actions → CI** and download `debug-apk-googletv`.

```
 macOS (Monterey+)            Google TV
 iOS / iPadOS (16+)           ┌──────────────────────┐
 ┌────────────────┐  AirPlay  │                      │
 │  [Your Screen] │ ────────► │  [Your TV Screen]    │
 │                │           │                      │
 └────────────────┘           └──────────────────────┘
      Click AirPlay →              PhairPlay
      Select your TV →             (this app)
      Done. ✓
```

---

## Latest update

The [current release page](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest) is the source of truth for the newest APK. Its title is taken from the version inside that APK, and its **What's new** section is refreshed for each successful update. See the [full changelog](CHANGELOG.md) for release history.

PhairPlay's AirPlay 2 receiver includes mDNS advertising, RTSP, pairing, FairPlay key handling, H.264 mirroring, AAC/ALAC audio, NTP A/V sync, and DACP remote control. Real-device validation with macOS and iOS senders is ongoing. Known limits are documented in the [Google Cast guide](docs/guides/CAST.md) and [Miracast guide](docs/guides/MIRACAST.md).

Earlier releases added the spoofed device name and iOS discovery fixes (v1.3), automatic APK updates and the release pipeline (v1.2), and Miracast video playback (v1.1). The [changelog](CHANGELOG.md) has the full details.

### Tested with

| Role | Device | Notes |
|------|--------|-------|
| Receiver | **Google TV 4K**, Android TV OS 14 (API 34) | Primary target; 4K mirror advertisement supported |
| Receiver | Chromecast with Google TV, Google TV Streamer, Sony/TCL/Hisense/Philips Google TV | ARM only (`armeabi-v7a`, `arm64-v8a`) |
| Sender | **iPhone 14**, iOS 27.0.1 | Screen mirroring + photos; portrait streams are aspect-fitted, not stretched |
| Sender | macOS 12+ | Mirroring, system audio, DACP reverse remote |

Report what you see on your hardware in a [bug report](.github/ISSUE_TEMPLATE/bug_report.md) — the device matrix above is what we want to grow.

## Features

### AirPlay 2 (fully implemented)
- Screen mirroring from macOS 12+ and iOS/iPadOS 16+ — H.264 hardware decode
- Spoofed receiver name, advertised consistently over mDNS, `GET /info` and `GET /server-info`; defaults to **Apple TV** (matching the `AppleTV5,3` model PhairPlay reports) and takes effect as soon as you save it
- Mirror resolution matched to the TV: 1080p by default, **up to 4K on a 4K Google TV** when you opt in, always capped by what the panel shows and the H.264 decoder reports it can decode
- FairPlay session decryption (fp-setup v2/v3 + legacy rsaaeskey) via native libplayfair
- HomeKit-style pairing (Ed25519/X25519) and legacy SRP PIN pairing
- Mirroring audio: AAC-ELD, AAC-LC, ALAC — with independent A/V start/stop
- System audio streaming (ALAC, unencrypted) — reliable path for app audio
- AirPlay video URL mode (`/play` content) + transport controls (play/pause/scrub)
- Now-playing metadata (DMAP) with album artwork overlay
- DACP reverse remote — TV remote controls the sender's playback
- NTP timing and UDP audio retransmit (packet-loss recovery)
- AirPlay photo receiver — JPEG/PNG from iOS Photos app displayed full-screen
- Access-control lockout after repeated failed pairing attempts

### App & Platform
- Google TV app shell with foreground service and status UI
- Mirror audio toggle, PIN-auth toggle and resolution toggle in Settings
- Built for Google TV (Android TV OS 10+, tested on Google TV 4K with Android TV OS 14); ARM-only APK for a smaller download
- Miracast Wi-Fi Direct / WFD advertisement, RTSP control-plane, and H.264 video playback (hardware decode)
- Built-in Google Cast receiver — mDNS `_googlecast._tcp`, DIAL on 8008, castv2 on 8009; no Google registration needed (see [docs/guides/CAST.md](docs/guides/CAST.md))
- Network summary on the Home screen: which interface and IP PhairPlay is advertising on (`Ethernet · 192.168.1.42`)
- In-app update checker and self-updater with SHA-256 and signing-certificate verification
- Zero ads, zero analytics, zero internet required
- Open source — Apache 2.0 license

### Releases
- One current [`latest` release](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest), updated from `main`
- Release title and notes identify the version and changes in the APK; the direct download link stays the same
- `SHA256SUMS.txt` and `version.json` accompany the APK; the in-app updater uses the version code
- Every APK is signed with the community key and verified by CI before publishing
- Older releases remain available as history; new builds do not create competing release entries

## What PhairPlay Does NOT Do

- **FairPlay DRM content** (Netflix, Disney+, Apple TV+) — Apple DRM; not decryptable by any open-source receiver
- **Apple Music in-app audio** — protected on every AirPlay path; use system audio output instead
- **Buffered audio playback** (AirPlay 2 type 103) — accepted but not played back yet
- **Cloud/remote streaming** — local network only
- **Miracast audio playback** — v1.1 renders Miracast video; WFD audio is negotiated but not played yet
- **Google Cast media playback without an App ID** — Google requires a registered Cast App ID; the control plane is ready and media flows through Google's SDK once an ID is provisioned
- **Appearing in another app's own cast button** — that button is Google Cast, which routes to the TV's built-in Chromecast receiver, never to PhairPlay. Use iOS **Screen Mirroring** (Control Centre) instead; see [Troubleshooting → Casting from an iPhone app](docs/guides/TROUBLESHOOTING.md#casting-from-an-iphone-app-rumble-youtube-)

---

## Requirements

**On your TV:**
- Google TV / Android TV OS 10+ (Android TV OS 14 on a Google TV 4K is the tested target)
- Connected to the same Wi-Fi network as your sender
- Sideloading enabled (Downloader app) or ADB debugging enabled

**On your iPhone / iPad:**
- iOS / iPadOS 16 or later (tested with iPhone 14 on iOS 27.0.1)
- Same Wi-Fi network as the TV; both on the same subnet

**On your Mac:**
- macOS 12 (Monterey) or later
- Connected to the same Wi-Fi network as your TV

**Network:**
- Both devices on the same subnet (common home router setup works)
- Multicast/mDNS must not be blocked (most home routers are fine)
- 5 GHz Wi-Fi or Ethernet strongly recommended for best performance — 4K mirroring really wants it

---

## Installation

### Option A: Download the release APK (easiest)

Download **`PhairPlay-googletv.apk`** from the [current release](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest). It is the only APK in the release and is built for Google TV (Android TV OS 10+, tested on Android TV OS 14).

Direct download link (always the latest successful build):

```
https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk
```

Install it with the *Downloader* app on the TV or with ADB — see the Sideloading Guide below. The title on the release page shows the version embedded in the APK and the notes explain what's changed.

### Option B: Build from Source

1. **Install prerequisites**
   ```bash
   # Install Android Studio from https://developer.android.com/studio
   # Install JDK 17 or later
   ```

2. **Clone the repository**
   ```bash
   git clone https://github.com/2archiver/phairplay-archiver-fork-.git
   cd phairplay-archiver-fork-
   ```

3. **Build the APK**
   ```bash
   # Release APK — the same thing the release workflow publishes
   ./gradlew :app:assembleGoogletvRelease
   # → app/build/outputs/apk/googletv/release/app-googletv-release.apk

   # Debug APK
   ./gradlew :app:assembleGoogletvDebug
   # → app/build/outputs/apk/googletv/debug/app-googletv-debug.apk

   # With a registered Cast App ID:
   ./gradlew :app:assembleGoogletvRelease -Pphairplay.castAppId=<APP_ID>
   ```
   The version comes from `phairplay.versionName` in `gradle.properties`; the versionCode is
   derived from the clock so each local build can update the previous one. Override the version
   with `-Pphairplay.versionName=1.6.0`; normally leave versionCode on its automatic setting.

   To run the same local checks used by CI before testing on a TV:
   ```bash
   ./gradlew :test-runner:test
   ./gradlew :app:lintGoogletvDebug :app:assembleGoogletvDebug
   ```

4. **Install via ADB**
   ```bash
   # Enable ADB on your TV first (see below)
   adb connect <TV-IP-ADDRESS>

   adb install -r app/build/outputs/apk/googletv/release/app-googletv-release.apk
   ```

---

## Sideloading Guide

### Google TV without a computer (Downloader app)

1. Install **Downloader** (AFTVnews) from the Google Play Store on your TV.
2. Go to **Settings → Apps → Security & restrictions → Install unknown apps** and allow **Downloader**.
3. Open Downloader, enter the direct link from Option A (or the [download page](https://2archiver.github.io/phairplay-archiver-fork-/)), then choose **Install**.

### Google TV with ADB (e.g., Chromecast with Google TV, Google TV Streamer 4K, Android TV OS 14)

1. Go to **Settings → System → About → Android TV OS build** and click it 7 times to enable Developer Options.
2. Go to **Settings → System → Developer Options** and enable **USB debugging** (Android TV OS 14 also offers **Wireless debugging**).
3. Note your TV's IP address from **Settings → Network & Internet**.
4. On your Mac/PC, run:
   ```bash
   adb connect <TV-IP>
   adb install -r PhairPlay-googletv.apk
   ```
5. Launch PhairPlay from your app list.

If Android refuses the update with `INSTALL_FAILED_VERSION_DOWNGRADE` or a signature mismatch, run `adb uninstall com.phairplay.googletv` once and install again.

---

## How to Use

1. Launch PhairPlay on your TV. You will see the Waiting Screen with your TV's name.
2. **iPhone/iPad:** open Control Centre → **Screen Mirroring** → select your TV. **Mac:** click the **AirPlay** icon in the menu bar (or **System Settings → Displays → AirPlay Display**).
3. Select your TV from the list (it should appear as your TV's name).
4. Your screen appears on the TV. Portrait phone streams are aspect-fitted, not stretched.
5. On a 4K Google TV, turn on **Settings → Higher resolution (up to 4K)** for a sharper mirror; turn it off again if your TV struggles to decode it.
6. To stop: turn off Screen Mirroring/AirPlay on the sender, or quit PhairPlay on the TV.

---

## Known Limitations

- **Beta software** — the AirPlay 2 stack is complete but real-device validation with various macOS/iOS senders is ongoing. Please report issues.
- **4K mirroring is opt-in and hardware-dependent.** PhairPlay only advertises 4K when the panel reports 4K *and* the H.264 decoder says it supports 3840×2160; a 4K advertisement still costs real decode work, so keep it off if frames drop.
- **Apple Music in-app audio is not decryptable.** macOS protects it with FairPlay on every AirPlay path. Route the Mac's system audio output instead (works fine).
- **FairPlay-protected video** (Netflix, Disney+, Apple TV+) cannot be mirrored — this is Apple's DRM, not a PhairPlay limitation.
- **Buffered audio (AirPlay 2 type 103)** is accepted but not yet played back.
- **Google Cast** requires a registered Cast app ID for end-to-end testing; see [docs/guides/CAST_APP_ID.md](docs/guides/CAST_APP_ID.md).
- **Miracast** — video now decodes and renders (H.264 → hardware); audio playback and discovery success still depend on the TV's Wi-Fi Direct stack and are being validated on real hardware.
- If your router has **AP isolation** or **multicast filtering** enabled, PhairPlay may not appear in the AirPlay menu. Disable these settings on your router.
- On very busy 2.4 GHz Wi-Fi networks, you may experience latency above 100 ms. Use 5 GHz or Ethernet for best results.
- **PIN auth is optional.** When disabled (default), any device on the same network can mirror to the TV. Enable PIN auth in Settings if you're on a shared network.

For real-device failures, run `tools/collect-device-logs.sh` before restarting the app. It captures package state, memory, CPU, and filtered PhairPlay logs into `device-test-logs/`.

---

## Contributing

Contributions are welcome! Please read [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) before submitting a pull request.

Key points:
- Follow the coding rules in CONTRIBUTING.md (file size ≤400 lines soft / ≤550 lines hard max, class comments, test coverage)
- All PRs require passing CI (build + tests + lint)
- Merging to `main` publishes a new `latest` APK automatically — see [docs/RELEASING.md](docs/RELEASING.md)
- Discuss major changes in a GitHub Issue first

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

---

## Acknowledgments

- [openairplay/airplay-spec](https://github.com/openairplay/airplay-spec) — Community-maintained AirPlay protocol documentation
- [UxPlay](https://github.com/FDH2/UxPlay) — Open-source AirPlay mirror server (reference implementation)
- [RPiPlay](https://github.com/FD-/RPiPlay) — AirPlay mirroring for Raspberry Pi (reference implementation)
