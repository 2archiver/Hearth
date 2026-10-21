# Hearth 1.9.3 — implementation and casting acceptance record

## Scope and honest status

This document separates code-path implementation from observed sender-to-TV playback. Source review,
unit tests, successful HTTP responses, parsed playlists, native still-image rendering, and audible
audio are not substitutes for the exact hardware acceptance run.

| Area | Implemented in source | Automated tests | Real-device status |
| --- | --- | --- | --- |
| Receiver shutdown reasons and separate Media Stop | Yes; initiating reason is recorded before listener/socket teardown. Media Stop leaves receiver discovery/listeners running. | Regression coverage added; **NOT RUN** (Gradle blocked; see Validation). | **NOT TESTED** |
| Hourly update checks and install safety | Yes; WorkManager best-effort cadence, shared eligibility state, retry/rate-limit deadlines, one in-flight check, staged verified download, and install deferral during casting. | Policy/scheduling regression coverage added; **NOT RUN**. | Doze, force-stop, notification delivery and package installation on TV: **NOT TESTED** |
| YouTube iOS app → AirPlay picker → Hearth video | Receiver-mediated `/play` / PTTH / FCUP handling and video-only master selection implemented; this is not a playback acceptance claim. | Protocol regressions added; **NOT RUN**. | **NOT TESTED** — no real TV/sender run or rendered first-frame evidence in this workspace. |
| Native Photos still image / slideshow | `cacheOnly` and `displayCached`, session-keyed bounded cache and cancellable bounded decode/display implemented. | Cache/action regressions added; **NOT RUN**. | **NOT TESTED** — no iPhone Photos-to-TV run. |
| Native Photos video | Kept separate from still-photo cache/display and routed through media `/play`; no native Photos video success is asserted. | URL and media-policy coverage is not end-to-end sender evidence; **NOT RUN**. | **NOT TESTED** — do not mark fixed. |
| Stop icon and receiver actions | Focused square Stop icon with accessible “Stop casting”; receiver buttons remain “Start” / “Stop receiver”. | UI tests/checks not run. | **NOT TESTED** on a TV remote/focus device. |

No row in this table should be upgraded to “hardware accepted” without recording the sender app,
TV model/OS, network topology, rendered moving frames, audio, controls, Stop, and reconnect result.

## 1.9.2 receiver-shutdown trace: evidence and limits

The supplied 1.9.2 trace shows that key exchange progressed and that the receiver subsequently shut
down. By itself, it does **not** show that shutdown was spontaneous; it does **not** identify an
encryption failure; and a `SocketException` observed around teardown does **not** establish that the
exception initiated shutdown. A local close commonly causes the peer/read side to report a socket
exception, so temporal proximity is not a causal chain. The root cause remains **undetermined** from
the available trace.

For 1.9.3, whole-receiver shutdown now records a bounded, structured initiating reason before the
RTSP listener and accepted sockets are closed (`USER_REQUESTED`, `APP_TASK_REMOVED`, `RESTART`,
`SERVICE_DESTROYED`, or `UNSPECIFIED`). Per-connection close traces distinguish that commanded
shutdown from peer EOF, timeout, parse/read failure, and socket exceptions. This improves the next
trace; it does not retroactively prove which path caused the 1.9.2 event, nor does it guarantee a
reason can be logged after abrupt process death or power loss.

## Relevant source review and design boundaries

