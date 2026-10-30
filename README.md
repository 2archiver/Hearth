<div align="center">

<img src="docs/branding/hearth-logo.svg" width="600" alt="Hearth — Signal Flame logo" />

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

[Install](#get-started) · [Features](#made-for-the-living-room) · [Compatibility](#tested-app-compatibility) · [Troubleshooting](docs/guides/TROUBLESHOOTING.md) · [What's new](CHANGELOG.md) · [Support](https://ko-fi.com/2archiver)

</div>

## Made for the living room

Mirror your Apple screen, share photos and play system audio on your TV.
Free, open source, ad-free and built for your remote.

- **Screen mirroring:** bring your iPhone, iPad or Mac display to Google TV or Android TV.
- **Video casting:** play supported videos from Safari, YouTube and Rumble through AirPlay.
- **Music casting:** listen to Spotify through AirPlay while Hearth stays connected.
- **TV controls:** navigate Hearth with your TV remote and update from the app.

## Get started

1. [Download the latest APK](https://github.com/2archiver/Hearth/releases/latest) and sideload it on your TV.
2. Connect your TV and Apple device to the same network.
3. Start the receiver in Hearth, then choose how to connect:

   - **Mirror your screen:** open **Control Centre → Screen Mirroring** and select the name shown in Hearth.
   - **Cast video:** use the video player’s **AirPlay** option in a supported app or website and select Hearth’s receiver name.
   - **Cast Spotify audio:** open the device picker in Spotify, choose the **AirPlay** option and select Hearth’s receiver name.

Update through **Settings → Updates → Check for updates**.
[Install help](docs/guides/INSTALLATION.md) · [Troubleshooting](docs/guides/TROUBLESHOOTING.md) · [Changelog](CHANGELOG.md)

## Requirements

Google TV / Android TV 10+ (ARM), iOS/iPadOS 16+ or macOS 12+, and a shared local network.
Ethernet or 5 GHz Wi-Fi is recommended.

## Tested app compatibility

The following apps were **confirmed working by the maintainer on Hearth 3.1**, using an **iPhone 14 running iOS 27.2**, with Hearth installed on **Google TV (Android 14 / API 34)**. Latest confirmation: **30 October 2026**.

| App / source | Confirmed on 3.1 | Test notes |
| --- | --- | --- |
| **Safari** | AirPlay video casting | Playback confirmed working. |
| **YouTube** | AirPlay video casting | Playback confirmed working. |
| **Spotify** | AirPlay audio casting | Clear audio, stable connection and artwork without observed flicker. |
| **Rumble** | AirPlay video casting | Playback confirmed working. |

These are real-device test results for the setup above; other devices, app versions and individual media sources may behave differently. Use **AirPlay** when testing Hearth: Google Cast and Spotify Connect use separate playback routes.

### Using other apps while casting music

During the Spotify test, opening TikTok stopped Spotify’s audio while the Hearth casting session stayed connected. Instagram audio was also heard on the TV during Spotify casting. Hearth does not currently provide an option to send only Spotify to the TV while keeping other apps’ audio on the iPhone.

For this Spotify use case, you can instead use the TV’s Spotify app with **Spotify Connect**, which plays music on the TV independently of Hearth’s AirPlay session.

## Good to know

- Protected video and Apple Music in-app audio are unsupported. Native Photos video casting still needs device validation.
- Google Cast uses your TV’s built-in receiver. Miracast and AirPlay multi-room audio are not included.
- Use a trusted home network: Hearth has no PIN authentication. If 4K stutters, lower the resolution.

## Contribute & credits

[Acknowledgements](ACKNOWLEDGEMENTS.md) · [Build and contribute](docs/CONTRIBUTING.md) · [Architecture](docs/ARCHITECTURE.md) · [Support on Ko-fi](https://ko-fi.com/2archiver)

Forked from [PhairPlay](https://github.com/mazer666/PhairPlay), with references from
[UxPlay](https://github.com/FDH2/UxPlay), [RPiPlay](https://github.com/FD-/RPiPlay) and the
[AirPlay specification](https://github.com/openairplay/airplay-spec).

[Apache 2.0](LICENSE); third-party notices apply. Not affiliated with Apple or Google.
