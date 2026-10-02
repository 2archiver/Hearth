# Troubleshooting

---

## Device not appearing in AirPlay / Miracast / Cast menu

**Cause 1: Not on the same network**
- Ensure your Mac/PC and the TV are connected to the **same Wi-Fi network** (same router, same subnet).
- Check: if your router has both 2.4 GHz and 5 GHz bands with different SSIDs, make sure both devices use the same one.

**Cause 2: AP Isolation / Client Isolation**
- Some routers have "AP isolation" that prevents devices from seeing each other.
- Log into your router and disable "AP Isolation", "Client Isolation", or "Wireless Isolation".

**Cause 3: Multicast filtering**
- mDNS (used by AirPlay) requires multicast traffic. Some routers block this.
- Look for "Enable Multicast", "IGMP Snooping", or "mDNS" options in your router's advanced settings.

**Cause 4: PhairPlay service is stopped**
- Check the HomeScreen: all service cards should show "Running".
- If stopped, press the **Start** button or swipe to the control card.

**Cause 5: iOS caches the AirPlay device list**
- On iPhone/iPad (tested with iPhone 14 on iOS 27.0.1), the picker is **Control Centre → Screen
  Mirroring**, not Settings.
- If the TV does not appear: restart PhairPlay on the TV (HomeScreen → Restart), then toggle
  Wi-Fi off and on on the phone. iOS keeps a stale Bonjour cache for a few minutes after a
  receiver disappears.
- A phone on cellular (or on a guest SSID) will never see the TV — both must be on the same
  Wi-Fi subnet.

**Cause 6: Wi-Fi P2P disabled (Miracast)**
- Miracast requires Wi-Fi Direct. Some Android TVs disable this.
- Check: Settings → System → About → verify Wi-Fi Direct is available.

---

## The name I set in Settings doesn't show up on my iPhone

The name a sender shows is the last thing it was told, and it is told the name **three** ways —
which is why a rename used to appear to do nothing:

1. **mDNS** (`_airplay._tcp`) — used while *browsing*, i.e. to build the picker list.
2. **`GET /info`** — asked as soon as you tap the device, and the `name` in that reply is what
   the picker then *displays*. (Fixed in v1.3: this used to answer with the Android device name
   no matter what Settings said.)
3. **The phone's Bonjour cache** — iOS holds on to what it last saw even after the receiver
   changes.

If the name still looks wrong:

- **Rename, then let it restart.** Saving the name in Settings now restarts the receivers
  automatically, so the new name is advertised immediately. On older builds you must press
  **Restart** on the Home screen yourself — saving alone did nothing.
- **Clear the phone's cache.** Toggle Wi-Fi off and on (or Airplane mode on/off) on the iPhone,
  then open **Control Centre → Screen Mirroring** again. iOS caches the AirPlay device list for
  a few minutes; a stale entry looks exactly like a failed rename.
- **Check what was really registered.** The Home screen shows *Visible as: …*. If you see
  **Apple TV (2)**, another device on your network already owns that name and Android's mDNS
  responder renamed us to resolve the collision — pick a different name in Settings.
- **A name can be silently shortened.** mDNS service names are capped at 63 bytes, and PhairPlay
  drops characters that would corrupt a Bonjour record (emoji and punctuation). What you typed
  and what gets advertised can therefore differ; the Settings row shows the cleaned value.

---

## Casting from an iPhone app (Rumble, YouTube, …)

**Use Screen Mirroring for anything that must reach PhairPlay.**

1. On the iPhone, open **Control Centre → Screen Mirroring**.
2. Pick the name PhairPlay advertises (**Apple TV** by default).
3. Open the app and play — the whole phone screen is mirrored, including video.

This is the only route that reaches PhairPlay, and it is what apps like Rumble mean by
"screen mirroring" in their own support notes.

### Why that app's own cast button doesn't list PhairPlay

If an app has its own cast icon (the rectangle-with-wi-fi-arc symbol), it is almost always
**Google Cast**, not AirPlay. PhairPlay cannot appear in that list, and this is not a bug that
can be fixed in this app:

- **Google Cast needs a Google-registered Cast App ID.** Google issues the ID, and a receiver
  only shows up in a Cast sender's device list once its App ID is registered and published.
  The Cast control plane in PhairPlay is wired up and the SDK is started, but with no
  registered App ID the Cast card reports *Cast App ID not set or Play Services unavailable*.
  See [CAST_APP_ID.md](CAST_APP_ID.md).
- **Emulating a Chromecast instead is not possible on a Google TV.** A Cast receiver listens on
  TCP port 8009, and on a Google TV that port is already owned by the built-in Chromecast
  receiver that ships with the device. An app cannot bind it.

