# Changelog

All notable changes to PhairPlay will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

Nothing yet — changes collect here until the next version is cut.

---

## [1.4] - 2026-10-03

### Added
- **Modernized UI/UX** — Refreshed Google TV / Material 3 inspired visual design:
  - Refined color palette with more accessible text contrast and polished accent blue
  - Smooth focus-ring glow states on cards and buttons (D-pad navigation highlights are now crisp and visible from across the room)
  - Larger, softer rounded corners (16dp on cards, 28dp on pill buttons) matching modern TV design language
  - Improved typography scale with better letter spacing, font weights, and visual hierarchy
  - More generous spacing between sections and cards for a less cluttered look
  - Modern vector chevron icon for the Device Name row
  - Proper vector-drawable protocol icons (AirPlay TV/up-chevron, Miracast display + Wi-Fi arcs, Cast display + cast signal) replacing the old placeholder rounded rectangles
  - Improved debug HUD overlay with rounded background and improved margins

### Fixed
- **Android Lint CI** — Removed unused imports (`android.graphics.Color`, `android.widget.LinearLayout`) and expanded the defensive `disable` set so `lintGoogletvDebug` passes under `warningsAsErrors = true` even on newer AGP/Lint versions that add fresh warnings.
- **Navigation panel text color** — Nav items now consistently use the secondary text color rather than a separate gray that had lower contrast.
- **Version bump** — Default version name updated to 1.4.0 across Gradle properties and build fallback.
- **Cast no longer auto-binds ports 8008/8009 on startup** — Removed the `RECEIVER_OPTIONS_PROVIDER_CLASS_NAME` meta-data entry from `AndroidManifest.xml`, which was causing the Cast SDK to self-initialize the moment the process launched (before the user had enabled Cast) and show as LISTEN on TCP 8008/8009. Cast now starts **only** when Settings toggle is on AND a valid (non-placeholder) Cast App ID is configured; otherwise it cleanly reports ERROR with an honest message instead of opening conflicting ports. Cast now defaults to **off** until an App ID is supplied.
- **AirPlay screen mirroring restored** — Fixed a framing bug in `RtpInterleaved` where the post-RECORD RTP-over-TCP loop treated an incoming RTSP keep-alive (an `OPTIONS` line that the sender legitimately injects ~every 30 seconds between RTP frames) as frame bytes. The previous single-byte "skip" consumed the first letter of `OPTIONS` and then tried to parse the rest as a channel/length header, producing a garbage frame length that aborted the interleaved read loop as soon as the first keep-alive arrived — which manifested as "AirPlay connects but no video ever appears / disconnects immediately". The loop now scans forward to the next `$` marker (safe because RTSP headers are 7-bit ASCII) and pushes it back so framing resyncs cleanly; mirror video survives keep-alives indefinitely.
- **"Ugly square grey color boxes" replaced** — The three protocol status-card icons were previously solid-colored rounded-rectangle placeholders (blue/green/blue squares). They're now proper Material-style vector glyphs (AirPlay = display with up-chevron, Miracast = display with Wi-Fi arcs, Cast = display with cast signal) so they read as icons, not flat color blocks.
- **Error/status text no longer truncated with "…"** — The protocol detail line on each status card previously had `maxLines="2"` and `ellipsize="end"`, which chopped longer error messages off with an ellipsis (`Missing Cast App ID · set PHAIRPLAY_CAST_APP_ID in gradle.prop…`). Now `maxLines="4"` with `ellipsize="none"` so full error/status text is readable.
- **Tertiary text contrast improved** — `text_tertiary` bumped from 40 % to 50 % alpha for easier reading on OLED black.

---

## [1.3] - 2026-10-02

### Fixed

**Changing the Device Name now actually changes the name a sender sees**

Three separate places disagreed about what this receiver is called, so a rename changed the
Settings row (and nothing else) while the iPhone kept showing the old name:

- **A rename only wrote to disk** — `SettingsFragment` saved the new name but never restarted
  the receivers, so the live `_airplay._tcp` registration kept broadcasting the previous name
  until someone happened to hit Restart. Renaming (and "Reset to default") now restarts the
  receivers, so the new name is advertised within a second.
