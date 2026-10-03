# Installation Guide

This guide covers every way to install PhairPlay on your Google TV (Android TV OS 10+, tested on
Google TV 4K running Android TV OS 14).

**The APK, always the newest build:**

```
https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk
```

It is rebuilt on every merge to `main` — no tag hunting. See [docs/RELEASING.md](../RELEASING.md)
for every other place the APK can be found.

**Updating:** once installed on this repository's community signing key, future APKs install
straight over the old one. Older 1.4 builds or APKs signed by another source may need a one-time
uninstall/reinstall; PhairPlay checks for this and explains the steps. Or skip the computer for
same-key updates: **Settings → Updates → Check for updates** on the TV. See
[docs/UPDATES.md](../UPDATES.md).

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

If adb reports `INSTALL_FAILED_VERSION_DOWNGRADE` or a signature mismatch, the previous install was
signed with a different key:

```bash
adb uninstall com.phairplay.googletv
adb install PhairPlay-googletv.apk
```

### Step 5: Launch

Find **PhairPlay** in your app list and launch it.

---

## Method 2: Downloader app (no computer needed)

Use the **Downloader** app (free, from the Google Play Store) to fetch the APK straight onto your TV.

1. Install **Downloader** from the Google Play Store on your TV
2. Settings → Apps → Security & restrictions → **Install unknown apps** → allow **Downloader**
3. Open Downloader and enter:
   `https://github.com/2archiver/phairplay-archiver-fork-/releases/download/latest/PhairPlay-googletv.apk`
   (the [download page](https://2archiver.github.io/phairplay-archiver-fork-/) works too, if Pages is enabled)
4. Choose **Install**, then **Open**

Downloader keeps the file name `PhairPlay-googletv.apk`, so re-downloading a newer build and
choosing **Install** updates the app in place — provided both builds are signed with the same key.
If the TV refuses, uninstall PhairPlay once (Settings → Apps → PhairPlay → Uninstall) and install
again.

---

## Method 3: Build from Source

```bash
git clone https://github.com/2archiver/phairplay-archiver-fork-.git
cd phairplay-archiver-fork-

# Build for Google TV (release APK — what the release workflow publishes)
./gradlew :app:assembleGoogletvRelease
# → app/build/outputs/apk/googletv/release/app-googletv-release.apk

# Debug APK
./gradlew :app:assembleGoogletvDebug
# → app/build/outputs/apk/googletv/debug/app-googletv-debug.apk

# Build for Google TV with a registered Cast App ID
./gradlew :app:assembleGoogletvRelease -Pphairplay.castAppId=<APP_ID>
```

The version comes from `phairplay.versionName` in `gradle.properties` and the versionCode is
derived from the clock, so a local build always installs over the previous one.

Google Cast works out of the box through PhairPlay's own built-in Cast receiver (no Google
registration needed). Registering a Cast App ID and building with `-Pphairplay.castAppId=…`
switches PhairPlay to the official Cast Connect SDK instead — see [Google Cast App ID](CAST_APP_ID.md)
and [Google Cast](CAST.md).

---

## After Installation

1. Launch PhairPlay — the **HomeScreen** appears showing three service cards
2. All services (AirPlay, Miracast, Cast) are enabled by default
3. On your iPhone/iPad: Control Centre → **Screen Mirroring** → select your TV (tested with
   iPhone 14 on iOS 27.0.1)
4. On your Mac: click the AirPlay icon → select your TV
5. On Windows: Settings → Display → Connect to wireless display → select your TV
6. In Chrome: Menu → Cast → select your TV
7. Casting a video from an iPhone app: use the app's cast button (PhairPlay now serves Google
   Cast itself) — what works and what doesn't is in [Google Cast](CAST.md)
7. On a 4K Google TV: **Settings → Higher resolution (up to 4K)** advertises the TV's real
   resolution to the sender for a sharper mirror. Leave it off if the TV struggles to decode it.

See [Troubleshooting](TROUBLESHOOTING.md) if the device doesn't appear in your sender's list.
