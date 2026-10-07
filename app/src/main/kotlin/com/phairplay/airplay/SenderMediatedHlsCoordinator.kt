package com.phairplay.airplay

import com.phairplay.airplay.handshake.FcupCodec
import com.phairplay.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns reverse-channel offers and active/warming sender-mediated HLS bridges for one receiver.
 *
 * A bridge is published in the warming slot *before* its master-playlist fetch begins. The sender's
 * `/action` arrives on another connection while `/play` is waiting, so action routing must already
 * know which request id to complete. During a replacement, the old bridge remains routable until the
 * new master is ready; process-wide FCUP ids make late replies unambiguous across both channels.
 */
internal class SenderMediatedHlsCoordinator(
    private val isStopped: () -> Boolean,
    private val isSessionCurrent: (SessionToken?) -> Boolean,
    private val onPlaybackReady: (SenderMediatedPlayRequest, SenderMediatedHlsBridge) -> Unit,
    private val requestTimeoutMs: Long = ReverseHttpChannel.DEFAULT_REQUEST_TIMEOUT_MS,
    private val reverseOfferWaitMs: Long = DEFAULT_REVERSE_OFFER_WAIT_MS,
) : SenderMediatedHlsHost {

    private data class ReverseOffer(
        val connectionId: String,
        val senderSessionId: String?,
        val writer: (ByteArray) -> Boolean,
        val revoke: (String) -> Unit,
    )

    private enum class BridgePhase { WARMING, ACTIVE }

    private data class BridgeEntry(
        val request: SenderMediatedPlayRequest,
        val reverseConnectionId: String,
        val bridge: SenderMediatedHlsBridge,
        val phase: BridgePhase,
    )

    private val lock = Any()
    /** Serializes whole /play warmups so an in-flight replacement cannot start a player after being displaced. */
    private val playLock = Any()
    private val reverseOffers = LinkedHashMap<String, ReverseOffer>()

    /** Rotating signal captured under [lock], so an offer arriving during /play cannot be missed. */
    private var reverseOffersChanged = CountDownLatch(1)

    @Volatile
    private var bridges: List<BridgeEntry> = emptyList()

    override fun registerReverseChannel(
        connectionId: String,
        senderSessionId: String?,
        writer: (ByteArray) -> Boolean,
        revoke: (String) -> Unit,
    ): Boolean = synchronized(lock) {
        if (isStopped() || connectionId.isBlank() || connectionId.length > MAX_CONNECTION_ID_CHARS) {
            return@synchronized false
        }
        val normalizedSessionId = senderSessionId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedSessionId != null &&
            (normalizedSessionId.length > FcupCodec.MAX_SESSION_ID_CHARS ||
                normalizedSessionId.any { it.code !in 0x20..0x7E })
        ) {
            return@synchronized false
        }
        if (connectionId !in reverseOffers && reverseOffers.size >= MAX_REVERSE_OFFERS) {
            return@synchronized false
        }
        reverseOffers[connectionId] = ReverseOffer(
            connectionId = connectionId,
            senderSessionId = normalizedSessionId,
            writer = writer,
            revoke = revoke,
        )
        signalReverseOfferChangeLocked()
        AirPlayTrace.record(
            "Reverse channel registered (senderSessionIdPresent=${senderSessionId?.isNotBlank() == true}, " +
                "openChannels=${reverseOffers.size})",
            connectionId = connectionId,
            role = AirPlayConnectionRole.REVERSE_EVENT.name,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        true
    }

    override fun releaseReverseChannel(connectionId: String) {
        val removed: List<BridgeEntry>
        val wasActive: Boolean
        synchronized(lock) {
            if (reverseOffers.remove(connectionId) != null) signalReverseOfferChangeLocked()
            removed = bridges.filter { it.reverseConnectionId == connectionId }
            wasActive = removed.any { it.phase == BridgePhase.ACTIVE }
            if (removed.isNotEmpty()) bridges = bridges.filterNot { it.reverseConnectionId == connectionId }
        }
        removed.forEach { entry ->
            runCatching { entry.bridge.close() }
                .onFailure { Logger.w("Sender-mediated bridge close failed (${it.javaClass.simpleName})") }
        }
        if (wasActive) {
            AirPlayTrace.record(
                "Sender-mediated session ended: its reverse channel closed",
                sessionId = removed.firstOrNull { it.phase == BridgePhase.ACTIVE }?.bridge?.sessionId,
                connectionId = connectionId,
                role = AirPlayConnectionRole.REVERSE_EVENT.name,
                kind = AirPlayTrace.Kind.FAILURE,
            )
        } else if (removed.isNotEmpty()) {
            AirPlayTrace.record(
                "Warming sender-mediated bridge cancelled: its reverse channel closed",
                connectionId = connectionId,
                role = AirPlayConnectionRole.REVERSE_EVENT.name,
                kind = AirPlayTrace.Kind.FAILURE,
            )
        } else {
            AirPlayTrace.record(
                "Reverse channel released (not used by the active session)",
                connectionId = connectionId,
                role = AirPlayConnectionRole.REVERSE_EVENT.name,
            )
        }
    }

    override fun startSenderMediatedPlay(request: SenderMediatedPlayRequest): SenderMediatedPlayResult =
        synchronized(playLock) { startSenderMediatedPlayLocked(request) }

    private fun startSenderMediatedPlayLocked(request: SenderMediatedPlayRequest): SenderMediatedPlayResult {
        if (isStopped()) return SenderMediatedPlayResult.reject("receiver is stopping")
        val token = request.token ?: return SenderMediatedPlayResult.reject("no claimed session")
        if (!isSessionCurrent(token)) return SenderMediatedPlayResult.reject("stale session")
        if (!SenderMediatedHlsBridge.isMasterPlaylistLocation(request.location)) {
            return SenderMediatedPlayResult.reject("not a sender-mediated master playlist location")
        }

        val offer = chooseReverseChannel(request)
            ?: return SenderMediatedPlayResult.reject("no unambiguous reverse channel is registered")
        if (request.senderSessionId != null && offer.senderSessionId != null &&
            request.senderSessionId != offer.senderSessionId
        ) {
            return SenderMediatedPlayResult.reject("reverse channel session id mismatch")
        }
        val senderSessionId = request.senderSessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: offer.senderSessionId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return SenderMediatedPlayResult.reject("sender session id is missing")
        if (senderSessionId.length > FcupCodec.MAX_SESSION_ID_CHARS ||
            senderSessionId.any { it.code !in 0x20..0x7E }
        ) {
            return SenderMediatedPlayResult.reject("sender session id is invalid")
        }

        AirPlayTrace.record(
            "Sender-mediated /play: reverseChannel=registered " +
                "senderSessionIdPresent=true",
            sessionId = token.sessionId,
            connectionId = request.connectionId,
            role = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL.name,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        val channel = try {
            ReverseHttpChannel(
                connectionId = offer.connectionId,
                senderSessionId = senderSessionId,
                writeFrame = offer.writer,
                requestTimeoutMs = requestTimeoutMs,
            )
        } catch (_: IllegalArgumentException) {
            return SenderMediatedPlayResult.reject("reverse channel configuration is invalid")
        }
        val bridge = SenderMediatedHlsBridge(
            sessionToken = token,
            contentLocation = request.location,
            channel = channel,
        )
        val candidate = BridgeEntry(request, offer.connectionId, bridge, BridgePhase.WARMING)
        val displaced = synchronized(lock) {
            if (isStopped() || !isSessionCurrent(token) || reverseOffers[offer.connectionId] !== offer) {
                null
            } else {
                val keepActive = bridges.filter {
                    it.phase == BridgePhase.ACTIVE && it.request.token == token
                }
                val remove = bridges.filterNot { it in keepActive }
                bridges = listOf(candidate) + keepActive
                remove
            }
        } ?: run {
            bridge.close()
            return SenderMediatedPlayResult.reject("reverse channel is no longer registered")
        }
        displaced.forEach { old -> old.bridge.close("replaced by a newer sender-mediated /play") }

        AirPlayTrace.record(
            "Sender-mediated /play: bridge published before master fetch",
            sessionId = token.sessionId,
            connectionId = request.connectionId,
            role = SenderMediatedHlsBridge.ROLE,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        if (!bridge.start()) {
            val reason = bridge.failureReason() ?: "the sender did not answer"
            removeBridge(bridge)
            bridge.close()
            AirPlayTrace.record(
                "Sender-mediated /play refused: $reason (the AirPlay audio stream is untouched)",
                sessionId = token.sessionId,
                connectionId = request.connectionId,
                kind = AirPlayTrace.Kind.FAILURE,
            )
            return SenderMediatedPlayResult.reject(reason, AirPlayPlaybackFailureStage.MANIFEST)
        }

        if (isStopped() || !isSessionCurrent(token) || !containsBridge(bridge) || !bridge.isWritable) {
            removeBridge(bridge)
            bridge.close("session changed while warming")
            return SenderMediatedPlayResult.reject("session changed while the sender was preparing video")
        }
        try {
            onPlaybackReady(request, bridge)
        } catch (error: Throwable) {
            removeBridge(bridge)
            bridge.close("player setup failed")
            Logger.e("Sender-mediated player setup failed (${error.javaClass.simpleName})")
            return SenderMediatedPlayResult.reject(
                "player setup failed",
                AirPlayPlaybackFailureStage.PLAYER_SETUP,
            )
        }

        val replaced = synchronized(lock) {
            val current = bridges.firstOrNull { it.bridge === bridge }
            if (current == null || isStopped() || !isSessionCurrent(token) || !bridge.isWritable) {
                null
            } else {
                bridges.filterNot { it.bridge === bridge }.also {
                    bridges = listOf(current.copy(phase = BridgePhase.ACTIVE))
                }
            }
        } ?: run {
            removeBridge(bridge)
            bridge.close("session changed before activation")
            return SenderMediatedPlayResult.reject("session changed before video playback started")
        }
        replaced.forEach { old ->
            old.bridge.close("replaced by a newer sender-mediated /play")
            AirPlayTrace.record(
                "Sender-mediated HLS released (replaced by a newer /play)",
                sessionId = old.bridge.sessionId,
                role = SenderMediatedHlsBridge.ROLE,
                kind = AirPlayTrace.Kind.LIFECYCLE,
            )
        }
        AirPlayTrace.record(
            "Sender-mediated HLS serving (${bridge.describe()})",
            sessionId = token.sessionId,
            connectionId = request.connectionId,
            role = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL.name,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        return SenderMediatedPlayResult.ACCEPTED
    }

    override fun deliverAction(senderSessionId: String?, body: ByteArray): SenderMediatedActionResult {
        val snapshot = bridges
        if (snapshot.isEmpty()) return SenderMediatedActionResult(400, "no sender-mediated session is active")
        val parsed = FcupCodec.parseAction(body)
        val matchingSession = snapshot.filter { it.bridge.channelSessionId == senderSessionId }
        if (matchingSession.isEmpty()) {
            return SenderMediatedActionResult(
                400,
                if (senderSessionId == null) "action without X-Apple-Session-ID" else "action session mismatch",
            )
        }

        val entry = when (parsed) {
            is FcupCodec.ActionParse.Response ->
                matchingSession.firstOrNull { it.bridge.hasPendingRequest(parsed.response.requestId) }
                    ?: matchingSession.firstOrNull { it.phase == BridgePhase.ACTIVE }
                    ?: matchingSession.first()
            else -> matchingSession.firstOrNull { it.phase == BridgePhase.ACTIVE } ?: matchingSession.first()
        }
        val delivery = entry.bridge.deliverParsed(parsed)
        return SenderMediatedActionResult(delivery.httpStatus, delivery.summary)
    }

    /**
     * Atomically closes old bridges and retains only the PTTH offer that belongs to the new claim.
     * A socket that is no longer eligible is closed too; otherwise its per-connection RTSP reader
     * would remain parked forever after the coordinator forgot the offer.
     */
    fun replaceSession(
        reason: String,
        connectionId: String,
        protocolSessionFingerprint: String?,
    ) {
        val (oldBridges, revokedOffers) = synchronized(lock) {
            val old = bridges
            bridges = emptyList()
            val offers = reverseOffers.values.toList()
            val keep = offers.filter { offer ->
                val offerFingerprint = AirPlaySessionFingerprint.of(offer.senderSessionId)
                val sameControlChannel = offer.connectionId == connectionId &&
                    (protocolSessionFingerprint == null || offerFingerprint == null ||
                        offerFingerprint == protocolSessionFingerprint)
                sameControlChannel || (protocolSessionFingerprint != null &&
                    offerFingerprint == protocolSessionFingerprint)
            }.mapTo(HashSet()) { it.connectionId }
            // An id-less offer on a different connection is not enough evidence to carry it into
            // the replacement session. Same-connection offers are preserved above.
            val revoked = offers.filterNot { it.connectionId in keep }
            reverseOffers.keys.retainAll(keep)
            if (revoked.isNotEmpty()) signalReverseOfferChangeLocked()
            old to revoked
        }
        closeEntries(oldBridges, reason)
        revokedOffers.forEach { offer -> runCatching { offer.revoke(reason) } }
    }

    /** Close media bridges but preserve reverse offers for stop/retry on the same sender session. */
    fun closeBridges(reason: String): Boolean {
        val old = synchronized(lock) {
            bridges.also { bridges = emptyList() }
        }
        closeEntries(old, reason)
        return old.isNotEmpty()
    }

    /** Full session/receiver cleanup: close bridges and revoke every upgraded PTTH socket. */
    fun reset(reason: String) {
        val (oldBridges, oldOffers) = synchronized(lock) {
            val old = bridges
            bridges = emptyList()
            val offers = reverseOffers.values.toList()
            reverseOffers.clear()
            if (offers.isNotEmpty()) signalReverseOfferChangeLocked()
            old to offers
        }
        closeEntries(oldBridges, reason)
        oldOffers.forEach { offer -> runCatching { offer.revoke(reason) } }
    }

    private fun closeEntries(entries: List<BridgeEntry>, reason: String) {
        entries.forEach { entry ->
            runCatching { entry.bridge.close(reason) }
                .onFailure { Logger.w("Sender-mediated bridge close failed (${it.javaClass.simpleName})") }
            AirPlayTrace.record(
                "Sender-mediated HLS released ($reason)",
                sessionId = entry.bridge.sessionId,
                connectionId = entry.reverseConnectionId,
                role = SenderMediatedHlsBridge.ROLE,
                kind = AirPlayTrace.Kind.LIFECYCLE,
            )
        }
    }

    private fun containsBridge(bridge: SenderMediatedHlsBridge): Boolean =
        bridges.any { it.bridge === bridge }

    private fun removeBridge(bridge: SenderMediatedHlsBridge) {
        synchronized(lock) { bridges = bridges.filterNot { it.bridge === bridge } }
    }

    /**
     * Waits briefly for POST /reverse to register when it races the sender's POST /play.
     *
     * Selection and the latch snapshot happen under the same lock used by registration. Registration
     * rotates and counts down that latch while holding the lock, so the signal cannot land between
     * checking the offer table and beginning to wait. Session ids are the only cross-connection
     * association evidence; peer addresses are deliberately not consulted.
     */
    private fun chooseReverseChannel(request: SenderMediatedPlayRequest): ReverseOffer? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(reverseOfferWaitMs.coerceAtLeast(0L))
        while (true) {
            val (selected, changed) = synchronized(lock) {
                selectReverseChannelLocked(request) to reverseOffersChanged
            }
            if (selected != null) {
                val (offer, evidence) = selected
                AirPlayTrace.record(
                    "Reverse channel selected for /play by $evidence",
                    connectionId = offer.connectionId,
                    role = AirPlayConnectionRole.REVERSE_EVENT.name,
                    associationEvidence = if (evidence == "AirPlay session id") "PROTOCOL_ID" else null,
                )
                return offer
            }
            if (isStopped() || !isSessionCurrent(request.token)) return null
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) return null
            try {
                if (!changed.await(remainingNanos, TimeUnit.NANOSECONDS)) return null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    /** Called only while [lock] is held. A missing/mismatched id never guesses between channels. */
    private fun selectReverseChannelLocked(request: SenderMediatedPlayRequest): Pair<ReverseOffer, String>? {
        val senderId = request.senderSessionId?.trim()?.takeIf { it.isNotEmpty() }
        val sameControl = reverseOffers[request.connectionId]
        if (sameControl != null && (senderId == null || sameControl.senderSessionId == null ||
                sameControl.senderSessionId == senderId)
        ) {
            return sameControl to "same control connection"
        }
        if (senderId == null) return null
        val matches = reverseOffers.values.filter { it.senderSessionId == senderId }
        return matches.singleOrNull()?.let { it to "AirPlay session id" }
    }

    /** Counts down the current waiter and installs the next generation; call only under [lock]. */
    private fun signalReverseOfferChangeLocked() {
        val previous = reverseOffersChanged
        reverseOffersChanged = CountDownLatch(1)
        previous.countDown()
    }

    private companion object {
        const val MAX_CONNECTION_ID_CHARS = 48
        const val MAX_REVERSE_OFFERS = 8
        const val DEFAULT_REVERSE_OFFER_WAIT_MS = 3_000L
    }
}
