package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StreamStatsTest — the debug overlay's counters and, importantly, what they are allowed to claim.
 *
 * WHY: 2.0's overlay printed `rx=60/s` and the app called it FPS. That number is RTP payload
 * throughput; it can read 60 while nothing is presented, and it cannot distinguish a 30 fps source
 * from a duplicated 60 fps one. These tests pin the 2.1.0 contract:
 *
 *  - RENDER fps only comes from [StreamStats.noteFramePresented] (frames handed to the Surface);
 *  - SOURCE fps only comes from the sender's presentation timestamps;
 *  - RX throughput is labelled RX and never rendered as an fps;
 *  - an unmeasured value prints `N/A`, never a fabricated `0`.
 *
 * Runs on the plain JVM: [StreamStats] has no Android dependencies, and every window takes an
 * injectable clock so no test has to sleep a second.
 */
class StreamStatsTest {

    @Test
    fun `compact HUD shows one fps and omits technical rows`() {
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        StreamStats.videoRes = "1920x1080"
        for (i in 0..62) StreamStats.noteFramePresented(i * 16_666_667L)
        val text = StreamStats.summary(nowNanos = 1_100_000_000L)
        assertEquals(1, Regex("fps").findAll(text).count())
        assertTrue(text.contains("1920x1080"))
        for (label in listOf("SOURCE", "PACE", "AUDIO", "RX", "KEYS", "decoder")) {
            assertFalse(text.contains(label))
        }
        assertTrue(text.lines().size <= 3)
        StreamStats.resetStreams()
    }

    @Test
    fun `compact HUD expires a stalled fps instead of freezing on the last rate`() {
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        for (i in 0..62) StreamStats.noteFramePresented(i * 16_666_667L)
        assertTrue(StreamStats.summary(nowNanos = 4_000_000_000L).contains("N/A"))
        StreamStats.resetStreams()
    }

    @Test
    fun `rx payload rate and bitrate publish once the sample window closes`() {
        StreamStats.resetStreams()
        StreamStats.videoRes = "1920x1080"

        // 50 payloads of 1000 bytes inside one window.
        for (i in 0 until 50) StreamStats.noteVideoPayload(1_000, 1_000L + i)
        assertEquals(50, StreamStats.videoFramesIn)
        // Window still open — publishing here is what used to make the HUD lag by seconds.
        assertEquals(0, StreamStats.rxPayloadsPerSec)

        // One more payload a second later closes the window — and is itself counted.
        StreamStats.noteVideoPayload(1_000, 2_000L)
        assertEquals(51, StreamStats.rxPayloadsPerSec)          // payloads, not frames
        assertEquals(408, StreamStats.rxBitrateKbps)            // 51 000 B → 408 kbit/s
        // Receive traffic must not be promoted to a frame rate.
        assertEquals(0.0, StreamStats.presentedFps, 0.0001)
    }

    @Test
    fun `a slow stream still publishes instead of waiting for 300 payloads`() {
        StreamStats.resetStreams()
        StreamStats.noteVideoPayload(500, 1_000L)
        StreamStats.noteVideoPayload(500, 1_400L)
        StreamStats.noteVideoPayload(500, 2_000L)
        assertEquals(3, StreamStats.rxPayloadsPerSec)
        assertEquals(12, StreamStats.rxBitrateKbps)             // 1500 B → 12 kbit/s
    }

    @Test
    fun `a payload timestamped at zero still opens a window`() {
        // Regression: windowStartMs used 0L as its "no window" marker, so a payload stamped at
        // 0 re-opened the window on every call and the rate counters never published at all.
        StreamStats.resetStreams()
        StreamStats.noteVideoPayload(500, 0L)
        StreamStats.noteVideoPayload(500, 400L)
        StreamStats.noteVideoPayload(500, 1_000L)
        assertEquals(3, StreamStats.rxPayloadsPerSec)
        assertEquals(12, StreamStats.rxBitrateKbps)
    }

    @Test
    fun `presented fps is measured from frames handed to the surface`() {
        StreamStats.resetStreams()
        // 60 fps cadence: one frame every 16.667 ms, for a little over a second.
        val intervalNanos = 16_666_667L
        for (i in 0..62) StreamStats.noteFramePresented(i * intervalNanos)

        assertEquals(63L, StreamStats.presentedFrames)
        assertTrue(
            "expected a ~60 fps presentation rate, got ${StreamStats.presentedFps}",
            StreamStats.presentedFps in 58.0..62.0,
        )
        assertEquals(16.7, StreamStats.frameTimeP50Ms, 0.5)
        assertTrue(
            "p95 must be at least p50 (${StreamStats.frameTimeP95Ms} vs ${StreamStats.frameTimeP50Ms})",
            StreamStats.frameTimeP95Ms >= StreamStats.frameTimeP50Ms,
        )
    }

    @Test
    fun `a 30 fps source is reported as 30 fps and never smoothed into 60`() {
        StreamStats.resetStreams()
        // A sender cadence of exactly 30 fps: 33 333 µs per frame.
        StreamStats.noteSourceFramePts(0L)
        for (i in 1..31) StreamStats.noteSourceFramePts(i * 33_333L)
        assertTrue(
            "sender cadence should be ~30 fps, got ${StreamStats.sourceFps}",
            StreamStats.sourceFps in 29.0..31.0,
        )
    }

