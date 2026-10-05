package com.phairplay.airplay

/** Playback mode is separate from connection role: a session can own several concurrent sockets. */
enum class AirPlaySessionMode {
    NEGOTIATING,
    AUDIO_ONLY,
    MIRRORING,
    URL_VIDEO,
    SENDER_MEDIATED_HLS,
}

enum class AirPlayPlaybackState {
    NEGOTIATING,
    LOADING,
    PLAYING,
    PAUSED,
    STOPPING,
    FAILED,
    DISCONNECTED,
}

enum class AirPlayConnectionRole {
    CONTROL,
    REVERSE_EVENT,
    MIRROR_DATA,
    AUDIO,
    DIRECT_VIDEO_CONTROL,
    PROBE,
}

enum class AirPlayMediaRole {
    MIRROR_VIDEO,
    MIRROR_AUDIO,
    URL_VIDEO,
}

data class SessionToken(val sessionId: String, val generation: Long)

data class SessionClaim(
    val token: SessionToken,
    /** The prior generation was replaced; its resources must be released before starting this one. */
    val replaced: SessionToken?,
    /** True only when an exact protocol-session identifier associated another connection. */
    val associatedByProtocolId: Boolean,
)

data class SessionSnapshot(
    val token: SessionToken,
    val mode: AirPlaySessionMode,
    val playbackState: AirPlayPlaybackState,
    val connectionRoles: Map<Any, AirPlayConnectionRole>,
    val mediaRoles: Set<AirPlayMediaRole>,
    val hasProtocolSessionId: Boolean,
)

enum class SessionCloseResult { STALE, KEEP_ACTIVE, CLEANUP }

/**
 * Owns Hearth's single active AirPlay media pipeline. A new connection joins an existing session
 * only when it is the same connection, or supplies the same opaque protocol-session fingerprint.
 * Sender IP addresses are deliberately not used for association.
 *
 * A generation token makes late EOF, teardown, and player callbacks from a replaced session inert.
 */
class SessionOwnership {
    private data class ActiveSession(
        val token: SessionToken,
        var protocolSessionFingerprint: String?,
        var mode: AirPlaySessionMode,
        var playbackState: AirPlayPlaybackState,
        val connectionRoles: MutableMap<Any, AirPlayConnectionRole>,
        val mediaRoles: MutableSet<AirPlayMediaRole>,
    )

    private var generation = 0L
    private var active: ActiveSession? = null

    /** Legacy convenience retained for small callers that only need to claim by connection. */
    @Synchronized
    fun claim(connection: Any): Boolean =
        claimSession(connection, null, AirPlayConnectionRole.CONTROL, AirPlaySessionMode.NEGOTIATING).replaced != null

    /**
     * Claims or associates [connection]. The fingerprint must be a one-way digest of an actual
     * protocol identifier, not a peer address or raw session token.
     */
    @Synchronized
    fun claimSession(
        connection: Any,
        protocolSessionFingerprint: String?,
        role: AirPlayConnectionRole,
        mode: AirPlaySessionMode,
    ): SessionClaim {
        val fingerprint = protocolSessionFingerprint?.takeIf { it.length in 8..64 }
        val current = active
        val alreadyRegistered = current?.connectionRoles?.containsKey(connection) == true
        val sameProtocolSession = current != null && fingerprint != null &&
            current.protocolSessionFingerprint == fingerprint
        val identifiersCompatible = current == null || current.protocolSessionFingerprint == null || fingerprint == null ||
            current.protocolSessionFingerprint == fingerprint

        if (current != null && identifiersCompatible && (alreadyRegistered || sameProtocolSession)) {
            current.connectionRoles[connection] = role
            if (current.protocolSessionFingerprint == null) current.protocolSessionFingerprint = fingerprint
            if (mode != AirPlaySessionMode.NEGOTIATING) current.mode = mode
            return SessionClaim(current.token, replaced = null, associatedByProtocolId = sameProtocolSession && !alreadyRegistered)
        }

        val previous = current?.token
        generation += 1L
        val token = SessionToken(sessionId = "S$generation", generation = generation)
        active = ActiveSession(
            token = token,
            protocolSessionFingerprint = fingerprint,
            mode = mode,
            playbackState = AirPlayPlaybackState.NEGOTIATING,
            connectionRoles = mutableMapOf(connection to role),
            mediaRoles = mutableSetOf(),
        )
        return SessionClaim(token, replaced = previous, associatedByProtocolId = false)
    }

    /** Adds a secondary connection only when it presents an exact fingerprint for the active session. */
    @Synchronized
    fun associateConnection(
        connection: Any,
        protocolSessionFingerprint: String?,
        role: AirPlayConnectionRole,
    ): SessionToken? {
        val fingerprint = protocolSessionFingerprint?.takeIf { it.length in 8..64 } ?: return null
        val session = active?.takeIf { it.protocolSessionFingerprint == fingerprint } ?: return null
        session.connectionRoles[connection] = role
        return session.token
    }

