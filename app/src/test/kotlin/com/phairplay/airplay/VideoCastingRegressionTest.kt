package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VideoCastingRegressionTest {
    @Test
    fun `direct playback diagnostics follow the player and explicit stop still ends the generation`() {
        AirPlayTrace.clear()
        val token = SessionToken("safari", 1)
        var stopped = false
        var explicit = false
        val handler = RtspHandler(
            context = mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {},
            onSessionClaim = { _, _, _, _ -> token },
            onPlaybackInfoSession = { PlaybackInfo(600.0, 20.0, 1.0, true) },
            onVideoStopSession = { stopped = it == token },
            onSessionStoppedDetailed = { _, _, _, terminal, _ -> explicit = terminal },
        )
        handler.routeRequest(RtspRequest("POST", "/play", mapOf("X-Apple-Session-ID" to "safari"), "",
            bodyBytes = PlistCodec.encode(mapOf("Content-Location" to "https://media.example/video.m3u8"))))
        handler.routeRequest(request("GET", "/playback-info", "safari"))
        assertTrue(AirPlayTrace.exportText().contains("state=url_video/playing->url_video/playing"))
        assertEquals(200, handler.routeRequest(request("POST", "/stop", "safari")).statusCode)
        assertTrue(stopped)
        assertTrue(explicit)
        handler.close()
    }

    @Test
    fun `video and HLS are advertised without changing legacy pairing`() {
        assertEquals(0L, AirPlayIdentity.FEATURES and (1L shl 2))
        assertEquals(0L, AirPlayIdentity.FEATURES and (1L shl 12))
        assertTrue(AirPlayIdentity.FEATURES and 1L != 0L)
        assertTrue(AirPlayIdentity.FEATURES and (1L shl 4) != 0L)
        assertTrue(AirPlayIdentity.FEATURES and (1L shl 27) != 0L)
        assertEquals(AirPlayIdentity.FEATURES, AirPlayIdentity.FEATURES_TXT.removePrefix("0x").toLong(16))
    }

    @Test
    fun `secondary Safari connection joins only matching session for playback controls`() {
        val ownership = SessionOwnership()
        val token = ownership.claimSession("play", AirPlaySessionFingerprint.of("safari"),
            AirPlayConnectionRole.DIRECT_VIDEO_CONTROL, AirPlaySessionMode.URL_VIDEO).token
        var lastRateToken: SessionToken? = null
        var lastStopToken: SessionToken? = null
        val handler = RtspHandler(
            context = mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {},
            onSessionAssociate = { owner, fingerprint, role -> ownership.associateConnection(owner, fingerprint, role) },
            onPlaybackInfoSession = { if (ownership.isCurrent(it)) PlaybackInfo(100.5, 12.25, 1.0, true) else null },
            onVideoRateSession = { _, t -> lastRateToken = t },
            onVideoStopSession = { lastStopToken = it },
        )
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val mismatch = handler.routeRequest(request("GET", "/scrub", "other"))
            assertTrue(mismatch.body.contains("position: 0.000000"))
            val scrub = handler.routeRequest(request("GET", "/scrub", "safari"))
            assertTrue(scrub.body.contains("duration: 100.500000"))
            assertTrue(scrub.body.contains("position: 12.250000"))
            val info = PlistCodec.decode(handler.routeRequest(request("GET", "/playback-info", "safari")).wireBody())
            assertEquals(true, info["readyToPlay"])
            handler.routeRequest(request("POST", "/rate?value=0", "safari"))
            assertEquals(token, lastRateToken)
            handler.routeRequest(request("POST", "/stop", "safari"))
            assertEquals(token, lastStopToken)
        } finally {
            Locale.setDefault(previous)
            handler.close()
        }
    }

    @Test
    fun `YouTube play acknowledges before action on same control connection`() {
        val preparing = CountDownLatch(1)
        val action = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val host = mockk<SenderMediatedHlsHost>()
        every { host.bindVideoSession(any(), any()) } returns Unit
        every { host.startSenderMediatedPlay(any()) } answers {
            preparing.countDown()
            try {
                check(action.await(3, TimeUnit.SECONDS))
                SenderMediatedPlayResult.ACCEPTED
            } finally { completed.countDown() }
        }
        every { host.deliverAction(any(), any()) } answers {
            action.countDown()
            SenderMediatedActionResult(200, "playlist delivered")
        }
        val handler = RtspHandler(
            context = mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {}, socket = Socket(), hlsHost = host,
            onSessionClaim = { _, _, _, _ -> SessionToken("youtube", 1) },
        )
        val reader = Executors.newSingleThreadExecutor()
        try {
            val response = reader.submit<RtspResponse> {
                handler.routeRequest(RtspRequest("POST", "/play", mapOf("X-Apple-Session-ID" to "youtube"), "",
                    bodyBytes = PlistCodec.encode(mapOf("Content-Location" to "mlhls://localhost/youtube/master.m3u8"))))
            }.get(1, TimeUnit.SECONDS)
            assertEquals(200, response.statusCode)
            assertTrue(preparing.await(1, TimeUnit.SECONDS))
            // A repeated /play cannot create an unbounded preparation thread or replace this claim.
            assertEquals(409, handler.routeRequest(RtspRequest("POST", "/play", emptyMap(), "",
                bodyBytes = PlistCodec.encode(mapOf("Content-Location" to "mlhls://localhost/youtube/master.m3u8")))).statusCode)
            val delivered = reader.submit<RtspResponse> {
                handler.routeRequest(request("POST", "/action", "youtube"))
            }.get(1, TimeUnit.SECONDS)
            assertEquals(200, delivered.statusCode)
            assertTrue(completed.await(1, TimeUnit.SECONDS))
        } finally {
            action.countDown()
            handler.close()
            reader.shutdownNow()
        }
    }

    private fun request(method: String, path: String, session: String) =
        RtspRequest(method, path, mapOf("X-Apple-Session-ID" to session), "")
}
