package com.phairplay.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorStreamPacingTest {
    @Test
    fun `maps little endian Q32 timestamps onto a monotonic microsecond timeline`() {
        val mapper = MirrorTimestampMapper()
        val origin = 5_000_000_000L
        val first = mapper.map(rawTimestamp = 1_000L, arrivalNanos = origin)
        val second = mapper.map(
            rawTimestamp = 1_000L + (1L shl 32) + (1L shl 31),
            arrivalNanos = origin + 1_400_000_000L,
        )

        assertEquals(0L, first.presentationTimeUs)
        assertEquals(1_500_000L, second.presentationTimeUs)
        assertEquals(MirrorTimestampSource.SENDER, second.source)
        assertEquals(origin + 1_500_000_000L, second.targetTimeNanos)
    }

    @Test
    fun `unsigned Q32 sender timestamps remain monotonic when the signed Long high bit is set`() {
        val mapper = MirrorTimestampMapper()
        val origin = 7_000_000_000L
        val rawStart = java.lang.Long.parseUnsignedLong("f123456789abcdef", 16)
        val first = mapper.map(rawTimestamp = rawStart, arrivalNanos = origin)
        val second = mapper.map(
            rawTimestamp = rawStart + (1L shl 32),
            arrivalNanos = origin + 900_000_000L,
        )

        assertEquals(0L, first.presentationTimeUs)
        assertEquals(1_000_000L, second.presentationTimeUs)
        assertEquals(MirrorTimestampSource.SENDER, second.source)
        assertFalse(second.discontinuity)
    }

    @Test
    fun `missing timestamps use arrival time and discontinuities reanchor monotonically`() {
        val mapper = MirrorTimestampMapper()
        val origin = 10_000_000_000L
        mapper.map(rawTimestamp = 8_000L, arrivalNanos = origin)
        val fallback = mapper.map(rawTimestamp = 0L, arrivalNanos = origin + 45_000_000L)
        val reset = mapper.map(rawTimestamp = 4_000L, arrivalNanos = origin + 55_000_000L)

        assertEquals(MirrorTimestampSource.ARRIVAL, fallback.source)
        assertEquals(45_000L, fallback.presentationTimeUs)
        assertTrue(reset.discontinuity)
        assertTrue(reset.presentationTimeUs > fallback.presentationTimeUs)
        assertEquals(origin, mapper.presentationOriginNanos)
    }

    @Test
    fun `queue requires a configured IDR after encoded loss`() {
        val queue = BoundedMirrorFrameQueue(maxFrames = 4, maxBytes = 1024, maxAgeNanos = 1_000_000_000L)
        val config = config()
        val predicted = frame(2, isKeyframe = false, config = null)
        val recovery = frame(3, isKeyframe = true, config = config)

        assertFalse(queue.offer(predicted, nowNanos = 1L))
        assertTrue(queue.snapshot().awaitingRecoveryPoint)
        assertTrue(queue.offer(recovery, nowNanos = 2L))
        assertFalse(queue.snapshot().awaitingRecoveryPoint)

        queue.requireRecoveryPoint()
        assertEquals(0, queue.snapshot().frames)
        assertTrue(queue.offer(recovery, nowNanos = 3L))
        assertEquals(recovery, queue.take(timeoutMillis = 0L, nowNanos = 3L))
    }

    @Test
    fun `overflow drops an incomplete prefix and resumes only at a fresh IDR`() {
        val queue = BoundedMirrorFrameQueue(maxFrames = 3, maxBytes = 1024, maxAgeNanos = 1_000_000_000L)
        val config = config()
        val firstRecovery = frame(1, isKeyframe = true, config = config)
        val latestRecovery = frame(3, isKeyframe = true, config = config)

        assertTrue(queue.offer(firstRecovery, nowNanos = 1L))
        assertTrue(queue.offer(frame(2, isKeyframe = false, config = null), nowNanos = 2L))
        assertTrue(queue.offer(latestRecovery, nowNanos = 3L))
        assertTrue(queue.offer(frame(4, isKeyframe = false, config = null), nowNanos = 4L))

        val snapshot = queue.snapshot(nowNanos = 4L)
        assertEquals(2, snapshot.frames)
        assertTrue(snapshot.overflows > 0L)
        assertFalse(snapshot.awaitingRecoveryPoint)
        assertEquals(latestRecovery, queue.take(timeoutMillis = 0L, nowNanos = 4L))
    }

    @Test
    fun `age and byte bounds are enforced`() {
        val queue = BoundedMirrorFrameQueue(maxFrames = 8, maxBytes = 4, maxAgeNanos = 100L)
        val config = config()
        assertTrue(queue.offer(frame(4, true, config), nowNanos = 1L))
        assertFalse(queue.offer(frame(2, false, null), nowNanos = 102L))
        assertTrue(queue.snapshot(nowNanos = 102L).awaitingRecoveryPoint)

        val tooLarge = frame(8, true, config)
        assertFalse(queue.offer(tooLarge, nowNanos = 103L))
        assertEquals(0, queue.snapshot().frames)
    }

    @Test
    fun `presentation policy only schedules within a small validated lead and drops stale output`() {
        val now = 1_000_000_000L
        assertEquals(
            MirrorPresentationAction.SCHEDULE,
            MirrorPresentationPolicy.decide(true, now + 20_000_000L, now).action,
        )
        assertEquals(
            MirrorPresentationAction.RENDER_NOW,
            MirrorPresentationPolicy.decide(true, now + 500_000_000L, now).action,
        )
        assertEquals(
            MirrorPresentationAction.DROP_STALE,
            MirrorPresentationPolicy.decide(true, now - 150_000_000L, now).action,
        )
        assertEquals(
            MirrorPresentationAction.RENDER_NOW,
            MirrorPresentationPolicy.decide(false, now - 5_000_000_000L, now).action,
        )
    }

    private fun config() = MirrorCodecConfig(byteArrayOf(0x67), byteArrayOf(0x68))

    private fun frame(
        marker: Int,
        isKeyframe: Boolean,
        config: MirrorCodecConfig?,
    ) = MirrorQueuedFrame(
        annexB = ByteArray(marker),
        arrivalNanos = marker.toLong(),
        presentationTimeUs = marker.toLong(),
        codecConfig = config,
        isKeyframe = isKeyframe,
    )
}
