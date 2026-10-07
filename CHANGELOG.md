# Changelog

All notable changes to Hearth (formerly PhairPlay) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.9.2] — lifecycle-safe Stop, canonical updates, and separate iOS video gates

This patch is a reliability and verification release, not a claim that iOS Photos or YouTube video
has been validated on hardware. The two real-device gates and their distinct procedures are in
[docs/CASTING-1.9.2.md](docs/CASTING-1.9.2.md).

### Changed
- Set the base version to **1.9.2** while preserving the Android `applicationId`, existing user
  preferences/data, signing identity and monotonically increasing CI `versionCode` policy.
- Make `2archiver/Hearth` the canonical update repository and release destination. The updater
  normalizes the former repository slug before building its API URL; a focused test covers that
  migration. This does not promise that every previously installed older APK can auto-migrate.
- Point install/update guidance at the canonical GitHub release page and the actual version-named
  Google TV APK. The current repository has Pages disabled; no active download instructions depend
  on the guessed Pages URL or a fixed APK filename.
- Select the actual `-googletv.apk` asset returned by GitHub, rejecting missing or ambiguous
  compatible assets rather than choosing the first APK in API order.

### Fixed — updater verification
- Require canonical GitHub API/release endpoints and reject asset URLs outside the configured
  repository. Missing, malformed or conflicting version, size or full SHA-256 metadata blocks an
  offer/download; streamed byte count and digest must match.
- Inspect the downloaded package identity and embedded version, then compare its signing
  certificate with the installed app. Recheck package/version/signature immediately before passing
  the staged APK to Android. A queued `PackageInstaller` request is not reported as installed;
  success follows Android's result.
- Keep the old repository preference as a tested migration alias for new code. Existing older APKs
  are not represented as automatically migrated; use the canonical release page if their updater
  cannot find or verify an update.

### Fixed — stop and teardown
- **Stop playback** now ends the active cast while keeping receiver discovery/listening available;
  **Stop receiver** remains the separate action that shuts down the receiver/service.
- Scope callbacks and cleanup to the owning session/generation, preserve partial audio/video
  teardown semantics, silence local output, cancel/close pending playback work, and continue later
  cleanup actions even when one teardown step throws.
- Keep the final bounded, redacted session trace available for export after Stop, including the last
  failure stage. URL-player startup receives the session ID, connection ID and media role so its
  diagnostics belong to the correct cast.

### Fixed — direct cleartext media redirects
- Apply the local-unicast HTTP host policy to every direct-media redirect hop, not only the first
  URL. A local cleartext source cannot redirect playback to a public HTTP host; cross-protocol and
  credential-bearing media redirects are rejected without recording their URLs.

### Video validation gates — intentionally separate
- **Priority 1: native iOS Photos in-video AirPlay.** Verify downloaded-local and iCloud-only media
  separately, with moving video and sound, pause/seek, Stop and reconnect. No Photos-app hardware
  run is available in this checkout; status is **PENDING**.
- **Priority 2: YouTube iOS app AirPlay picker.** Verify rendered moving video plus continuous
  sound, controls, Stop and reconnect on a real TV. Control Centre audio, screen mirroring and
  source inspection are not evidence. No hardware run is available; status is **PENDING**.
- Report codec/container combinations only when observed. Safari, Rumble, live streams and protected
  media remain separate; this patch does not scrape YouTube, bypass DRM, create a parallel player,
  or claim an unsupported transport.

### Validation
- Added focused regression coverage for repository-alias migration and release URL trust,
  compatible asset/checksum metadata, local cleartext hosts and redirect policy, cleanup continuation,
  and playback/stop callback ordering.
- Offline checks passed: workflow shell/YAML sanity scan, release-notes helper tests (4 passed),
  `node --check` on the site script, Android XML parsing (30 files), and `git diff --check`.
  `./gradlew :test-runner:test` was attempted but could not start because this checkout has no Java
  runtime or `JAVA_HOME`; Kotlin tests, Android lint/build and instrumented tests therefore remain
  unverified here. Real-device acceptance remains pending as above.

## [1.9.1] — PTTH response framing and truthful video status

