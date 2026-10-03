# PhairPlay

PhairPlay is a free, open-source, ad-free AirPlay 2 receiver for Google TV. It lets your macOS or iOS/iPadOS device mirror its screen and audio directly to your TV — no Apple TV required.

---

## Download the latest APK

**[Download the latest release](https://github.com/2archiver/phairplay-archiver-fork-/releases/latest)**

Every release carries **one file**: the Google TV APK, named after its version —
`PhairPlay-1.6.0-main.43-googletv.apk`. `releases/latest` always redirects to the newest successful
build from `main`, and because there is a single asset there is nothing to choose and nothing to
grab by mistake. Its SHA-256 and `versionCode` are printed in the release notes, which is also what
the in-app updater verifies against.

- [Current release, notes and checksum](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest)
- Runs on Google TV / Android TV OS 10+ — **tested on a Google TV 4K on Ethernet, Android TV OS 14**
- Optional [download page](https://2archiver.github.io/phairplay-archiver-fork-/) — works without Pages

**Install:** open the release link above, copy the APK's URL (or open the [download page](https://2archiver.github.io/phairplay-archiver-fork-/) from the TV), paste it into *Downloader* on the TV and choose **Install**. From a computer, use `adb install -r PhairPlay-<version>-googletv.apk`. See the [installation guide](docs/guides/INSTALLATION.md).

**Update:** on the TV, choose **Settings → Updates → Check for updates**. PhairPlay reads the latest release in one request, checks the download against the SHA-256 published in that release's notes, and offers to install it. Builds signed with this repository's key install over the existing app; older or differently signed builds may need a one-time reinstall. See [keeping PhairPlay up to date](docs/UPDATES.md).

**Help:** [Troubleshooting](docs/guides/TROUBLESHOOTING.md) · [Why there is no Google Cast receiver](docs/guides/CAST.md) · [Miracast](docs/guides/MIRACAST.md).

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

PhairPlay's AirPlay 2 receiver includes mDNS advertising (with the multicast lock and re-advertising
a wired TV needs), RTSP, pairing, FairPlay key handling, H.264 mirroring up to 4K, AAC/ALAC audio,
NTP A/V sync, and DACP remote control. Real-device validation with macOS and iOS senders is ongoing.
Miracast is opt-in and limited by what each TV's Wi-Fi Direct stack allows — see the
[Miracast guide](docs/guides/MIRACAST.md). PhairPlay is *not* a Google Cast receiver, and
[CAST.md](docs/guides/CAST.md) explains why that is the right trade on a Google TV.

Earlier releases added the spoofed device name and iOS discovery fixes (v1.3), automatic APK updates and the release pipeline (v1.2), and Miracast video playback (v1.1). The [changelog](CHANGELOG.md) has the full details.

### Tested with

| Role | Device | Notes |
|------|--------|-------|
| Receiver | **Google TV 4K over Ethernet (wired)**, Android TV OS 14 (API 34) | **Primary target.** AirPlay discovery, mirroring and 4K advertisement verified on a wired set: mDNS runs over Ethernet, the multicast lock is held while advertising, and the Home card shows `Advertising on Ethernet · <IP>` |
| Receiver | **Google TV 4K on Wi-Fi**, Android TV OS 14 | Same build; 5 GHz strongly preferred. Miracast needs Wi-Fi Direct and is reported as unavailable on sets that keep it to themselves |
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
- No Google Cast receiver, by design — the TV's built-in Chromecast owns ports 8008/8009 and `_googlecast._tcp`, so PhairPlay binds nothing and advertises nothing for Cast ([why](docs/guides/CAST.md))
- Network summary on the Home screen: which interface and IP PhairPlay is advertising on (`Ethernet · 192.168.1.42`)
- In-app update checker and self-updater with SHA-256 and signing-certificate verification
- Zero ads, zero analytics, zero internet required
- Open source — Apache 2.0 license

### Releases
- One current [`latest` release](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest), updated from `main`
- Release title and notes identify the version and changes in the APK; `releases/latest` is the stable link
- Exactly one asset per release — the version-named APK. Version code and SHA-256 live in the release notes, so the updater needs one HTTPS request and can still verify what it downloaded
- Every APK is signed with the community key and verified by CI before publishing
- Older releases remain available as history; new builds do not create competing release entries

## What PhairPlay Does NOT Do

- **FairPlay DRM content** (Netflix, Disney+, Apple TV+) — Apple DRM; not decryptable by any open-source receiver
- **Apple Music in-app audio** — protected on every AirPlay path; use system audio output instead
- **Buffered audio playback** (AirPlay 2 type 103) — accepted but not played back yet
- **Cloud/remote streaming** — local network only
- **Miracast audio playback** — v1.1 renders Miracast video; WFD audio is negotiated but not played yet
- **Google Cast / appearing in another app's cast button** — that button searches for the TV's built-in Chromecast receiver, which permanently owns 8008/8009 and `_googlecast._tcp`. PhairPlay does not compete with it; use iOS **Screen Mirroring** (Control Centre) instead. See [Troubleshooting → Casting from an iPhone app](docs/guides/TROUBLESHOOTING.md#casting-from-an-iphone-app-rumble-youtube-) and [why there is no Cast receiver](docs/guides/CAST.md)

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

Open the [current release](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest) and download its single file: **`PhairPlay-<version>-googletv.apk`** (e.g. `PhairPlay-1.6.0-main.43-googletv.apk`). Built for Google TV — Android TV OS 10+, tested on a Google TV 4K on Ethernet running Android TV OS 14.

The stable link to that page is:

```
https://github.com/2archiver/phairplay-archiver-fork-/releases/latest
```

The file is named after its version, so the release carries one unambiguous asset instead of an
APK plus two side files that could be downloaded by mistake. Copy the asset's URL from the release
page when a tool needs a direct `.apk` link (Downloader on the TV does exactly that), or use the
[download page](https://2archiver.github.io/phairplay-archiver-fork-/), which links the current one
for you.

Install it with the *Downloader* app on the TV or with ADB — see the Sideloading Guide below. The title on the release page shows the version embedded in the APK, and the notes explain what's changed and give the file's SHA-256.

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
3. Open Downloader, enter the APK's direct link from the release page (or use the [download page](https://2archiver.github.io/phairplay-archiver-fork-/)), then choose **Install**.

### Google TV with ADB (e.g., Chromecast with Google TV, Google TV Streamer 4K, Android TV OS 14)

1. Go to **Settings → System → About → Android TV OS build** and click it 7 times to enable Developer Options.
2. Go to **Settings → System → Developer Options** and enable **USB debugging** (Android TV OS 14 also offers **Wireless debugging**).
3. Note your TV's IP address from **Settings → Network & Internet**.
4. On your Mac/PC, run:
   ```bash
   adb connect <TV-IP>
   adb install -r PhairPlay-<version>-googletv.apk
   ```
5. Launch PhairPlay from your app list.

If Android refuses the update with `INSTALL_FAILED_VERSION_DOWNGRADE` or a signature mismatch, run `adb uninstall com.phairplay.googletv` once and install again.

---

## How to Use

1. Launch PhairPlay on your TV. You will see the Waiting Screen with your TV's name.
2. **iPhone/iPad:** open Control Centre → **Screen Mirroring** → select your TV. **Mac:** click the **AirPlay** icon in the menu bar (or **System Settings → Displays → AirPlay Display**).
3. Select your TV from the list (it should appear as your TV's name).
4. Your screen appears on the TV. Portrait phone streams are aspect-fitted, not stretched.
5. On a 4K Google TV, **Settings → Higher resolution (up to 4K)** is on by default, so the mirror runs at the panel's native size (capped by what the decoder accepts). Turn it off if a marginal TV drops frames; it restarts the receiver when you change it.
6. To stop: turn off Screen Mirroring/AirPlay on the sender, or quit PhairPlay on the TV.

---

## Known Limitations

- **Beta software** — the AirPlay 2 stack is complete but real-device validation with various macOS/iOS senders is ongoing. Please report issues.
- **4K mirroring is opt-in and hardware-dependent.** PhairPlay only advertises 4K when the panel reports 4K *and* the H.264 decoder says it supports 3840×2160; a 4K advertisement still costs real decode work, so keep it off if frames drop.
- **Apple Music in-app audio is not decryptable.** macOS protects it with FairPlay on every AirPlay path. Route the Mac's system audio output instead (works fine).
- **FairPlay-protected video** (Netflix, Disney+, Apple TV+) cannot be mirrored — this is Apple's DRM, not a PhairPlay limitation.
- **Buffered audio (AirPlay 2 type 103)** is accepted but not yet played back.
- **Google Cast** is out of scope: a Google TV's built-in Chromecast owns the Cast ports and
  service record, so PhairPlay neither binds 8008/8009 nor advertises `_googlecast._tcp`. Use the
  TV's own Cast for apps that only offer a cast button, and AirPlay for mirroring.
- **Miracast** — video decodes and renders (H.264 → hardware); audio playback and discovery both
  depend on the TV's Wi-Fi Direct stack, which most Google TVs keep for themselves. Where it is
  refused, the Miracast card says so as *Unavailable* with the reason instead of an error.
- **A wired TV cannot receive Miracast at all** — Wi-Fi Direct needs the Wi-Fi radio, so on a TV on
  Ethernet the card says exactly that. AirPlay over the cable is unaffected.
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
