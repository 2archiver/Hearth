package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.phairplay.airplay.handshake.PlistCodec

/**
 * RtspHandlerTest — Unit tests for the RTSP protocol implementation.
 *
 * WHY: The RTSP handler is the most security-critical component of Hearth.
 * It processes untrusted data from the network. Every parsing path must be
 * tested with both valid inputs and malformed/malicious inputs.
 *
 * WHAT WE TEST:
 * - Correct responses for each RTSP method (OPTIONS, ANNOUNCE, SETUP, RECORD, TEARDOWN)
 * - SDP body parsing (codec parameters and encryption keys)
 * - Security: malformed/empty input handling (must not crash)
 * - Security: oversized messages (should be rejected)
 * - Correct state machine: RECORD without prior ANNOUNCE returns 455
 */
class RtspHandlerTest {

    // Captures whether the callbacks were invoked
    private var streamingStarted = false
    private var lastSession: SessionDescription? = null
    private var streamingStopped = false
    private var photoReceived = false
    private var photoCleared = false
    private var lastPhotoType: PhotoImageType? = null
    private var audioStopped = false
    private var videoStopped = false

    @Before
    fun setup() {
        streamingStarted = false
        lastSession = null
        streamingStopped = false
        photoReceived = false
        photoCleared = false
        lastPhotoType = null
        audioStopped = false
        videoStopped = false
    }

    // ─── OPTIONS ─────────────────────────────────────────────────────────────

    /**
     * Test: OPTIONS response includes all required RTSP methods.
     *
     * WHY: macOS reads the OPTIONS response to know what methods it can use.
     * If a required method is missing, the connection attempt will fail.
     */
    @Test
    fun `OPTIONS response includes all required methods`() {
        val response = createTestHandler().handleOptionsPublic(
            RtspRequest(
                method = "OPTIONS",
                uri = "rtsp://192.168.1.1/phairplay",
                headers = mapOf("CSeq" to "1"),
                body = ""
            )
        )

        assertEquals(200, response.statusCode)
        val publicMethods = response.headers["Public"] ?: ""
        assertTrue("OPTIONS missing", "OPTIONS" in publicMethods)
        assertTrue("ANNOUNCE missing", "ANNOUNCE" in publicMethods)
        assertTrue("SETUP missing", "SETUP" in publicMethods)
        assertTrue("RECORD missing", "RECORD" in publicMethods)
        assertTrue("TEARDOWN missing", "TEARDOWN" in publicMethods)
    }

    // ─── ANNOUNCE ────────────────────────────────────────────────────────────

