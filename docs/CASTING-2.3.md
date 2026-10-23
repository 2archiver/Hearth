# Hearth 2.3 video casting

## Changes and evidence

Safari and YouTube are separate paths. Safari can supply direct HTTP(S) MP4/HLS to `/play`;
YouTube can supply `mlhls` playlists requiring reverse PTTH/FCUP requests and `/action` replies.

2.2 omitted Video (bit 0) and VideoHTTPLiveStreams (bit 4) from the shared capability mask.
2.3 advertises those implemented paths in mDNS, `/info` and `/server-info`, retaining legacy
pairing. This is a protocol correction, not evidence of successful playback on a particular sender.

The old `/play` reader synchronously waited for the first FCUP playlist. A sender returning its
`/action` on that same control connection could not be read until the wait timed out. Network
handlers now acknowledge accepted preparation immediately and use one bounded worker per control
connection. Actual playing state still requires a rendered frame. Stop/close interrupts the worker;
the coordinator retains generation checks and bounded fetch timeouts. A concurrent repeated `/play`
gets 409 rather than spawning another worker. Socket-free protocol tests retain synchronous routing.

Secondary video controls now join by exact Apple session fingerprint. Unknown IDs do not gain an
active playback token. Scrub replies use decimal points regardless of the TV locale. LAN HTTP
endpoints may redirect to HTTPS; each hop is checked and TLS downgrades remain blocked. Sensitive
headers are still stripped across origins. Decoder initialization may try another available decoder.

## Upstream review — 2026-10-23

- Latest stable [UxPlay v1.73.7](https://github.com/FDH2/UxPlay/releases/tag/v1.73.7).
- Current source inspected at [517cbb1240d9b9f69330c55b957cdd52b4175b2a](https://github.com/FDH2/UxPlay/commit/517cbb1240d9b9f69330c55b957cdd52b4175b2a)
  (2026-10-22): `lib/http_handlers.h` and `lib/fcup_request.h`. Its direct HTTP(S) video branch
  supports Safari, while sender-mediated playback sends the FCUP request without waiting inside
  the HTTP handler for its reply. Hearth's wire field names and PTTH framing were compared.
- [AirPlay video requests](https://openairplay.github.io/airplay-spec/video/http_requests.html)
  and [feature definitions](https://openairplay.github.io/airplay-spec/features.html) describe
  the direct MP4/HLS and capability-bit contract.
- No upstream implementation code or GStreamer dependency was imported. Hearth remains its own
  Kotlin receiver, not an embedded UxPlay build. Media3 remains at 1.4.1; the current Media3 stable
  is 1.11.1 per [Android release notes](https://developer.android.com/jetpack/androidx/releases/media3),
  but migrating that far needs separate Android/Kotlin toolchain and API validation. This PR does
  not claim to update Media3 or provide complete AirPlay 2/DRM support.

## Device acceptance — pending

Use the existing installed app's update path with the same signing key. Record iOS/YouTube versions,
TV model/Android version and the exact Hearth APK version. Keep the exported Activity log.

| Flow | Checks | Status |
|---|---|---|
| Safari direct MP4 | Video element AirPlay button → Hearth; visible first frame and one soundtrack; pause/resume, seek, stop, recast | NOT TESTED |
| Safari HLS | Repeat with a public unprotected HLS video, including extensionless media URL if available | NOT TESTED |
| YouTube iPhone app | Apple Devices/AirPlay → Hearth; visible video, one soundtrack, seek, pause, next video, stop and recast | NOT TESTED |
| General direct casting | Another unprotected MP4/HLS sender, portrait clip, receiver restart and network interruption recovery | NOT TESTED |
| Regression | Screen mirroring and audio-only AirPlay still work; stale controls cannot stop another session | NOT TESTED |

For a failure distinguish: no `/play` (discovery/sender routing), rejected `/play` (negotiation),
FCUP sent but no matching `/action` (sender transport), manifest/network error, decoder error,
or first-frame timeout. Exported logs redact media URLs and credentials. DRM, browser-local blob
URLs and sites requiring credentials the sender does not transfer are not established as supported.

## Automated validation

`VideoCastingRegressionTest` covers discovery bits, exact-ID secondary controls, locale-independent
scrub replies and same-control-connection `/play` → `/action` progress. Existing FCUP, HLS bridge,
coordinator, URL-player and ownership suites remain in CI. Release workflow checks pass locally;
local Gradle could not download its distribution due to network restrictions. CI and device results
must be checked before publishing 2.3 as verified.
