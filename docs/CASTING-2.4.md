# Hearth 2.4 validation

The receiver now sends standard `category=video` reverse-HTTP status events, independently of
FCUP playlist requests, using an exact Apple session ID and generation-bound playback token.
Video events are serialized off the UI thread, deduplicated by channel/state and ignored for
replaced sessions. Unknown or ambiguous reverse channels are not selected by IP address.

Buffering uses Media3 state and buffered position. A prepared player no longer reports the entire
movie as loaded or every buffer as full. A seekable range is supplied only when the current item
is seekable. A stall after the first frame ends with a network failure after 30 seconds; startup
and missing-surface timeouts remain in place.

References: [AirPlay video events](https://openairplay.github.io/airplay-spec/video/events.html)
and [video requests](https://openairplay.github.io/airplay-spec/video/http_requests.html).
No website names, media URLs or sender credentials belong in release notes or test fixtures.

## Installation

The application ID is now `com.hearth.tv`; source namespace is retained internally. Android cannot
update a different package in place. Download `Hearth2.4.0.apk`, stop/uninstall the old receiver,
install 2.4 and set preferences again. Two receivers must not compete for the AirPlay ports.
Future updates use the new identity and the existing signing certificate. Release checks verify
that package ID, both supported ARM ABIs, signature and versionCode before publication.

## Device acceptance (pending)

- Safari direct MP4 and HLS: loading resolves, visible first frame, one soundtrack.
- Pause, seek, resume, end, stop and recast, including a mid-stream network interruption.
- YouTube: FCUP playlists still load and its reverse-channel acknowledgements remain readable.
- Screen mirroring and audio-only routing after removing the optional pacing feature.
- Activity log: held D-pad movement, latest/oldest shortcuts, details, filters and copy.
- Overlay/diagnostic version matches About and the installed APK.

A cloud browser can inspect a web player but cannot reproduce an iPhone-to-TV AirPlay session.
No hardware success is claimed until these checks pass on the target devices.
