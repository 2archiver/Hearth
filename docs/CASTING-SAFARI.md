# Safari-first video casting investigation

## Receiver changes

- Direct HTTP playback now validates `206 Content-Range` against the requested offset before
  passing bytes to Media3. Wrong offsets fail rather than sending a mismatched MP4/HLS fragment
  into the decoder. Short final byte ranges are capped to the available response window.
- Premature EOF for a known-length response is an IO failure, allowing player retry instead of
  silently treating truncated video as a complete resource.
- The URL data-source wrapper exposes the guarded HTTP response headers to Media3.
- Extensionless HLS probing retries a normal GET when a server rejects bounded Range with 400
  or 416. Reading remains capped at 1 KiB; authentication failures are not retried. The same
  redirect, TLS and credential rules apply. This is media classification, not scheduled probing.

## What the research establishes

[WebKit: MSE and AirPlay](https://webkit.org/blog/15036/how-to-use-media-source-extensions-with-airplay/)
explains that Safari needs a shareable playback URL. A browser-only blob/MSE source cannot be
fetched on a TV. A site can supply an alternate MP4 or HLS source; the receiver cannot manufacture
one from a page URL. This applies separately to each site's Safari player, including Rumble and
YouTube; do not assume the native apps and Safari send the same transport.

[Media3 HLS documentation](https://developer.android.com/media/media3/exoplayer/hls)
confirms MPEG-TS and fMP4/CMAF support and explicit HLS source selection for URLs without a
`.m3u8` suffix. Keep Hearth's existing guarded classifier and HlsMediaSource for these sources.
Hardware codec support still depends on the TV.

[UxPlay](https://github.com/FDH2/UxPlay) documents its YouTube HLS support separately and explicitly
states that streaming through a browser's AirPlay icon is not supported by that implementation.
It is useful reference material for FCUP, not evidence that Safari works. Hearth retains its
existing sender-mediated bridge and session ownership; no capability bits are broadened.

## Hardware acceptance, in priority order

All rows remain **NOT RUN** until tested with an iPhone and target Google TV. A 200 response,
source preparation or audio playback alone is not evidence of working video.

| Sender and source | Required evidence |
| --- | --- |
| Safari direct MP4 | Visible first frame + audio; seek forward/back; pause/resume; stop/recast |
| Safari HLS, including extensionless URL | Video variant selected, first frame + audio; playlist refresh; seek if VOD |
| Safari redirected media | Relative segment paths use final playlist URL; no leaked cross-origin credentials |
| Rumble in Safari | AirPlay picker selects Hearth; record whether `/play` arrives; first frame + audio |
| YouTube in Safari | Same; distinguish unsupported browser source from receiver HTTP/decoder failure |
| YouTube app | Existing FCUP reverse-channel playlist flow; first frame + audio; seek and next video |
| Rumble app | Record the actual selected transport and verify first frame + audio independently |
| Regression | Photos, mirroring, audio-only, stop/recast, network interruption |

Use the Activity Log to identify `/play`, direct vs sender-mediated source, HTTP status, track
selection and first frame. Do not publish signed media URLs, cookies or authorization headers.
If Safari never sends `/play`, investigate the site's source/route choice before changing decoding.
If it sends `/play` but HTTP fails, investigate response status/ranges; if tracks exist but no frame
renders, inspect surface and codec evidence. Protected video remains unsupported.