    @Test
    fun `a backwards sender timestamp restarts the window instead of inventing a rate`() {
        StreamStats.resetStreams()
        for (i in 0..31) StreamStats.noteSourceFramePts(i * 33_333L)
        val before = StreamStats.sourceFps
        assertTrue(before > 0.0)
        // A clock reset: the next window must start fresh, not produce a negative/huge value.
        StreamStats.noteSourceFramePts(0L)
        StreamStats.noteSourceFramePts(33_333L)
        assertTrue(StreamStats.sourceFps.isFinite() && StreamStats.sourceFps >= 0.0)
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
        StreamStats.rxPayloadsPerSec = 58
        StreamStats.audioActive = true

        // A new session must not inherit the previous session's numbers.
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        assertEquals(StreamStats.SOURCE_AIRPLAY, StreamStats.source)
        assertEquals("", StreamStats.videoRes)
        assertEquals(0, StreamStats.rxPayloadsPerSec)
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
        StreamStats.receiverState = "stopped"
        val text = StreamStats.diagnosticsSummary(0L)
        assertTrue(text.contains("idle"))
        assertTrue(text.contains("N/A"))
        assertFalse("a zero must never stand in for an unmeasured fps", text.contains("0.0 fps"))
    }

    @Test
    fun `summary labels render, source and rx separately and never calls rx an fps`() {
        StreamStats.resetStreams()
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        StreamStats.sessionStartedAtMillis = 1_000L     // uptime is only shown once started
        StreamStats.videoRes = "1920x1080"
        StreamStats.rxPayloadsPerSec = 60
        StreamStats.rxBitrateKbps = 8_000
        StreamStats.videoDecoderReady = true
        StreamStats.decoderName = "OMX.test.decoder (hw)"
        StreamStats.audioActive = true
        StreamStats.audioCodec = "AAC-ELD"

        // One second of a genuine 60 fps presentation at ~8 Mbps of receive traffic.
        for (i in 0..62) StreamStats.noteFramePresented(i * 16_666_667L)
        StreamStats.noteSourceFramePts(0L)
        for (i in 1..61) StreamStats.noteSourceFramePts(i * 16_667L)

        val text = StreamStats.diagnosticsSummary(91_000L)  // 90 s after the session began
        assertTrue(text.contains(StreamStats.SOURCE_AIRPLAY))
        assertTrue(text.contains("1:30"))                       // uptime
        assertTrue(text.contains("1920x1080"))
        assertTrue(text.contains("RENDER"))
        assertTrue(text.contains("SOURCE"))
        assertTrue(text.contains("p50/p95"))
        assertTrue("decoder must be named", text.contains("OMX.test.decoder"))
        assertTrue(text.contains("8.0 Mbps"))
        assertTrue(text.contains("AAC-ELD"))
        // The 2.0 regression: the RX packet rate was printed as an fps.
        assertFalse("RX throughput must not be rendered as fps", text.contains("rx=60/s"))
        assertFalse(text.contains("rx=60"))
        // And it must not claim a 60 Hz panel is a 60 fps render rate.
        assertFalse(text.contains("60 fps\nSOURCE"))
        StreamStats.resetStreams()
    }

    @Test
    fun `unavailable telemetry is labelled, not zeroed`() {
        StreamStats.resetStreams()
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        val text = StreamStats.diagnosticsSummary(1_000L)
        // No frame has been presented and no sender timestamp has been read.
        assertTrue(text.contains("RENDER  N/A"))
        assertTrue(text.contains("SOURCE  N/A"))
        assertTrue(text.contains("sync N/A"))
        assertTrue(text.contains("N/A kbps"))
    }

    @Test
    fun `summary reports decoder state before the first keyframe`() {
        StreamStats.resetStreams()
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        StreamStats.sessionStartedAtMillis = 0L
        val text = StreamStats.diagnosticsSummary(0L)
        assertTrue(text.contains("N/A"))
        StreamStats.resetStreams()
    }

    @Test
    fun `the HUD names the receiver that owns the session`() {
        StreamStats.resetStreams()
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        StreamStats.sessionStartedAtMillis = 0L
        StreamStats.videoRes = "1920x1080"
        StreamStats.noteVideoPayload(4096)

        val text = StreamStats.diagnosticsSummary(1_000L)
        assertTrue("the source line must say which receiver is streaming", text.contains("AirPlay"))
        assertTrue(text.contains("1920x1080"))
        StreamStats.resetStreams()
    }

    @Test
    fun `uptime formats past an hour`() {
        StreamStats.resetStreams()
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        StreamStats.sessionStartedAtMillis = 1_000L
        StreamStats.videoRes = "1280x720"
        assertTrue(StreamStats.diagnosticsSummary(3_662_000L).contains("1:01:01"))
        StreamStats.resetStreams()
    }

    @Test
    fun `the panel refresh rate is reported in Hz and is not used as an fps`() {
        StreamStats.resetStreams()
        StreamStats.beginSession(StreamStats.SOURCE_AIRPLAY)
        StreamStats.displayRefreshHz = 60f
        val text = StreamStats.diagnosticsSummary(1_000L)
        assertTrue(text.contains("panel 60.0 Hz"))
        // No frames were presented, so RENDER is still unavailable.
        assertTrue(text.contains("RENDER  N/A"))
        StreamStats.displayRefreshHz = 0f
        StreamStats.resetStreams()
    }
}