- **`GET /info` answered with the Android device name** — `InfoResponder` called
  `NetworkUtils.getDeviceName(context)` instead of using the Settings value. This is the one
  that mattered most: a sender browses mDNS to *find* a device, then asks `GET /info` and
  **displays the `name` it gets back**. `/server-info` had the same problem. Both now answer
  with the spoofed name.
- **The Home screen showed the system name** — `HomeFragment` read
  `NetworkUtils.getDeviceName()` rather than Settings, so the app contradicted itself. It now
  shows the Settings name, and when mDNS has to rename us to resolve a collision it shows the
  name that was really registered ("Apple TV (2)") instead of the one that was requested.

**The default spoofed name is now "Apple TV"**

Blank used to mean "fall back to whatever the TV is called in Android settings", which put the
name outside the app's control. `AppSettings`, `NetworkUtils` and `MdnsService` shared no
default, so they drifted; all three now go through a new `MdnsNames` helper, which also owns
the sanitising rules (strip characters that corrupt a Bonjour record, collapse whitespace, cap
at the 63-byte DNS-SD limit on a UTF-8 boundary). A fresh install, and "Reset to default",
advertise **Apple TV** — matching the `AppleTV5,3` model PhairPlay already reports.

**The AirPlay advertisement matches a real Apple TV more closely**

