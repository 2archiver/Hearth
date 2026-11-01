package com.phairplay.airplay

/** Main/application-looper-owned counters. Initial loading is not a rebuffer. */
internal class UrlVideoMetrics {
    private var startedNanos = 0L
    private var firstFrameNanos: Long? = null
    private var bufferingSince: Long? = null
    private var rebufferNanos = 0L
    private var ready = false
    var sourceType: String = "unknown"
        private set
    var rebufferCount: Int = 0
        private set

    fun begin(sourceType: String, nowNanos: Long) {
        this.sourceType = sourceType
        startedNanos = nowNanos
        firstFrameNanos = null
        bufferingSince = null
        rebufferNanos = 0L
        rebufferCount = 0
        ready = false
    }

    fun firstFrame(nowNanos: Long) {
        if (firstFrameNanos == null) firstFrameNanos = nowNanos
    }

    fun playbackState(buffering: Boolean, isReady: Boolean, nowNanos: Long) {
        if (buffering && ready && bufferingSince == null) {
            bufferingSince = nowNanos
            rebufferCount++
        } else if (!buffering) {
            bufferingSince?.let { rebufferNanos += (nowNanos - it).coerceAtLeast(0L) }
            bufferingSince = null
        }
        if (isReady) ready = true
    }

    fun summary(nowNanos: Long): String {
        val firstMs = firstFrameNanos?.let { (it - startedNanos).coerceAtLeast(0L) / 1_000_000L }
        val totalMs = (rebufferNanos + (bufferingSince?.let { (nowNanos - it).coerceAtLeast(0L) } ?: 0L)) / 1_000_000L
        return "URL source=$sourceType; first frame=${firstMs?.let { "${it}ms" } ?: "N/A"}; " +
            "rebuffers=$rebufferCount; buffering=${totalMs}ms"
    }
}
