package com.phairplay.service

/**
 * Monotonic tickets for receiver lifecycle commands. A callback can publish only while its
 * generation remains current; executing the small publication block under the same lock as
 * [next] prevents a stale callback from racing past an immediate Stop acknowledgment.
 */
internal class ReceiverLifecycleGate {
    private val lock = Any()
    private var generation = 0L

    /** Issues a ticket for the next ordered receiver lifecycle command. */
    fun next(): Long = synchronized(lock) { ++generation }

    /** Checks whether [ticket] still represents the newest lifecycle command. */
    fun isCurrent(ticket: Long): Boolean = synchronized(lock) { generation == ticket }

    /** Runs a short state-publication block only if [ticket] is still current. */
    fun runIfCurrent(ticket: Long, publish: () -> Unit): Boolean = synchronized(lock) {
        if (generation != ticket) return@synchronized false
        publish()
        true
    }
}