Fix the sender-mediated video exchange path introduced in 1.9.0. On an upgraded `/reverse` socket,
the sender's `HTTP/1.1` / `EVENT/1.0` reply frames belong to PTTH, not RTSP. Hearth now consumes
those bounded response frames and keeps the upgraded connection available for later FCUP requests;
it also prevents an FCUP write from racing ahead of the complete `101 Switching Protocols` reply.
This is a protocol-path correction, not a claim of verified Apple YouTube app playback on Google TV.

### Fixed
- Read fixed-length and chunked PTTH response bodies without retaining them or routing the response
  status line through the RTSP dispatcher. Reject malformed, ambiguous, oversized or truncated
  frames; close/release an idle or explicitly closed channel with a stated reason.
- Publish a warming sender-mediated bridge before requesting its master playlist, so an immediate
  `POST /action` can be matched while `/play` is waiting. Keep the previous active bridge until the
  replacement master is loaded, use process-wide FCUP request IDs, coalesce same-URL fetches, and
  revoke stale reverse sockets when ownership changes.
- Treat FCUP fetched-resource 2xx statuses (including 200) as success; reject non-2xx results.
- Keep player preparation distinct from actual video: only a rendered first frame reports
  `PLAYING`. The Home card now collects URL-free failure stages for `/play`, manifest, audio/player
  setup, preparation, network/decoder/protection errors, seek and first frame.

### Validation and compatibility
- Added PTTH framing/socket-loop, coordinator lifecycle, FCUP status/ID/deduplication, binary request
  body and failure-classification tests. These tests and the Gradle build were **NOT RUN** in this
  environment because no Java runtime or `JAVA_HOME` is available.
- Apple YouTube app → AirPlay picker → Hearth on Google TV first-frame and audio/control validation
  are **NOT RUN** (no `adb` or test device). Therefore 1.9.1 must not be described as an end-to-end
  verified YouTube fix. Safari, Rumble and live-stream compatibility are not inferred from this path.
- Versioning remains on the established `phairplay.versionName` / clock-derived `versionCode`
  convention. The application ID, updater repository and signing identity are unchanged.

## [1.9.0] — video, not just a connection

Direct successor to 1.8.3: same signing key, same package id, same updater repository. The base
version moved to 1.9.0 for the release train, and the clock-based versionCode is forced above the
published one by `release.yml`, so it installs over an installed 1.8.3 in place.

**The headline: AirPlay video now has a player.** Until this release a video `/play` could be
accepted, reported as "connected" and produce nothing but the RAOP audio stream — the sender was
right that it had handed over a video session, and the TV showed no picture. The causes were that
Hearth had no video backend at all (no `MediaPlayer`/ExoPlayer was ever attached to the URL-video
path) and that the YouTube app's video transport (sender-mediated, FCUP) was not implemented — the
`/reverse` upgrade was acknowledged with a `PTTH bridge unavailable` trace line and `POST /action`
was answered 501.

### Added — video playback
- **A real player for URL video: Media3 (ExoPlayer) 1.4.1**, with `media3-exoplayer` and
  `media3-exoplayer-hls`, installed once at process start
  (`PhairPlayApp` → `UrlVideoBackends`). Media3 was chosen over `android.media.MediaPlayer`
  because sender-mediated HLS cannot be a plain URL (it needs a custom `DataSource`) and because a
  master playlist's alternate audio, init maps, byte ranges and live window must live in *one*
  player timeline.
