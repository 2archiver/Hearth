package com.phairplay.airplay.handshake

import com.phairplay.settings.PlaybackProfile
import org.junit.Assert.*
import org.junit.Test

class PlaybackProfileTest {
    @Test fun `missing and corrupt preferences preserve original behavior`() {
        assertEquals(PlaybackProfile.LEGACY, PlaybackProfile.fromStored(null))
        assertEquals(PlaybackProfile.LEGACY, PlaybackProfile.fromStored("future-profile"))
        assertNull(PlaybackProfile.LEGACY.bufferMillis)
    }

    @Test fun `each opt-in target is bounded and burst scheduling cannot park codec owner`() {
        for (profile in PlaybackProfile.entries.filter { it.bufferMillis != null }) {
            val buffer = profile.bufferMillis!! * 1_000_000L
            val clock = MirrorPlayoutClock(buffer, 150_000_000L)
            val now = 1_000_000_000L
            assertEquals(now + buffer, clock.targetTimeNanos(0, now))
            // A network burst producing a far-future PTS reanchors inside the bound.
            val target = clock.targetTimeNanos(900_000, now + 1_000_000)
            assertTrue(target - (now + 1_000_000) in 0L..150_000_000L)
            assertEquals(MirrorPresentationAction.SCHEDULE,
                MirrorPresentationPolicy.decide(true, target, now + 1_000_000,
                    maxLeadNanos = 150_000_000L).action)
        }
    }
}
