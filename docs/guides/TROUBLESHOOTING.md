# Troubleshooting

---

## Device not appearing in the AirPlay menu

**Cause 1: Not on the same network**
- Ensure your Mac/PC and the TV are connected to the **same Wi-Fi network** (same router, same subnet).
- Check: if your router has both 2.4 GHz and 5 GHz bands with different SSIDs, make sure both devices use the same one.

**Cause 2: AP Isolation / Client Isolation**
- Some routers have "AP isolation" that prevents devices from seeing each other.
- Log into your router and disable "AP Isolation", "Client Isolation", or "Wireless Isolation".

**Cause 3: Multicast filtering**
- mDNS (used by AirPlay) requires multicast traffic. Some routers block this.
- Look for "Enable Multicast", "IGMP Snooping", or "mDNS" options in your router's advanced settings.

**Cause 4: Hearth service is stopped**
- Check the HomeScreen: all service cards should show "Running".
- If stopped, press the **Start** button or swipe to the control card.

**Cause 5: iOS caches the AirPlay device list**
- On iPhone/iPad (tested with iPhone 14 on iOS 27.0.1), the picker is **Control Centre → Screen
  Mirroring**, not Settings.
- If the TV does not appear: restart Hearth on the TV (HomeScreen → Restart), then toggle
  Wi-Fi off and on on the phone. iOS keeps a stale Bonjour cache for a few minutes after a
  receiver disappears.
- A phone on cellular (or on a guest SSID) will never see the TV — both must be on the same
  Wi-Fi subnet.

**Cause 6: the sender and the TV are on different networks**
- Hearth advertises on the TV's default network only (the line on Home says which). A phone on
  a guest SSID, or on a VLAN the TV cannot multicast to, will never see it.
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
- **A name can be silently shortened.** mDNS service names are capped at 63 bytes, and Hearth
  drops characters that would corrupt a Bonjour record (emoji and punctuation). What you typed
  and what gets advertised can therefore differ; the Settings row shows the cleaned value.

---

## Casting from an iPhone app (Rumble, YouTube, …)

Two different things get called "casting". Which you want decides the route:

**Screen mirroring (always works)** — the whole phone screen, including any video:

1. On the iPhone, open **Control Centre → Screen Mirroring**.
2. Pick the name Hearth advertises (**Apple TV** by default).
3. Open the app and play.

**Google Cast — not Hearth.** A cast icon inside an app looks for the TV's built-in Chromecast,
which permanently owns TCP 8008/8009 and the `_googlecast._tcp` record; Hearth does not register
a Cast receiver and never will, so it cannot appear in that list. If an app offers only a cast button
and no Screen Mirroring, its video reaches the TV through Google's receiver or not at all —
[CAST.md](CAST.md) explains the boundary. Screen Mirroring is the route that always works, because
it is AirPlay, and AirPlay is what Hearth advertises.

**Apple Casting (screen mirroring)** — the card on Hearth's Home screen of the same name; it
mirrors the whole screen (video and audio) from an iPhone, iPad or Mac and is what
[APPLE_CASTING.md](APPLE_CASTING.md) covers. If the card says **Waiting**, the receiver is up and
the sender has not started a mirroring session yet — compare the card's line with the step list
in that guide.

Anything mentioning **8008/8009** means an old build is still installed: 1.6 removed Hearth's
Cast bridge, so those ports are never bound and that conflict cannot be reported. Reinstall the
current APK if a card still says it.

### If the iPhone cannot see the TV at all

Read the AirPlay card on Hearth's Home screen first — it names the interface and address the
advertisement is live on (`Advertising on Ethernet · 192.168.1.42`). Three cases:

