package com.phairplay.airplay

/** Validates hardware timestamps without changing samples, playback speed, or NTP state.
 * This clock has no shared RTP/video anchor: its measurements cannot establish A/V sync.
 */
internal class AudioPlaybackClock(private val sampleRate: Int) {
    private var previousFrame: Long? = null
    private var previousNanos: Long? = null
    private var stable = false

    init { require(sampleRate > 0) }

    fun observe(framePosition: Long, timestampNanos: Long, nowNanos: Long): Boolean {
        if (framePosition < 0L || timestampNanos <= 0L || timestampNanos > nowNanos ||
            nowNanos - timestampNanos > MAX_SAMPLE_AGE_NANOS) {
            reset()
            return false
        }
        val frame = previousFrame
        val time = previousNanos
        if (frame != null && time != null) {
            if (framePosition == frame && timestampNanos == time) return stable
            val deltaFrames = framePosition - frame
            val deltaNanos = timestampNanos - time
            if (deltaFrames < 0L || deltaNanos <= 0L || deltaNanos > MAX_SAMPLE_AGE_NANOS ||
                kotlin.math.abs(deltaFrames.toDouble() * 1_000_000_000.0 / sampleRate - deltaNanos) > MAX_ERROR_NANOS) {
                reset()
                return false
            }
            stable = true
        }
        previousFrame = framePosition
        previousNanos = timestampNanos
        return stable
    }

    fun reset() {
        previousFrame = null
        previousNanos = null
        stable = false
    }

    companion object {
        private const val MAX_SAMPLE_AGE_NANOS = 2_000_000_000L
        private const val MAX_ERROR_NANOS = 100_000_000L
    }
}
