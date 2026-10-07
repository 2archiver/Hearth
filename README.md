<div align="center">

# Hearth
### Your Apple devices. Your Google TV. One cosy connection.

**A free, open-source AirPlay receiver for Google TV and Android TV.**
Mirror your iPhone, iPad or Mac. Share photos. Bring your music to the big screen.

[![Download APK](https://img.shields.io/badge/Download-Android_TV_APK-71334B?style=for-the-badge&logo=android&logoColor=white)](https://github.com/2archiver/Hearth/releases/latest)
[![Support on Ko-fi](https://img.shields.io/badge/Support-Ko--fi-F2A4BB?style=for-the-badge&logo=kofi&logoColor=211017)](https://ko-fi.com/2archiver)

[![CI](https://github.com/2archiver/Hearth/actions/workflows/ci.yml/badge.svg)](https://github.com/2archiver/Hearth/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/2archiver/Hearth?label=latest%20APK&color=71334B)](https://github.com/2archiver/Hearth/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-71334B)](LICENSE)
[![Platform](https://img.shields.io/badge/Android_TV-10%2B-71334B)](#requirements)

[Install](#get-started) · [Features](#made-for-the-living-room) · [Troubleshooting](docs/guides/TROUBLESHOOTING.md) · [What's new](CHANGELOG.md) · [Support](https://ko-fi.com/2archiver)

</div>

## Made for the living room

Hearth turns a Google TV into a local AirPlay receiver, without an Apple TV box, an account,
ads or analytics. It is designed for a TV remote, with clear connection status and the exact
receiver name to select on your Apple device.

| Bring to your TV | What Hearth does |
|---|---|
| **Your screen** | iPhone, iPad and Mac screen mirroring through **Apple Casting**, Hearth's name for AirPlay mirroring |
| **Your music** | AirPlay system audio, track metadata, album artwork and supported remote playback commands |
| **Your photos** | Full-screen photo sharing with the original aspect ratio |
| **AirPlay video** | Supported H.264 streams use hardware decoding; source app/transport compatibility is tested separately |
| **Your preferences** | Receiver name, mirror audio, resolution, startup and in-app updates |

### The wine-red Hearth — 1.9.2

A warm wine-red interface with cream text, a three-step connection guide, clearer D-pad focus,
a quieter Home screen, an explicit **Activity** button, and a landscape Now Playing view.
Settings descriptions grow to fit, and resetting preferences asks for confirmation.

Under the surface: bounded photo/artwork decoding, service observers that clean up on departure,
playback that keeps the TV awake, and validation of AirPlay timing replies. See
[the changelog](CHANGELOG.md) and [upstream review notes](docs/UPSTREAM-REVIEW.md).

## Get started

1. **Install on the TV.** [Open the latest release](https://github.com/2archiver/Hearth/releases/latest)
   and download its version-named Google TV APK asset. Sideload using Downloader or ADB.
2. **Use the same network.** Your Apple device and TV must be on the same local network.
   The TV can use Ethernet while the phone uses Wi-Fi on the same router.
3. **Open Hearth.** On iPhone or iPad, open **Control Centre → Screen Mirroring**. On Mac,
   open **Control Centre → Screen Mirroring**. Select the name shown on Hearth's Home screen.

```bash
adb install -r Hearth-<version>-googletv.apk
```

[Step-by-step installation](docs/guides/INSTALLATION.md) ·
[Apple Casting guide](docs/guides/APPLE_CASTING.md)

**Updating:** choose **Settings → Updates → Check for updates**. Hearth compares build numbers,
verifies downloaded APKs and offers only newer builds. Releases from this repository use the same
signing key so updates can install over an existing version. [Update help](docs/UPDATES.md).

## Requirements

- **Receiver:** Google TV / Android TV 10+ on ARM (`arm64-v8a` or `armeabi-v7a`).
- **Sender:** iPhone/iPad running iOS/iPadOS 16+, or a Mac running macOS 12+.
- **Network:** one local subnet with multicast/mDNS allowed. Ethernet or 5 GHz Wi-Fi is recommended.
- **Internet:** needed for downloading releases and checking updates; local mirroring works without it.

The project's primary reported hardware setup is Google TV 4K over Ethernet on Android TV OS 14,
with an iPhone 14 sender. Please [report your device and results](https://github.com/2archiver/Hearth/issues/new?template=bug_report.md)
to help expand real-device coverage. Software rendering tests do not replace testing on a TV.

## Know before you install

Hearth is community software with ongoing real-device compatibility testing.

- **Protected video** from services such as Netflix, Disney+ and Apple TV+ cannot be decrypted.
- **Native Photos in-video AirPlay and YouTube-app AirPlay video each require separate iPhone-to-TV
  moving-video and continuous-playback checks. Both hardware checks are pending; Control Centre
  audio routing, screen mirroring, still photos, and source-code inspection do not establish them.
  See the 1.9.2 [changelog](CHANGELOG.md).
- **Apple Music in-app protected audio** is not supported; unprotected system audio is a separate path.
- **AirPlay 2 multi-room/buffered audio (type 103)** is not implemented for playback.
- **Google Cast** is handled by your TV's built-in Chromecast receiver. Hearth does not replace it.
- **Miracast** is not included. Apple Casting here means AirPlay screen mirroring.
- **No PIN authentication:** devices on the same network can connect. Use a trusted home network.
- **4K depends on hardware and network quality.** Switch off higher resolution if your TV drops frames.

[Why no Google Cast?](docs/guides/CAST.md) · [Connection troubleshooting](docs/guides/TROUBLESHOOTING.md)

## Build and contribute

Use JDK 17+, Android SDK 35, NDK `28.2.13676358` and CMake `3.22.1`.

```bash
git clone https://github.com/2archiver/Hearth.git
cd Hearth
./gradlew :test-runner:test
./gradlew :app:lintGoogletvDebug :app:assembleGoogletvDebug
./gradlew :app:testGoogletvDebugUnitTest --tests 'com.phairplay.ui.HearthUiTest'
```

The UI test renders the actual Android views with sample data into
`app/build/reports/hearth-ui/`. Build a signed release with `./gradlew :app:assembleGoogletvRelease`.

Good contributions include device compatibility reports, accessibility testing with a TV remote,
translations, and reproducible protocol fixes. Read [CONTRIBUTING](docs/CONTRIBUTING.md),
[architecture](docs/ARCHITECTURE.md) and [release instructions](docs/RELEASING.md) first.

## Help Hearth grow

If Hearth is useful to you, **star the repository**, share the download link with someone who
uses a Google TV, or contribute a device report. Clear reports and real-world testing help the
project improve.

**[Support development on Ko-fi → ko-fi.com/2archiver](https://ko-fi.com/2archiver)**
Donations are optional. Hearth stays free and open source.

## Acknowledgments

Hearth builds on a community of open-source AirPlay work:

- [PhairPlay](https://github.com/mazer666/PhairPlay) — the original Android TV receiver this project forks.
- [UxPlay](https://github.com/FDH2/UxPlay) — a reference for discovery, timing, mirroring, session handling,
  metadata and audio behavior. [Review notes and scope](docs/UPSTREAM-REVIEW.md).
- [RPiPlay](https://github.com/FD-/RPiPlay) — a reference for pairing, FairPlay, mirroring and audio.
- [AirPlay specification](https://github.com/openairplay/airplay-spec) — community protocol documentation.

Hearth's application code is licensed under [Apache 2.0](LICENSE). Bundled third-party components
retain their own notices and licenses. Hearth is an independent project and is not affiliated
with Apple or Google. AirPlay and the other product names belong to their respective owners.
