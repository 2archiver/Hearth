# Hearth – Requirements

> **Note (1.6.1):** FR-02 and §1.3 (Miracast) are historical. The Miracast receiver and its
> Wi-Fi Direct permissions were removed in 1.6.1 — most Google TVs refuse Wi-Fi Direct to apps.
> Apple Casting (AirPlay screen mirroring) replaced it; see docs/guides/APPLE_CASTING.md.

Version: 2.2
Status: Active
Date: 2026-03-23

---

## 1. Functional Requirements

### 1.1 Service Discovery & Advertising

- FR-01: The app MUST advertise an AirPlay 2 receiver via mDNS (`_airplay._tcp`, `_raop._tcp`).
- FR-02: The app MUST advertise a Miracast receiver via Wi-Fi Direct (P2P) service discovery.
- FR-03: The app MUST optionally advertise a Google Cast receiver (if Cast SDK is registered).
- FR-04: All three services can be enabled/disabled independently in Settings.
- FR-05: The device name shown in sender pickers MUST be configurable in Settings (defaults to Android device name).
- FR-06: All services start advertising within 2 seconds of app or service launch.
- FR-07: All services stop advertising within 5 seconds of being disabled or app close.

### 1.2 AirPlay 2 Receiver

- FR-08: Accept Screen Mirroring connections from **macOS 12+** and **iOS/iPadOS 13+** via RTSP on TCP port 7000.
- FR-09: Complete the RTSP handshake (OPTIONS → SETUP → ANNOUNCE → RECORD) without errors.
- FR-10: Reject second concurrent AirPlay connection with RTSP 503.
- FR-11: Auto-reconnect and resume advertising after connection loss.
- FR-12: Accept **audio-only AirPlay** streams (music, podcasts) from macOS and iOS senders, playing audio through the TV speakers without requiring a video stream.

#### AirPlay 2 Codec Requirements

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video (Mandatory) | H.264 AVC — up to High Profile Level 5.2 | **Required** | Hardware MediaCodec decode |
| Video (Optional / 4K) | H.265 HEVC | Optional | From Apple TV 4K baseline; hardware check required |
| Audio (Mandatory – Mirroring) | AAC-LC or LPCM (depending on latency requirements) | **Required** | AAC-LC for low bandwidth, LPCM for minimal latency |
| Audio (Mandatory – Music) | ALAC (Apple Lossless) 16-bit / 44.1 kHz | **Required** | Default for music/podcast streaming |
| Audio (Surround – Optional) | Dolby Atmos (E-AC3 with JOC), AC-3 | Optional | Only on capable hardware |
| Container | MP4, MOV, M4V; HLS (HTTP Live Streaming) | **Required** | As used by AirPlay media sessions |
| Image / Photo | JPEG, PNG (via `/photo` HTTP endpoint) | **Required** | See FR-42 |
| Max Resolution | Up to 4K UHD @ 60fps (HDR10, Dolby Vision) | Optional | 1080p @ 60fps is mandatory baseline |
| DRM | FairPlay (evaluation only — see §3) | Not in v1 | Requires Apple licensing |

- FR-42: Accept **AirPlay Photo / Image** transfers sent by iOS/macOS (via the `/photo` HTTP endpoint). Display the received image full-screen. Support JPEG and PNG. Return to HomeScreen on connection close. (Requires AirPlay feature bit 1 `Photo` to be set in the `features` TXT record.)

### 1.3 Miracast Receiver

- FR-13: Discover and accept incoming Miracast (Wi-Fi Display / WFD) connection requests.
- FR-14: Negotiate the WFD session (capability exchange, RTSP-WFD handshake).
- FR-15: Decode and display the incoming video stream from the Miracast sender per the codec matrix below.
- FR-16: Play audio from the Miracast stream per the codec matrix below.
- FR-17: Support Windows 10+, Android, and Samsung Galaxy as Miracast senders (Phase 3+).
- FR-18: Show the Miracast connection state on the HomeScreen status card.

