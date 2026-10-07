# Installation Guide

This guide covers every way to install Hearth on your Google TV (Android TV OS 10+, tested on
Google TV 4K running Android TV OS 14).

**The current release:** <https://github.com/2archiver/Hearth/releases/latest>

Download the actual version-named `Hearth-<version>-googletv.apk` asset shown on that page. The
release filename changes with each build; do not use a guessed fixed-name `/download/latest/` URL.
See [docs/RELEASING.md](../RELEASING.md) for the release and updater contract.

**Updating:** once installed on this repository's community signing key, future APKs install
straight over the old one. Older 1.4 builds or APKs signed by another source may need a one-time
uninstall/reinstall; Hearth checks for this and explains the steps. Or skip the computer for
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
adb install -r Hearth-<version>-googletv.apk
```

If ADB reports `INSTALL_FAILED_VERSION_DOWNGRADE`, stop and check that you downloaded the newest
release; do not uninstall to work around it. A signing-key mismatch means the source/key differs.
Do not uninstall unless you have intentionally chosen to switch signing sources and have backed up
anything important—Android may erase Hearth's settings when it is removed.

### Step 5: Launch

Find **Hearth** in your app list and launch it.

---

## Method 2: Downloader app (no computer needed)

Use the **Downloader** app (free, from the Google Play Store) to fetch the APK straight onto your TV.

1. Install **Downloader** from the Google Play Store on your TV
2. Settings → Apps → Security & restrictions → **Install unknown apps** → allow **Downloader**
3. Open Downloader and enter:
   `https://github.com/2archiver/Hearth/releases/latest`
4. Choose **Install**, then **Open**

Downloader keeps the file name `Hearth-<version>-googletv.apk`, so re-downloading a newer build and
choosing **Install** updates the app in place — provided both builds are signed with the same key.
If the TV refuses, uninstall Hearth once (Settings → Apps → Hearth → Uninstall) and install
again.

---

## Method 3: Build from Source

```bash
git clone https://github.com/2archiver/Hearth.git
cd Hearth

# Build for Google TV (release APK — what the release workflow publishes)
./gradlew :app:assembleGoogletvRelease
# → app/build/outputs/apk/googletv/release/app-googletv-release.apk

# Debug APK
./gradlew :app:assembleGoogletvDebug
# → app/build/outputs/apk/googletv/debug/app-googletv-debug.apk

```

The version comes from `phairplay.versionName` in `gradle.properties` and the versionCode is
derived from the clock, so a local build always installs over the previous one. The published
release names its single APK after that version (`Hearth-<version>-googletv.apk`).

Hearth is an AirPlay receiver only. It does not implement Google Cast in any build — not a
built-in bridge, not Cast Connect behind a registered app ID — because a Google TV's own
Chromecast permanently owns TCP 8008/8009 and the `_googlecast._tcp` record, and claiming them
produced nothing but a port-conflict error. See [Google Cast on a Google TV](CAST.md).

---

## After Installation

1. Launch Hearth — the **Home screen** appears with two cards, AirPlay and Apple Casting.
2. AirPlay is on by default and advertising (the Apple Casting card is the same receiver, seen
   from the video stream); there is nothing else to switch on.
   most Google TVs keep that to themselves. Switch it on in Settings if your set supports it.
3. Read the AirPlay card: it says which network you are advertising on, e.g.
   `Advertising on Ethernet · 192.168.1.42`. Your sender has to be on that same network.
4. On your iPhone/iPad: Control Centre → **Screen Mirroring** → select the advertised name
   (default **Apple TV**; tested with iPhone 14 on iOS 27.0.1)
5. On your Mac: click the AirPlay icon → select the same name
6. On Windows or an Android phone: **Connect** / **Cast** → the TV's own wireless-display
   receiver. Hearth asks for no Wi-Fi Direct or location permissions, and never
   receives Google Cast — see [CAST.md](CAST.md)
7. On a 4K Google TV: **Settings → Higher resolution (up to 4K)** is on by default and advertises
   the panel's real resolution for a sharper mirror. Turn it off if the TV struggles to decode it.

See [Troubleshooting](TROUBLESHOOTING.md) if the device doesn't appear in your sender's list.