- **"Playing video" now requires a rendered frame.** The state machine reports `Playing video` only
  after the player's first-frame callback; prepared-and-started-without-a-frame stays `Loading
  video` and becomes a stated failure (`video did not start`) after 30 s. Media with no video track
  reports `Audio only — the sender is not sending video`, and a failed preparation is shown as a
  failure with a short reason instead of being reset to Ready.
- **Video reaches the TV surface.** The player gets the same `SurfaceView` the mirroring path uses,
  sized from the decoded video size (`StreamStats`), re-attached after a surface recreate, and
  released on stop/replacement. Decoding and network I/O stay off the UI thread; the player runs on
  the main Looper.
- **Sender-mediated (FCUP) HLS transport** — the YouTube app's AirPlay video path, implemented from
  the wire contract UxPlay documents (`POST /reverse` PTTH channel; a reverse `POST /event` XML
  plist carrying `FCUP_Response_RequestID`/`FCUP_Response_URL`; the sender's `POST /action` binary
  plist reply; UxPlay commit `3dbf7ce`, no code copied):
  - session-scoped bridge with request ids, URL-matched replies, per-URL de-duplication, 15 s
    bounded waits, cancellation on stop/replacement, and bounded playlists (8 MiB) and items
    (32 MiB, 8 MiB cache);
  - master and media playlists, alternate audio renditions, init maps, keys and byte ranges are
    fetched through the sender; relative references resolve against the *sender's* playlist URL and
    stay on the sender's transport (their base may be a host only the sender can reach:
    `mlhls://`, `localhost:<port>`, a session-bound CDN), while a URL the sender wrote out in full is
    handed to the player byte-for-byte (no parent-query propagation); loopback hosts
    (`localhost`, `127.0.0.0/8`, `0.0.0.0`, `::1`) are never treated as player-fetchable;
  - live playlists are re-read on every refresh; a reference this receiver cannot serve fails the
    playlist instead of silently dropping it (a master is never downgraded to audio-only), and a
    SAMPLE-AES playlist is refused with that reason rather than played as silence;
  - YouTube's `#YT-EXT-CONDENSED-URL` tag is expanded, including the empty-`PARAMS=""` form real
    playlists carry;
  - segments referenced by sender-internal or relative URIs are fetched through FCUP too — an
    extension beyond the reference implementations, which fetch only playlists and leave segments to
    the player. It is labelled as such in the code and in the connection log.
- **`X-Apple-Session-ID` is matched before an action is applied** (upstream does the same), and
  `/action` without an active bridge is answered 501 with the field names (never values) traced,
  rather than acknowledged as success.
- **A request arriving on an upgraded PTTH socket** is now refused with a stated reason instead of
  being answered — writing a response there would interleave with the bridge's frames.

### Added — audio ownership and sync
- **One soundtrack owner.** The player starts muted; the AirPlay audio stream is suspended **only**
  after the player reports that the media carries its own audio track, and the suspension is
  restored for a new video item that has not proven anything yet. A prepared player, a connected
  transport or a first frame are not evidence of audio.
- **Suspension silences the output, not the stream.** The AirPlay audio path keeps receiving (and
  decoding) its packets and stops writing to the output, so the sender does not see a dead stream
  and tear the video session down with it (upstream stops the RAOP service here, which a sender can
  react to).
- **A failed video never restarts stale audio**, and a video that had taken over is not handed back
  to the AirPlay stream on failure — the session is ended instead. Everything is generation-checked,
  so a late callback from a replaced player cannot silence a newer session.
- Pause/resume/seek still map to one player; a seek on sender-mediated HLS refreshes the media
  playlist and can land on a segment the sender has re-signed.

### Changed
- `#YT-EXT-CONDENSED-URL` with an empty `PREFIX` now expands (the whole segment line is the
  fragment). Earlier code refused it, which is the form real YouTube playlists use.
- The offline JVM test runner excludes `ExoUrlVideoBackend.kt` (Media3 is not on its classpath);
  `AirPlayVideoPlayer` asks `UrlVideoBackends` for a factory instead of naming ExoPlayer, and
  reports "no URL-video backend is installed in this process" if the install is missing.
- Removed the unused first draft of the playlist codec and the loopback HTTP server it needed;
  the bridge serves the player through a private `hearth-hls://` data source instead.

### Validation
- New JVM tests: FcupCodec (10), HlsPlaylistCodec (15), SenderMediatedHlsBridge (16, against a fake
  sender over the real reverse-channel framing), VideoAudioHandover (8), UrlVideoPlaybackController
  (10 new: first frame, audio-only, mute/handover, stale first frame, bridge failure reason),
  RtspHandler (7 new: `/reverse`, `/action`, sender-mediated `/play`).
- CI on this branch: `:test-runner:test` **404 tests, 0 failures**; `:app:lintGoogletvDebug` pass;
  `:app:assembleGoogletvDebug` + `HearthUiTest` pass. The debug APK that run attaches uses the
  repository's published key (`app/signing/phairplay.p12`, the same key debug and release use) and a
  clock-derived `versionCode` higher than any earlier build, so it installs as an update over the
  installed 1.8.3; the signed release comes from `release.yml` on `main` with this
  `gradle.properties` version.
