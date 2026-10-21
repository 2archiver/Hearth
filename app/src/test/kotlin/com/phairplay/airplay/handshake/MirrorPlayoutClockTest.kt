package com.phairplay.airplay.handshake

import org.junit.Assert.*
import org.junit.Test

class MirrorPlayoutClockTest {
    private val start = 1_000_000_000L

    @Test fun `first decoded frame has headroom despite a slow codec startup`() {
        val clock = MirrorPlayoutClock()
        val ready = start + 800_000_000L
        val target = clock.targetTimeNanos(0L, ready)
        assertEquals(ready + 60_000_000L, target)
        assertEquals(MirrorPresentationAction.SCHEDULE,
            MirrorPresentationPolicy.decide(true, target, ready).action)
    }

    @Test fun `packet bursts preserve sender spacing instead of rendering together`() {
        val clock = MirrorPlayoutClock()
        val first = clock.targetTimeNanos(0L, start)
        val second = clock.targetTimeNanos(33_333L, start + 2_000_000L)
        val third = clock.targetTimeNanos(66_666L, start + 4_000_000L)
        assertEquals(33_333_000L, second - first)
        assertEquals(33_333_000L, third - second)
    }

    @Test fun `a larger valid burst never resets presentation backwards`() {
        val clock = MirrorPlayoutClock()
        val first = clock.targetTimeNanos(0L, start)
        for (i in 1..12) {
            assertEquals(first + i * 33_333_000L,
                clock.targetTimeNanos(i * 33_333L, start + i * 2_000_000L))
        }
    }

    @Test fun `jitter within the buffer keeps sixty fps sender cadence`() {
        val clock = MirrorPlayoutClock()
        val first = clock.targetTimeNanos(0L, start)
        for (i in 1..600) {
            val pts = i * 16_667L
            val arrival = start + pts * 1000L + (i % 4) * 6_000_000L
            assertEquals(first + pts * 1000L, clock.targetTimeNanos(pts, arrival))
        }
    }

    @Test fun `moderately late output is discarded without resetting the sender clock`() {
        val clock = MirrorPlayoutClock()
        clock.targetTimeNanos(0L, start)
        val now = start + 200_000_000L
        val target = clock.targetTimeNanos(33_333L, now)
        assertEquals(start + 93_333_000L, target)
        assertEquals(MirrorPresentationAction.DROP_STALE,
            MirrorPresentationPolicy.decide(true, target, now, maxLatenessNanos = 45_000_000L).action)
    }

    @Test fun `long stall rebuffers then resumes instead of dropping indefinitely`() {
        val clock = MirrorPlayoutClock()
        clock.targetTimeNanos(0L, start)
        val resumed = start + 2_000_000_000L
        assertEquals(resumed + 60_000_000L, clock.targetTimeNanos(33_333L, resumed))
        assertEquals(resumed + 93_333_000L,
            clock.targetTimeNanos(66_666L, resumed + 33_333_000L))
    }

    @Test fun `timestamp jumps and backwards resets cannot schedule far ahead`() {
        val clock = MirrorPlayoutClock()
        clock.targetTimeNanos(0L, start)
        assertEquals(start + 70_000_000L, clock.targetTimeNanos(9_000_000L, start + 10_000_000L))
        assertEquals(start + 80_000_000L, clock.targetTimeNanos(0L, start + 20_000_000L))
    }

    @Test fun `overflowing sender delta rebuffers within the latency budget`() {
        val clock = MirrorPlayoutClock()
        clock.targetTimeNanos(0L, start)
        assertEquals(start + 60_000_000L, clock.targetTimeNanos(Long.MAX_VALUE, start))
    }
}
