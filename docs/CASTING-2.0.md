# Hearth 2.0 casting and validation record

This record separates code-path changes from sender-to-TV outcomes. A passing unit test, `/play` HTTP
response, parsed playlist, prepared player, audible audio, or still image is not evidence that moving
video rendered or that TV audio and controls were sustained.

## Evidence labels

- **USER-REPORTED PASS** — the user reports that the behavior worked; Hearth has not independently
  reproduced or instrumented it in this workspace.
- **TESTED PASS** / **TESTED FAIL** — an actual test was run and its result/evidence is recorded.
- **NOT TESTED** — no qualifying test was run. An implementation or protocol test does not change
  a real-device status to PASS.

## Status matrix

| Path or behavior | Current evidence/status | Notes |
|---|---|---|
| Gallery still photo sent through native Photos AirPlay | **USER-REPORTED PASS** | Preserve the 1.9.3 behavior; not independently retested in this workspace. This is a still-photo action, not native Photos `/play` video. |
| Gallery video fullscreen while screen mirroring | **USER-REPORTED PASS** | Preserve independently from still-photo display. It is not proof of native Photos AirPlay video. |
| Gallery image display while screen mirroring | **USER-REPORTED PASS** | Preserve independently from gallery video fullscreen and native Photos still-photo AirPlay. |
| Native Photos `/play` video (moving picture, sustained TV audio, controls) | **NOT TESTED** | A `.mov` URL accepted by `/play` is not video acceptance. |
| Native YouTube iOS app → its AirPlay picker → Hearth video | **NOT TESTED** | Do not substitute YouTube's TV-code flow, Control Centre screen mirroring, ordinary audio routing, Safari, or a playlist parse. |
| YouTube AirPlay video: continuous TV audio | **NOT TESTED** | Track separately from first-frame/video rendering. |
| YouTube pause / resume / seek / stop / reconnect controls | **NOT TESTED** | Requires a real sender and TV result. |
| YouTube audio-only AirPlay routing | **NOT TESTED** | Separate from the in-app video path and from screen mirroring. |
| iPhone Control Centre screen mirroring | **NOT TESTED** in this change | Separate AirPlay mirroring pipeline; not evidence for native-app video casting. |
| Rumble native-app video | **NOT TESTED** | No Rumble packet capture, matching TV log, or real moving-video run was available. The direct-HLS MIME/header repair is generic code-path coverage, not a Rumble compatibility claim. |
| Rumble sustained TV audio | **NOT TESTED** | Must be observed independently of video. |
| Rumble pause / seek / stop / reconnect controls | **NOT TESTED** | Must be observed independently of audio and video. |
| Stable FPS persistence, next-session behavior and pacing on a Google TV | **NOT TESTED** | JVM policy tests were added but could not be run because Java is unavailable; no Android build or hardware run is available here. No locked 60 fps is claimed. |
| Media Stop versus Stop receiver; update-install deferral during media/photo modes | **NOT TESTED** on hardware | Keep Media Stop from disabling receiver discovery. Keep Stop receiver separate. Updater install must remain deferred while any active audio, video, mirroring or still-photo mode is owned. |

The three gallery behaviors reported as passes above stay separate, and none is upgraded to
**TESTED PASS** by this patch. Native Photos video is a separate `/play` path and remains **NOT TESTED**.

## Hearth 2.0 implementation scope

- **Stable FPS:** persisted Boolean, default OFF for fresh and existing installs. Setting changes are
  for the next mirroring session; they do not restart active casting. The active mirror snapshots its
  Stable FPS flag and effective dimensions. Stable mode caps mirroring at 1080p or a lower known
  panel/decoder ceiling, and leaves the stored Higher resolution preference unchanged. Unknown
  capability probes retain the documented 1080p fallback. This is not sender negotiation and does
  not promise a locked 60 fps.
- **Mirroring pacing:** sender Q32.32 deltas map to a monotonic anchor; invalid/backward/implausible
  timestamps re-anchor. The AES-CTR reader remains ordered. Encoded queue bounds cover count, bytes
  and time, and encoded loss resumes only at an IDR with matched SPS/PPS. Stable mode's timestamp
  presentation is bounded; stale decoded frames may be discarded without corrupting the encoded
  reference chain. No sleeping is used. ExoPlayer URL video and gallery bitmap presentation are not
  paced by this feature.
- **Direct HLS URLs:** an extensionless/signed HTTP(S) URL can be classified from response MIME type
  or a short `#EXTM3U` prefix using a bounded probe through the existing redirect/TLS-guarded
  datasource. Failed/ambiguous probes fall back to progressive playback. Supported validated request
  headers are propagated without logging their values; sensitive headers are stripped across
  origins. Sender-local URLs remain distinct from receiver-local URLs.
