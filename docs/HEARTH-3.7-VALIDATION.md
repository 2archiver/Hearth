# Hearth 3.7 ARMv7 candidate validation

Prepared 1 November 2026 (Australia/Brisbane). Base: `dd3c96f` on main (3.6). Review branch: `feat/hearth-3.7-armv7-modernisation`. This document records the rc.1 preparation snapshot, before publication. The maintainer subsequently authorized pushing 3.7 to main and releasing `hearth3.7.apk` on 1 November 2026; see RELEASING.md. Existing release history remains immutable.

## Scope and compatibility

This is a conservative subset of the complete brief. It preserves the 3.6 TextureView transform, display geometry, direct/FCUP HLS data-source guards, FairPlay/AES ordering, encoded-frame queue bounds, Spotify metadata/D-pad routes and soundtrack handover. Their existing code paths remain in use; physical-device compatibility is not proven by compilation or unit tests.

Implemented: coordinated Media3/toolchain migration; ARMv7-only packaging; small-control-response TCP_NODELAY and progressive-MIME probe completion at headers; opt-in fixed receiver-buffer profiles with bounded timestamp release; local read-only audio hardware timing/underruns; source/first-frame/rebuffer diagnostics; a concise local session report; honest type-103 warning; explicit stable publication and immutable retries. No generic “Why doesn't this cast?” feature is added.

Default mirroring remains Original/3.6 behavior. Responsive 30 ms, Balanced 60 ms and Stable 120 ms profiles are experimental receiver-buffer targets, latched per session. They are not glass-to-glass measurements. Bounded timestamp scheduling reanchors excessive decode lead instead of parking the sole codec owner. Disable an experimental profile if it degrades this hardware.

Deferred: adaptive jitter tuning, allocation pools, new native media dependencies, HEVC, real type-103 decoding, multi-room/PTP, track/subtitle picker and color overrides. These require profiling, protocol fixtures or physical-device evidence and would add risk to the stable core. No new codec, protected-content or certified AirPlay 2 capability is advertised. See AIRPLAY2-FEASIBILITY-3.7.md.

## Toolchain and corrective evidence

- Media3: 1.11.1, verified in the release runtime dependency graph for all eight modules: common, container, datasource, decoder, exoplayer, exoplayer-hls, extractor, database. No Media3 1.4.1 remains in that graph.
- Kotlin: 2.2.21; old global 1.9.23 stdlib force removed; JVM test runner uses the plugin's stdlib.
- AGP: 8.10.1; Gradle: 8.11.1, distribution SHA-256 pinned; JDK: 17.
- compileSdk: **36**, required by eight resolved Media3 1.11.1 AAR metadata checks. The initial compileSdk35 attempt failed `:app:checkGoogletvDebugAarMetadata` with that exact requirement. No metadata checks were suppressed.
- targetSdk35, minSdk29, NDK28.2.13676358, CMake3.22.1, package `com.hearth.tv`, one `armeabi-v7a` APK.
- Existing community signing certificate is preserved; final APK certificate verification is reported with the artifact.

New AGP lint also reported 26 style-only `UseKtx` suggestions in existing framework calls. That advisory rule is disabled alongside the existing style exclusions because shared protocol sources intentionally compile without AndroidX in the standalone runner. API/correctness checks and Kotlin metadata checks remain enabled.

The first JVM run had 66 failures caused by MockK/Byte Buddy dynamic self-attach being unavailable in this container. Preloading the existing test-only agent fixed the common root cause; no tests were removed or assertions weakened. This agent is not packaged into the app.

## Reproducible commands

Use JDK17 and an installed SDK36/NDK/CMake. The build's network proxy and CA setup are local environment settings, not application changes.

```bash
python3 tools/test_extract_release_notes.py
python3 tools/test_release_workflow.py
python3 tools/check-workflows.py .github/workflows/*.yml
./gradlew :test-runner:test :app:testGoogletvDebugUnitTest \
  --tests 'com.phairplay.ui.HearthUiTest' \
  --tests 'com.phairplay.airplay.VideoDecoderSpsTest' \
  --tests 'com.phairplay.airplay.MediaHttpDataSourceTest' \
  :app:lintGoogletvDebug :app:assembleGoogletvRelease -Pphairplay.versionName=3.7-rc.1
./gradlew :app:dependencies --configuration googletvReleaseRuntimeClasspath
```

CI uses the full standalone protocol suite plus app-only Media3 HTTP, decoder SPS and rendered TV UI gates to avoid duplicating the entire pure JVM suite. Release verifies metadata, exact ABI set, signature, hash, monotonic code, tag commit and publication freshness before publishing.

## Final configured verification evidence