- Reviewed the pinned UxPlay revision
  [`5f5f9357e4bf5d1c972fc2546ee247a231c6bc31`](https://github.com/FDH2/UxPlay/tree/5f5f9357e4bf5d1c972fc2546ee247a231c6bc31)
  for AirPlay reverse-channel / PTTH / FCUP behavior. A reverse socket can be offered on a
  different TCP connection from `/play`; Hearth does not merge sockets on peer IP or “only one
  pending channel”. The exact AirPlay session ID is the association signal for a cross-connection
  offer. Late and stale callbacks are scoped to their session/resource owner.
- Reviewed [`seancheung/airplayer`](https://github.com/seancheung/airplayer), including its HLS
  player and Photos handling. Its GPL-3.0 license and receiver-owned local HLS HTTP proxy make its
  source and localhost assumptions unsuitable for direct copying into Hearth. **No AirPlayer or
  UxPlay implementation code was copied.** Hearth keeps its own `hearth-hls://` sender-mediated
  bridge and its guarded direct HTTP(S) datasource; a sender-supplied `localhost` is not the TV's
  `localhost`.
- AirPlayer confines ExoPlayer access to the main thread and snapshots state for synchronous native
  playback callbacks. Its local HTTP proxy is part of that design and is not interchangeable with
  Hearth's FCUP route. AirPlayer's Photos service uses cache/display actions; Hearth implements the
  corresponding actions with a smaller explicit session boundary, bounded cache/decode and
  cancellation rather than borrowing its source.
- An HLS master with audio renditions but no video variant is not a successful YouTube video
  negotiation. Such a master is rejected; only a rendered first frame can be evidence of video
  playback. Audio by itself, a prepared player, an HTTP 200, or decrypted playlist/key data does
  not count.
- Direct media URL policy rejects loopback hosts in the request, playlist references, and each
  redirect hop (including HTTPS). The app does not proxy by default, loosen host/TLS verification,
  scrape YouTube, use TV codes, or substitute mirroring for native app AirPlay.

## Release identity and update behavior

The source version is `phairplay.versionName=1.9.3`. The existing Google TV flavor, application ID,
update repository and release metadata contract, signing identity/lineage, ABI selection and
monotonically increasing CI `versionCode` policy remain unchanged. Automatic update checks are
best-effort: WorkManager can be deferred by Doze/sleep, and force-stop prevents background work.
Manual **Check now** is immediate except while another check is in flight or a GitHub rate limit is
active. Update notifications announce availability/readiness only. An install is not launched over
active audio, video, mirroring or a displayed still image; the user can install after casting stops.

## Validation

- Regression tests were added for updater cadence/retry/rate limiting and single flight; receiver
  Stop/cleanup ownership; reverse-channel correlation and PTTH/FCUP handling; audio-only HLS
  rejection; loopback URL/redirect policy; and Photos actions/cache isolation/eviction.
- Local attempts to run `./gradlew test`, `./gradlew :test-runner:test`, `./gradlew lint`, and
  `./gradlew assembleGoogletvRelease` stopped before Gradle with
  `JAVA_HOME is not set and no 'java' command could be found in your PATH.` The earlier OpenJDK
  install attempt was blocked by unreachable Debian package repositories. Therefore Kotlin tests,
  lint and the release APK task could not be run locally.
- Remote PR CI passed `:test-runner:test`, Google TV Android lint, debug APK assembly, and
  `HearthUiTest` on the final updater-policy code. CI uploaded the installable Google TV debug APK as
  the `debug-apk-googletv` artifact; it is a debug build, not the requested release-variant build.
  `assembleGoogletvRelease` was not run, so no release APK is claimed.
- Offline checks passed: `git diff --check`, XML parsing for 32 Android resource/manifest files,
  version-catalog TOML parsing, workflow shell/YAML validation, and the four release-notes helper
  tests. These checks do not replace hardware acceptance.

## Required device acceptance before claiming fixes

1. **YouTube video:** from the native iOS YouTube app, select Hearth in the AirPlay picker. Capture
   the TV's first rendered moving frame, sustained video and audio, pause/resume/seek behavior,
   playback status, Media Stop (receiver remains discoverable), then reconnect and repeat. Record TV
   model/OS, iOS/app version, and whether video and audio are both sustained. Do not use Control
   Centre audio, screen mirroring, Safari or another sender as a substitute.
2. **Photos still:** separately send an image already downloaded locally and an iCloud-only image;
   exercise cache-only then display-cached, slideshow/transition, replace/delete, Stop and reconnect.
   Confirm stale-session cache entries do not render after reconnect.
3. **Photos video:** separately initiate a native Photos video AirPlay session and record moving
   frames, continuous audio, pause/seek, Stop and reconnect. A `.mov` URL parse or a passing HTTP
   request is not acceptance.
4. **Updater/lifecycle:** while playing, verify availability notifications do not launch an installer;
   stop casting and manually choose installation. Verify receiver discovery remains active after
   Media Stop but stops after **Stop receiver**. Observe update timing across idle, Doze and reboot;
   record Android's actual deferrals instead of asserting wall-clock precision.

Until these runs are performed, YouTube video, native Photos still and native Photos video remain
**NOT TESTED** on hardware.