- **It says "Advertising" and shows an address** → discovery is running; the problem is between the
  networks. See [Ethernet (wired Google TV)](#ethernet-wired-google-tv).
- **It says "Advertising" with no address** → the TV has no usable network. Check the TV's own
  network settings; a TV that is not joined to a network cannot be found on one.
- **It says "Error"** → registration failed, and the line under it says why. Press **Restart**; if
  it comes back red, grab the `mDNS: registration failed (…)` line from `adb logcat` for a report.

## Ethernet (wired Google TV)

Nothing in Hearth's AirPlay receiver assumes Wi-Fi. Sockets bind all interfaces, the multicast
lock is taken on whatever network is up, and discovery is mDNS, which behaves identically over
Ethernet — a wired 4K Google TV is the **primary tested target**, and the mirror is capped at 4K by
the panel rather than by the network.

What wiring does change is how many networks there are. In order:

1. **Is the iPhone on the same subnet as the address on the card?** TV wired + phone on Wi-Fi is
   only one network if the router bridges wired and wireless into the same subnet. Plenty of routers
   keep them apart, or turn on "AP isolation" / a guest network.
2. **Does multicast cross between them?** mDNS is multicast. A router that filters multicast between
   its wired and wireless segments (sometimes sold as "IGMP snooping" or "multicast enhancement")
   makes discovery impossible from the phone's side, and no app can work around it.
3. **Is the TV joined to Wi-Fi as well as wired?** Then it has two addresses and the phone can pick a
   route the TV never advertises on. Hearth advertises on the interface it is reachable by
   (Ethernet wins), and the card tells you which. Either match it on the phone, or forget the Wi-Fi
   network on the TV.

Quick check from a computer on the same network:

```bash
dns-sd -B _airplay._tcp             # macOS: list AirPlay receivers
avahi-browse -rt _airplay._tcp      # Linux
```

If Hearth appears there but not on the iPhone, it is multicast or segmentation. If it does not
appear at all, Hearth is not advertising — press **Restart** on the Home screen and read the
error line on the AirPlay card.

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
- Apple blocks mirroring of protected content by design. This is not a Hearth limitation.
- Solution: use a different app/tab on your Mac.

**Cause 2: Mirroring resolution above what the TV can decode**
- Turn **Settings → Higher resolution (up to 4K)** off and restart the receiver: that setting
  advertises a bigger mirror (up to 3840×2160 on a 4K Google TV) and a marginal decoder can fail
  to configure at that size.
- Hearth already caps the advertisement at what the panel shows and what the H.264 decoder
  reports it supports; check logcat for the line `AirPlay mirror advertised at …` to see what was
  chosen and why.

**Cause 3: MediaCodec decoder unavailable**
- Rare: some cheap Android TV boxes lack H.264 hardware decode.
- Check logcat: `adb logcat -s Hearth` — look for "MediaCodec" errors.
- Solution: not fixable in software; the TV box needs hardware H.264 support.

---

## The debug overlay shows nothing

**Settings → Debug overlay** should take effect the moment you flip it — no Restart, and it works
during a live session. If the HUD is blank:

- **"SRC idle — nothing streaming"** means the overlay is working and no sender is connected.
  That is the correct reading when nothing is casting.
- **"DECODE waiting for SPS/PPS + surface"** with a rising `kbps`: the TV is receiving video it
  cannot decode yet. Give it a second; WFD senders repeat SPS/PPS with every keyframe.
- **Never appears at all:** the overlay is drawn on the streaming screen, so it only shows while
  AirPlay streaming or Apple Casting (mirror video) on screen. AirPlay
  *audio-only* shows the now-playing card instead, which has no HUD.

Before 1.5 the toggle only took effect at receiver start-up (so it looked broken until you hit
Restart), `fps` only refreshed every 300 packets, and mirroring sessions fed no counters at
all. All three are fixed.

## High latency (>200ms)

1. Switch from 2.4 GHz Wi-Fi to **5 GHz Wi-Fi** or **Ethernet**.
2. Move the TV closer to the router.
3. Check if other devices are using the same Wi-Fi band heavily.

---

## Audio out of sync

1. Try stopping and restarting the stream from your Mac.
2. Restart the Hearth service (HomeScreen → Restart button).
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
5 GHz Wi-Fi or Ethernet is effectively mandatory for it. Hearth will not advertise more than
the panel and the hardware H.264 decoder support, so on a 1080p TV this setting tops out at 1080p.

Portrait mirroring from an iPhone (Screen Mirroring while the phone is held vertically) is
aspect-fitted: black bars left and right are correct, a stretched image would be a bug.

---

## Installing a new APK fails

**"App not installed as package conflicts with an existing package"**

This means *different signing key*, not *different version* — Android never updates across keys.
The in-app updater now checks the APK certificate before installation and shows **One-time
reinstall required** when the installed build uses another key (for example, an early 1.4 build,
a fork, or a private-key build). This is a platform rule, not a retryable download error; see
[docs/UPDATES.md](../UPDATES.md) for the safe one-time transition.

Do not uninstall if you are not intentionally switching signing sources. First confirm that the
APK came from the same repository/key as the installed app. After a deliberate one-time switch,
keep using builds from that same signing key and future updates install normally.

For same-key updates, **Settings → Updates → Check for updates** verifies the downloaded APK
before installing it. A different-key APK is never passed to Android's installer.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / signature mismatch**
- Same cause and same fix as above.

**"Install blocked" / the install dialog never appears**
- Allow installs from this source: **Settings → Apps → Special access → Install unknown apps →
  Hearth → Allow**.

**`INSTALL_FAILED_VERSION_DOWNGRADE`**
- You are installing an older APK over a newer one. Every build published by CI has a higher
  versionCode than the one before it, so this means the file is old — re-download from the
  [current `latest` release](https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest).
- Forcing it: `adb install -r -d Hearth-googletv.apk`.

**The download link returns 404**
- The release is created by GitHub Actions on the first push to `main` after
  `.github/workflows/release.yml` lands. Check **Actions → Release**; if it has not run yet, grab
  the `debug-apk-googletv` artifact from the newest **Actions → CI** run instead.

---

## App crashes on startup

1. Check you installed `Hearth-googletv.apk` and your TV runs Android TV OS 10 or newer.
2. Try reinstalling: `adb uninstall com.phairplay.googletv` then install again.
3. Report the crash: attach `adb logcat -d` output to a GitHub Issue.

---

## Still stuck?

Open an issue at <https://github.com/2archiver/phairplay-archiver-fork-/issues> with:
- Your TV model and OS version (e.g. Google TV Streamer 4K, Android TV OS 14)
- Your sender and its version (e.g. iPhone 14, iOS 27.0.1; macOS 15.3)
- The Hearth version from **Settings → Version** on the TV
- The protocol you were trying to use
- A description of what happened
- `adb logcat -d | grep Hearth` output (or `tools/collect-device-logs.sh` before restarting)