- Standalone JVM protocol suite: **587 tests, 0 failures, 0 errors**.
- Android subset: **41 tests, 0 failures, 0 errors** (HearthUiTest 10, VideoDecoderSpsTest 21, MediaHttpDataSourceTest 10). Rendered Activity Log/settings previews were visually inspected for fit.
- Release workflow tests: **42 passed**; release-notes tests: **4 passed**; all workflow shell blocks passed syntax checking.
- `:app:lintGoogletvDebug`: **PASS**, “No issues found”.
- `:app:assembleGoogletvRelease`: **PASS** including R8 and native ARMv7 compilation; release build completed in 6m27s in this environment.
- `apksigner verify --verbose --print-certs`: **PASS**. Certificate matches the downloaded published 3.6 APK: `b9cb7e4b4d5179870e672e850f5d3661f02c248f943dbaf10cd78cd0c047eaae`.
- APK: `Hearth-3.7-rc.1-googletv.apk`, package `com.hearth.tv`, versionCode **1490697**, minSdk29, targetSdk35. Published 3.6 is code **1490630**; candidate is newer.
- All three native libraries (`libalac.so`, `libplayfair.so`, `libdatastore_shared_counter.so`) are verified ELF32 ARM under `lib/armeabi-v7a/`; no arm64 library is packaged.
- APK SHA-256: `fb453463ff7e0d8d71e99885c54864410d31264c42dd80447b10b6e73c311ebd`.
- Binary implementation corresponds to source commit `5d9dc1c` plus its ancestors on this branch; the final documentation commit only records validation evidence.

## Supplementary broad app-module sweep

A supplementary unfiltered `:app:testGoogletvDebugUnitTest` attempt ran 638 tests with 26 failures: MdnsServiceTest (6), RaopRsaTest (1), ImmutableReleaseTest (6), UpdateInfoTest (12), NetworkUtilsTest (1). This is not recorded as a pass. The plain-JUnit duplicates in that task use AGP's default-return Android stubs (including JSON/Base64 behavior); the designated standalone JVM runner supplies real android-all classes instead. NetworkUtilsTest also expects `Hearth` where the current source intentionally defaults to `Apple TV`. These are outside the repository's configured Android subset. The original CI split is retained; no assertions or failed tests were deleted or suppressed. Do not describe this work as every repository test passing. Real mDNS/device lifecycle acceptance remains pending.

## Baseline rotation confirmation

On 1 November 2026, the maintainer confirmed that Hearth 3.6 rotation works well on the Chromecast. Treat that as the working baseline to preserve. The 3.7 TextureView rotation/flip transform is unchanged; 3.7 physical-device regression acceptance is still pending.

## Hardware performance comparison

| Measurement | 3.6 baseline | 3.7 candidate |
|---|---|---|
| APK bytes (actual downloaded/built files) | 13,946,545 | 12,018,046 |
| APK MiB | 13.30 | 11.46 |
| Native ABI set | ARMv7 + arm64 | ARMv7 only |
| Discovery → connection | N/A | N/A |
| Direct MP4 first actual frame | N/A | N/A |
| Direct HLS / sender HLS first actual frame | N/A | N/A |
| Mirror rendered-callback FPS / p95 frame interval | N/A | N/A |
| Glass-to-glass latency | N/A | N/A |
| CPU / peak heap / GC / thermal behavior | N/A | N/A |
| Audio hardware underruns / drift | N/A | N/A |
| Startup / rebuffer duration | N/A | N/A |

APK size is a measured file-size comparison; both ABI filtering and dependency/shrinker changes contribute. It is not an isolated Media3 size benchmark. No device measurements or runtime percentage improvements are claimed. A receiver's render callback is not evidence of physical panel presentation. Local AudioTrack timestamps do not establish RTP/video synchronization; A/V offset remains N/A without a real common anchor.

## Required 3.7 real-device acceptance (all pending)

- Chromecast Google TV 4K, Android14/API34 and ARMv7; record sender iPhone14/iOS27.2 and actual network.
- Portrait → landscape right/left → portrait; fixed viewport, flips, fit and Surface recreation. The maintainer reports 3.6 rotation working well; verify that 3.7 preserves that behavior.
- Rumble1080p, Safari MP4/HLS/extensionless HLS, native/Safari YouTube; controls, seeks, 30-minute sessions and reconnects.
- Spotify phone/TV pause/resume, next track, volume, metadata, stable art and audio-only lifetime for 30 minutes.
- Photos/slideshow transitions, stop/start, app backgrounding, sleep/wake, network interruption and second sender.
- Same-media/network baseline versus candidate CPU/heap/frame/first-frame/underrun measurements.
- In-place installation over the user's actual installed package/certificate.

Previously failing sites such as americafirst.plus are not declared fixed. Unsupported browser blobs, denied credentials, protected sources and absent shared media URLs remain source-dependent limitations.

## Shipping decision

This is a review/testing candidate, not a stable release approval. A failed build, test, signature, ABI or updater check blocks shipping. Hardware regressions, leaks, abnormal resource use or unsupported feature advertisement also block stable publication. The maintainer must explicitly authorize a stable release after device acceptance.
