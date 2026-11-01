# AirPlay capability audit and feasibility, 3.7

This patch leaves advertised identities, feature bits, pairing schemes, supported codecs and the working FairPlay ordering unchanged. The legacy AppleTV3,2 profile remains the default. There is no HEVC or new AirPlay 2 capability claim.

## Retained identity and transport audit

| Field | Current source | Interpretation |
|---|---|---|
| Model | `AppleTV3,2` | Legacy pairing identity |
| Feature mask | `0x5A7FEFF3` in mDNS and /info | Existing mask retained; no new HEVC/type-103 bit introduced |
| Source/protocol version | `220.68` / `1.1`, `vv=2` | Compatibility fields, not AirPlay 2 certification |
| Flags/status | `0x4` / `68` | Existing legacy behavior retained |
| RAOP transport | `UDP` | Existing real-time audio pipeline |
| Buffered type 103 | TCP accept/inspect only | No audible playback implementation |

Legacy public-key setup and session verification remain in use; HomeKit/SRP pairing is not implemented. This audit does not validate every historical feature-mask bit against every sender. Changing legacy identity/clock bits requires a separate sender regression matrix.

## Buffered audio (type 103)

Current implementation accepts a TCP transport and discards received bytes; it has no packet decoder, authenticated anchor timeline, decryptor or audible output. A connected socket is not playback. The 3.7 warning says this explicitly and raw payload-prefix dumps have been removed. Replacing the transport stub is deferred; rejecting previously accepted SETUP messages without sender testing could disrupt existing legacy negotiation.

Shairport Sync demonstrates open-source AirPlay 2 audio on supported Linux systems. Its deployment with NQPTP establishes that a blanket claim that all open-source buffered receivers are impossible is incorrect. It does not establish a working unrooted Android port. Further work must establish authenticated control/session derivation, framing/sequence handling, specifically supported encryption/codec paths, clock anchor semantics, network permissions for the required timing protocol and Android AudioTrack presentation timing. Hardware timestamps added here are local observations; they do not supply an RTP/video or PTP anchor.

Before enabling type 103, build bounded parser fixtures for truncation/oversize/wrap, authenticated control failures, anchor discontinuities, encrypted streams and teardown/reconnect generations. Demonstrate actual PCM decode and hardware presentation on the Chromecast with a named sender. Never substitute zero audio or advertise accept-only support as playback.

## HEVC and 4K

A hardware codec listing does not establish sender negotiation or a working mirror receiver. Required independent work includes ScreenMultiCodec negotiation, incoming codec selection, bounded VPS/SPS/PPS parsing, IDR/reference recovery, encryption-state preservation, `video/hevc` setup, sender/device fixtures and safe H.264 fallback. Keep all advertisement disabled until end-to-end physical-device tests pass. Current resolution preferences are unchanged; they do not prove 4K/60 input or protected playback.

## License boundary

No code from UxPlay, RPiPlay, Shairport Sync or other GPL implementations was copied for this patch. Existing native FairPlay/ALAC provenance and distribution obligations need a separate exact-license audit before a new distribution claim. The top-level Apache license alone does not settle transitive/native obligations. Retain all original notices; do not import GPL source merely as a performance shortcut.

Primary references:

- https://github.com/FDH2/UxPlay
- https://github.com/mikebrady/shairport-sync
- https://github.com/mikebrady/nqptp
- https://github.com/openairplay/airplay-spec
- https://github.com/FD-/RPiPlay
