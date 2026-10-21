# Hearth 2.0 focused upstream review

Reviewed 2026-10-22 for the optional mirroring pacing and direct-HLS URL classification changes. This
is a protocol/source review, not evidence of hardware playback. Hearth remains an Apache-2.0 app;
no upstream implementation code was copied.

## UxPlay: pinned release and current source

| Reference | Revision/date | Files examined | Findings and disposition |
|---|---|---|---|
| [UxPlay v1.73.7 release](https://github.com/FDH2/UxPlay/releases/tag/1.73.7) | Release published 2026-09-04; tag commit `df67c212a433cf6dda3676dd40c097900d24e645` | `lib/raop_rtp_mirror.c`, `lib/byteutils.c` | The 128-byte mirror packet carries its timestamp at bytes 8–15. `byteutils_get_long` documents/reads a little-endian 64-bit value; packet timestamps are Q32.32. Video packets are decrypted in order. A pending SPS/PPS config is prepended only when its raw timestamp matches the following encrypted video payload; UxPlay's source comment says this payload is almost always an IDR, not an unconditional guarantee. Hearth keeps config pending until a matching configured recovery IDR and binds it to that recovery frame. |
| [UxPlay current master snapshot](https://github.com/FDH2/UxPlay/commit/dbb8a8f451597aa5f2e56c830ce41c4a655a8f98) | Current `master` at review time: commit `dbb8a8f451597aa5f2e56c830ce41c4a655a8f98`, dated 2026-10-21; untagged snapshot | `lib/raop_rtp_mirror.c`, `lib/raop_ntp.c`, `lib/byteutils.c`; latest commit changes `lib/fcup_request.h`, `lib/http_handlers.h`, `lib/http_request.c`, `lib/http_request.h`, `lib/httpd.c`, `lib/httpd.h`, `lib/raop.c` | Current mirror code still reads the timestamp from bytes 8–15 and requires raw-timestamp equality before prepending pending SPS/PPS; it now applies NTP-epoch/fixed-offset handling and tracks a video-arrival offset. The latest commit reworks reverse-HTTP connection classification/parsing toward EVENT. These untagged changes are research only; Hearth does not adopt the NTP/session or reverse-HTTP lifecycle changes without compatible sender evidence. |
| [UxPlay issue #550](https://github.com/FDH2/UxPlay/issues/550) | Open; last updated 2026-09-25 | Issue report and discussion | Reports YouTube/HLS transition and seek problems; comments also describe a fullscreen lifecycle crash hypothesis without a verified reproduction. Treated as leads, not proof or a Hearth test result. No X11/GStreamer-specific workaround was imported. |

The pinned mirror source describes its remote timestamp as Q32.32, with AirPlay's mirror packet
header providing the raw value at bytes 8–15. Hearth maps only forward deltas onto a monotonic
arrival anchor; it does not claim a sender/local wall-clock synchronization. Invalid, backward or
implausibly large deltas re-anchor and increment diagnostics. Encoded overflow requires a configured
IDR. Stable FPS schedules only decoded output using that bounded mapping and drops stale decoded
presentation; it does not sleep, negotiate a rate, or assume a fixed 60 fps.

## Other focused references

| Reference | Revision/date | Files examined | Findings and disposition |
|---|---|---|---|
| [RPiPlay](https://github.com/FD-/RPiPlay) | Current `master` at `64d0341ed3bef098c940c9ed0675948870a271f9` (commit dated 2022-01-11; snapshot reviewed 2026-10-22) | `lib/raop_rtp_mirror.c`, `lib/raop_ntp.c`, `lib/byteutils.c` | A historical mirroring reference for timestamp conversion and mirror-packet processing. It corroborates a separate mirrored-video path but predates the selected UxPlay release; no code or Android-specific behavior was adopted. |
| [OpenAirPlay protocol references](https://github.com/openairplay/airplay-spec) | `master` at `00063da0e7f16c20fbfb5d29331334da1a89590a` (commit dated 2020-07-21; snapshot reviewed 2026-10-22) | `src/screen_mirroring/stream_packets.md`, `src/screen_mirroring/time_synchronization.md` | Used to keep stream-packet and timing terminology distinct. Hearth's Stable FPS mapping remains receiver-local and monotonic; it is not an implementation of AirPlay sender rate negotiation or a replacement for full NTP synchronization. |
| [Shairport Sync](https://github.com/mikebrady/shairport-sync) | Current `master` at `b991d91350e537c1f50dac98d9a3c836eceddcfc`, dated 2026-10-21 | `README.md`, `rtp.c`, `AIRPLAY2.md` | The README explicitly states that Shairport Sync does not support video or photo streaming; `rtp.c` is an audio/control/timing path. No mirroring fix was selected, and no code or dependency was added. |

## Applied, limited to Hearth-owned paths

- AirPlay mirroring keeps AES-CTR consumption in network packet order. Queue loss clears the incomplete
  encoded chain and waits for an IDR with usable SPS/PPS; it never resumes on a predicted frame.
- The encoded queue is bounded by frame count, bytes and age. Overflow retains a valid IDR-led suffix
  when one remains, otherwise it waits for a new recovery point. Queue age, byte depth, overflow,
  IDR wait, timestamp source/discontinuities and scheduled/stale output are exposed as diagnostics.
- Stable FPS is a persisted, opt-in, next-session-only policy (default OFF). It caps only mirroring's
  advertised/decoder request at 1080p or a lower reported panel/decoder ceiling, preserves the stored
  Higher resolution preference, and does not pace gallery bitmaps or ExoPlayer URL video.
- Extensionless direct HTTP(S) media is classified using a bounded range probe through Hearth's
  redirect-guarded HTTP source. HLS response MIME types are preferred; a short `#EXTM3U` body prefix
  is the fallback. Probe failure falls back to progressive playback, which still uses the existing
  guarded datasource. Validated request headers are passed without logging values; credential-like
  headers are removed on cross-origin requests/redirects.
- This generic direct-media handling may apply to Rumble URLs that use HLS or request headers, but
  **does not establish Rumble compatibility**. Native YouTube AirPlay, screen mirroring and
  audio-only routing remain separate flows and separate test results.

No GPL implementation code was copied. No GStreamer, AWDL, extra mDNS, or multiroom dependency was
added. Hearth does not claim complete AirPlay 2 support.
