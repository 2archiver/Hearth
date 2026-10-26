# Hearth 2.5 upstream review

Reviewed 2026-10-26 against PhairPlay's latest published release,
[v1.0.0-alpha.5](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-alpha.5),
commit `c7330f0`. Hearth's starting point was `d79ac6a` (2.4.0).

| Upstream change | Decision for Hearth |
| --- | --- |
| FU-A reassembly bound, `7b5122a` | Adapt the 8 MiB bound in Hearth's existing depacketizer, keeping Hearth's STAP-A support and persistent RTSP reader. Test exact-boundary completion, overflowing completion, endless fragments and subsequent recovery. Source: Apache-2.0 PhairPlay `RtpInterleaved.kt`. |
| SPS Exp-Golomb overflow, `cc555b9` | Adapt Long arithmetic and the 65535 value bound in production and the JVM stub. Add boundary/overflow regressions and run the production parser tests in Android CI and the release gate, too. |
| Safe bitmap decoding, `ff993e2` | Hearth already reads image dimensions and samples before allocation through `BoundedBitmap`. Keep that path, including support for photos larger than the display; upstream rejects oversized source dimensions. Address the remaining main-thread artwork decode and recycle replaced artwork instead. |
| Network recovery, `1384548` and `347ff97` | Hearth already watches the default network and retries failed mDNS. Apply the useful principle of avoiding initial/duplicate registration churn. Keep DHCP/address-change recovery rather than importing a second network observer or the upstream DLNA restart path. |
| Resource diagnostics, `b1e511f` | Useful for observing CPU/RAM but does not itself accelerate playback. Do not add continuous polling or a second diagnostics UI in this release. |
| DLNA/Samsung discovery and control | A separate renderer/media pipeline with additional services and endpoints. Not a drop-in AirPlay performance fix; leave outside this focused update. |
| Miracast and Google Cast | Upstream still documents a control-plane-only Miracast path and no bundled Google Cast receiver. Do not advertise these as new Hearth casting capabilities. |
| Alpha.5 Play Store/AAB tooling, translations, release scripts | Keep Hearth's existing signed APK pipeline, package and updater; the store/tooling changes do not improve receiver playback. |

## Changes and expected cost

- An unfinished FU-A unit cannot retain more than 8 MiB of payload. Overflow is checked before
  append and clears the incomplete unit; continuation fragments are ignored until a new start.
  This does not cap the whole Android process, codec, or mirror queue at 8 MiB.
- Discovery works from usable IPv4 address transitions, not every onAvailable/properties callback.
  A 500 ms trailing refresh replaces the old behavior that could discard a recovery occurring
  inside a 5-second cooldown. The initial matching address and identical updates do no work.
  A network loss cancels pending work; stale callbacks cannot mark a newer registration active.
- Native album-art decoding runs on Dispatchers.Default. A mutex prevents overlapping decodes
  inside one card; cancelled or superseded completions cannot replace newer artwork. Existing
  bounded decoding and full colour quality remain unchanged.

These changes remove work and bound memory; their effect on TV CPU, FPS, latency and peak RAM is
not measured here. No new permission, dependency, protocol endpoint or external network service.

## Validation

Local release-automation tests, release-note tests, workflow shell validation and `git diff --check`.
The local Gradle wrapper could not download Gradle 8.7 (`Network is unreachable`), so JVM protocol,
Robolectric, lint and APK checks must complete in GitHub Actions before release publication.

On the TV: install 2.5 over 2.4; check screen mirroring, Photos and direct URL playback; disconnect
and reconnect Wi-Fi/Ethernet (including within 5 seconds); confirm the iPhone discovers Hearth
again; change music tracks rapidly and verify artwork and controls remain responsive; stop/recast.
Compare the same sender/TV session with 2.4 before claiming a performance gain.