#### Miracast (WFD) Codec Requirements

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video (Mandatory) | H.264 AVC — Constrained High Profile (CHP) and Constrained Baseline Profile (CBP), up to Level 4.2 | **Required** | WFD spec primary codec |
| Video (Optional / 4K) | H.265 HEVC | Optional | Newer senders (Windows 11 / Android 10+) |
| Audio (Mandatory) | LPCM 16-bit / 48 kHz (mono, stereo) | **Required** | WFD baseline audio |
| Audio (Optional) | AAC-LC, AAC-HE | Optional | More efficient than LPCM |
| Audio (Surround – Optional) | AC-3 (Dolby Digital) | Optional | When sender/display supports it |
| Container | MPEG-TS (MPEG Transport Stream) | **Required** | Standard WFD stream encapsulation |
| Max Resolution | 1080p @ 60fps | **Required** | Mandatory WFD baseline |
| Max Resolution (Optional) | 4K UHD @ 60fps | Optional | Only on hardware that supports it |
| DRM / Copy Protection | HDCP 2.x (hardware-based link protection) | **Required** | Negotiated during WFD setup |

### 1.4 Google Cast Receiver

- FR-19: Run as a Cast Custom Receiver application registered with the Google Cast SDK.
- FR-20: Accept Cast connections from Chrome/Android/macOS/iOS senders.
- FR-21: Support Cast screen mirroring from Chrome and Android senders.
- FR-22: Display Cast status on the HomeScreen status card.
- FR-23: Gracefully degrade if the Cast SDK is unavailable (missing Google Play Services on Fire TV).

#### Google Cast Codec Requirements

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video (Mandatory) | H.264 AVC (Baseline, Main, High Profile); VP8 | **Required** | Cast baseline codecs |
| Video (Optional / 4K) | H.265 HEVC, VP9, AV1 | Optional | Newer hardware (Android TV API 31+ for AV1) |
| Audio (Mandatory) | AAC-LC, AAC-HE, MP3, WAV (LPCM) | **Required** | Cast baseline audio |
| Audio (Surround – Optional) | Dolby Digital Plus (E-AC3), Dolby Atmos, Opus, FLAC | Optional | High-quality audio on capable devices |
| Container | MP4, WebM | **Required** | Native Cast containers |
| Container (Adaptive) | DASH (Dynamic Adaptive Streaming over HTTP), HLS | **Required** | For adaptive bitrate streaming |
| Max Resolution | Up to 4K UHD @ 60fps (HDR10+) | Optional | 1080p mandatory, 4K hardware-dependent |
| DRM | Widevine L1 / L3, PlayReady | **Required** | For DRM-protected content streams |

### 1.5 Service Control

- FR-24: The user MUST be able to **start**, **stop**, and **restart** all receiver services from the app UI.
- FR-25: The receiver runs as an Android **ForegroundService** so it continues operating when the app is in the background (e.g., screensaver active).
- FR-26: A **persistent notification** MUST be shown while the ForegroundService is running, with quick-action buttons: Stop, Restart.
- FR-27: Service state (running/stopped/error) MUST be visible on the HomeScreen at all times.
- FR-28: Stopping the service MUST release all network ports, close all connections, and stop advertising.
- FR-29: Restarting the service MUST perform a clean stop followed by a clean start within 3 seconds.

### 1.6 Settings

- FR-30: A dedicated **Settings screen** MUST be accessible from the HomeScreen.
- FR-31: Settings MUST be persisted between app restarts (Android DataStore / SharedPreferences).
- FR-32: The following settings MUST be configurable:

| Setting | Default | Description |
|---|---|---|
| Display name | Android device name | Name shown in sender pickers |
| AirPlay enabled | ON | Enable/disable AirPlay service |
| Miracast enabled | ON | Enable/disable Miracast service |
| Cast enabled | ON | Enable/disable Cast service |
| AirPlay PIN auth | OFF | Require PIN for AirPlay connections |
| Start on boot | OFF | Auto-start service on device boot |
| Show debug overlay | OFF | Show FPS / latency overlay (dev) |