- A failing CI run now also prints the failing tests' own messages from the JUnit XML — Gradle's
  console output did not always carry them, and the runner log host is not reachable here.
- **No real hardware was used for this change** — no capture, no device playback. The device matrix
  (YouTube app, Rumble app, Safari YouTube, Safari Rumble, pause/seek/stop, 10-minute stability,
  mirroring, idle leak check) is still **NOT RUN**; see the pull request for the checklist.

## [1.8.3] — hardening the 1.8.2 session model (build fix + successor release)

Direct successor to 1.8.2: same signing key, same package id, same updater repository, and a
versionCode above the published 1.8.2 APK, so it installs over an existing 1.8.2 in place.
The base version moved from 1.8.2 to 1.8.3 so the published APK is named for the release train
that contains it (`Hearth-1.8.3-main.N-googletv.apk`).

### Fixed — build
- **The 1.8.2 follow-up commit did not compile.** `AirPlayReceiver` reported URL-video playback
  state with a nullable session token while `SessionOwnership.updatePlaybackState` accepted only a
  non-null token, so `:app:compileGoogletvReleaseKotlin` (and the debug compile, lint and the JVM
  test compile behind it) failed with a type mismatch. `updatePlaybackState` now accepts a nullable
  token and reports `false` for a connection that never claimed a session, matching
  `updateMediaRole`/`closeConnection`/`isCurrent`.
- Failure reports now reach the commit for `CI`/`Lint` runs on `main`: both workflows asked for
  `contents: read`, which cannot create commit comments, so a red build on `main` produced no
  readable cause on GitHub. They now request `contents: write` like the Release workflow does.

### Fixed — AirPlay sessions
- **A stale RTSP close, teardown or media callback can no longer tear down a newer session.** Each
  session gets a generation token; callbacks carry it and are inert once the generation is
  replaced. A connection that never claimed the session (a probe, a failed mirror `SETUP`, an
  event-only socket) cannot end it either.
- **Secondary sockets join only by protocol identity.** A reverse/event channel or a follow-up
  connection is associated with the active session only when it presents the same
  `X-Apple-Session-ID` fingerprint; sender IP addresses are never used for association (two
  senders behind one NAT used to look identical).
- EOF is no longer treated as a session teardown: the pipeline is released only when the last
  control connection and all confirmed media roles (mirror video/audio, URL video) are gone.
  Protocol `TEARDOWN` and `POST /stop` remain terminal.
- Media lifetimes are mirrored into the ownership model, so a stream that stops on its own can end
  an orphaned session while a still-running stream keeps it alive.

### Added — diagnostics
- URL-video states (loading/playing/paused/failed) are shown on the AirPlay card, with failures
  reported as a short reason — never the media URL.
- The connection trace is now structured (session, connection, role, kind) and bounded to 256
  entries, with a redaction pass that strips media URLs, bearer tokens, keystream parameters,
  IP/MAC addresses, UUIDs and long opaque values before anything is logged or exported, plus a
  UTC export formatter for support reports.
- RTSP reads distinguish clean peer EOF from malformed/truncated/oversized input and time out with
  a stated reason, so "it just disconnected" reports say what actually happened.
- `/play` bodies are decoded from binary plist, XML plist or legacy text with explicit size limits;
  only direct `http`/`https` locations are played, and a sender-mediated (`mlhls`/FCUP) location is
  rejected with the same 400 as before while recording *why* in the trace.

### Validation
- `./gradlew :test-runner:test` (JVM suite: ownership, RTSP routing/robustness, URL-video parsing
  and playback-controller fakes, redaction), the Android debug APK + lint job, and the release APK
  build all run in CI on this commit.
- Still pending: casting from the Rumble app and Safari AirPlay video on real hardware. This
  release does not claim the Rumble issue is fixed; it removes the session-teardown causes the
  1.8.2 trace showed and fixes the build that 1.8.2's follow-up left broken.

## [1.8.2] — installs that finish, prompts that stop, sessions that survive reconnects

