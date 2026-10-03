# ADR-001: Multi-Protocol Support (AirPlay + Miracast + Cast)

**Date:** 2026-03-23
**Status:** Accepted — partially superseded by ADR-006 (2026-10-03): AirPlay + Miracast remain,
Google Cast was removed in v1.6

---

## Context

PhairPlay v1.0 was scoped to AirPlay 2 only (macOS senders). User feedback indicated demand for Miracast (Windows/Android senders) and Google Cast (Chrome/Android senders). Supporting all three makes PhairPlay a universal wireless display receiver.

## Decision

Support all three protocols simultaneously:
- **AirPlay 2** — for macOS and future iOS senders
- **Miracast (WFD)** — for Windows 10+ and Android senders
- **Google Cast** — for Chrome, Android, and iOS senders

Each protocol is implemented as an independent component that can be enabled/disabled via Settings.

## Rationale

1. **User experience**: Users should not need to know which protocol their sender uses. PhairPlay simply works.
2. **Independence**: Protocols don't share network ports or state. One can fail without affecting others.
3. **Graceful degradation**: If a protocol is unavailable (e.g., Cast without Google Play Services), it is hidden in the UI.

## Consequences

- Adds ~3 new package directories (`airplay/`, `miracast/`, `cast/`)
- Increases APK size by ~2-5 MB (Cast SDK dependency)
- The Cast receiver must gracefully handle missing Google Play Services or a missing Cast App ID
- Miracast requires `CHANGE_WIFI_STATE` and `ACCESS_FINE_LOCATION` permissions (Wi-Fi P2P)

## Alternatives Considered

1. **AirPlay-only** — simpler, but limits audience to macOS users only.
2. **AirPlay + Miracast, no Cast** — reduces dependencies but misses Chrome users.

---

## Amendment — 2026-10-03 (v1.6): Google Cast removed

Consequence 2 in Rationale ("Protocols don't share network ports or state") was wrong for Cast on
this platform, and it is why the third protocol is gone:

- A Google TV's **built-in Chromecast permanently owns TCP 8008 (DIAL), TCP 8009 (castv2) and UDP
  1900 (SSDP)**, plus the `_googlecast._tcp` mDNS record. A third-party receiver cannot bind them,
  so "independent protocols" was only true on devices with no Chromecast — which excludes every
  Google TV this app targets.
- PhairPlay's own bridge advertised a second `_googlecast._tcp` record through the same
  `NsdManager` that AirPlay needs, and the Home screen carried a permanent red card about the port
  clash. The Cast code was therefore a *dependency of the thing we actually ship*, not an optional
  extra.
- "Cast a video URL" (the Default Media Receiver) worked in tests but is not what people wanted;
  the apps they named (YouTube, Netflix) speak private receiver channels and hand off to the TV's
  own installed apps — which work fine without PhairPlay in the path.

Miracast stays: it uses Wi-Fi Direct (`WifiP2pManager`) rather than a contested TCP port, so the
original independence argument still holds — though it is now reported as **Unavailable** with the
reason when a TV refuses Wi-Fi Direct to apps, instead of as an error.
