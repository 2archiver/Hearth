package com.phairplay.airplay

import java.io.IOException

/**
 * Media access for a URL-video session whose bytes do not come from an ordinary network URL.
 *
 * WHY: an AirPlay video session normally hands over an `http(s)` location and the player fetches it
 * itself. A sender-mediated (`mlhls`) session is the opposite — every byte has to be fetched by the
 * *sender*, through the FCUP reverse channel — so the player needs one narrow seam:
 * "read this player-facing URI for me". [SenderMediatedHlsBridge] implements it; the direct-URL path
 * passes null and the player fetches normally.
 *
 * Contract:
 *  - [open] returns the complete body for `uri` (the caller applies byte ranges itself);
 *  - [open] throws [IOException] with a URL-free message when the URI cannot be served;
 *  - the URIs handed to the player are private to this process and carry the session id, so a stale
 *    player can never read another session's media;
 *  - a source that has been closed fails every read rather than blocking.
 */
interface UrlVideoSessionSource {
    /** Generation label of the AirPlay session this source belongs to. */
    val sessionId: String

    /** Reads one player-facing (bridge) URI. */
    @Throws(IOException::class)
    fun open(uri: String): ByteArray

    /**
     * Why the session can no longer serve media, in a short URL-free phrase — null while healthy.
     *
     * The player only sees a failed read; this is how the *reason* (no reverse channel, the sender
     * refused, a protected playlist, the session ended) reaches the connection log and the trace.
     */
    fun failureReason(): String? = null
}