### Fixed — updater
- **Installs now actually complete.** The PackageInstaller result PendingIntent was created
  `FLAG_IMMUTABLE`, so Android could not attach the status or the confirmation screen. The app
  saw a bare "failure" and never showed Android's install confirmation. It is now `FLAG_MUTABLE`
  on Android 12+ (the intent is explicit and the receiver is not exported).
- **No more repeated "update ready" prompts.** A staged APK whose build is already installed is
  deleted instead of being offered again, and staged builds are keyed by the versionCode read from
  the APK itself rather than from the release notes.
- **A release whose notes overstate its build is offered once.** After its downloaded APK proves to
  be no newer than this install, it is not downloaded again until a different build is published.
- The staged file is checked again (signature and real versionCode) right before installing.

### Added — updater
- Visible install states on the Settings card: *Installing*, *Confirm on screen*, *Permission
  needed*, *Install cancelled* and *Install failed*, with Android's reason. An unanswered
  "Installing…" expires after two minutes instead of staying on screen.
- **"Install unknown apps" flow:** if Hearth lacks that permission, it explains what to turn on and
  opens the right Settings page, with a fallback path for TVs that do not have that page.

### Fixed — AirPlay
- **A stale or probe connection can no longer end someone else's session.** Each connection that
  starts a session (mirror keys, legacy RECORD, URL video) claims it. Stops from any other
  connection are logged and ignored. This matches the reported trace: the session stopped about
  one second after audio setup, before any video started.
- A mirror `SETUP` that fails no longer marks its connection as owning a session.
- When the connection limit is reached, the oldest *idle* connection is closed instead of the
  control channel.
- RTSP header names are case-insensitive. A lower-case `content-length` used to leave the body in
  the socket and desynchronise the connection.

### Diagnostics
- The on-TV Activity log now gives a **stop reason** for every ended session: TEARDOWN, sender
  closed the control connection without TEARDOWN, idle timeout, connection error, receiver
  shutdown, URL video ended, or a stop that was ignored and the reason it was ignored.

### Design
- New launcher icon and Android TV banner: an ivory flame with a play mark and casting arcs on wine red.

### Validation
- New JVM tests: `InstallPolicyTest`, `SessionOwnershipTest`, `RtspRobustnessTest`.
- Still pending: testing on real devices, including Rumble and FCUP direct video. The private-playlist
  bridge is a separate feature and is not part of this release.
- **If 1.8.0/1.8.1 is stuck on "Installing…", install 1.8.2 manually once** (Downloader or
  `adb install -r`). The fix is in the new build and cannot repair an updater that is already
  installed.

## [1.8.1] — direct AirPlay video compatibility (pending validation)

- Correct seconds-based resume offsets for direct video casting while retaining fractional offsets.
- Keep URL video visible over audio metadata and wait for a usable video surface.
- Serialize URL-player operations on Android's main Looper and clean up failures/stale callbacks.
- Preserve the existing mirroring and RAOP paths. Rumble hardware validation and FCUP HLS
  compatibility remain pending; see docs/CASTING-1.8.1.md.

## [1.8.0] - 2026-10-04 — The wine remaster

### Changed
- Wine-red and cream design, clearer remote focus, a large receiver name and a three-step connection guide.
- Home keeps troubleshooting behind an Activity action; staged updates open Settings directly.
- Settings rows grow to fit descriptions; toggles expose their On/Off state to accessibility services.
- Landscape Now Playing layout with metadata, bounded artwork and a remote-control hint.
- Rewritten project overview, accurate capability limits, contribution links and Ko-fi support.

### Fixed
- Service-state observers are cancelled on unbind/rebind instead of accumulating across visits.
- The active navigation item is restored after activity recreation; Back returns through Settings and navigation.
- Active playback keeps the display awake and releases that request on return to Home.
- Photos take precedence over an empty connected-session video surface.
- Photo and album-art decoding is sampled to a bounded size; unchanged artwork is reused.
- Timing replies must match the sender, packet type and last request before changing the clock.
- NTP polling is cancellable and ignores duplicate starts.
- Fixed a Kotlin visibility error in the mirroring queue that prevented the existing source from compiling.
- Split Settings update handling into a separate view-lifecycle controller.
- Resetting preferences requires confirmation and immediately restarts the receiver.

