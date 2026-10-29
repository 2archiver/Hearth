# Hearth 2.7: Safari and Spotify

## Changes and evidence

The screenshot shows the generic audio card, with neither track metadata nor cover art. The old
receiver accepts DMAP and raw image SET_PARAMETER bodies but has no POST /command route. iOS can
send Now Playing through that route. 2.7 accepts the `updateMRNowPlayingInfo` / `npi-text` envelope,
with replace/update semantics and embedded artwork, while keeping legacy DMAP/image support.
A progress-only update reuses the previous artwork bytes; replacement clears stale fields.

The protocol envelope and field names were checked against the primary sender implementation in
[Music Assistant airplay-cli, ap2_mrp.c](https://github.com/music-assistant/airplay-cli/blob/main/src/ap2_mrp.c),
`ap2_mrp_build_nowplaying_command` and `ap2_mrp_build_nowplaying_progress_command`. This is a
wire-compatibility implementation, not evidence that a particular Spotify/iOS version sends it.
Hearth does not implement the encrypted type-130 MediaRemote channel. A sender that offers only
that channel, without the HTTP metadata fallback or DACP, still needs additional protocol work.

Artwork and command bodies have a 2 MiB limit instead of the generic 64 KiB control-message limit.
The command reader keeps binary bodies out of UTF-8 conversion, preserves persistent framing and
rejects oversized uploads before reading/allocating their payload. Artwork still uses the existing
bounded background bitmap decoder.

Remote input is intercepted before a focused background view can consume OK. OK toggles playback;
left/right send previous/next for audio and seek ten seconds for URL video. Explicit play/pause
keys retain their separate meanings. Repeated key-down events and key-up do not duplicate actions.
DACP discovery normalizes hexadecimal IDs and DNS name case, accepts split identity headers,
serializes commands, and retains at most four presses for three seconds while resolving. Old
resolution callbacks and queued commands cannot take over a replacement sender identity. The
HTTP target is built with URI, supporting IPv6 addresses without malformed host/port syntax.

The 2.6 direct-media source rejected all gzip 206 responses immediately. 2.7 retries one normal
GET through the same redirect/header guards and skips decoded bytes to the requested offset.
It still rejects a compressed partial response returned to that normal GET. Initial bounded
range requests rejected with 400/416 get one normal-GET retry. HLS identification now reads only
a bounded prefix of a normal GET, avoiding a speculative Range request. Wrong identity-encoded
206 offsets, truncation, and cross-origin credential protections remain enforced. This addresses
identified failure paths; the user's exact Safari regression has not been reproduced on hardware.

## Validation status

- Release-note and workflow validation: passed locally (4 + 33 tests), workflow shell syntax passed.
- Added automated tests: metadata/cover replacement and merge behavior; command routing and binary
  framing above 64 KiB; oversized and malformed bodies; remote mappings and DACP name matching;
  real-socket gzip-206 recovery and decoded seeking.
- Kotlin/JVM protocol tests, Robolectric TV/media tests, Android lint and signed APK build:
  PASSED in [release workflow 40844884379](https://github.com/2archiver/Hearth/actions/runs/40844884379).
  APK version, signature, both ARM ABIs and monotonic update code checks also passed.
  The local Gradle wrapper could not download its distribution; validation ran on GitHub Actions.
- iPhone/Spotify/Safari/TV acceptance: NOT RUN. No target hardware is attached.
- Published on main after explicit authorization: [Hearth 2.7](https://github.com/2archiver/Hearth/releases/tag/v2.7),
  source commit `a655a1c2592ba2591738f6c865c1c5549913c4e7`.

## Hardware acceptance

1. Cast from Spotify: verify title, artist, album and cover; change tracks and seek on the phone;
   confirm progress updates retain the cover and stop/recast does not retain the previous song.
2. On the TV remote: OK pauses/resumes once; left/right change tracks once per press. Test the
   first press while discovery resolves and reconnect with a second sender.
3. Safari: test direct MP4, HLS and the previously failing source in each previously used mode.
   Require visible video and audio, then pause/resume, seek, stop and recast. Generic audio,
   a 200 response, or player preparation alone does not demonstrate working video.
4. Check mirroring, Photos and legacy audio for regressions. Retain redacted diagnostics privately.
