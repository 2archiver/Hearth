# Open-source acknowledgements

Hearth is built on the work of the following projects. Thank you to their maintainers and contributors.

| Project | Role in Hearth | License / reference |
| --- | --- | --- |
| [PhairPlay](https://github.com/mazer666/PhairPlay) | Original Android receiver base; frame safety and discovery reference | Preserve the inherited notices and source credits. |
| [AndroidX Media3](https://github.com/androidx/media) | Actual URL-video/HLS playback engine and data-source contracts | Apache-2.0; dependency remains 1.4.1 in this release. |
| [UxPlay](https://github.com/FDH2/UxPlay) | AirPlay direct-video, FCUP and playlist transport research | GPL-3.0; reviewed source at `28ed70a9876fe3f5864479f4e0d07abe4678383a`. |
| [android-airplay-server](https://github.com/jqssun/android-airplay-server) | Decoder backpressure/draining and sender compatibility research | GPL-3.0; reviewed source at `a46cca0a6a1274e9e33c0e750f0f1c1eb1d881ea`. |
| [RPiPlay](https://github.com/FD-/RPiPlay) | Existing mirroring/protocol reference | GPL-3.0; see the project's own notices. |
| [OpenAirPlay specification](https://github.com/openairplay/airplay-spec) | HTTP video controls and reverse-event documentation | Documentation reference; consult the upstream license. |

The 2.6 changes are independent Kotlin implementations of protocol/data-source behavior and the
bounded drain/retry idea. This release does not vendor new GPL source files or change dependency
versions. Existing native-code notices and obligations remain applicable; this document does not
replace them or assert that all inherited components share Hearth's top-level license.

Additional primary references: [WebKit on Safari/AirPlay sources](https://webkit.org/blog/15036/how-to-use-media-source-extensions-with-airplay/),
[RFC 8216 HLS](https://www.rfc-editor.org/rfc/rfc8216), and
[Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec).
