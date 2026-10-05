package com.phairplay.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HLS playlist parsing and reference rewriting — the core of the sender-mediated transport.
 *
 * What each test protects:
 *  - **signed bytes are never touched**: an absolute URL that the player *can* fetch is handed over
 *    unchanged, query string included (rewriting it would break the signature);
 *  - **relative references resolve against the playlist the sender gave us**, not against the URI
 *    the player will end up requesting;
 *  - **the player is never silently downgraded**: a reference this receiver cannot serve fails the
 *    rewrite instead of being dropped, and a protected playlist fails instead of silently losing
 *    its segments;
 *  - **YouTube's condensed tag is expanded**, including the empty-`PARAMS=""` form that real
 *    playlists carry (independent receivers had to fix exactly that case).
 */
class HlsPlaylistCodecTest {

    private val masterUri = "https://cdn.example/video/master.m3u8?token=abc"

    @Test
    fun `a master playlist keeps its variants, renditions and their signed urls`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",URI="https://cdn.example/video/en.m3u8?sig=1"
            #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,URI="iframe.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="audio"
            v800/prog.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=400000,CODECS="avc1.4d401e,mp4a.40.2"
            https://other.example/v400.m3u8?expires=99
        """.trimIndent() + "\n"

        val parsed = HlsPlaylistCodec.parseMaster(master)
        // The i-frame stream is a variant too (it carries a URI), so: i-frame + two stream-inf lines.
        assertEquals(3, parsed.variants.size)
        assertEquals(1, parsed.renditions.size)
        assertEquals(HlsPlaylistCodec.MasterPlaylist.Shape.SEPARATE_AUDIO, parsed.shape)

        var index = 0
        val served = mutableListOf<String>()
        val rewrite = HlsPlaylistCodec.rewriteMaster(master, masterUri) { resolved, _ ->
            served += resolved
            "bridge:${index++}"
        }
        val ok = rewrite as HlsPlaylistCodec.Rewrite.Ok

        assertEquals(
            listOf(
                "https://cdn.example/video/en.m3u8?sig=1",
                "https://cdn.example/video/iframe.m3u8",
                "https://cdn.example/video/v800/prog.m3u8",
                "https://other.example/v400.m3u8?expires=99",
            ),
            served,
        )
        // The alternate audio rendition and the audio group survive: if the player cannot reach a
        // rendition, it must be because of a fetch failure, not because the rewrite deleted it.
        assertTrue(ok.text.contains("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\""))
        assertTrue(ok.text.contains("AUDIO=\"audio\""))
        // The codec replaces what the callback replaced and reports both counts; it does not decide
        // for the bridge. Here every reference was bridged, so: one rendition + one i-frame `URI=`
        // attribute rewritten in place, and the two bare variant lines replaced on their own line.
        assertEquals(2, ok.text.lines().count { it.startsWith("bridge:") })
        assertTrue(ok.text.contains("URI=\"bridge:0\""))
        assertTrue(ok.text.contains("URI=\"bridge:1\""))
        assertEquals(listOf("bridge:2", "bridge:3"), ok.text.lines().filter { it.startsWith("bridge:") })
        assertEquals(4, ok.bridged)
        assertEquals(0, ok.direct)
    }

    @Test
    fun `an absolute url the player can fetch is preserved byte for byte`() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:6.0,
            https://cdn.example/seg/1.ts?sig=AbC%2Fd%3D&exp=1700000000
        """.trimIndent() + "\n"

        var directCalls = 0
        val relativeFlags = mutableListOf<Boolean>()
        val rewrite = HlsPlaylistCodec.rewriteMedia(media, masterUri) { resolved, relative ->
            assertEquals(false, relative) // written out in full by the sender
            relativeFlags += relative
            if (HlsPlaylistCodec.isDirectlyFetchable(resolved)) {
                directCalls++
                resolved
            } else {
                "bridge:0"
            }
        } as HlsPlaylistCodec.Rewrite.Ok

