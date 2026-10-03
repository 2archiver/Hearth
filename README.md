# PhairPlay

PhairPlay is a free, open-source, ad-free AirPlay 2 receiver for Google TV. It lets your macOS or iOS/iPadOS device mirror its screen and audio directly to your TV — no Apple TV required.

---

## ⬇ Download the APK

**`PhairPlay-googletv.apk`** — one file, rebuilt automatically on every merge to `main`:

```
https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk
```

That link never goes stale and never needs a tag, a release to be cut by hand, or GitHub Pages to be switched on.

| | |
|---|---|
| **APK** | [`PhairPlay-googletv.apk`](https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk) |
| **Checksum** | [`SHA256SUMS.txt`](https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/SHA256SUMS.txt) |
| **Release page** | [releases/tag/latest](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest) — version, versionCode, commit |
| **Numbered releases** | [all releases](https://github.com/2archiver/phairplay-archiver-fork-/releases) — `v1.2.0` and friends stay available |
| **Download page** | <https://2archiver.github.io/phairplay-archiver-fork-/> (optional; needs Pages → Source: *GitHub Actions*) |
| **Runs on** | Google TV / Android TV OS 10+ — tested on Google TV 4K, Android TV OS 14 |

**Guides:** [Installation](docs/guides/INSTALLATION.md) · [Keeping up to date](docs/UPDATES.md) · [Google Cast](docs/guides/CAST.md) · [Troubleshooting](docs/guides/TROUBLESHOOTING.md)

**Install it on the TV:** open the *Downloader* app, paste the link above, then **Install** — or from a computer, `adb install -r PhairPlay-googletv.apk`. Full steps: [docs/guides/INSTALLATION.md](docs/guides/INSTALLATION.md).

**Updating:** once you are on a build signed with this repository's community key, install future APKs straight over the old one — Android treats them as in-place updates. Some early 1.4 builds (and builds from other forks or private keys) need a one-time uninstall/reinstall to switch signing keys; the in-app updater identifies this and explains the safe steps. See [docs/UPDATES.md](docs/UPDATES.md).

Or skip the computer: **Settings → Updates → Check for updates** on the TV. PhairPlay asks GitHub what the newest build is, verifies the download against the published SHA-256 **and** against its own signing certificate, and installs it — silently on Android 12+ when the signing key matches. See [docs/UPDATES.md](docs/UPDATES.md).

**No APK there yet?** The release is published by GitHub Actions on the first push to `main` after the workflow lands. Until then, run **Actions → CI** on the repository and download the `debug-apk-googletv` artifact — CI builds that APK on every push and pull request.

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

## Current Status — v1.4

PhairPlay's AirPlay 2 receiver is fully implemented and published as a beta. Grab the APK above — a merge to `main` is all it takes to publish a new one.

**v1.4 removes the two things that made PhairPlay annoying to live with:**
- **Updates just work after a one-time signing-key transition.** Builds from this repository share one key, so subsequent APKs install in place; some early 1.4 or differently signed installs need a one-time reinstall first. PhairPlay checks the certificate before install and guides that transition from **Settings → Updates**.
- **Google Cast works without registering anything with Google.** PhairPlay serves Cast directly (mDNS + DIAL + castv2), so an iPhone app's cast button now lists your TV.

The AirPlay 2 stack is complete end-to-end: mDNS advertising, RTSP handshake, HomeKit-style pairing, FairPlay key decryption, H.264 mirroring, AAC-ELD/AAC-LC/ALAC audio, NTP A/V sync, and DACP reverse remote. Real-device validation with macOS and iOS senders is the current focus.

v1.3 fixes the spoofed device name end to end: renaming now restarts the receivers, `GET /info` answers with the name you set instead of the Android device name, and a fresh install advertises **Apple TV**. It also adds the `pk` record iOS reads while browsing, and stops an abandoned connection from locking out the next sender. v1.2 makes releases automatic (rolling `latest` build on every merge to `main`, plus permanent `v*` tag releases), fixes the Gradle wrapper so CI can actually build the APK, and lets a 4K Google TV advertise a 4K mirror instead of a hardcoded 1080p/1440p. v1.1 added real Miracast video playback and replaced the old fake "Check Wi-Fi settings" errors with honest, per-protocol status details. Google Cast is now served by PhairPlay itself and needs no Google-registered Cast App ID; registering one still switches to the official SDK (see [docs/guides/CAST.md](docs/guides/CAST.md)).

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
- Rolling [`latest`](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest) release rebuilt on every merge to `main`
- Permanent versioned releases from `v*` tags
- `SHA256SUMS.txt` with every release; versionCode grows with every build so updates install in place
- `version.json` with every release — what the in-app updater reads
- Every APK signed with one committed key, and verified by CI before it is published

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

Download **`PhairPlay-googletv.apk`** from the rolling [`latest` release](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest) — it is the only APK, built for Google TV (Android TV OS 10+, tested on Android TV OS 14).

Direct link that always points at the newest build:

```
https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk
```

Install it with the *Downloader* app on the TV (enter the link above) or via ADB — see the Sideloading Guide below. Prefer a numbered version? Every `v*` release also ships `PhairPlay-googletv.apk`, so this works too:

```
https://github.com/2archiver/phairplay-archiver-fork-/releases/download/v1.2.0/PhairPlay-googletv.apk
```

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
   derived from the clock so every local build installs over the previous one. Override either
   with `-Pphairplay.versionName=1.2.0 -Pphairplay.versionCode=10200`.

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
