package com.phairplay.airplay.handshake

import java.util.ArrayDeque
import kotlin.math.max

/** Parsed SPS/PPS. Byte arrays are immutable by convention after construction. */
internal class MirrorCodecConfig(val sps: ByteArray, val pps: ByteArray) {
    val byteSize: Int get() = sps.size + pps.size

    fun sameAs(other: MirrorCodecConfig?): Boolean =
        other != null && sps.contentEquals(other.sps) && pps.contentEquals(other.pps)
}

internal enum class MirrorTimestampSource { SENDER, ARRIVAL }

/** Sender timestamp mapped to a monotonic presentation timeline; no wall-clock/NTP offset is used. */
internal data class MirrorTimestampSample(
    val presentationTimeUs: Long,
    val targetTimeNanos: Long,
    val source: MirrorTimestampSource,
    val discontinuity: Boolean,
)

/**
 * Converts the 64-bit little-endian AirPlay mirroring timestamp (Q32.32 seconds) into a PTS whose
 * origin is the first packet's monotonic arrival time. The timestamp reference epoch is not
 * assumed: only forward deltas are used. Missing, backwards, or implausibly large deltas fall back
 * to monotonic arrival time and re-anchor, rather than mixing sender and wall clocks.
 */
internal class MirrorTimestampMapper(
    private val maximumForwardGapUs: Long = DEFAULT_MAX_FORWARD_GAP_US,
) {
    private var started = false
    private var originNanos = 0L
    private var senderAnchorRaw: Long? = null
    private var senderAnchorPtsUs = 0L
    private var lastPtsUs = 0L

    val presentationOriginNanos: Long?
        @Synchronized get() = if (started) originNanos else null

    @Synchronized
    fun map(rawTimestamp: Long, arrivalNanos: Long): MirrorTimestampSample {
        if (!started) {
            started = true
            originNanos = arrivalNanos
        }

        val elapsedUs = ((arrivalNanos - originNanos).coerceAtLeast(0L) / NANOS_PER_MICROSECOND)
        if (rawTimestamp == 0L) {
            val ptsUs = max(lastPtsUs, elapsedUs)
            lastPtsUs = ptsUs
            return sample(ptsUs, MirrorTimestampSource.ARRIVAL, discontinuity = false)
        }

        val anchor = senderAnchorRaw
        if (anchor == null) {
            val ptsUs = max(lastPtsUs, elapsedUs)
            senderAnchorRaw = rawTimestamp
            senderAnchorPtsUs = ptsUs
            lastPtsUs = ptsUs
            return sample(ptsUs, MirrorTimestampSource.SENDER, discontinuity = false)
        }

        val rawDelta = rawTimestamp - anchor
        val deltaUs = if (java.lang.Long.compareUnsigned(rawTimestamp, anchor) >= 0 && rawDelta >= 0L) {
            q32DeltaToMicroseconds(rawDelta)
        } else {
            null
        }

        if (deltaUs != null && deltaUs <= maximumForwardGapUs) {
            val ptsUs = max(lastPtsUs, saturatingAdd(senderAnchorPtsUs, deltaUs))
            lastPtsUs = ptsUs
            return sample(ptsUs, MirrorTimestampSource.SENDER, discontinuity = false)
        }

        // A clock reset, wrap, or large jump is a segment boundary, not a reason to schedule a
        // frame hours into the future. Preserve monotonicity and anchor this point to arrival.
        val ptsUs = max(saturatingAdd(lastPtsUs, 1L), elapsedUs)
        senderAnchorRaw = rawTimestamp
        senderAnchorPtsUs = ptsUs
        lastPtsUs = ptsUs
        return sample(ptsUs, MirrorTimestampSource.SENDER, discontinuity = true)
    }

    private fun sample(
        ptsUs: Long,
        source: MirrorTimestampSource,
        discontinuity: Boolean,
    ): MirrorTimestampSample = MirrorTimestampSample(
        presentationTimeUs = ptsUs,
        targetTimeNanos = saturatingAdd(originNanos, saturatingMultiply(ptsUs, NANOS_PER_MICROSECOND)),
        source = source,
        discontinuity = discontinuity,
    )

    private fun q32DeltaToMicroseconds(delta: Long): Long {
        val wholeSeconds = delta ushr 32
        val fraction = delta and FRACTION_MASK
        if (wholeSeconds > maximumForwardGapUs / MICROSECONDS_PER_SECOND + 1L) return Long.MAX_VALUE
        val fractionalUs = (fraction * MICROSECONDS_PER_SECOND) ushr 32
        return saturatingAdd(wholeSeconds * MICROSECONDS_PER_SECOND, fractionalUs)
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        try {
            Math.addExact(first, second)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    private fun saturatingMultiply(first: Long, second: Long): Long =
        try {
            Math.multiplyExact(first, second)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    companion object {
        const val DEFAULT_MAX_FORWARD_GAP_US = 30_000_000L
        private const val MICROSECONDS_PER_SECOND = 1_000_000L
        private const val NANOS_PER_MICROSECOND = 1_000L
        private const val FRACTION_MASK = 0xffff_ffffL
    }
}

/** One decrypted/converted encoded access unit; a recovery point carries the config it needs. */
internal data class MirrorQueuedFrame(
    val annexB: ByteArray,
    val arrivalNanos: Long,
    val presentationTimeUs: Long,
    val codecConfig: MirrorCodecConfig?,
    val isKeyframe: Boolean,
) {
    /** A queue may resume only at an IDR with usable SPS/PPS. */
    val isRecoveryPoint: Boolean get() = isKeyframe && codecConfig != null
    val byteSize: Int get() = annexB.size
}

internal data class MirrorFrameQueueSnapshot(
    val frames: Int,
    val bytes: Long,
    val oldestAgeMillis: Long,
    val awaitingRecoveryPoint: Boolean,
    val droppedFrames: Long,
    val overflows: Long,
)

/**
 * A synchronized, time/byte/count-bounded encoded-frame queue. If capacity pressure creates a gap,
 * it retains only a complete suffix beginning at the newest queued IDR+config, or waits for the next
 * such IDR. It never resumes with a predicted frame after encoded loss.
 */
internal class BoundedMirrorFrameQueue(
    private val maxFrames: Int = DEFAULT_MAX_FRAMES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxAgeNanos: Long = DEFAULT_MAX_AGE_NANOS,
) {
    private val lock = Object()
    private val frames = ArrayDeque<MirrorQueuedFrame>()
    private var queuedBytes = 0L
    private var awaitingRecoveryPoint = true
    private var droppedFrames = 0L
    private var overflows = 0L

    init {
        require(maxFrames > 0) { "frame limit must be positive" }
        require(maxBytes > 0L) { "byte limit must be positive" }
        require(maxAgeNanos > 0L) { "age limit must be positive" }
    }

    /** Returns true only if [frame] remains queued after enforcing every bound. */
    fun offer(frame: MirrorQueuedFrame, nowNanos: Long = System.nanoTime()): Boolean = synchronized(lock) {
        trimIfNeeded(nowNanos)
        if (frame.annexB.isEmpty() || frame.byteSize.toLong() > maxBytes) {
            droppedFrames++
            clearFrames(countAsDropped = true)
            awaitingRecoveryPoint = true
            return@synchronized false
        }
        if (awaitingRecoveryPoint && !frame.isRecoveryPoint) {
            droppedFrames++
            return@synchronized false
        }

        frames.addLast(frame)
        queuedBytes += frame.byteSize
        if (frame.isRecoveryPoint) awaitingRecoveryPoint = false
        val wasOverBound = isOverBound(nowNanos)
        if (wasOverBound) {
            overflows++
            trimToNewestRecoveryPoint()
            if (isOverBound(nowNanos)) {
                clearFrames(countAsDropped = true)
                awaitingRecoveryPoint = true
            }
        }
        if (frames.isNotEmpty()) lock.notifyAll()
        frames.any { it === frame }
    }

    /**
     * Waits briefly for input, then removes the oldest non-stale frame. This is a consumer wait, not
     * frame pacing; the presentation clock is handled separately by MediaCodec's render timestamp.
     */
    fun take(
        timeoutMillis: Long,
        nowNanos: Long = System.nanoTime(),
    ): MirrorQueuedFrame? {
        val timeoutNanos = timeoutMillis.coerceAtLeast(0L) * NANOS_PER_MILLISECOND
        val deadline = saturatingAdd(System.nanoTime(), timeoutNanos)
        synchronized(lock) {
            while (frames.isEmpty()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return null
                val waitMillis = remaining / NANOS_PER_MILLISECOND
                val waitNanos = (remaining % NANOS_PER_MILLISECOND).toInt()
                lock.wait(waitMillis, waitNanos)
            }
            trimIfNeeded(if (timeoutMillis <= 0L) nowNanos else System.nanoTime())
            val frame = frames.pollFirst() ?: return null
            queuedBytes -= frame.byteSize
            queuedBytes = queuedBytes.coerceAtLeast(0L)
            return frame
        }
    }

    /** Clears any encoded dependency chain after decoder input loss or surface replacement. */
    fun requireRecoveryPoint() = synchronized(lock) {
        clearFrames(countAsDropped = true)
        awaitingRecoveryPoint = true
    }

    /** The currently-consumed configured IDR restored the decoder after a surface/codec reset. */
    fun markRecoveryPointAccepted() = synchronized(lock) {
        awaitingRecoveryPoint = false
    }

    fun snapshot(nowNanos: Long = System.nanoTime()): MirrorFrameQueueSnapshot = synchronized(lock) {
        val ageNanos = frames.peekFirst()?.let { (nowNanos - it.arrivalNanos).coerceAtLeast(0L) } ?: 0L
        MirrorFrameQueueSnapshot(
            frames = frames.size,
            bytes = queuedBytes,
            oldestAgeMillis = ageNanos / NANOS_PER_MILLISECOND,
            awaitingRecoveryPoint = awaitingRecoveryPoint,
            droppedFrames = droppedFrames,
            overflows = overflows,
        )
    }

    private fun trimIfNeeded(nowNanos: Long) {
        if (frames.isEmpty() || !isOverBound(nowNanos)) return
        overflows++
        trimToNewestRecoveryPoint()
        if (isOverBound(nowNanos)) {
            clearFrames(countAsDropped = true)
            awaitingRecoveryPoint = true
        }
    }

    private fun isOverBound(nowNanos: Long): Boolean {
        if (frames.size > maxFrames || queuedBytes > maxBytes) return true
        val oldest = frames.peekFirst() ?: return false
        return (nowNanos - oldest.arrivalNanos).coerceAtLeast(0L) > maxAgeNanos
    }

    /** Drop the incomplete prefix before the freshest recovery point. */
    private fun trimToNewestRecoveryPoint() {
        val newestRecoveryIndex = frames.indexOfLast { it.isRecoveryPoint }
        if (newestRecoveryIndex < 0) {
            clearFrames(countAsDropped = true)
            awaitingRecoveryPoint = true
            return
        }
        repeat(newestRecoveryIndex) {
            val removed = frames.pollFirst() ?: return@repeat
            queuedBytes -= removed.byteSize
            droppedFrames++
        }
        queuedBytes = queuedBytes.coerceAtLeast(0L)
        awaitingRecoveryPoint = false
    }

    private fun clearFrames(countAsDropped: Boolean) {
        if (countAsDropped) droppedFrames += frames.size
        frames.clear()
        queuedBytes = 0L
    }

    private fun saturatingAdd(first: Long, second: Long): Long =
        try {
            Math.addExact(first, second)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    companion object {
        const val DEFAULT_MAX_FRAMES = 30
        const val DEFAULT_MAX_BYTES = 16L * 1024L * 1024L
        const val DEFAULT_MAX_AGE_NANOS = 500_000_000L
        const val STABLE_FPS_MAX_AGE_NANOS = 250_000_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal enum class MirrorPresentationAction { RENDER_NOW, SCHEDULE, DROP_STALE }

internal data class MirrorPresentationDecision(
    val action: MirrorPresentationAction,
    val renderTimeNanos: Long? = null,
)

/** Pure policy for bounded timestamp-based presentation; never invents a target frame rate. */
internal object MirrorPresentationPolicy {
    const val MAX_RENDER_LATENESS_NANOS = 120_000_000L
    const val MAX_RENDER_LEAD_NANOS = 80_000_000L

    fun decide(
        enabled: Boolean,
        targetTimeNanos: Long,
        nowNanos: Long,
        maxLatenessNanos: Long = MAX_RENDER_LATENESS_NANOS,
        maxLeadNanos: Long = MAX_RENDER_LEAD_NANOS,
    ): MirrorPresentationDecision {
        if (!enabled) return MirrorPresentationDecision(MirrorPresentationAction.RENDER_NOW)
        val delta = targetTimeNanos - nowNanos
        if (delta < -maxLatenessNanos) {
            return MirrorPresentationDecision(MirrorPresentationAction.DROP_STALE)
        }
        return if (delta > 0L && delta <= maxLeadNanos) {
            MirrorPresentationDecision(MirrorPresentationAction.SCHEDULE, targetTimeNanos)
        } else {
            MirrorPresentationDecision(MirrorPresentationAction.RENDER_NOW)
        }
    }
}
