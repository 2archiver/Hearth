# Hearth 1.8.1 direct-video compatibility patch

Prepared against main `9fe52abfde3e587dcfa83eb05726ecafccc9a448`.
Upstream comparison: FDH2/UxPlay master
`8ef677617c864756ae930a565edf0ae03e7da134` (source reports 1.74).
This is an Android implementation of selected direct-video behavior, not a full UxPlay merge.
UxPlay's GStreamer renderer and Unix discovery infrastructure are not Android components.
The referenced upstream commit is its `master` branch, not a verified release tag.

## UxPlay comparison

UxPlay 1.74's `http_handler_play` distinguishes absolute `Start-Position-Seconds` from the legacy
fractional `Start-Position`; Hearth adopts those request semantics for its Android `MediaPlayer`
path. No upstream implementation code is copied. UxPlay renders through GStreamer, while Hearth's
Android platform player and existing RAOP/mirroring pipelines remain separate. The internal HLS
FCUP path is not included here; see [Remaining UxPlay HLS work](#remaining-uxplay-hls-work).

## Changes

- Route modern `Start-Position-Seconds` to a seconds-aware player path; retain the legacy
  `Start-Position` fraction. Give explicit seconds precedence if both are supplied.
- Parse binary and XML plists plus legacy text `/play` bodies. Accept only valid direct HTTP(S)
  media URLs, preserve signed query strings, and reject malformed locations or non-finite/negative
  offsets instead of passing them to native playback.
- Mark URL video as active and select the existing video overlay, preventing the audio metadata
  screen from hiding a URL movie. Reset that state at stop, completion or player failure.
- Serialize URL-player lifecycle and controls on the main Looper through a testable playback
  controller. The RTSP thread reads an immutable volatile snapshot; callbacks from replaced players
  are ignored.
- Wait for a valid video surface before starting playback; reattach replacement surfaces and pause
  while one is unavailable. Preserve pause and seek commands received during preparation.
- Clean up failed players and terminate preparation/surface waits after 30 seconds. Record
  URL-video failures in AirPlayTrace without recording full signed media URLs in new diagnostics.
- Use the connected AirPlay state to show the streaming surface and suppress the audio metadata
  card; do not mark a URL-video session as screen mirroring.

RAOP audio, mirroring codecs, crypto, timing, discovery and advertised capabilities are unchanged.

## Validation status

`git diff --check`, the release-note extraction tests, and the workflow checker pass; the 1.8.1
release-note heading resolves correctly. Added parser/route regressions and a fake-backend playback
suite covering seconds/fraction seeks, early pause/seek, late and replacement surfaces, stale
callbacks, error cleanup, preparation/surface timeouts and rapid release/restart. Neither
`./gradlew :test-runner:test --no-daemon` nor `./gradlew :app:assembleGoogletvRelease --no-daemon`
can start Gradle here: this environment has no `java` executable or `JAVA_HOME`. The Kotlin tests
are **not run** and no Google TV release APK is **built**.
No Rumble/iPhone/TV packet capture or crash log was available. These are concrete URL-mode fixes,
not a verified diagnosis of Rumble's crash or a guarantee of Rumble compatibility.

## Remaining UxPlay HLS work

Current UxPlay separates direct HTTP(S) locations from internal HLS locations ending in
`/master.m3u8`. The internal locations require PTTH reverse events, FCUP request IDs,
`POST /action` playlist responses and a local playlist proxy. Hearth's `/reverse` upgrade alone
is not that complete implementation. This patch rejects unsupported internal locations safely;
it does not implement the FCUP bridge. Determine from a Rumble trace whether it is needed before
porting it. Preserve existing mirroring and audio routes, and review upstream licensing before
copying code into this Apache-licensed project.

## Required checks before publishing

1. Run `./gradlew :test-runner:test --no-daemon`, then the Google TV release build used by CI.
2. On an Android/Google TV, cast a known direct MP4 and HLS URL; verify video plus audio,
   initial seconds and fractional positions, pause/seek during load, stop and retry.
3. Test bad media, missing surface, background/resume, replacement surface, rapid new `/play`
   and stale completion/error callbacks. Verify cleanup and no app crash.
4. Reproduce Rumble casting and collect AndroidRuntime/MediaPlayer logs plus the AirPlayTrace.
   Redact signed URLs, tokens and personal information before sharing logs.
5. Verify working iPhone/Mac mirroring, audio-only AirPlay and reconnect behavior on hardware.
6. Publish only after validation, with the existing signing key, package ID, updater repository
   and a versionCode above the latest published APK. CI should generate 1.8.1-main.N-googletv.
