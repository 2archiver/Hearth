# Hearth 3.8 maintenance validation

## Scope

Remove the unwanted Run session diagnostic action and its unused report helper. Keep Copy diagnostics and Clear. Date each exported activity entry, clarify retained history, and redact scoped IPv6 interface names completely. Keep 3.7 playback, discovery, encryption, audio ownership and the single TextureView transform unchanged.

The 1 November report includes a 3.7 startup at 15:35:52 local time. Entries preceding that startup are retained history and are not evidence of that new installation. The reported direct HLS sessions reached 1080p, had first-frame events around 4.4 and 2.0 seconds, and each recorded one rebuffer. These are user-supplied 3.7 observations, not a measured 3.8 improvement.

## Rotation issue remains open

The supplied recording shows upright landscape in one direction and upside-down landscape in the other. Each logged landscape transition reports an 888x1920 decoded raster, a 1920x886 display rectangle, and geometry fallback rotation 90; no explicit SEI orientation was detected. Those rectangles do not encode a direction. A global switch to 270 would break the currently upright direction. No speculative direction heuristic, decoder restart, pixel inspection, or new capability advertisement is added in 3.8.

H.264 display-orientation SEI already supports 90 and 270 degree transforms when present. The next protocol investigation needs a reliable sender direction field or a bounded, opt-in metadata trace; raw encrypted packets, keys and screen contents must not be exported. UxPlay's observed header layout was consulted for context; no GPL source was copied.

## Automated checks

Local protocol/JVM suite: 589 passed, zero failures. Selected Android tests: 41 passed, zero failures (10 TV layout, 21 decoder/SPS, 10 HTTP/Media3). This is the configured regression subset, not every app test. Android lint, native release compilation, APK signing and metadata verification must pass in the Release workflow before tagging or publication. The workflow retains its reports for review.

Release script checks: 42 passed. Release-note extraction: 4 passed. Workflow shell checks passed.

## Device checks

No physical Chromecast, iPhone, or network is attached to this build environment. 3.8's left/right rotation, Rumble, Spotify, Safari, YouTube, sleep/wake, installation upgrade and long-session acceptance are unmeasured. The known leftward rotation bug is disclosed in the release notes.

| Metric | 3.7 | 3.8 |
|---|---|---|
| Controlled first-frame benchmark | N/A | N/A |
| CPU / heap / thermal | N/A | N/A |
| Mirroring latency / frame intervals | N/A | N/A |
| Startup improvement | N/A | N/A |

## Build contract

Media3 1.11.1, Kotlin 2.2.21, AGP 8.10.1, Gradle 8.11.1, JDK 17. minSdk 29, targetSdk 35, compileSdk 36. One ARMv7-only APK, package com.hearth.tv, existing signing certificate. Published versionCode, size and SHA-256 come from the verified final APK and release workflow; previous releases stay immutable.
