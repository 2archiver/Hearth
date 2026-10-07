package com.phairplay.service

/**
 * Serializes current-cast callbacks against the service's immediate Stop acknowledgment.
 *
 * WHY: receiver teardown runs asynchronously, so a callback already in flight must finish before
 * Stop clears the visible session, and callbacks arriving during teardown must not restore it.
 */
internal class PlaybackStopGate {
    private val lock = Any()
    private var stopPending = false

    /** Publish an event unless Stop owns the UI; lifecycle-neutral updates may opt in explicitly. */
    fun runCallback(publishDuringStop: Boolean = false, publish: () -> Unit): Boolean = synchronized(lock) {
        if (stopPending && !publishDuringStop) return@synchronized false
        publish()
        true
    }

    /** Atomically checks whether a cast is active, claims Stop and publishes its immediate ack. */
    fun beginStopIf(isActive: () -> Boolean, acknowledge: () -> Unit): Boolean = synchronized(lock) {
        if (stopPending || !isActive()) return@synchronized false
        stopPending = true
        try {
            acknowledge()
            true
        } catch (error: Throwable) {
            stopPending = false
            throw error
        }
    }

    /** Re-enable callbacks after the old session has been invalidated and its resources released. */
    fun finishStop() = synchronized(lock) { stopPending = false }
}