    @Test
    fun `ANNOUNCE with valid video+audio SDP returns 200`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(
                method = "ANNOUNCE", uri = "", headers = emptyMap(),
                body = VALID_SDP_VIDEO_AUDIO
            )
        )
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `ANNOUNCE with valid audio-only SDP returns 200`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(
                method = "ANNOUNCE", uri = "", headers = emptyMap(),
                body = VALID_SDP_AUDIO_ONLY
            )
        )
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `ANNOUNCE with empty body returns 400`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(400, response.statusCode)
    }

    @Test
    fun `ANNOUNCE with blank body returns 400`() {
        val response = createTestHandler().handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = "   \n  ")
        )
        assertEquals(400, response.statusCode)
    }

    // ─── SETUP ───────────────────────────────────────────────────────────────

    @Test
    fun `SETUP response includes Session and Transport headers`() {
        val handler = createTestHandler()
        // ANNOUNCE first so session is established
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_VIDEO_AUDIO)
        )

        val response = handler.handleSetupPublic(
            RtspRequest(method = "SETUP", uri = "", headers = emptyMap(), body = "")
        )

        assertEquals(200, response.statusCode)
        assertNotNull("Session header required", response.headers["Session"])
        assertNotNull("Transport header required", response.headers["Transport"])
    }

    @Test
    fun `first SETUP uses TCP interleaved transport (video)`() {
        val handler = createTestHandler()
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_VIDEO_AUDIO)
        )

        val response = handler.handleSetupPublic(
            RtspRequest(method = "SETUP", uri = "", headers = emptyMap(), body = "")
        )

        val transport = response.headers["Transport"] ?: ""
        assertTrue("First SETUP should be TCP interleaved", "TCP" in transport || "interleaved" in transport)
    }

    // ─── RECORD ──────────────────────────────────────────────────────────────

    @Test
    fun `RECORD after ANNOUNCE triggers onStreamingStarted`() {
        val handler = createTestHandler()
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_VIDEO_AUDIO)
        )
        handler.handleRecordPublic(
            RtspRequest(method = "RECORD", uri = "", headers = emptyMap(), body = "")
        )

        assertTrue("onStreamingStarted should be called", streamingStarted)
        assertNotNull("SessionDescription should be passed to callback", lastSession)
    }

    @Test
    fun `RECORD without prior ANNOUNCE returns 455`() {
        // No ANNOUNCE → currentSession is null → must return 455 Method Not Valid in This State
        val response = createTestHandler().handleRecordPublic(
            RtspRequest(method = "RECORD", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(455, response.statusCode)
    }

    @Test
    fun `RECORD with audio-only SDP sets isAudioOnly on session`() {
        val handler = createTestHandler()
        handler.handleAnnouncePublic(
            RtspRequest(method = "ANNOUNCE", uri = "", headers = emptyMap(), body = VALID_SDP_AUDIO_ONLY)
        )
        handler.handleRecordPublic(
            RtspRequest(method = "RECORD", uri = "", headers = emptyMap(), body = "")
        )

        assertTrue("audio-only session flag should be set", lastSession?.isAudioOnly == true)
    }

    // ─── TEARDOWN ────────────────────────────────────────────────────────────

    @Test
    fun `TEARDOWN triggers onStreamingStopped callback`() {
        val handler = createTestHandler()
        handler.handleTeardownPublic(
            RtspRequest(method = "TEARDOWN", uri = "", headers = emptyMap(), body = "")
        )
        assertTrue("onStreamingStopped should have been called", streamingStopped)
    }

    @Test
    fun `TEARDOWN returns 200`() {
        val response = createTestHandler().handleTeardownPublic(
            RtspRequest(method = "TEARDOWN", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(200, response.statusCode)
    }

    @Test
    fun `TEARDOWN of audio stream stops audio and keeps video session alive`() {
        val handler = createTestHandler()
        handler.seedActiveStreams(96, 110)
        handler.handleTeardownPublic(teardownRequest(teardownBody(96)))
        assertTrue("audio should be stopped", audioStopped)
        assertFalse("video should NOT be stopped", videoStopped)
        assertFalse("session must stay alive while video remains", streamingStopped)
    }

    @Test
    fun `TEARDOWN of video stream stops video and keeps audio session alive`() {
        val handler = createTestHandler()
        handler.seedActiveStreams(96, 110)
        handler.handleTeardownPublic(teardownRequest(teardownBody(110)))
        assertTrue("video should be stopped", videoStopped)
        assertFalse("audio should NOT be stopped", audioStopped)
        assertFalse("session must stay alive while audio remains", streamingStopped)
    }

    @Test
    fun `TEARDOWN naming all streams ends the session`() {
        val handler = createTestHandler()
        handler.seedActiveStreams(96, 110)
        handler.handleTeardownPublic(teardownRequest(teardownBody(96, 110)))
        assertTrue("audio should be stopped", audioStopped)
        assertTrue("video should be stopped", videoStopped)
        assertTrue("session should end when the last stream is removed", streamingStopped)
    }

    @Test
    fun `TEARDOWN of the last remaining stream ends the session`() {
        val handler = createTestHandler()
        handler.seedActiveStreams(110)
        handler.handleTeardownPublic(teardownRequest(teardownBody(110)))
        assertTrue("video should be stopped", videoStopped)
        assertTrue("session should end when no streams remain", streamingStopped)
    }

    @Test
    fun `TEARDOWN of an unknown stream type leaves the active session untouched`() {
        val handler = createTestHandler()
        handler.seedActiveStreams(110)
        handler.handleTeardownPublic(teardownRequest(teardownBody(200)))
        assertFalse("no known stream named — nothing stopped", videoStopped)
        assertFalse("no known stream named — nothing stopped", audioStopped)
        assertFalse("session with active streams must stay alive", streamingStopped)
    }

    // ─── Unknown method ───────────────────────────────────────────────────────

    @Test
    fun `unknown RTSP method returns 501`() {
        val response = createTestHandler().handleUnknownMethodPublic(
            RtspRequest(method = "FOOBAR", uri = "", headers = emptyMap(), body = "")
        )
        assertEquals(501, response.statusCode)
        assertEquals("Not Implemented", response.statusMessage)
    }

    // ─── Photo endpoint ─────────────────────────────────────────────────────

    @Test
    fun `PUT photo with valid JPEG triggers photo callback`() {
        val response = createTestHandler().handlePhotoPutPublic(
            RtspRequest(
                method = "PUT",
                uri = "/photo",
                headers = mapOf("Content-Type" to "image/jpeg"),
                body = "",
                bodyBytes = JPEG_BYTES,
                protocol = "HTTP/1.1"
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals("HTTP/1.1", response.protocol)
        assertTrue("photo callback should be called", photoReceived)
        assertEquals(PhotoImageType.JPEG, lastPhotoType)
    }

    @Test
    fun `PUT photo with invalid payload returns 400`() {
        val response = createTestHandler().handlePhotoPutPublic(
            RtspRequest(
                method = "PUT",
                uri = "/photo",
                headers = mapOf("Content-Type" to "image/jpeg"),
                body = "not an image",
                bodyBytes = "not an image".toByteArray(),
                protocol = "HTTP/1.1"
            )
        )

        assertEquals(400, response.statusCode)
    }

    @Test
    fun `DELETE photo triggers clear callback`() {
        val response = createTestHandler().handlePhotoDeletePublic(
            RtspRequest(
                method = "DELETE",
                uri = "/photo",
                headers = emptyMap(),
                body = "",
                protocol = "HTTP/1.1"
            )
        )

        assertEquals(200, response.statusCode)
        assertTrue("photo clear callback should be called", photoCleared)
    }

    @Test
    fun `TEARDOWN with empty body stops remaining active mirror video stream (iOS 27 fix)`() {
        val handler = createTestHandler().apply { seedActiveStreams(110) }

        val response = handler.handleTeardownPublic(teardownRequest(ByteArray(0)))

        assertEquals(200, response.statusCode)
        assertEquals("close", response.headers["Connection"])
        assertTrue("active video stream 110 must be stopped on session TEARDOWN", videoStopped)
        assertTrue("full session teardown must run", streamingStopped)
    }

    @Test
    fun `parseFlushSeq extracts sequence number from RTP-Info header`() {
        assertEquals(12345, RtspHandler.parseFlushSeq("seq=12345;rtptime=987654"))
        assertEquals(0, RtspHandler.parseFlushSeq("rtptime=100; seq=0"))
        assertEquals(-1, RtspHandler.parseFlushSeq(null))
        assertEquals(-1, RtspHandler.parseFlushSeq("rtptime=100"))
        assertEquals(-1, RtspHandler.parseFlushSeq("seq=70000"))
    }

    @Test
    fun `FLUSH routes parsed sequence number to onAudioFlush`() {
        var flushedSeq = -99
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            onAudioFlush = { flushedSeq = it }
        )

        val response = handler.routeRequest(
            RtspRequest(
                method = "FLUSH",
                uri = "rtsp://192.168.1.10/123",
                headers = mapOf("RTP-Info" to "seq=4321;rtptime=88888"),
                body = ""
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals(4321, flushedSeq)
    }

    @Test
    fun `POST reverse returns 101 Switching Protocols with PTTH upgrade`() {
        val response = createTestHandler().routeRequest(
            RtspRequest(
                method = "POST",
                uri = "/reverse",
                headers = mapOf("Upgrade" to "PTTH/1.0", "Connection" to "Upgrade", "X-Apple-Purpose" to "event"),
                body = "",
                protocol = "HTTP/1.1"
            )
        )

        assertEquals(101, response.statusCode)
        assertEquals("PTTH/1.0", response.headers["Upgrade"])
        assertEquals("Upgrade", response.headers["Connection"])
    }

    @Test
    fun `POST fp-setup2 returns 421 Misdirected Request`() {
        val response = createTestHandler().routeRequest(
            RtspRequest(
                method = "POST",
                uri = "/fp-setup2",
                headers = emptyMap(),
                body = "",
                protocol = "HTTP/1.1"
            )
        )

        assertEquals(421, response.statusCode)
    }

    @Test
    fun `PUT setProperty and POST getProperty return 200 XML plist with errorCode 0`() {
        val handler = createTestHandler()
        val setRes = handler.routeRequest(
            RtspRequest(
                method = "PUT",
                uri = "/setProperty?forwardEndTime",
                headers = emptyMap(),
                body = "",
                protocol = "HTTP/1.1"
            )
        )
        assertEquals(200, setRes.statusCode)
        assertEquals("text/x-apple-plist+xml", setRes.contentType)

        val getRes = handler.routeRequest(
            RtspRequest(
                method = "POST",
                uri = "/getProperty?playbackAccessLog",
                headers = emptyMap(),
                body = "",
                protocol = "HTTP/1.1"
            )
        )
        assertEquals(200, getRes.statusCode)
        assertEquals("text/x-apple-plist+xml", getRes.contentType)
    }

    @Test
    fun `SET_PARAMETER volume updates GET_PARAMETER volume response`() {
        var reportedVolume = 0f
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            onVolume = { reportedVolume = it },
            initialVolume = -10f
        )

        val initialGet = handler.routeRequest(
            RtspRequest(method = "GET_PARAMETER", uri = "*", headers = emptyMap(), body = "volume\r\n")
        )
        assertEquals(200, initialGet.statusCode)
        assertTrue(initialGet.body.startsWith("volume: -10.000000"))

        val setRes = handler.routeRequest(
            RtspRequest(
                method = "SET_PARAMETER",
                uri = "*",
                headers = mapOf("Content-Type" to "text/parameters"),
                body = "volume: -18.500000\r\n"
            )
        )
        assertEquals(200, setRes.statusCode)
        assertEquals(-18.5f, reportedVolume, 0.001f)

        val updatedGet = handler.routeRequest(
            RtspRequest(method = "GET_PARAMETER", uri = "*", headers = emptyMap(), body = "volume\r\n")
        )
        assertTrue(updatedGet.body.startsWith("volume: -18.500000"))
    }

    @Test
    fun `POST play supports Start-Position-Seconds in binary plist`() {
        var playedUrl: String? = null
        var playedStart = -1.0
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            onVideoPlay = { _, _ -> throw AssertionError("seconds must not use the fractional callback") },
            onVideoPlaySeconds = { u, s -> playedUrl = u; playedStart = s }
        )
        val body = PlistCodec.encode(
            mapOf(
                "Content-Location" to "https://example.com/video.m3u8",
                "Start-Position-Seconds" to 42.5
            )
        )
        val res = handler.routeRequest(
            RtspRequest(
                method = "POST",
                uri = "/play",
                headers = mapOf("Content-Type" to "application/x-apple-binary-plist"),
                body = "",
                bodyBytes = body,
                protocol = "HTTP/1.1"
            )
        )
        assertEquals(200, res.statusCode)
        assertEquals("https://example.com/video.m3u8", playedUrl)
        assertEquals(42.5, playedStart, 0.001)
    }

    @Test
    fun `POST play preserves fractional Start-Position for legacy senders`() {
        var playedStart = -1.0
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            onVideoPlay = { _, start -> playedStart = start },
            onVideoPlaySeconds = { _, _ -> throw AssertionError("fraction must use the legacy callback") }
        )
        val request = RtspRequest(
            method = "POST",
            uri = "/play",
            headers = mapOf("Content-Type" to "text/parameters"),
            body = "Content-Location: https://example.com/video.mp4?token=a%2Bb\r\nStart-Position: 0.25\r\n",
            protocol = "HTTP/1.1"
        )

        val response = handler.routeRequest(request)

        assertEquals(200, response.statusCode)
        assertEquals(0.25, playedStart, 0.0)
    }

    @Test
    fun `POST play accepts XML plists with absolute seconds`() {
        var playedStart = -1.0
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            onVideoPlaySeconds = { _, start -> playedStart = start }
        )
        val body = PlistCodec.encodeXml(mapOf(
            "Content-Location" to "https://example.com/video.m3u8",
            "Start-Position-Seconds" to 7.25
        ))
        val request = RtspRequest(
            method = "POST",
            uri = "/play",
            headers = mapOf("Content-Type" to "text/x-apple-plist+xml"),
            body = String(body, Charsets.UTF_8),
            bodyBytes = body,
            protocol = "HTTP/1.1"
        )

        assertEquals(200, handler.routeRequest(request).statusCode)
        assertEquals(7.25, playedStart, 0.0)
    }

    @Test
    fun `POST play rejects unsupported internal locations`() {
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            onVideoPlay = { _, _ -> throw AssertionError("unsupported URLs must not be played") },
            onVideoPlaySeconds = { _, _ -> throw AssertionError("unsupported URLs must not be played") }
        )
        val request = RtspRequest(
            method = "POST",
            uri = "/play",
            headers = mapOf("Content-Type" to "application/x-apple-binary-plist"),
            body = "",
            bodyBytes = PlistCodec.encode(mapOf("Content-Location" to "mlhls://localhost/master.m3u8")),
            protocol = "HTTP/1.1"
        )

        assertEquals(400, handler.routeRequest(request).statusCode)
    }

    @Test
    fun `isOldProtocolClient detects legacy 3rd-party User-Agents`() {
        assertTrue(RtspHandler.isOldProtocolClient("AirMyPC/2.0"))
        assertTrue(RtspHandler.isOldProtocolClient("AirParrot/3.1"))
        assertTrue(RtspHandler.isOldProtocolClient("TuneBlade/1.8"))
        assertFalse(RtspHandler.isOldProtocolClient("AirPlay/670.6.2"))
        assertFalse(RtspHandler.isOldProtocolClient(null))
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun createTestHandler(): TestableRtspHandler = TestableRtspHandler(
        onStreamingStarted = { session ->
            streamingStarted = true
            lastSession = session
        },
        onStreamingStopped = { streamingStopped = true }
        ,
        onPhotoReceived = { _, imageType ->
            photoReceived = true
            lastPhotoType = imageType
        },
        onPhotoCleared = { photoCleared = true },
        onMirrorAudioStop = { audioStopped = true },
        onMirrorVideoStop = { videoStopped = true }
    )

    /** Binary-plist TEARDOWN body naming the given stream types, e.g. `{streams:[{type:96}]}`. */
    private fun teardownBody(vararg streamTypes: Int): ByteArray =
        PlistCodec.encode(mapOf("streams" to streamTypes.map { mapOf("type" to it.toLong()) }))

    private fun teardownRequest(bytes: ByteArray) =
        RtspRequest(method = "TEARDOWN", uri = "", headers = emptyMap(), body = "", bodyBytes = bytes)

    companion object {
        // Minimal valid SDP with H.264 video + AAC-ELD audio (base64 SPS/PPS included)
        val VALID_SDP_VIDEO_AUDIO = """
            v=0
            o=AirTunes AABBCCDDEEFF 1 IN IP4 192.168.1.10
            s=AirTunes
            t=0 0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;profile-level-id=640020;sprop-parameter-sets=Z2QAKKwbGAoAofjA,aO48gA==
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 mpeg4-generic/44100/2
            a=fmtp:96 streamtype=5;profile-level-id=15;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=F8E85000
            a=rsaaeskey:MTIzNDU2Nzg5MDEyMzQ1Ng==
            a=aesiv:MTYtYnl0ZS1pdmluaXR2
        """.trimIndent()

        // Audio-only SDP (no video section — used for music/podcast streaming)
        val VALID_SDP_AUDIO_ONLY = """
            v=0
            o=AirTunes AABBCCDDEEFF 1 IN IP4 192.168.1.10
            s=AirTunes
            t=0 0
            m=audio 0 RTP/AVP 96
            a=rtpmap:96 AppleLossless
            a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
            a=rsaaeskey:MTIzNDU2Nzg5MDEyMzQ1Ng==
            a=aesiv:MTYtYnl0ZS1pdmluaXR2
        """.trimIndent()

        val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    }

    // ─── Sender-mediated (FCUP) HLS routing ─────────────────────────────────

    @Test
    fun `POST reverse registers the channel and answers 101`() {
        val host = FakeHlsHost()
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            hlsHost = host,
        )
        val request = RtspRequest(
            method = "POST",
            uri = "/reverse",
            headers = mapOf(
                "X-Apple-Session-ID" to "SESSION-1",
                "X-Apple-Purpose" to "event",
                "Upgrade" to "PTTH/1.0",
                "Connection" to "Upgrade",
            ),
            body = "",
            protocol = "HTTP/1.1",
        )

        val response = handler.routeRequest(request)

        assertEquals(101, response.statusCode)
        assertEquals("PTTH/1.0", response.headers["Upgrade"])
        assertEquals("SESSION-1", host.registeredSessionId)
        assertNotNull("the reverse channel must be registered for this connection", host.registeredConnectionId)
        assertNotNull(host.registeredWriter)
    }

    @Test
    fun `POST reverse without a host is still the protocol acknowledgement`() {
        val handler = TestableRtspHandler(onStreamingStarted = {}, onStreamingStopped = {})

        val response = handler.routeRequest(
            RtspRequest(
                method = "POST",
                uri = "/reverse",
                headers = emptyMap(),
                body = "",
                protocol = "HTTP/1.1",
            )
        )

        assertEquals(101, response.statusCode)
    }

    @Test
    fun `POST action is handed to the session bridge and its answer is returned`() {
        val host = FakeHlsHost().apply { actionStatus = 200 }
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            hlsHost = host,
        )
        val body = PlistCodec.encode(
            mapOf(
                "type" to "unhandledURLResponse",
                "params" to mapOf(
                    "FCUP_Response_RequestID" to 3L,
                    "FCUP_Response_URL" to "https://cdn.example/a.m3u8",
                    "FCUP_Response_Data" to "#EXTM3U\n".toByteArray(),
                ),
            )
        )
        val request = RtspRequest(
            method = "POST",
            uri = "/action",
            headers = mapOf("X-Apple-Session-ID" to "SESSION-1"),
            body = "",
            bodyBytes = body,
            protocol = "HTTP/1.1",
        )

        val response = handler.routeRequest(request)

        assertEquals(200, response.statusCode)
        assertEquals("SESSION-1", host.actionSessionId)
        assertTrue(host.actionBody.contentEquals(body))
    }

    @Test
    fun `POST action is refused when the bridge rejects it`() {
        val host = FakeHlsHost().apply { actionStatus = 400 }
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            hlsHost = host,
        )

        val response = handler.routeRequest(
            RtspRequest(
                method = "POST",
                uri = "/action",
                headers = mapOf("X-Apple-Session-ID" to "another-session"),
                body = "",
                bodyBytes = PlistCodec.encode(mapOf("type" to "unhandledURLResponse")),
                protocol = "HTTP/1.1",
            )
        )

        assertEquals(400, response.statusCode)
    }

    @Test
    fun `POST action without a bridge host is never acknowledged as success`() {
        val handler = TestableRtspHandler(onStreamingStarted = {}, onStreamingStopped = {})

        val response = handler.routeRequest(
            RtspRequest(
                method = "POST",
                uri = "/action",
                headers = mapOf("X-Apple-Session-ID" to "S"),
                body = "",
                bodyBytes = PlistCodec.encode(
                    mapOf("type" to "unhandledURLResponse", "params" to mapOf("a" to 1L))
                ),
                protocol = "HTTP/1.1",
            )
        )

        assertEquals(501, response.statusCode)
    }

    @Test
    fun `POST play of a sender-mediated location goes to the bridge host`() {
        val host = FakeHlsHost()
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            onVideoPlay = { _, _ -> throw AssertionError("a sender-mediated location is not a URL") },
            onVideoPlaySeconds = { _, _ -> throw AssertionError("a sender-mediated location is not a URL") },
            hlsHost = host,
        )
        val request = RtspRequest(
            method = "POST",
            uri = "/play",
            headers = mapOf(
                "Content-Type" to "application/x-apple-binary-plist",
                "X-Apple-Session-ID" to "SESSION-1",
            ),
            body = "",
            bodyBytes = PlistCodec.encode(
                mapOf(
                    "Content-Location" to "mlhls://localhost/abcd/master.m3u8",
                    "Start-Position-Seconds" to 1.5,
                )
            ),
            protocol = "HTTP/1.1",
        )

        val response = handler.routeRequest(request)

        assertEquals(200, response.statusCode)
        val played = host.playRequest
        assertNotNull("the bridge must be asked to host the session", played)
        assertEquals("mlhls://localhost/abcd/master.m3u8", played!!.location)
        assertEquals(1.5, played.startSeconds, 0.0)
        assertTrue(played.seconds)
        assertEquals("SESSION-1", played.senderSessionId)
    }

    @Test
    fun `POST play of a sender-mediated location fails when the bridge refuses it`() {
        val host = FakeHlsHost().apply { playResult = SenderMediatedPlayResult.reject("no reverse channel") }
        val handler = TestableRtspHandler(
            onStreamingStarted = {},
            onStreamingStopped = {},
            hlsHost = host,
        )

        val response = handler.routeRequest(
            RtspRequest(
                method = "POST",
                uri = "/play",
                headers = mapOf(
                    "Content-Type" to "application/x-apple-binary-plist",
                    "X-Apple-Session-ID" to "SESSION-1",
                ),
                body = "",
                bodyBytes = PlistCodec.encode(
                    mapOf("Content-Location" to "mlhls://localhost/abcd/master.m3u8")
                ),
                protocol = "HTTP/1.1",
            )
        )

        assertEquals(400, response.statusCode)
    }
}