        assertEquals(listOf(false), relativeFlags)
        assertEquals(1, directCalls)
        assertEquals(1, rewrite.direct)
        assertEquals(0, rewrite.bridged)
        assertTrue(
            "the signed query must be handed over exactly as the sender wrote it",
            rewrite.text.contains("https://cdn.example/seg/1.ts?sig=AbC%2Fd%3D&exp=1700000000"),
        )
    }

    @Test
    fun `a url naming the sender's own machine is never treated as fetchable`() {
        // The sender's HLS locations name its own machine (`localhost:<port>`); a player on the TV
        // can never reach those, so such a reference must stay on the sender's transport.
        assertFalse(HlsPlaylistCodec.isDirectlyFetchable("http://localhost:64321/x/seg.ts"))
        assertFalse(HlsPlaylistCodec.isDirectlyFetchable("http://127.0.0.1/seg.ts"))
        assertFalse(HlsPlaylistCodec.isDirectlyFetchable("https://[::1]/seg.ts"))
        assertFalse(HlsPlaylistCodec.isDirectlyFetchable("http://0.0.0.0:8080/seg.ts"))
        assertTrue(HlsPlaylistCodec.isDirectlyFetchable("https://rr1---sn-abc.googlevideo.com/videoplayback/seg.ts"))
        assertTrue(HlsPlaylistCodec.isDirectlyFetchable("http://192.168.1.20:8008/seg.ts"))
    }

    @Test
    fun `relative segment references resolve against the playlist's own url`() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            ../segments/000.ts
            #EXTINF:6.0,
            extra/001.ts
        """.trimIndent() + "\n"

        val resolved = mutableListOf<String>()
        val relativeFlags = mutableListOf<Boolean>()
        HlsPlaylistCodec.rewriteMedia(media, "https://cdn.example/video/v800/prog.m3u8?token=t") { uri, relative ->
            resolved += uri
            relativeFlags += relative
            "bridge:${resolved.size - 1}"
        }
        assertEquals("both references were relative paths in the sender's playlist", listOf(true, true), relativeFlags)

        assertEquals(
            listOf(
                "https://cdn.example/video/segments/000.ts",
                "https://cdn.example/video/v800/extra/001.ts",
            ),
            resolved,
        )
    }

    @Test
    fun `init maps, byte ranges and discontinuity markers survive a rewrite`() {
        val media = """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-BYTERANGE:1000@0
            #EXTINF:6.0,
            seg0.m4s
            #EXT-X-DISCONTINUITY
            #EXT-X-MAP:URI="https://cdn.example/init2.mp4?x=1"
            #EXT-X-BYTERANGE:1500
            #EXTINF:6.0,
            seg1.m4s
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"

        val parsed = HlsPlaylistCodec.parseMedia(media)
        assertEquals(1, parsed.discontinuityCount)
        assertTrue(parsed.hasByteRanges)
        assertNotNull(parsed.initMapUri)

        val mapped = mutableListOf<String>()
        val rewrite = HlsPlaylistCodec.rewriteMedia(media, "https://cdn.example/v/prog.m3u8") { resolved, _ ->
            mapped += resolved
            if (HlsPlaylistCodec.isDirectlyFetchable(resolved)) resolved else "bridge:${mapped.size - 1}"
        } as HlsPlaylistCodec.Rewrite.Ok

        assertEquals(
            listOf(
                "https://cdn.example/v/init.mp4",
                "https://cdn.example/v/seg0.m4s",
                "https://cdn.example/init2.mp4?x=1",
                "https://cdn.example/v/seg1.m4s",
            ),
            mapped,
        )
        assertTrue(rewrite.text.contains("#EXT-X-BYTERANGE:1000@0"))
        assertTrue(rewrite.text.contains("#EXT-X-BYTERANGE:1500"))
        assertTrue(rewrite.text.contains("#EXT-X-DISCONTINUITY"))
        assertTrue(rewrite.text.endsWith("#EXT-X-ENDLIST\n"))
    }

    @Test
    fun `a live playlist is recognized by the absence of an end marker`() {
        val live = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\na.ts\n"
        val vod = live + "#EXT-X-ENDLIST\n"

        assertTrue(HlsPlaylistCodec.parseMedia(live).isLive)
        assertFalse(HlsPlaylistCodec.parseMedia(vod).isLive)
        assertEquals(6, HlsPlaylistCodec.parseMedia(vod).targetDurationSec)
    }

    @Test
    fun `a reference that cannot be served fails the rewrite instead of being dropped`() {
        val media = "#EXTM3U\n#EXTINF:6.0,\nseg.ts\n"

        val rewrite = HlsPlaylistCodec.rewriteMedia(media, "https://cdn.example/v/prog.m3u8") { _, _ -> null }

        val failed = rewrite as HlsPlaylistCodec.Rewrite.Failed
        assertEquals(HlsPlaylistCodec.ReferenceKind.SEGMENT_URI, failed.referenceKind)
        assertFalse("the reason must stay URL-free", failed.reason.contains("cdn.example"))
    }

    @Test
    fun `a protected playlist is reported, never served as if it were playable`() {
        val media = """
            #EXTM3U
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:6.0,
            seg.ts
        """.trimIndent() + "\n"

        val rewrite = HlsPlaylistCodec.rewriteMedia(media, "https://cdn.example/v/prog.m3u8") { _, _ -> "bridge:0" }

        val failed = rewrite as HlsPlaylistCodec.Rewrite.Failed
        assertEquals(HlsPlaylistCodec.ReferenceKind.KEY_URI, failed.referenceKind)
        assertTrue(failed.reason.contains("SAMPLE-AES"))
    }

    @Test
    fun `a plain aes-128 key uri is rewritten like any other reference`() {
        val media = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6.0,
            seg.ts
        """.trimIndent() + "\n"

        val mapped = mutableListOf<String>()
        HlsPlaylistCodec.rewriteMedia(media, "https://cdn.example/v/prog.m3u8") { uri, _ ->
            mapped += uri
            "bridge:${mapped.size - 1}"
        }

        assertEquals(listOf("https://cdn.example/v/key.bin", "https://cdn.example/v/seg.ts"), mapped)
    }

    @Test
    fun `youtube's condensed tag expands into absolute segment urls with empty params`() {
        // The empty PARAMS form is what real playlists carry; NX-Cast had to fix exactly this case.
        val media = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-TARGETDURATION:5
            #YT-EXT-CONDENSED-URL:BASE-URI="https://rr1---sn-abc.googlevideo.com/videoplayback/",PARAMS="",PREFIX=""
            #EXTINF:5.0,
            seg-1.ts
            #EXTINF:5.0,
            seg-2.ts
        """.trimIndent() + "\n"

        val rewritten = HlsPlaylistCodec.rewriteMedia(media, "https://cdn.example/v/prog.m3u8") { resolved, _ ->
            if (HlsPlaylistCodec.isDirectlyFetchable(resolved)) resolved else "bridge:0"
        } as HlsPlaylistCodec.Rewrite.Ok

        assertTrue(rewritten.condensedExpanded)
        assertTrue(
            rewritten.text.contains("https://rr1---sn-abc.googlevideo.com/videoplayback/seg-1.ts"),
        )
        assertTrue(
            rewritten.text.contains("https://rr1---sn-abc.googlevideo.com/videoplayback/seg-2.ts"),
        )
        assertFalse("the non-standard tag must not reach the player", rewritten.text.contains("CONDENSED"))
    }

    @Test
    fun `a condensed tag this build cannot expand fails loudly`() {
        // No BASE-URI: nothing here can be expanded, and serving the playlist as-is would give the
        // player segment names that do not exist anywhere.
        val media = """
            #EXTM3U
            #YT-EXT-CONDENSED-URL:PARAMS="",PREFIX=""
            #EXTINF:5.0,
            seg.ts
        """.trimIndent() + "\n"

        val rewrite = HlsPlaylistCodec.rewriteMedia(media, "https://cdn.example/v/prog.m3u8") { _, _ -> "bridge:0" }

        // The tag is present, so the segment lines are fragments; refusing is the only honest answer.
        assertTrue(rewrite is HlsPlaylistCodec.Rewrite.Failed)
        assertTrue((rewrite as HlsPlaylistCodec.Rewrite.Failed).reason.contains("BASE-URI"))
    }

    @Test
    fun `a condensed tag with parameters interleaves them with the segment path`() {
        val media = """
            #EXTM3U
            #YT-EXT-CONDENSED-URL:BASE-URI="https://x.example/v/",PARAMS="a,b",PREFIX="/"
            #EXTINF:5.0,
            /p/q
        """.trimIndent() + "\n"

        val rewritten = HlsPlaylistCodec.rewriteMedia(media, "https://cdn.example/v/prog.m3u8") { uri, _ ->
            if (HlsPlaylistCodec.isDirectlyFetchable(uri)) uri else "bridge:0"
        } as HlsPlaylistCodec.Rewrite.Ok

        assertTrue(rewritten.text.contains("https://x.example/v/a/p/b/q"))
    }

    @Test
    fun `a playlist with no usable base uri cannot be rewritten`() {
        val media = "#EXTM3U\n#EXTINF:6.0,\nseg.ts\n"

        val rewrite = HlsPlaylistCodec.rewriteMedia(media, "not-a-uri") { _, _ -> "bridge:0" }

        assertTrue(rewrite is HlsPlaylistCodec.Rewrite.Failed)
    }

    @Test
    fun `the master's shape tells muxed media from separate audio and from audio only`() {
        val muxed = HlsPlaylistCodec.parseMaster(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"avc1.4d401f,mp4a.40.2\"\nv.m3u8\n"
        )
        val separate = HlsPlaylistCodec.parseMaster(
            "#EXTM3U\n" +
                "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",URI=\"a.m3u8\"\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=1,CODECS=\"avc1.4d401f\",AUDIO=\"a\"\nv.m3u8\n"
        )
        val audioOnly = HlsPlaylistCodec.parseMaster(
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",URI=\"a.m3u8\"\n"
        )

        assertEquals(HlsPlaylistCodec.MasterPlaylist.Shape.MUXED, muxed.shape)
        assertEquals(HlsPlaylistCodec.MasterPlaylist.Shape.SEPARATE_AUDIO, separate.shape)
        assertEquals(HlsPlaylistCodec.MasterPlaylist.Shape.AUDIO_ONLY, audioOnly.shape)
    }

    @Test
    fun `an empty body is neither a master nor a media playlist`() {
        val empty = HlsPlaylistCodec.parseMedia("")
        assertEquals(0, empty.segmentCount)
        assertNull(empty.targetDurationSec)
    }
}
