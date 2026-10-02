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
