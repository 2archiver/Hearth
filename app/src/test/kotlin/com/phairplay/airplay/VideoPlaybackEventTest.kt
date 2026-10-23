package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackEventTest {
    @Test
    fun `status events use framed XML and exact sender association with deduplication`() {
        val frames = mutableListOf<ByteArray>()
        val token = SessionToken("current", 2)
        val coordinator = SenderMediatedHlsCoordinator(
            isStopped = { false }, isSessionCurrent = { it == token }, onPlaybackReady = { _, _ -> }
        )
        coordinator.registerReverseChannel("unrelated", "other", { error("wrong sender selected") })
        coordinator.bindVideoSession(token, "sender")
        coordinator.publishVideoState(token, AirPlayPlaybackState.LOADING)
        // A reverse connection can arrive after /play. A previously unsent state is retried.
        coordinator.registerReverseChannel("reverse", "sender", { frames += it; true })
        coordinator.publishVideoState(token, AirPlayPlaybackState.LOADING)
        coordinator.publishVideoState(token, AirPlayPlaybackState.LOADING)
        coordinator.publishVideoState(SessionToken("old", 1), AirPlayPlaybackState.PLAYING)
        coordinator.publishVideoState(token, AirPlayPlaybackState.PLAYING)
        coordinator.publishVideoState(token, AirPlayPlaybackState.PAUSED)
        coordinator.publishVideoState(token, AirPlayPlaybackState.FAILED)
        assertEquals(4, frames.size)
        val states = frames.map { frame ->
            val text = frame.toString(Charsets.UTF_8)
            val separator = text.indexOf("\r\n\r\n")
            val headers = text.substring(0, separator)
            assertTrue(headers.startsWith("POST /event HTTP/1.1"))
            assertTrue(headers.contains("X-Apple-Session-ID: sender"))
            val body = frame.copyOfRange(separator + 4, frame.size)
            assertTrue(headers.contains("Content-Length: ${body.size}"))
            val plist = PlistCodec.decode(body)
            assertEquals("video", plist["category"])
            assertEquals(1L, (plist["sessionID"] as Number).toLong())
            plist["state"]
        }
        assertEquals(listOf("loading", "playing", "paused", "stopped"), states)
        coordinator.bindVideoSession(SessionToken("replacement", 3), "new-sender")
        coordinator.publishVideoState(token, AirPlayPlaybackState.PLAYING)
        assertEquals(4, frames.size)
    }

    @Test
    fun `buffering replies do not claim the whole movie is loaded or seekable`() {
        val handler = RtspHandler(
            context = mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {},
            onPlaybackInfo = { PlaybackInfo(100.0, 30.0, 0.0, true,
                buffering = true, bufferedPositionSec = 30.0, seekable = false) },
        )
        val response = handler.routeRequest(RtspRequest("GET", "/playback-info", emptyMap(), ""))
        val fields = PlistCodec.decode(response.wireBody())
        assertEquals(true, fields["readyToPlay"])
        assertEquals(true, fields["playbackBufferEmpty"])
        assertEquals(false, fields["playbackBufferFull"])
        assertEquals(false, fields["playbackLikelyToKeepUp"])
        assertEquals(emptyList<Any>(), fields["loadedTimeRanges"])
        assertEquals(emptyList<Any>(), fields["seekableTimeRanges"])
    }

    @Test
    fun `ambiguous reverse connections cannot receive another session's status`() {
        val token = SessionToken("session", 1)
        val coordinator = SenderMediatedHlsCoordinator(
            isStopped = { false }, isSessionCurrent = { true }, onPlaybackReady = { _, _ -> }
        )
        repeat(2) { n -> coordinator.registerReverseChannel("reverse-$n", "sender", { error("ambiguous channel selected") }) }
        coordinator.bindVideoSession(token, "sender")
        coordinator.publishVideoState(token, AirPlayPlaybackState.PLAYING)
    }
}
