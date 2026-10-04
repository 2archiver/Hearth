# Upstream review for the 1.8 remaster

Reviewed on 2026-10-04 against [UxPlay](https://github.com/FDH2/UxPlay) commit
`8ef677617c864756ae930a565edf0ae03e7da134`, and the projects in the README acknowledgments.
This is a focused compatibility and robustness review, not a wholesale import of upstream code.

## Applied to Hearth

- **Timing exchange:** UxPlay's `lib/raop_ntp.c` documents the reply's originating timestamp at
  bytes 8–15 as the echo of the last transmitted request. Hearth now checks that echo, RTP
  version, timing response type, datagram length, sender address/port and nonzero sender times
  before applying a clock offset. Late replies and unrelated packets cannot become clock samples.
  New JVM tests cover matching, stale, truncated, wrong-type and empty-clock packets.
- **Timing lifecycle:** the NTP loop uses cancellable coroutine delays and a tracked job instead
  of sleeping an IO thread. Starting twice no longer creates duplicate loops; stop closes the socket.
- **Playback display:** UxPlay documents screensaver inhibition during playback and cover-art
  rendering. Hearth uses Android's `keepScreenOn` on the visible playback container, releases it
  when playback ends, and samples cover art and photos to bounded dimensions before decoding.
- **Receiver UI lifecycle:** a single parent job owns each service binding's collectors. It is
  cancelled on unbind/rebind so background/foreground cycles do not accumulate observers.
- **Photo routing:** an available photo takes priority over the generic connected-session video
  surface, which could otherwise hide the photo behind an empty surface.

## Deliberately not advertised as added support

UxPlay's experimental internal mDNS responder, macOS-only AWDL, GStreamer HLS language selection,
Bluetooth beacon and recording features are platform-specific work. They are not new Hearth
capabilities in this release. Existing Android NSD, MediaCodec and AirPlay identity remain in use.

Hardware validation is still required for real iPhone/Mac mirroring, audio/video synchronization,
network loss/reconnection, screen-saver behavior, remote media control and long playback sessions.
The tests and rendered previews establish software behavior, not a new hardware compatibility claim.