### Validation
- Native Android layout renders and TV focus/bitmap tests added to CI.
- Timing-packet regression tests cover stale, truncated and invalid replies.
- Real-device compatibility remains an ongoing test requirement; see [upstream review](docs/UPSTREAM-REVIEW.md).

## [1.7.0] - 2026-10-04 — PhairPlay is now Hearth

**The app is renamed. Nothing else about it changes.**

`PhairPlay` named the protocol rather than the product, and it named it with Apple's words. The
app is now **Hearth** — the warm centre of the home, which is where the TV already is. Full
reasoning, the runner-up names and the list of things that deliberately did *not* change are in
[docs/RENAME.md](docs/RENAME.md).

### Changed

- **Launcher label and app name** are `Hearth` (English, German, French and the Google TV
  flavour). The Settings version row reads `1.7.0 (… ) · Hearth (formerly PhairPlay)` so an
  update never looks like a different app.
- **Release asset** is `Hearth-<version>-googletv.apk`; the release title is `Hearth <version>
  for Google TV`. The download page, README, CI workflows, issue templates and device-log tool
  follow the new name.
- **Gradle root project** is `Hearth`.

### Unchanged on purpose (this is why your TV keeps working)

- **`applicationId` is still `com.phairplay.googletv`** and the **signing key is unchanged**, so
  the next release installs *over* the existing app — no uninstall, no lost settings.
- **Gradle properties and the Kotlin package** stay `phairplay.*` / `com.phairplay.*`; DataStore
  preference keys are untouched, so display name, toggles and the skipped-update choice survive.
- **The updater still reads `PhairPlay-…` assets** as well as `Hearth-…` ones: every release
  published before the rename must stay installable from inside the app (`UpdateInfoTest` covers
  both names).
- **The repository** is still `phairplay-archiver-fork-`, which is also the default
  `phairplay.updateRepo`.

## [1.6.1] - 2026-10-03

An AirPlay-connection release: the receiver can now hold a real session with an iPhone/iPad/Mac,
the Home screen shows the two things a TV can actually receive, and the updater can no longer
offer a build that is older than the one installed.

### Fixed — AirPlay connections (the "it shows up in the list, then nothing" bug)

**One sender connection at a time was the whole problem.** The RTSP listener served a single
client and answered every other connection with `503 Service Unavailable`. An Apple sender never
uses one socket: while it starts a session it opens the control channel *and* a second, silent
**event channel**, plus short-lived probes. So the moment the sender opened its second socket the
session was over — and because the first socket was still held by a coroutine blocked in `read()`,
the next attempt was refused too. `RtspServer` now accepts **every** connection and gives each one
its own handler (each with its own pairing and FairPlay state), keeps a bounded list of live
connections, and retires the oldest instead of refusing a reconnect.

**Asterisk-shaped discovery data.** The mDNS record, the `GET /info` capability reply and the
`GET /server-info` reply each described a different device (`AppleTV5,3` with features
`0x1E5A7FFFF7` in two of them, `0x5A7FFFF7`/`0x1E` in the third). A sender decides *how to pair*
from those values, so it would start a pairing dialect this receiver does not implement and give
up. All three now come from one place — `AirPlayIdentity` — advertising a legacy-pairing AirPlay
receiver (`AppleTV3,2`, features `0x5A7FFEE6` with bit 27 *SupportsLegacyPairing* on, `srcvers`
220.68) which is the profile the working open-source receivers use.

**The record the sender asks for first.** `GET /info` with a `{qualifier: ["txtAirPlay"]}` body
asked for the TXT record as a data blob and was answered with the capability dictionary. It now
answers the qualifier request with the raw TXT record (and `txtRAOP` for `_raop._tcp`).

**Mirroring sockets that could not be re-entered.** The video data server accepted exactly one TCP
connection; after any interruption the listener stayed bound (so the SETUP reply stayed valid) but
nothing was reading it, and the sender's reconnect died in the backlog. It now accepts in a loop,
flags the decoder to resync at the next keyframe, and the event channel does the same.

### Added — Apple Casting, and a connection log on the TV