    @Synchronized
    fun updateConnectionRole(token: SessionToken, connection: Any, role: AirPlayConnectionRole): Boolean {
        val session = active?.takeIf { it.token == token } ?: return false
        if (connection !in session.connectionRoles) return false
        session.connectionRoles[connection] = role
        return true
    }

    @Synchronized
    fun updateMode(token: SessionToken, mode: AirPlaySessionMode): Boolean {
        val session = active?.takeIf { it.token == token } ?: return false
        session.mode = mode
        return true
    }

    /**
     * Records playback state for [token]. A null token (a connection that never claimed a session)
     * and a stale generation both report false, so callers can drop the callback.
     */
    @Synchronized
    fun updatePlaybackState(token: SessionToken?, state: AirPlayPlaybackState): Boolean {
        val session = active?.takeIf { it.token == token } ?: return false
        session.playbackState = state
        if (state == AirPlayPlaybackState.FAILED || state == AirPlayPlaybackState.DISCONNECTED) {
            session.mediaRoles.clear()
        }
        return true
    }

    @Synchronized
    fun setMediaRole(token: SessionToken, role: AirPlayMediaRole, active: Boolean): Boolean {
        val session = this.active?.takeIf { it.token == token } ?: return false
        if (active) session.mediaRoles += role else session.mediaRoles -= role
        return true
    }

    /** Updates one confirmed media role and reports whether it was the final live owner. */
    @Synchronized
    fun updateMediaRole(token: SessionToken?, role: AirPlayMediaRole, active: Boolean): SessionCloseResult {
        val session = this.active?.takeIf { it.token == token } ?: return SessionCloseResult.STALE
        if (active) {
            session.mediaRoles += role
            return SessionCloseResult.KEEP_ACTIVE
        }
        session.mediaRoles -= role
        val controllingConnectionRemains = session.connectionRoles.values.any {
            it == AirPlayConnectionRole.CONTROL || it == AirPlayConnectionRole.DIRECT_VIDEO_CONTROL
        }
        if (controllingConnectionRemains || session.mediaRoles.isNotEmpty()) return SessionCloseResult.KEEP_ACTIVE
        this.active = null
        return SessionCloseResult.CLEANUP
    }

    /**
     * Closes exactly one registered connection. A protocol TEARDOWN is session-wide and terminal;
     * EOF is not. EOF keeps the pipeline only while another control connection or confirmed media
     * role remains live. Event-only sockets do not keep a media session alive.
     */
    @Synchronized
    fun closeConnection(
        token: SessionToken?,
        connection: Any,
        explicitTeardown: Boolean,
        externallyLiveMediaRoles: Set<AirPlayMediaRole> = emptySet(),
    ): SessionCloseResult {
        val session = active ?: return SessionCloseResult.STALE
        if (token == null || session.token != token || connection !in session.connectionRoles) {
            return SessionCloseResult.STALE
        }
        if (explicitTeardown) {
            active = null
            return SessionCloseResult.CLEANUP
        }
        session.connectionRoles.remove(connection)
        val controllingConnectionRemains = session.connectionRoles.values.any {
            it == AirPlayConnectionRole.CONTROL || it == AirPlayConnectionRole.DIRECT_VIDEO_CONTROL
        }
        if (controllingConnectionRemains || session.mediaRoles.isNotEmpty() || externallyLiveMediaRoles.isNotEmpty()) {
            return SessionCloseResult.KEEP_ACTIVE
        }
        active = null
        return SessionCloseResult.CLEANUP
    }

    /** Ends [token] idempotently (player completion, explicit stop, receiver cleanup). */
    @Synchronized
    fun end(token: SessionToken?): Boolean {
        if (token == null || active?.token != token) return false
        active = null
        return true
    }

    @Synchronized
    fun isCurrent(token: SessionToken?): Boolean = token != null && active?.token == token

    @Synchronized
    fun isOwner(connection: Any): Boolean = active?.connectionRoles?.containsKey(connection) == true

    @Synchronized
    fun isClaimed(): Boolean = active != null

    @Synchronized
    fun snapshot(): SessionSnapshot? = active?.let { session ->
        SessionSnapshot(
            token = session.token,
            mode = session.mode,
            playbackState = session.playbackState,
            connectionRoles = session.connectionRoles.toMap(),
            mediaRoles = session.mediaRoles.toSet(),
            hasProtocolSessionId = session.protocolSessionFingerprint != null,
        )
    }

    /**
     * Compatibility helper for old callers. Returns true only if this call ended the complete
     * session; a connection release that leaves another role active returns false.
     */
    @Synchronized
    fun release(connection: Any): Boolean {
        val token = active?.token ?: return false
        return closeConnection(token, connection, explicitTeardown = false) == SessionCloseResult.CLEANUP
    }

    /** Receiver shutdown: invalidates all connection/player generations. */
    @Synchronized
    fun reset() {
        active = null
    }
}