So: an in-app cast button sends your video to the TV's **own** built-in receiver, which plays it
but never hands it to PhairPlay. Screen Mirroring is the route that does.

### If Screen Mirroring connects but the video is black

That is a different problem, and usually one of:

- **DRM.** FairPlay/Widevine-protected streams are blocked by design (see *Connected but black
  screen → Cause 1*).
- **Mirroring resolution.** Turn **Settings → Higher resolution (up to 4K)** off and restart; a
  marginal decoder can fail to configure at 4K.

---

## Connected but black screen

**Cause 1: FairPlay-protected content**
- Netflix, Disney+, Apple TV+, and other streaming services use FairPlay DRM.
- Apple blocks mirroring of protected content by design. This is not a PhairPlay limitation.
- Solution: use a different app/tab on your Mac.

**Cause 2: Mirroring resolution above what the TV can decode**
- Turn **Settings → Higher resolution (up to 4K)** off and restart the receiver: that setting
  advertises a bigger mirror (up to 3840×2160 on a 4K Google TV) and a marginal decoder can fail
  to configure at that size.
- PhairPlay already caps the advertisement at what the panel shows and what the H.264 decoder
  reports it supports; check logcat for the line `AirPlay mirror advertised at …` to see what was
  chosen and why.

**Cause 3: MediaCodec decoder unavailable**
- Rare: some cheap Android TV boxes lack H.264 hardware decode.
- Check logcat: `adb logcat -s PhairPlay` — look for "MediaCodec" errors.
- Solution: not fixable in software; the TV box needs hardware H.264 support.

---

## High latency (>200ms)

1. Switch from 2.4 GHz Wi-Fi to **5 GHz Wi-Fi** or **Ethernet**.
2. Move the TV closer to the router.
3. Check if other devices are using the same Wi-Fi band heavily.

---

## Audio out of sync

1. Try stopping and restarting the stream from your Mac.
2. Restart the PhairPlay service (HomeScreen → Restart button).
3. If persistent, check logcat for NTP timing errors.

---

## Mirroring looks soft on a 4K Google TV

AirPlay mirrors at 1080p by default, which a 4K panel then upscales — that is normal, not a bug.
For a sharper image:

1. **Settings → Higher resolution (up to 4K)** → on.
2. Restart the receiver (HomeScreen → Restart) so the new size is advertised.
3. Reconnect from the sender: macOS re-renders the mirror at the new size, and small text becomes
   noticeably crisper.

If frames drop or the TV struggles, turn it back off — a 4K mirror costs real decode work, and
5 GHz Wi-Fi or Ethernet is effectively mandatory for it. PhairPlay will not advertise more than
the panel and the hardware H.264 decoder support, so on a 1080p TV this setting tops out at 1080p.

Portrait mirroring from an iPhone (Screen Mirroring while the phone is held vertically) is
aspect-fitted: black bars left and right are correct, a stretched image would be a bug.

---

## Installing a new APK fails

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signature mismatch / "App not installed"**
- The installed build was signed with a different key. Android never updates across keys.
- Fix: uninstall once, then install — `adb uninstall com.phairplay.googletv` (or Settings → Apps →
  PhairPlay → Uninstall on the TV), then install the new APK.
- Prevent it: a maintainer can add the signing secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD`) so every published APK uses one key — see
  [docs/RELEASING.md](../RELEASING.md).

**`INSTALL_FAILED_VERSION_DOWNGRADE`**
- You are installing an older APK over a newer one. Every build published by CI has a higher
  versionCode than the one before it, so this means the file is old — re-download from the
  [rolling `latest` release](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest).
- Forcing it: `adb install -r -d PhairPlay-googletv.apk`.

**The download link returns 404**
- The release is created by GitHub Actions on the first push to `main` after
  `.github/workflows/release.yml` lands. Check **Actions → Release**; if it has not run yet, grab
  the `debug-apk-googletv` artifact from the newest **Actions → CI** run instead.

---

## App crashes on startup

1. Check you installed `PhairPlay-googletv.apk` and your TV runs Android TV OS 10 or newer.
2. Try reinstalling: `adb uninstall com.phairplay.googletv` then install again.
3. Report the crash: attach `adb logcat -d` output to a GitHub Issue.

---

## Still stuck?

Open an issue at <https://github.com/2archiver/phairplay-archiver-fork-/issues> with:
- Your TV model and OS version (e.g. Google TV Streamer 4K, Android TV OS 14)
- Your sender and its version (e.g. iPhone 14, iOS 27.0.1; macOS 15.3)
- The PhairPlay version from **Settings → Version** on the TV
- The protocol you were trying to use
- A description of what happened
- `adb logcat -d | grep PhairPlay` output (or `tools/collect-device-logs.sh` before restarting)