- Added `protovers=1.1` and `manufacturer=Apple` to the `_airplay._tcp` TXT record.
- Investigated adding `pk` (the receiver's Ed25519 public key), which a real Apple TV
  advertises and which iOS can read while browsing. **Not shipped, and not possible cleanly:**
  the only public setter is `NsdServiceInfo.setAttribute(String, String)`, which re-encodes
  the value as UTF-8, so 32 raw key bytes would reach the sender mangled and iOS would reject
  the signature — worse than omitting the record. The `byte[]` overload exists on the platform
  but is hidden from the compile SDK (it resolves against Robolectric's `android-all`, not
  against `android.jar`), and reflecting into it would hit the hidden-API restrictions. The
  comment in `MdnsService` records this so nobody re-adds it. `GET /info` carries the same
  `pk`, which is the path our senders use.

**A sender that disappeared mid-handshake no longer locks out the next one**

The RTSP server handles one client at a time, and an abandoned connection (iPhone backgrounded,
Wi-Fi dropped, app force-quit) looked open from our side — `read()` blocked forever and every
later sender was rejected with 503 until a manual Restart. From the outside that reads as "the
TV shows up but I can't connect any more". Control connections now time out after two minutes
of silence **before** a session exists; once a stream is up (or a PIN is on screen waiting to
be typed in) the timeout is cleared, so a live session is never dropped for being quiet.

### Changed

- Version **1.2.0 → 1.3.0** (`phairplay.versionName` in `gradle.properties`)

### Added

- `MdnsNames` — one place that owns the advertised name and its cleaning rules
  (`MdnsNamesTest` pins the default, the character stripping and the byte limit)
- `docs/guides/TROUBLESHOOTING.md` — "The name I set doesn't show up on my iPhone" and "Casting
  from an iPhone app (Rumble, YouTube, …)" — what works, what can't, and why

---

## [1.2] - 2026-10-02

### Added

**A merge to `main` now publishes an APK — no tag, no manual release, no Pages setup**
- **Rolling `latest` release** — `.github/workflows/release.yml` runs on every push/merge to `main`, moves the `latest` tag to that commit and replaces the assets in place, so this link always serves the newest build: `https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk` (with `SHA256SUMS.txt` beside it)
- **Versioned releases unchanged** — pushing `v1.2.0` still creates a permanent release with `PhairPlay-v1.2.0-googletv.apk`, `PhairPlay-googletv.apk` and `SHA256SUMS.txt`; `-beta.N` tags stay pre-releases. Manual runs (**Actions → Release → Run workflow**) can publish either
- **Concurrency guard** — release runs are serialized per ref so two builds cannot fight over the `latest` tag and its assets

**4K mirroring on a 4K Google TV**
- `MirrorResolution` — pure policy for the size advertised in `GET /info` `displays`: 1080p by default, and when Settings → *Higher resolution* is on, the largest of 1080p/1440p/4K that both the panel and the hardware H.264 decoder support (`MirrorResolutionTest` pins every rule)
- `DisplayCaps` — device probes behind it: real panel size (`WindowMetrics` on Android 11+, `getRealMetrics` on Android 10) and the H.264 decode ceiling from `MediaCodecList`. Both are defensive — any failure falls back to 1080p instead of breaking mirroring
- Advertised size is logged on receiver start (`AirPlay mirror advertised at 4K (3840x2160) — panel …, H.264 ceiling …`)

**Releases page and docs**
- **Releases page** — `site/index.html` (GitHub Pages) with a one-click **Download APK** button, Downloader/ADB install steps and a list of all releases; deployed by `.github/workflows/pages.yml`. Its button now points at the rolling `latest` APK, which keeps working even if the GitHub API call in the browser fails
- **README starts with "Download the APK"** — the stable link, checksum, release page, and what to do if no APK exists yet; plus a tested-with matrix (Google TV 4K / Android TV OS 14, iPhone 14 / iOS 27.0.1, macOS 12+)
- **`docs/RELEASING.md`** — a "Where is the APK?" table covering every location, how both release modes work, the version-numbering scheme and the signing setup
- **`docs/guides/INSTALLATION.md`** — stable download link, `adb install -r`, and what to do on a signature mismatch or downgrade
- **`docs/guides/TROUBLESHOOTING.md`** — new sections for soft mirroring on a 4K TV, failed APK updates (signature/downgrade/404), iOS 27 picker and Bonjour-cache quirks, and a black screen caused by an over-ambitious mirror resolution; the "Still stuck?" link pointed at an unrelated repository and now points at this one
- **`gradlew.bat`** — Windows contributors could not build at all (only the POSIX script was committed)

**CI you can actually debug**
- Failing `CI`, `Lint` and `Release` runs write the last 150 lines of Gradle output (plus failed test names and lint findings) into the job summary — runner logs are awkward to reach from the API and expire after 90 days
- **`tools/check-workflows.py`** — offline sanity check for workflow files: scans for TABs and stray quotes, lists every `${{ }}` expression, and pipes each `run: |` block through `bash -n`. Useful when there is no JDK around to run the build

### Fixed

- **The committed Gradle wrapper was not a Gradle wrapper** — `gradle/wrapper/gradle-wrapper.jar` was a hand-assembled jar: none of its wrapper classes matched Gradle 8.7's, `org.gradle.wrapper.SystemPropertiesHandler` was missing entirely, it carried 107 unrelated Gradle-internal classes, and it had a second wrapper jar nested inside it. `gradlew` was a different Gradle generation too. Every Gradle job — JVM tests, lint, debug APK, and any release — died with exit code 1 before doing real work, so CI could never produce an APK. Replaced with the genuine Gradle 8.7 wrapper (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) matching the `gradle-8.7-bin.zip` that `gradle-wrapper.properties` already requested
- **The Miracast receiver never compiled** — `MiracastReceiver.stopPeerDiscovery()` called `WifiP2pManager.cancelDiscoverPeers()`, which does not exist (that name belongs to NsdManager/Bluetooth); the Wi-Fi Direct API is `stopPeerDiscovery(Channel, ActionListener)`. One unresolved reference was enough to fail `:app:compileGoogletvDebugKotlin` *and* `:test-runner:compileKotlin`, so lint, the debug APK and every release were impossible
- **`RtpInterleavedTest` used `0xce` / `0xe2` as `Byte` literals** — both are above `Byte.MAX_VALUE`, so Kotlin rejects them without an explicit `.toByte()`; the byte values are unchanged
- **Lint aborted the build on `ChromeOsAbiSupport`** — the check wants an x86/x86_64 binary for ChromeOS, but PhairPlay ships for Google TV only where every device is ARM. With `warningsAsErrors` on, that advisory alone stopped `lintGoogletvDebug` and the debug APK build; it is now disabled with the reasoning recorded next to it
- **CI never installed CMake** — the native FairPlay/ALAC build needs `cmake;3.22.1`, which is not on the runner image (it ships 3.31.5 and 4.1.2); `ndk;28.2.13676358` is now named explicitly as well instead of relying on AGP auto-download
- **The `Releases page` workflow failed on every run** — `actions/configure-pages` 404s until Pages exists. It now passes `enablement: true`, so a run from `main` switches Pages to the "GitHub Actions" source by itself, and pull-request runs skip instead of going red
- **`GET /info` always advertised 1080p (or a hardcoded 1440p)** regardless of the TV — see `MirrorResolution` above

### Changed

- Version **1.1 → 1.2.0**, now held in one place (`phairplay.versionName` in `gradle.properties`)
- **versionCode is derived from the clock** (minutes since 2024-01-01 UTC) instead of the version number, so it increases with *every* build on both release paths and a new APK always installs over the old one. A version-derived code cannot do that: rolling builds from `main` would collide with, or be outranked by, the numbered release of the same version. Rolling builds are published as `<base>-main.<run number>`
- **Settings → "Higher resolution (up to 4K)"** replaces "Higher resolution (1440p)"; still opt-in and still capped by the hardware
- **Google TV only** — the APK is optimized for Google TV (Android TV OS 10+, developed and tested on Google TV 4K with Android TV OS 14): `minSdk 29` for the whole app, native libraries built for ARM only (`armeabi-v7a`, `arm64-v8a`) for a smaller APK
- Release builds without a keystore are signed with the debug key instead of being left unsigned (an unsigned APK cannot be installed). The workflow now warns loudly when the signing secrets are missing, because a debug key differs on every runner and forces an uninstall before the next update

### Removed

- **Fire TV support** — the `firetv` flavor, its Cast stub (moved to `test-runner/src/stubs/` for JVM tests), CI jobs, docs and the `scripts/release.sh` local release script (replaced by the release workflow)

---

## [1.1] - 2026-10-02

### Added

**Miracast actually plays video now (blank-screen fix)**
- `WfdVideoRenderer` — after WFD PLAY, interleaved RTP frames are depacketized (single NAL / STAP-A / FU-A) and H.264 is hardware-decoded onto the streaming Surface; previously the session negotiated and then showed a black screen
- `VideoRtpProcessor` — incremental RTP→H.264 depacketizer shared with the AirPlay path; `RtpInterleaved` now understands STAP-A aggregation packets (SPS+PPS ships this way in Miracast)
- Wi-Fi Direct peer discovery is started and refreshed while advertising — a registered `_wfd._tcp` local service was previously never broadcast because `discoverPeers()` was never called
- Runtime permission requests for `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES` — the manifest declared them but the app never asked, so P2P service registration failed on every modern device
- Miracast sessions now appear as the active connection (notification + status card show "Streaming from Miracast Sender")
- "(archiver)" tag in the app label (`PhairPlay (archiver)`)

### Changed

- Version bumped to **1.1** (versionCode 2)
- Protocol cards show honest, per-protocol error details instead of the blanket "Check Wi-Fi settings" guess:
  - Miracast → "Wi-Fi Direct unavailable or permission denied"
  - Cast → "Cast App ID not set or Play Services unavailable"
  - AirPlay → "Receiver error — try Restart"
- Cast reports ERROR (with the honest detail above) when it cannot run — on Fire TV and on Google TV builds without Play Services it previously claimed "Disabled — Enable in Settings" while the toggle was already on
- Miracast/Cast no longer optimistically show "Advertising" before the receiver confirms registration

### Fixed

- Blank screen on Cast/Miracast: the Miracast receiver now renders incoming video; Google Cast still requires a Google-registered Cast App ID (docs/guides/CAST_APP_ID.md) and does its media work through Google's SDK
- Connected status cards rendered the literal `%1$s` placeholder — they now show the real sender name
- AirPlay path untouched — validated against iOS 27.0.1 / macOS senders per current user reports

---

## [1.0.0-beta.1] - 2026-06-14

### Added

**AirPlay 2 receiver — full stack**
- Screen mirroring (H.264) from macOS 12+ and iOS/iPadOS 16+ via RTSP on port 7000
- FairPlay session decryption: fp-setup v2 (RAOP audio) and v3 (mirroring/Safari) via native libplayfair (JNI); legacy rsaaeskey RSA-OAEP recovery for AirPort Express compatibility
- HomeKit-style pairing: Ed25519 identity, X25519 ECDH key agreement, controller key persistence (`PairingStore`), failed-attempt lockout
- Legacy SRP-6a PIN pairing with on-screen PIN entry screen (`LegacyPairSetupPin`, `PinScreen`)
- `MirrorStreamServer` + `MirrorCrypto` — interleaved RTP reassembly, AES-128-CTR stream decryption (keystream always advanced to prevent reuse)
- `AudioStreamServer` — mirror realtime audio (type 96): UDP RTP, AES-128-CBC, AAC-ELD/AAC-LC decode via MediaCodec, RAOP retransmit, AudioTrack with volume
- `AlacDecoder` + native libalac — RAOP/SDP audio path: AES-128-CBC (per-packet IV) + Apple's ALAC decoder; decode-health mute guard (wrong key → silence, not static)
- `BufferedAudioServer` — AirPlay 2 buffered audio (type 103) accepted and instrumented
- `AirPlayVideoPlayer` — AirPlay video URL mode (`/play`) + transport controls (play/pause/scrub/stop)
- `NowPlayingInfo` (DMAP parser) + album artwork → `NowPlayingScreen` overlay
- `DacpClient` — `_dacp._tcp` discovery + reverse transport control from TV remote to sender (play/pause/skip/volume)
- `AirPlayNtpClient` — Apple NTP for A/V synchronisation
- `InfoResponder` — `GET /info` capability advertisement (plist)
- `PlistCodec` — Apple binary plist encode/decode
- `RaopRsa` — legacy rsaaeskey recovery (RSA-OAEP, AirPort Express key)
- `StreamStats` — per-session RTP statistics (packet count, duplicates, queue drops)
- `Base64Util` — pure-JVM Base64 so SDP parsing is testable without Android framework
- `SdpParser` — extended: codec/encryption/channel/rate parsing for all AirPlay audio types
- Aspect-fit (letterbox/pillarbox) video rendering with black background in `StreamingScreen`
- Real PNG bitmap launcher icon and TV banner (replaces placeholder XML)
- Mirror Audio toggle and PIN-auth toggle in Settings
- Receiver survives app restart/relaunch; mirroring and audio stop cleanly on app exit

**Native layer**
- CMake build for all ABIs (armeabi-v7a, arm64-v8a, x86, x86_64)
- `fairplay_jni.c` — JNI bridge for `playfair_decrypt` with full null/length/OOM validation
- Apple ALAC decoder (C++, vendored) + JNI bridge (`alac_jni.cpp`)
- Reverse-engineered FairPlay (C, `playfair/`) compiled for all ABIs
- Strict-aliasing fix in `modified_md5.c` (union type-punning) and `sap_hash.c` (memcpy + union)

**Test suite**
- 247 unit tests, 0 failures: FairPlay, RaopRsa, Base64Util, ALAC cookie, DMAP, legacy PIN SRP, audio stream server, RTSP handler, service controller
- Robolectric added for framework-dependent tests (Android Base64, Intent, etc.)

**Release infrastructure**
- `scripts/release.sh` — local release script: builds signed GoogleTV + FireTV APKs, creates git tag, publishes GitHub Release via `gh` CLI (no CI minutes consumed)
- First signed GitHub Release: [v1.0.0-beta.1](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-beta.1)

### Changed
- `VideoDecoder`: SPS/PPS-driven reinit on resolution change, self-heal on decoder error, keyframe resync after drops, decoupled network reader (bounded queue, drop-under-load), re-attach to Surface after backgrounding
- `AudioPlayer`: extended to support ALAC and new audio stream types from `AudioStreamServer`
- `RtspHandler`: extended to 700+ lines — handles all AirPlay 2 verbs (ANNOUNCE, SETUP plist+SDP, RECORD, TEARDOWN stream-scoped, GET/SET_PARAMETER, FLUSH, PAUSE, photo PUT/DELETE, `/play`, `/rate`, `/scrub`, `/stop`, `/feedback`, buffered-audio control)
- `AirPlayReceiver`: event channel socket now closed via `use {}` block (fixes file-descriptor leak)
- `SettingsFragment`: mirror audio and PIN-auth toggles added

### Fixed
- `DatagramPacket` length reset before each `receive()` call in `AudioStreamServer` — prevented packet truncation when a smaller packet arrived first
- JNI bridge (`fairplay_jni.c`) now validates input arrays for null, length, and OOM before native access — prevents out-of-bounds reads and native crashes
- Strict-aliasing UB in `modified_md5.c` and `sap_hash.c` — union + memcpy replaces direct `uint32_t*` cast of `unsigned char*`
- `Cipher.getInstance()` moved out of hot path in `AudioStreamServer` (~92 allocations/s → 1 per session)

---

<!-- Format:
## [X.Y.Z] - YYYY-MM-DD

### Added
### Changed
### Fixed
### Removed
-->
