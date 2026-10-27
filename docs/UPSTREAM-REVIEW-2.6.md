# Hearth 2.6 upstream review

Reviewed 27 October 2026. Safari direct video is the first acceptance target, followed by Rumble
and YouTube in Safari, then their native apps. Native-app success does not establish browser success.

## Sources and concrete changes

| Source reviewed | Finding | Hearth change |
| --- | --- | --- |
| [UxPlay play handler](https://github.com/FDH2/UxPlay/blob/28ed70a9876fe3f5864479f4e0d07abe4678383a/lib/http_handlers.h) | Current source handles HTTP(S) direct locations, including Safari; older README language is incomplete. | Keep the direct MP4/HLS path separate from FCUP and correct the older research note. |
| [android-airplay-server renderer](https://github.com/jqssun/android-airplay-server/blob/a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea/app/src/main/kotlin/io/github/jqssun/airplay/renderer/VideoRenderer.kt) | Drain output between short input-buffer waits; avoid unnecessary loss of reference frames when busy. | Independently implement a bounded input pump: ten 10 ms waits, draining between them, with interruption handling. Total input-wait budget remains 100 ms. |
| [RFC 8216](https://www.rfc-editor.org/rfc/rfc8216) and [Media3 HLS](https://developer.android.com/media/media3/exoplayer/hls) | The initial HLS resource may be a media playlist, not only a multivariant master. Live playlists must refresh. | Sender-mediated root media playlists now use the existing URI rewriting, signed URL preservation and encryption checks. Cache ended VOD roots; refresh live roots. |
| [Media3 HTTP data source](https://github.com/androidx/media/blob/1.4.1/libraries/datasource/src/main/java/androidx/media3/datasource/DefaultHttpDataSource.java) | Transport encoding must be decoded before media parsing. | Decode gzip full responses, avoid using compressed Content-Length as a decoded byte count and reject compressed partial bodies. |
| [Media3 data sources](https://github.com/androidx/media/tree/1.4.1/libraries/datasource) | Byte-range windows must respect resource bounds; a zero-length read returns zero. | Avoid bridge seek overflow from huge requested lengths, reject seeks beyond the resource, retain zero-length read semantics. |
| [WebKit](https://webkit.org/blog/15036/how-to-use-media-source-extensions-with-airplay/) | MSE/blob playback alone is not a TV-fetchable URL. | Preserve clear unsupported-source diagnostics and require a site-provided AirPlay URL; do not pretend a page URL is video. |

No new GPL implementation files are copied into this Apache-licensed tree. See
[acknowledgements](../ACKNOWLEDGEMENTS.md) for source and dependency credits. Larger upstream features
such as OpenGL surface pipelines, speculative codec flags and software decoder fallback are not
introduced without device evidence. The existing Android Media3 dependency is retained to keep the
release's decoder/SDK requirements stable.

## Automation

One PR verification job shares a checkout/SDK/Gradle setup for JVM tests, lint, debug APK, TV layout
and HTTP data-source tests. The separate duplicate lint workflow is removed. Main runs the release
pipeline's equivalent gates once; README/docs-only changes do not build an APK. Superseded PR runs
are cancelled, while publishing remains serialized without cancelling an upload.

Release notes use one short changelog summary, one download/update instruction and collapsed build
metadata. Hash, versionCode, signing certificate and both ARM ABI checks remain. The previous build
`v2.5.0-main.34` receives only the requested display title `2.5 nightly` during the 2.6 transition.

## Evidence boundary

Regression tests cover root VOD/live HLS, protected-media rejection, bridge seeking, bounded decoder
backpressure and interruption. Automated checks cannot measure TV FPS or prove Safari, Rumble or
YouTube playback. Complete the [Safari-first hardware checklist](CASTING-SAFARI.md) on an iPhone and
Google TV before describing a particular site as working. No measured FPS/latency gain is claimed.