### 1.7 Video / Audio

- FR-33: Decode video using hardware `MediaCodec`. Supported codecs per protocol:
  - **AirPlay**: H.264 AVC (mandatory, up to High Profile Level 5.2); H.265 HEVC (optional, hardware check required).
  - **Miracast**: H.264 AVC CHP/CBP (mandatory); H.265 HEVC (optional).
  - **Google Cast**: H.264 AVC + VP8 (mandatory); H.265 HEVC, VP9, AV1 (optional, API-level gated).
  - Software fallback is NOT used; if hardware decoder is unavailable, the stream is rejected gracefully.
- FR-34: Display decoded video full-screen maintaining aspect ratio.
- FR-35: Play audio in sync with video (A/V drift ≤ 40ms).
- FR-36: For **audio-only** streams (no video): play through the default audio output without launching the streaming screen.
- FR-37: Return to HomeScreen (or remain on HomeScreen for audio-only) when stream ends.
- FR-43: For **image / photo** transfers (AirPlay `/photo` endpoint): display received JPEG or PNG full-screen. No video decoder is involved; image is decoded via Android's `BitmapFactory`. Return to HomeScreen when sender disconnects.

### 1.8 Internationalization (i18n)

- FR-38: All user-visible strings MUST be defined in Android resource files (never hardcoded in Kotlin/XML).
- FR-39: The app MUST support at least **English** and **German** at launch.
- FR-40: The i18n structure MUST allow adding new languages without changing Kotlin or layout files.
- FR-41: Date/time formats, number formats, and text direction MUST respect the device locale.

---

## 2. Non-Functional Requirements

### 2.1 Performance (unchanged)
- NFR-01: Video latency ≤ 100ms on local 5 GHz Wi-Fi.
- NFR-02: Frame rate ≥ 25fps sustained.
- NFR-03: Peak RAM ≤ 150 MB (single active stream).
- NFR-04: Average CPU ≤ 30% on mid-range TV SoC.

### 2.2 UI / UX
- NFR-05: UI design MUST follow the **Google TV Streamer** design language: dark background, card-based layout, large focus indicators, D-pad navigable.
- NFR-06: All interactive elements MUST be focusable via D-pad / remote control.
- NFR-07: Minimum touch target and focus target size: 48dp.
- NFR-08: Text minimum size: 18sp for body, 32sp for titles.
- NFR-09: Sufficient contrast ratio for TV viewing from 3 meters.
- NFR-10: The HomeScreen MUST show the active protocol, sender name, and duration within 1 second of change.

### 2.3 Privacy & Security (unchanged)
- NFR-11: No ads, no analytics, no telemetry.
- NFR-12: All network input validated before processing.
- NFR-13: No hardcoded secrets.
- NFR-14: Minimal Android permissions.

### 2.4 Compatibility
- NFR-15: Google TV: Android 10+ (API 29+), ARMv8.
- NFR-16: Fire TV: Android 7.1+ (API 25+), ARMv7/ARMv8.
- NFR-17: AirPlay sender (screen mirroring): macOS 12+, iOS/iPadOS 13+.
- NFR-18: AirPlay sender (audio-only): macOS 12+, iOS/iPadOS 13+.
- NFR-19: Miracast sender: Windows 10+, Android 4.2+.
- NFR-20: Cast sender: Chrome 72+, Android 5+.

### 2.5 Code Quality
- NFR-23: No source file exceeds 400 lines.
- NFR-24: Every public method has at least one unit test.
- NFR-25: All classes have KDoc headers.

---

## 3. Explicitly Excluded / Deferred Features

