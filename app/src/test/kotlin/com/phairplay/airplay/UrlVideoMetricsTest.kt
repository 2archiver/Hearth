package com.phairplay.airplay

import org.junit.Assert.*
import org.junit.Test

class UrlVideoMetricsTest {
    @Test fun `initial buffering is excluded and first actual frame is recorded once`() {
        val metrics = UrlVideoMetrics()
        metrics.begin("direct HLS", 1_000_000)
        metrics.playbackState(true, false, 2_000_000)
        assertTrue(metrics.summary(3_000_000).contains("first frame=N/A"))
        assertEquals(0, metrics.rebufferCount)
        metrics.playbackState(false, true, 4_000_000)
        metrics.firstFrame(5_000_000)
        metrics.firstFrame(8_000_000)
        assertTrue(metrics.summary(9_000_000).contains("first frame=4ms"))
        metrics.playbackState(true, false, 10_000_000)
        metrics.playbackState(true, false, 11_000_000)
        assertEquals(1, metrics.rebufferCount)
        assertTrue(metrics.summary(12_000_000).contains("buffering=2ms"))
        metrics.playbackState(false, true, 13_000_000)
        assertTrue(metrics.summary(15_000_000).contains("buffering=3ms"))
        metrics.begin("sender HLS", 20_000_000)
        assertEquals(0, metrics.rebufferCount)
        assertTrue(metrics.summary(21_000_000).contains("first frame=N/A"))
    }
}
