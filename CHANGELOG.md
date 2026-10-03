# Changelog

All notable changes to PhairPlay will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.5] - 2026-10-03

Casting and Miracast both worked end-to-end on paper and neither worked in practice, and the
debug overlay showed nothing at all. Three separate bugs, three separate fixes.

### Fixed — Cast

**The TV answered DIAL but never answered discovery.** PhairPlay advertised itself over mDNS
(`_googlecast._tcp`) and served DIAL over HTTP on 8008 — but it never answered SSDP
`M-SEARCH`. Senders built on the Cast SDK browse mDNS and found the TV; senders built on DIAL
(Netflix, YouTube, Windows, the Chrome desktop button) send `M-SEARCH` to
`239.255.255.250:1900` and only talk to devices that reply. To every one of those the TV did
not exist. `SsdpResponder` now answers `ssdp:all`, `upnp:rootdevice` and the DIAL service
target, pointing the sender at the DIAL device description.

**Every reply went to every connected phone.** Cast replies were broadcast to all open
channels instead of to the sender that asked, so a second phone was bombarded with statuses
addressed to the first. Replies are now addressed to the connection the request arrived on.

**Failures were answered with silence.** A `LOAD` with no `contentId`, or any media command
with no session open, returned nothing. A sender waiting on a reply it will never get sits on
"connecting…" forever. Those now get `LOAD_FAILED` / `INVALID_REQUEST`.

**The scrubber froze mid-playback.** The sender's progress bar is driven by `MEDIA_STATUS`,
which only ever arrived on a state change. It is now pushed once a second while playing, and
the rebuffering callbacks report `BUFFERING` honestly instead of leaving `PLAYING` on screen.

**Queue commands did nothing.** `QUEUE_LOAD` / `QUEUE_NEXT` / `QUEUE_PREV` /
`QUEUE_GET_ITEMS` / `QUEUE_INSERT` / `QUEUE_REMOVE` / `QUEUE_UPDATE` are implemented, so
senders that build a playlist rather than loading one URL get an answer and start playing.

**`GET /apps/<id>` claimed the TV could run everything.** It now reports `installed`
truthfully, which stops senders showing the device and then failing.

### Added — Cast

**DIAL hand-off to the TV's own apps.** A real smart TV does not try to emulate Netflix or
YouTube — it *launches the app installed on the TV* and lets the phone drive that. PhairPlay
runs on an Android TV, so it can do exactly the same thing: a recognised DIAL name whose app
is installed is started by intent (deep-linked to the title when the sender sent one), and
PhairPlay stays out of the way. Anything unrecognised (`CC1AD845`, VLC, Plex, Chrome) is
played by the built-in receiver as before.

**Private Cast channels are reported instead of silently ignored.** Apps with their own
registered receiver — YouTube, Netflix, Spotify — launch here and then speak a private channel
only their own receiver understands. That is now surfaced on the Cast card as an explanation
with "use Screen Mirroring" as the workaround, rather than presenting as a black screen.

### Fixed — Miracast

**The capability negotiation ignored what the source asked for.** Every `GET_PARAMETER` was
answered with one fixed blob. A WFD source asks for a *specific list* and validates the reply
against it; Windows asks for `wfd_uibc_capability` and `wfd_standby_resume_capability` and
drops the session when they are missing. Replies now name exactly the requested parameters,
in the order requested, answering `none` for anything unsupported rather than omitting it.

**`OPTIONS` did not advertise `SETUP` or `PLAY`.** Sources re-check the `Public:` header before
using them; the old reply omitted both.

**`wfd_video_formats` was not a valid format.** It reported profile `02` / level `10` — neither
is defined by the Wi-Fi Display spec — so a validating source could reject the whole list. It
now reports Constrained Baseline / level 4.2 against the CEA modes it advertises.

**`SET_PARAMETER` results were thrown away.** The source's `wfd_presentation_URL` and
`wfd_trigger_method` are remembered and echoed back. Its video formats, audio codecs and RTP
ports are deliberately *not* echoed: promising a codec or transport this receiver does not
implement would have the source stream in a format that is then dropped.

**Audio is counted, not decoded.** WFD carries audio inside an MPEG-2 transport stream and
there is no demuxer here, so the audio channel is drained and logged rather than played. It was
previously undocumented; [docs/guides/MIRACAST.md](docs/guides/MIRACAST.md) now says so, and
the debug HUD shows whether the source is sending audio.

### Fixed — Debug overlay

The overlay could sit on screen showing a row of zeroes, which is indistinguishable from
"the overlay is broken". Three causes:

- **The setting was never live.** `overlayEnabled` was copied from Settings only inside
  `startAirPlay()`. Toggling *Debug overlay* does not restart the service, so the toggle did
  nothing until the user happened to press Restart. It is now mirrored from the settings flow
  and takes effect mid-session.
- **Only AirPlay wrote counters.** A Cast or Miracast session showed an empty HUD. Every
  receiver now feeds the stats bus, and the HUD names the source.
- **`fps` refreshed every 300 payloads** — five seconds of "0 fps" at 60 fps, and forever if
  the sender sent fewer than 300 payloads. Sampling is now a rolling one-second window that
  also produces a bitrate, an uptime clock, and a per-protocol layout (Cast reports player
  state and position; AirPlay/Miracast report resolution, fps, kbps, queue depth and drops).

The HUD is also explicitly elevated and brought to the front, because a `SurfaceView` is
composited in its own layer below the window and could end up drawn over it.

## [1.4] - 2026-10-03

### Fixed

**Updating no longer fails with "App not installed as package conflicts with an existing package"**

That message means *the APK is signed with a different key than the installed app* — never a
version problem. PhairPlay is sideloaded, so every build that did not carry a maintainer's
signing secrets fell back to a throw-away debug key that differed on **every CI run**, which
meant every update had to be preceded by an uninstall.

