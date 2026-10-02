# Installation Guide

This guide covers every way to install PhairPlay on your Google TV (Android TV OS 10+, tested on Android 14).

---

## Prerequisites

- A Google TV device (see [supported devices](../spec/REQUIREMENTS.md))
- A computer (Windows, macOS, or Linux) with ADB installed — OR — a direct APK sideload method
- Both devices on the same Wi-Fi network

---

## Method 1: ADB (Recommended for developers)

### Step 1: Enable ADB on your TV

**Google TV (Chromecast with Google TV, Google TV Streamer, TVs with Google TV):**
1. Settings → System → About → Android TV OS Build → click 7 times
2. Settings → System → Developer Options → USB debugging → ON


### Step 2: Find your TV's IP address

**Google TV:** Settings → Network & Internet → your Wi-Fi → scroll down to see IP

### Step 3: Connect ADB

```bash
adb connect <TV-IP-ADDRESS>:5555
# Example: adb connect 192.168.1.42:5555
```

Confirm the connection prompt that appears on your TV.

### Step 4: Install

```bash
adb install -r PhairPlay-googletv.apk
```

### Step 5: Launch

Find **PhairPlay** in your app list and launch it.

---

## Method 2: Downloader app (no computer needed)

Use the **Downloader** app (free, from the Google Play Store) to fetch the APK straight onto your TV.

1. Install **Downloader** from the Google Play Store on your TV
2. Settings → Apps → Security & restrictions → **Install unknown apps** → allow **Downloader**
3. Open Downloader and enter:
   `https://github.com/2archiver/phairplay-archiver-fork-/releases/latest/download/PhairPlay-googletv.apk`
4. Choose **Install**, then **Open**

---

## Method 3: Build from Source

```bash
git clone https://github.com/2archiver/phairplay-archiver-fork-.git
cd phairplay-archiver-fork-

# Build for Google TV
./gradlew assembleGoogletvRelease

# Build for Google TV with a registered Cast App ID
./gradlew assembleGoogletvRelease -Pphairplay.castAppId=<APP_ID>
```

The APK is in `app/build/outputs/apk/`.

Google Cast requires a registered Cast App ID for real testing. See
[Google Cast App ID](CAST_APP_ID.md) before testing Cast on Google TV.

---

## After Installation

1. Launch PhairPlay — the **HomeScreen** appears showing three service cards
2. All services (AirPlay, Miracast, Cast) are enabled by default
3. On your Mac: click the AirPlay icon → select your TV
4. On Windows: Settings → Display → Connect to wireless display → select your TV
5. In Chrome: Menu → Cast → select your TV

See [Troubleshooting](TROUBLESHOOTING.md) if the device doesn't appear in your sender's list.