/** A host that records what the handler asked it to do; results are set per test. */
private class FakeHlsHost : SenderMediatedHlsHost {
    var registeredConnectionId: String? = null
    var registeredSessionId: String? = null
    var registeredWriter: ((ByteArray) -> Boolean)? = null
    var releasedConnectionId: String? = null
    var playRequest: SenderMediatedPlayRequest? = null
    var playResult: SenderMediatedPlayResult = SenderMediatedPlayResult.ACCEPTED
    var actionSessionId: String? = null
    var actionBody: ByteArray = ByteArray(0)
    var actionStatus: Int = 200

    override fun registerReverseChannel(
        connectionId: String,
        senderSessionId: String?,
        writer: (ByteArray) -> Boolean,
    ): Boolean {
        registeredConnectionId = connectionId
        registeredSessionId = senderSessionId
        registeredWriter = writer
        return true
    }

    override fun releaseReverseChannel(connectionId: String) {
        releasedConnectionId = connectionId
    }

    override fun startSenderMediatedPlay(request: SenderMediatedPlayRequest): SenderMediatedPlayResult {
        playRequest = request
        return playResult
    }

    override fun deliverAction(senderSessionId: String?, body: ByteArray): SenderMediatedActionResult {
        actionSessionId = senderSessionId
        actionBody = body
        return SenderMediatedActionResult(actionStatus, "test")
    }
}