- **One key for every build.** `app/signing/phairplay.p12`, a public "community build" key, is
  committed to the repository and used by `debug` **and** `release`, on CI **and** in local
  clones. A debug APK from a CI run and a release APK from the release workflow now also replace
  each other. Supply `KEYSTORE_PATH` (or the `phairplay.keystore*` Gradle properties) to sign
  with your own key instead — `tools/make-signing-key.sh` writes one.
- **The in-app updater refuses an APK it could not install.** Before handing anything to
  Android it reads the downloaded APK's signing certificate and compares it with PhairPlay's
  own; a mismatch is reported with an explanation instead of reproducing the error.
- **CI proves the signature.** `apksigner verify` runs on every published APK and prints the
  certificate SHA-256. Set the repository variable `EXPECTED_APK_CERT_SHA256` and a build signed
  with an unexpected key fails the release instead of shipping.

The trade-off is documented rather than hidden: a committed private key is public, so anyone
could ship an APK signed with it. See [docs/RELEASING.md](docs/RELEASING.md#signing-why-updates-install-in-place).

### Added

**Update checker and self-updater** — **Settings → Updates**

- *Check for updates* asks GitHub what the newest published build is and compares
  **versionCode** (a number, so `1.10.0` no longer sorts before `1.9.0`), reading the
  `version.json` the release workflow now publishes with every release. Releases that predate it
  fall back to the `versionCode` in their notes.
- *Check automatically* (on) — looks in the background a few times a day and posts a
  notification; *Download automatically* (on) fetches and verifies the APK as soon as one is
  found; *Install automatically* (off) installs a verified update without another prompt. On
  Android 12+ a package replacing **itself** needs no confirmation dialog, so with all three on
  the update is genuinely hands-off.
- The download is checked against the published **SHA-256** while it streams, and against
  PhairPlay's own **signing certificate** before it is installed.
- Installs go through `PackageInstaller` (not the deprecated `ACTION_INSTALL_PACKAGE`), and the
  install *result* is now received and logged instead of the app optimistically claiming success.
- Forks: set `phairplay.updateRepo` in `gradle.properties` so the app checks your releases.

**Google Cast works without registering anything with Google** — **Settings → Built-in Cast bridge**

PhairPlay now serves Cast itself — the same wire protocol a Chromecast speaks — because Google's
Cast Connect SDK refuses to start without a Cast Application ID issued after registering the
package in the Cast SDK Developer Console. Until someone does that, the Cast card could only
ever show an error (which is what 1.2 showed).

- **mDNS** `_googlecast._tcp`, so an iPhone's Cast picker finds the TV.
- **DIAL** over HTTP on 8008 (`/ssdp/device-desc.xml`, `GET`/`POST`/`DELETE /apps/<id>`).
- **castv2** over TLS on 8009: `CONNECT → GET_STATUS → LAUNCH → LOAD <url> → PLAY/PAUSE/SEEK/STOP`,
  with the protobuf framing implemented directly.
- Any requested app ID is accepted, so senders that cast a media URL (VLC, Plex, Infuse, photo
  and file apps, Chrome, the Default Media Receiver) work as-is. Apps that need their own
  registered receiver with a private protocol — YouTube, Netflix, Spotify — can launch but
  cannot be decoded, by design; [docs/guides/CAST.md](docs/guides/CAST.md) says so plainly.
- If the TV's **built-in** Chromecast already owns ports 8008/8009 (which it does on a
  Chromecast with Google TV) the bridge says exactly that instead of failing silently.
- A build carrying `-Pphairplay.castAppId=…` still uses the official SDK, and the bridge stays
  off — two receivers advertising one IP would show duplicates in every picker.

**The Home screen now names the network PhairPlay is advertising on**

`Network: Ethernet · 192.168.1.42`. "My iPhone can't see the TV" is nearly always a network
question, and on a wired Google TV it was previously invisible which interface PhairPlay was
using. This is the first thing to check, and it is documented in
[docs/guides/CAST.md → Ethernet](docs/guides/CAST.md#ethernet-wired-google-tv).

**The Cast card explains itself**

Instead of one generic error, the card now reports which back-end is running, that the Cast
ports are already taken by the TV's own receiver, or that both back-ends are unavailable.

### Changed

- **Stable AirPlay `deviceid` on wired (Ethernet) Google TVs.** `NetworkUtils.getMacAddress()`
  took the first interface Java handed back, and on an Ethernet-connected TV that is commonly
  `wlan0` even when Wi-Fi is disconnected — so the `deviceid` (and the `_raop._tcp` service
  name) changed between reboots, which shows up as an iPhone that refuses to reconnect, asks to
  re-pair, or lists the same TV twice. Interfaces are now ranked Ethernet → Wi-Fi → other, with
  one that has an IPv4 address preferred over one that does not.
- **iOS AirPlay discovery: the `pk` TXT record is now advertised.** A real Apple TV publishes its
  Ed25519 public key in the `_airplay._tcp` TXT record and iOS reads it *while browsing*, before
  opening a connection. `NsdServiceInfo` only grew `setAttribute(String, byte[])` in Android 12,
  so it is published on 12+ (resolved reflectively, so the compile SDK does not decide) and
  skipped below that, where — as before — `GET /info` carries the key. This reverses the
  "deliberately not shipped" note in the 1.3 changelog.
- `version.json` is published with every release, so the app — and anyone scripting an update
  check — has the versionCode without parsing release titles.
- The release workflow fails a rolling build whose `versionCode` does not exceed the published
  one; a code that stops growing is how an update quietly stops being installable.

---

## [Unreleased]

Nothing yet — changes collect here until the next version is cut.

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