| Feature | Reason | Status |
|---|---|---|
| FairPlay DRM (streaming content) | See evaluation note below | Not in v1; v2 research item |
| HomeKit / HAP pairing | Complex separate protocol | v3 roadmap |
| WiDi (Intel) | EOL technology | Not planned |
| DLNA / UPnP | Out of scope | Not planned |
| Cloud / remote streaming | Security risk | Not planned |
| Screen recording to file | Privacy concern | Not planned |
| H.265 / HEVC decode (AirPlay) | Optional, hardware-gated | v2 optional (API check required) |
| H.265 / HEVC decode (Miracast) | Optional, hardware-gated | v2 optional |
| VP9 / AV1 decode (Cast) | Optional, API level gated (API 31+ for AV1) | v2 optional |
| Dolby Atmos / AC-3 decode | Optional surround audio | v2 optional |
| AirPlay audio grouping (multi-room) | Requires full AirPlay 2 stack | v3 roadmap |
| 4K UHD streams (AirPlay, Miracast) | Optional, hardware-dependent | v2 optional |
| HDR10 / Dolby Vision (AirPlay) | Optional, API 31+ required | v2 optional |

### FairPlay DRM – Evaluation Note

**What is FairPlay?** Apple FairPlay Streaming (FPS) is Apple's DRM system for protecting HLS content. It is used by Apple TV+, iTunes, and other Apple-licensed content platforms.

**Technical path to implementation:**
1. Register as an Apple Developer (active program membership required).
2. Apply to Apple for an **FPS deployment package** (Key Security Module — KSM). Apple approves or denies based on use case.
3. Implement the FPS key exchange protocol: the receiver must communicate with a license server using Apple's proprietary key exchange, using the provided KSM library (binary-only, Apple-supplied).
4. The KSM binary is platform-specific — Apple provides it for macOS/iOS/tvOS. An Android implementation would require Apple to supply an Android-compatible KSM, which Apple has never done publicly.

**Conclusion for Hearth v1:**
- FairPlay is **not implementable** on Android without Apple providing an Android KSM binary — which they have not done.
- Even if Apple provided one, the license terms would likely be **incompatible with open-source distribution** under Apache 2.0.
- **FairPlay is excluded from Hearth v1 and is not on the roadmap** unless Apple changes their licensing policy.
- Hearth can receive unencrypted AirPlay streams and AirPlay streams encrypted with the open AES-128-CTR session key mechanism. FairPlay-protected premium content (e.g., Apple TV+) will not play.

> **Note on open codecs:** H.265 HEVC, VP9, and AV1 are fully implementable on Android via `MediaCodec` with hardware support checks. These are planned as optional features in v2 behind `MediaCodecInfo.CodecCapabilities` capability queries at runtime.

> **Note on HDCP and Widevine/PlayReady:** HDCP (for Miracast) is negotiated at the WFD protocol level and enforced by hardware — no software implementation is needed. Widevine and PlayReady (for Cast) are handled by the Google Cast SDK and the Android DRM framework (`MediaDrm`) — the app does not need to implement DRM logic directly.

---

## 4. Target Devices & Senders

### Receivers (this app)
| Platform | Min OS | API | Arch |
|---|---|---|---|
| Google TV (Chromecast with Google TV) | Android 10 | 29 | ARMv8 |
| Amazon Fire TV Stick 4K | Android 7.1 | 25 | ARMv8 |
| Amazon Fire TV Stick 3rd gen | Android 7.1 | 25 | ARMv7/ARMv8 |

### Senders (clients)
| Protocol | Platform | Version | Notes |
|---|---|---|---|
| AirPlay 2 | macOS | 12 (Monterey)+ | Screen mirroring + audio-only |
| AirPlay 2 | iOS / iPadOS | 13+ | Screen mirroring + audio-only |
| Miracast | Windows | 10+ | Screen mirroring |
| Miracast | Android | 4.2+ | Screen mirroring |
| Google Cast | Chrome browser | 72+ | Tab/screen cast |
| Google Cast | Android | 5+ | Screen cast |