/**
 * TestableRtspHandler — Subclass of [RtspHandler] that exposes internal methods for unit testing
 * without requiring a real network socket.
 */

class TestableRtspHandler(
    onStreamingStarted: (SessionDescription) -> Unit,
    onStreamingStopped: () -> Unit,
    onPhotoReceived: (ByteArray, PhotoImageType) -> Unit = { _, _ -> },
    onPhotoCleared: () -> Unit = {},
    onMirrorAudioStop: () -> Unit = {},
    onMirrorVideoStop: () -> Unit = {},
    onVolume: (Float) -> Unit = {},
    onVideoPlay: (String, Double) -> Unit = { _, _ -> },
    onVideoPlaySeconds: (String, Double) -> Unit = onVideoPlay,
    onAudioFlush: (Int) -> Unit = {},
    initialVolume: Float = 0f,
    hlsHost: SenderMediatedHlsHost? = null,
) : RtspHandler(
    context = io.mockk.mockk(relaxed = true),
    videoSurfaceProvider = { null },
    onStreamingStarted = onStreamingStarted,
    onStreamingStopped = onStreamingStopped,
    onPhotoReceived = onPhotoReceived,
    onPhotoCleared = onPhotoCleared,
    onMirrorAudioStop = onMirrorAudioStop,
    onMirrorVideoStop = onMirrorVideoStop,
    onVolume = onVolume,
    onVideoPlay = onVideoPlay,
    onVideoPlaySeconds = onVideoPlaySeconds,
    onAudioFlush = onAudioFlush,
    initialVolume = initialVolume,
    hlsHost = hlsHost,
) {
    /** Test seam: mark mirror streams active without driving the full FairPlay SETUP handshake. */
    fun seedActiveStreams(vararg types: Int) { activeStreamTypes.addAll(types.toList()) }

    fun handleOptionsPublic(req: RtspRequest) = handleOptionsInternal(req)
    fun handleAnnouncePublic(req: RtspRequest) = handleAnnounceInternal(req)
    fun handleSetupPublic(req: RtspRequest) = handleSetupInternal(req)
    fun handleRecordPublic(req: RtspRequest) = handleRecordInternal(req)
    fun handleTeardownPublic(req: RtspRequest) = handleTeardownInternal(req)
    fun handleUnknownMethodPublic(req: RtspRequest) = handleUnknownInternal(req)
    fun handlePausePublic(req: RtspRequest) = handlePauseInternal(req)
    fun handlePhotoPutPublic(req: RtspRequest) = handlePhotoPutInternal(req)
    fun handlePhotoDeletePublic(req: RtspRequest) = handlePhotoDeleteInternal(req)
}
