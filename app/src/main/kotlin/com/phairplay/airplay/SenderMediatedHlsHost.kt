package com.phairplay.airplay

/**
 * The receiver-side owner of the sender-mediated (FCUP) HLS transport.
 *
 * WHY THIS SEAM EXISTS: none of these four operations belong to a single RTSP connection. The
 * sender upgrades one connection with `POST /reverse` (the PTTH channel), then sends the playback
 * request on another, and the sender's answers arrive as `POST /action` on a third. The receiver has
 * to own that state for the session, while [RtspHandler] stays a per-connection protocol adapter.
 *
 * [AirPlayReceiver] implements this. When no host is supplied, a sender-mediated `/play` is refused
 * with 400 and an explicit trace line — the honest answer, and exactly what Hearth did before the
 * bridge existed.
 */
interface SenderMediatedHlsHost {

    /**
     * Registers the PTTH reverse channel of [connectionId].
     *
     * @param senderSessionId the sender's `X-Apple-Session-ID` header on `/reverse`, when present
     *   (the reverse upgrade does not require it; the `/play` request always carries it)
     * @param writer writes one complete request frame to that connection; returns false when the
     *   socket is gone
     * @return true when the receiver accepted the channel
     */
    fun registerReverseChannel(
        connectionId: String,
        senderSessionId: String?,
        writer: (ByteArray) -> Boolean,
    ): Boolean

    /** The reverse channel of [connectionId] is gone (connection closed); drop it if it is held. */
    fun releaseReverseChannel(connectionId: String)

    /**
     * Starts a sender-mediated playback session: binds the receiver's local playlist endpoint and
     * points the URL player at it. Called from the `/play` request that carried [location].
     */
    fun startSenderMediatedPlay(request: SenderMediatedPlayRequest): SenderMediatedPlayResult

    /**
     * Delivers a `POST /action` body to the session's bridge.
     *
     * @return the HTTP status the handler should answer with, plus a URL-free summary for the trace
     */
    fun deliverAction(senderSessionId: String?, body: ByteArray): SenderMediatedActionResult
}

/** One sender-mediated `/play`, with the evidence the receiver needs to accept or refuse it. */
data class SenderMediatedPlayRequest(
    val connectionId: String,
    /** `X-Apple-Session-ID` of the `/play` request — the id every FCUP request must echo. */
    val senderSessionId: String?,
    /** The `Content-Location`: a sender-mediated URI such as `mlhls://localhost/…/master.m3u8`. */
    val location: String,
    val startSeconds: Double,
    val seconds: Boolean,
    val token: SessionToken?,
)

/** Whether the sender-mediated session is serving, or a short URL-free reason it is not. */
data class SenderMediatedPlayResult(val accepted: Boolean, val reason: String? = null) {
    companion object {
        val ACCEPTED = SenderMediatedPlayResult(true)
        fun reject(reason: String) = SenderMediatedPlayResult(false, reason)
    }
}

/** How the handler must answer one `POST /action`. */
data class SenderMediatedActionResult(val httpStatus: Int, val summary: String)
