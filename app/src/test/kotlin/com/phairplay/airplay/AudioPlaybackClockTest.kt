package com.phairplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AudioPlaybackClockTest {
    @Test fun `requires two advancing plausible hardware observations`() {
        val clock = AudioPlaybackClock(48_000)
        assertFalse(clock.observe(0, 1_000_000_000, 1_000_000_000))
        assertTrue(clock.observe(48_000, 2_000_000_000, 2_000_000_000))
        assertTrue(clock.observe(48_000, 2_000_000_000, 2_100_000_000))
    }

    @Test fun `future stale regressing and implausible samples lose stability`() {
        val clock = AudioPlaybackClock(48_000)
        assertFalse(clock.observe(0, 2_000_000_000, 1_000_000_000))
        assertFalse(clock.observe(0, 1_000_000_000, 4_000_000_000))
        assertFalse(clock.observe(48_000, 4_000_000_000, 4_000_000_000))
        assertFalse(clock.observe(47_000, 5_000_000_000, 5_000_000_000))
        assertFalse(clock.observe(96_000, 6_000_000_000, 6_000_000_000))
        assertFalse(clock.observe(960_000, 7_000_000_000, 7_000_000_000))
    }
}