**Apple Casting** replaces the Miracast card on Home. It is the screen-mirroring half of AirPlay:
the same receiver, reported from the **video stream** instead of the session, so the two cards are
honest — "AirPlay: Connected · Apple Casting: Waiting" means a Mac is streaming audio, not that a
phone is mirroring. The waiting line says exactly what to do (Control Centre → Screen Mirroring →
the advertised name) and see `docs/guides/APPLE_CASTING.md` for the full guide.

**The connection log** (the strip under the cards, or tap either card) lists every step of the
most recent sender connection — discovery, pairing, FairPlay, stream setup, the sender's video
connection — and a failure names the step it stopped at. On a TV this is the difference between
"it doesn't work" and "it stopped at fp-setup".

### Removed — Miracast

The Miracast / Wi-Fi Direct receiver is gone: `com.phairplay.miracast.*`, its card, its Settings
toggle, its tests and its `Wi-Fi Direct`, `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES`
permissions. Every Google TV in the test matrix either had no Wi-Fi radio on (wired sets) or keeps
Wi-Fi Direct to the system, so the receiver could only ever report *Unavailable*; it was a
permission prompt and a grey card for a feature that never ran. Screen mirroring from an Android
device belongs to the TV's own features.

### Changed — updater: never offer a downgrade, and say what is happening

* **A published build that is not newer than the installed one is never offered.** Equal
  versionCodes are "up to date"; a *lower* one is reported as "the newest published build is
  older than this install" (usual for a locally built APK, whose clock-derived code runs ahead).
  A release whose notes carry no `versionCode` at all is reported as unidentifiable rather than
  silently claimed to be current.
* **The downloaded APK is inspected before it is staged** (`PackageManager` versionCode), and
  anything not strictly newer than the install is deleted with an explanation — the release notes
  are scraped text, the APK is the truth, and Android would refuse the install anyway
  (`INSTALL_FAILED_VERSION_DOWNGRADE`).
* **"Skip this version"** now exists as its own action (separate from "Later"), is remembered, and
  suppresses background announcements/badges for exactly that build while a newer one is still
  offered.
* The update card shows **both builds**: "Version 1.6.1-main.7 (build 1451000) is available — you
  have 1.6.0 (build 1450123)".

### Docs

* New: [`docs/guides/APPLE_CASTING.md`](docs/guides/APPLE_CASTING.md) (connecting, stopping, and a
  field guide to the connection log) and a rename shortlist with a recommendation —
  applied in 1.7.0 as **Hearth**; the shortlist is kept at
  [`docs/archive/RENAME_IDEAS_2026-10-04.md`](docs/archive/RENAME_IDEAS_2026-10-04.md).

## [1.6] - 2026-10-03

Built for the TV in front of it: a Google TV 4K on Ethernet. Everything that only worked on a
Wi-Fi test set — or only worked in the changelog — has been taken out or fixed.

### Removed — the built-in Google Cast bridge

**PhairPlay no longer receives Cast at all, and that is the fix.** The Home screen carried a
permanent red card: *"Ports 8008, 8009 are already in use — this TV's built-in Chromecast is
handling Cast, so PhairPlay's receiver stays off."* That was not a fault to work around. Every
Google TV already runs its own Cast receiver, and it owns TCP 8008/8009 and the
`_googlecast._tcp` record before any app starts. A second receiver inside PhairPlay could only
ever lose that argument, and while losing it it took AirPlay down with it: the same process was
asking `NsdManager` to advertise a second Cast service and binding ports the system had claimed.

Removed with it: `com.phairplay.cast.*` (castv2 DIAL server, `SsdpResponder`, the receiver
bridge), the Cast Connect receiver and `ReceiverOptionsProvider` from the Google TV manifest, the
`play-services-cast-tv` dependency, the `CAST_APP_ID` build config (and with it the
`PHAIRPLAY_CAST_APP_ID` / `phairplay.castAppId` build inputs), the Cast toggle and Cast bridge
toggle in Settings, the third card on Home, `Protocol.CAST`, and the Cast source row on the stream
HUD. An installed TV's `cast_enabled` / `cast_bridge_enabled` preference keys are simply ignored
now; nothing has to migrate.

