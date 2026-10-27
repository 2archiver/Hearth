package com.phairplay.airplay

/**
 * Bounded decoder backpressure handling. Drain output between short input waits so a full output
 * queue cannot hold input buffers hostage. This preserves the encoded reference chain when a
 * decoder is briefly busy; it does not add pacing or manufacture frames.
 * Used by VideoDecoder with MediaCodec callbacks, and with deterministic fakes in JVM tests.
 */
internal object DecoderInputPump {
    const val WAIT_US = 10_000L
    const val MAX_ATTEMPTS = 10 // Same 100 ms maximum input wait as before.

    fun acquire(
        dequeue: (Long) -> Int,
        drain: () -> Unit,
        interrupted: () -> Boolean = { Thread.currentThread().isInterrupted },
    ): Int {
        repeat(MAX_ATTEMPTS) {
            if (interrupted()) return -1
            val index = dequeue(WAIT_US)
            if (index >= 0) return index
            drain()
        }
        return -1
    }
}
