package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StreamStatsTest — the debug overlay's counters.
 *
 * WHY: the overlay could sit on screen showing `0 fps … 0 kbps … AUDIO off` and there was no way
 * to tell "the overlay is broken" from "nothing has been counted yet". These tests pin the two
 * behaviours that made it look broken: fps used to refresh only every 300 payloads, and a session
 * in progress used to publish nothing at all for several seconds.
 *
 * Runs on the plain JVM: [StreamStats] has no Android dependencies, and the sampling window
 * takes an injectable clock so no test has to sleep a second.
 */
class StreamStatsTest {

    @Test
    fun `fps and bitrate publish once the sample window closes`() {
        StreamStats.resetStreams()
        StreamStats.videoRes = "1920x1080"

        // 50 payloads of 1000 bytes inside one window.
        for (i in 0 until 50) StreamStats.noteVideoPayload(1_000, 1_000L + i)
        assertEquals(50, StreamStats.videoFramesIn)
        // Window still open — publishing here is what used to make the HUD lag by seconds.
        assertEquals(0, StreamStats.videoFps)

        // One more payload a second later closes the window — and is itself counted.
        StreamStats.noteVideoPayload(1_000, 2_000L)
        assertEquals(51, StreamStats.videoFps)                 // 51 payloads / 1000 ms
        assertEquals(408, StreamStats.videoBitrateKbps)        // 51 000 B → 408 kbit/s
    }

    @Test
    fun `a slow stream still publishes instead of waiting for 300 payloads`() {
        StreamStats.resetStreams()
        // 3 payloads over a second is a nearly-static screen — the old code showed 0 fps
        // forever at this rate because it only recomputed every 300 payloads.
        StreamStats.noteVideoPayload(500, 1_000L)
        StreamStats.noteVideoPayload(500, 1_400L)
        StreamStats.noteVideoPayload(500, 2_000L)
        assertEquals(3, StreamStats.videoFps)                  // 3 payloads / 1000 ms
        assertEquals(12, StreamStats.videoBitrateKbps)         // 1500 B → 12 kbit/s
    }

    @Test
    fun `a payload timestamped at zero still opens a window`() {
        // Regression: windowStartMs used 0L as its "no window" marker, so a payload stamped at
        // 0 re-opened the window on every call and the rate counters never published at all.
        StreamStats.resetStreams()
        StreamStats.noteVideoPayload(500, 0L)
        StreamStats.noteVideoPayload(500, 400L)
        StreamStats.noteVideoPayload(500, 1_000L)
        assertEquals(3, StreamStats.videoFps)
        assertEquals(12, StreamStats.videoBitrateKbps)
    }

    @Test
    fun `dropped payloads are reported as a percentage`() {
        StreamStats.resetStreams()
        for (i in 0 until 10) StreamStats.noteVideoPayload(100, i.toLong())
        StreamStats.noteVideoPayloadDropped()
        StreamStats.noteVideoPayloadDropped()
        assertEquals(20, StreamStats.videoDropPct)
        assertEquals(2, StreamStats.videoFramesDropped)
    }

    @Test
    fun `beginSession clears the previous session but not the overlay setting`() {
        StreamStats.overlayEnabled = true
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        StreamStats.videoRes = "1920x1080"
        StreamStats.videoFps = 58
        StreamStats.audioActive = true

        // A Miracast session starting must not inherit AirPlay's numbers.
        StreamStats.beginSession(StreamStats.SOURCE_MIRACAST)
        assertEquals(StreamStats.SOURCE_MIRACAST, StreamStats.source)
        assertEquals("", StreamStats.videoRes)
        assertEquals(0, StreamStats.videoFps)
        assertEquals(0, StreamStats.videoFramesIn)

        // Toggling the overlay is a setting; ending a session must not switch it back off.
        assertTrue(StreamStats.overlayEnabled)
        StreamStats.endSession()
        assertTrue(StreamStats.overlayEnabled)
        StreamStats.overlayEnabled = false
    }

    @Test
    fun `summary says it is idle rather than printing a row of zeroes`() {
        StreamStats.resetStreams()
        val text = StreamStats.summary(0L)
        assertTrue(text.contains("SRC"))
        assertTrue(text.contains("idle"))
    }

    @Test
    fun `summary reports the live source and uptime`() {
        StreamStats.resetStreams()
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        StreamStats.sessionStartedAtMillis = 1_000L     // uptime is only shown once started
        StreamStats.videoRes = "1920x1080"
        StreamStats.videoFps = 60
        StreamStats.videoBitrateKbps = 8_000
        StreamStats.videoDecoderReady = true
        StreamStats.audioActive = true
        StreamStats.audioCodec = "AAC-ELD 44k"

        val text = StreamStats.summary(91_000L)  // 90 s after the session began
        assertTrue(text.contains(StreamStats.SOURCE_AIRPLAY))
        assertTrue(text.contains("1:30"))         // uptime
        assertTrue(text.contains("1920x1080"))
        assertTrue(text.contains("60fps"))
        assertTrue(text.contains("8000kbps"))
        assertTrue(text.contains("ready"))
        assertTrue(text.contains("AAC-ELD"))
        StreamStats.resetStreams()
    }

    @Test
    fun `summary reports decoder state before the first keyframe`() {
        StreamStats.resetStreams()
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        StreamStats.sessionStartedAtMillis = 0L
        val text = StreamStats.summary(0L)
        assertTrue(text.contains("waiting for SPS/PPS + surface"))
        StreamStats.resetStreams()
    }

    @Test
    fun `the HUD names the receiver that owns the session`() {
        StreamStats.resetStreams()
        StreamStats.beginSession(StreamStats.SOURCE_MIRACAST)
        StreamStats.sessionStartedAtMillis = 0L
        StreamStats.videoRes = "1920x1080"
        StreamStats.noteVideoPayload(4096)

        val text = StreamStats.summary(1_000L)
        assertTrue("the source line must say which receiver is streaming", text.contains("Miracast"))
        assertTrue(text.contains("1920x1080"))
        StreamStats.resetStreams()
    }

    @Test
    fun `uptime formats past an hour`() {
        StreamStats.resetStreams()
        StreamStats.beginSession(StreamStats.SOURCE_MIRACAST)
        StreamStats.sessionStartedAtMillis = 1_000L
        StreamStats.videoRes = "1280x720"
        assertTrue(StreamStats.summary(3_662_000L).contains("1:01:01"))
        StreamStats.resetStreams()
    }
}