The Cast work described in [1.5] is therefore reverted rather than fixed — it is the entry that
documents the regression. Screen mirroring from Android devices is Miracast's job (below), and Air
Play-to-audio and Cast-style app hand-off are the TV's own.

### Fixed — AirPlay discovery on a wired TV

**An advertisement nobody could hear.** `MdnsService` registered `_airplay._tcp` from a pooled
worker on `Dispatchers.IO`. `NsdManager` dispatches its `RegistrationListener` callbacks on the
calling thread's Looper, and a background worker thread has none — so on a real TV registration
either never completed or failed, and the iPhone on the other end just said "no devices found".
Both registrations now happen on the main Looper, where the platform expects them, with a bounded
retry (four seconds apart, five attempts) if the TV's mDNS daemon says no the first time.

**Multicast was not ours to receive.** Android drops incoming multicast unless the app holds a
`WifiManager.MulticastLock`, and an mDNS *advertisement* is nothing but answers to multicast
queries. PhairPlay now takes the lock for exactly as long as it advertises — the same window the
Cast bridge used to hold it in, so removing Cast would otherwise have cost AirPlay its discovery.

**A network change ended the advertisement.** DHCP renewals and Ethernet/Wi-Fi handovers on a TV
are routine. `MdnsService` watches the default network with `ConnectivityManager` and re-advertises
(with a five-second cooldown) when it changes, instead of staying registered on an interface that
no longer exists.

**Nothing told you what was happening.** Home's AirPlay card now shows the interface and address
the advertisement is live on — `Advertising on Ethernet · 192.168.1.42` — which is how you can see
in one glance that the TV is reachable on the network your iPhone is joined to. A failed
registration is an error state with a message rather than an eternal "advertising…".

### Fixed — Miracast reported a permission problem it could not cause

*"Wi-Fi Direct unavailable or permission denied"* was shown on TVs where Miracast was never going
to run, which read as a PhairPlay bug. Google TV does not let third-party apps open a Wi-Fi Direct
group at all (and a set on Ethernet has no usable Wi-Fi radio to open one with), so the outcome is
"this TV cannot do it", not "you did it wrong". There is a new protocol state for that
(`ProtocolState.UNAVAILABLE`): grey, worded as a limitation, with the actual reason — no Wi-Fi
Direct feature on the build, radio off, permission not granted, or the Wi-Fi P2p stack refusing the
service (`reason N`). Genuine failures are still red. Miracast also now defaults to off instead of
prompting every wired TV for nearby-Wi-Fi and location permissions on first launch; enabling it in
Settings asks for those, and restarting is offered for the toggle to take effect.

### Fixed — audio dropped out during video

The RTSP keep-alive path could desync the interleaved RTP channel, which surfaced as audio
silently stopping a few minutes into a mirror. The interleaved framing now resynchronises on the
keep-alive boundary (this is the one piece of the reverted Cast branch that was worth keeping).

### Changed — releases carry one file

A PhairPlay release used to publish `PhairPlay-googletv.apk`, `SHA256SUMS.txt` and `version.json`.
It now publishes exactly one asset: the APK, named after its version —
`PhairPlay-1.6.0-main.43-googletv.apk`. The version name is read from that file name, and the
version code and SHA-256 are written into the release notes, so the in-app updater needs a single
HTTPS request instead of three, and a TV or Downloader session can no longer grab the wrong file.
Older releases, which do have the side files, still resolve.

### Changed — defaults

- AirPlay on, Miracast off, 4K mirroring offered on capable panels (`forceHighResolution` now
  defaults on: a 4K Google TV was being told to mirror at 1080p, which is why a wired 4K set
  looked soft).
- Settings → Update now restarts the receiver when a toggle needs it, rather than asking for a
  manual relaunch.
- Home shows two protocol cards, each with its own one-line detail; the layout no longer reserves a
  third slot that could only ever say "off".

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
- **Rolling `latest` release (historical 1.2 scheme)** — `.github/workflows/release.yml` moved a `latest` tag and replaced fixed-name assets. That old `PhairPlay-googletv.apk` path is obsolete; use the canonical release page at <https://github.com/2archiver/Hearth/releases/latest> for the actual version-named Google TV APK.
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