- **YouTube:** preserve the existing sender-mediated PTTH/FCUP implementation; do not route
  `mlhls://` locations through direct HTTP or a competing receiver/player. A protocol path is not an
  end-to-end video/audio/control result.
- **Rumble:** no service-specific implementation was inferred. Exercise its actual AirPlay route and
  collect redacted evidence before claiming a fix. The generic direct-HLS/header change may be
  relevant but is not a device result.
- **Gallery and lifecycle boundaries:** preserve cache/session ownership, aspect-fit/fullscreen
  presentation, background decode, cancellation, stale-result rejection and bitmap cleanup; keep an
  empty video surface from covering a photo. Keep Stop casting distinct from Stop receiver and keep
  update installation deferred through active media/photo modes.

## Build identity and validation

- Source version: `phairplay.versionName=2.0.0` (candidate source version only).
- Branch: `arena/7ed1d5eb-hearth`, based on `79ffcfa4d718662985e706f2425418da01e15de`.
- Application ID/signing lineage and the `armeabi-v7a` plus `arm64-v8a` ABI filters are intended to
  remain unchanged. The Android `versionCode` remains clock/CI-derived and monotonic; it is not
  asserted until a build reads it from an APK.
- **Candidate APK: NOT BUILT.** No APK identity, versionCode, checksum or device install is available.
  `java`, `gradle` and `adb` are not on `PATH`; `JAVA_HOME`, `ANDROID_HOME` and `ANDROID_SDK_ROOT` are
  unset.
- Attempted all Gradle commands listed in `docs/TESTING.md` plus the release APK task:
  `./gradlew test`; `./gradlew testGoogletvDebugUnitTest`; `./gradlew test --tests
  "com.phairplay.airplay.RtspHandlerTest"`; `./gradlew connectedAndroidTest`; `./gradlew lint`;
  `./gradlew :test-runner:test`; `./gradlew :app:lintGoogletvDebug :app:assembleGoogletvDebug`;
  `./gradlew :app:testGoogletvDebugUnitTest --tests "com.phairplay.ui.HearthUiTest"`; and
  `./gradlew :app:assembleGoogletvRelease`. Also attempted unfiltered `:app:testGoogletvDebugUnitTest`
  and individual `:app:lintGoogletvDebug` / `:app:assembleGoogletvDebug` tasks. Every Gradle
  invocation exited 1 before Gradle started with `JAVA_HOME is not set and no 'java' command could
  be found in your PATH.` Thus Kotlin/Android tests, instrumented tests, lint and APK builds are
  **NOT RUN**; connected hardware was not available.
- Offline checks passed: `git diff --check`; `python3 tools/test_extract_release_notes.py` (4 tests);
  `python3 tools/check-workflows.py .github/workflows/*.yml`; parsing all 30 Android XML files; and
  parsing `gradle/libs.versions.toml`. These checks do not replace Kotlin compilation, Android lint,
  or a build.
- No hardware test has been run for this candidate. The target request is Google TV 4K / Android TV
  OS 14 with iPhone 14, but the actual TV model, OS/app versions, phone version and ABI must be
  recorded before any hardware result is added.

## Short device procedure

1. Record the installed candidate's APK version name/code, signing identity, ABI, TV make/model and
   Android TV OS/build, iPhone model/iOS version, app versions, and Wi-Fi/Ethernet topology.
2. Independently verify the three reported gallery paths above: (a) still photo AirPlay, (b) gallery
   video fullscreen while screen mirroring, and (c) image display while screen mirroring. Then test
   native Photos `/play` video separately with moving frames, sustained TV audio, pause/seek, Stop
   and reconnect.
3. Start a normal mirror with Stable FPS OFF; toggle the setting ON mid-session and confirm there is
   no restart or active-stream mutation. Stop the session, start a new one and verify the new session
   uses the capped offer. Repeat ON→OFF. Verify the stored Higher resolution preference is unchanged.
   Capture diagnostics; do not use a guessed FPS percentage or claim a fixed rate.
4. From the native iOS YouTube app, choose Hearth in its own AirPlay picker. Confirm actual moving
   frames and sustained TV audio, then exercise pause/resume/seek/Stop/reconnect. Test YouTube
   audio-only routing and Control Centre screen mirroring as separate cases.
5. Repeat the native-app moving-video, sustained-audio and control checks separately for Rumble.
   Include a redacted trace and identify whether it used direct HTTP(S), HLS or sender-mediated
   transport; do not include signed URLs or header values.
6. Verify Media Stop ends casting while discovery remains available; verify Stop receiver disables
   the receiver. With every media/photo mode active in turn, confirm update installation remains
   deferred until the media session has stopped.

Keep results as **TESTED PASS**, **TESTED FAIL** or **NOT TESTED**, with the build identity and
observable evidence. Do not promote user reports or source inspection to a hardware pass.
