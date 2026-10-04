package com.phairplay.airplay

/**
 * SessionOwnership — which RTSP connection owns the receiver's one media session.
 *
 * WHY: an Apple sender opens several sockets to port 7000 (control, event, probes) and opens a
 * fresh control connection on every reconnect. Before 1.8.2 *any* connection that had ever
 * run a mirror SETUP could end the session when it closed. The trace that motivated this: keys
 * and audio set up on the new control connection, then — about a second later, with no video
 * yet — the previous connection's socket closed and "Streaming stopped" released everything the
 * new connection had just built.
 *
 * Now a connection *claims* the session when it supplies keys (mirror SETUP), starts a legacy
 * RECORD or starts URL video, and only the current owner's stop is honoured. A stop from any
 * other connection is logged with its reason and ignored.
 */
class SessionOwnership {
    private var owner: Any? = null

    /** Makes [connection] the owner. Returns true when it took over from a different owner. */
    @Synchronized
    fun claim(connection: Any): Boolean {
        val previous = owner
        owner = connection
        return previous != null && previous !== connection
    }

    /**
     * Releases the session if [connection] owns it. Returns false — and changes nothing — when
     * another connection owns it or nothing does.
     */
    @Synchronized
    fun release(connection: Any): Boolean {
        if (owner == null || owner !== connection) return false
        owner = null
        return true
    }

    /** Ends ownership regardless of who holds it (receiver shutdown, URL video ended). */
    @Synchronized
    fun reset() {
        owner = null
    }

    /** True when [connection] currently owns the session. */
    @Synchronized
    fun isOwner(connection: Any): Boolean = owner != null && owner === connection

    /** True when any connection owns the session. */
    @Synchronized
    fun isClaimed(): Boolean = owner != null
}
