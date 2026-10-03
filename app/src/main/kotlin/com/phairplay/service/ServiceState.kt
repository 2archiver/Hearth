package com.phairplay.service

/**
 * ServiceState — Represents the lifecycle state of the PhairPlayService.
 *
 * WHY: The UI needs to know if the service is running, stopped, or in an error
 * state to show the correct UI and enable/disable controls appropriately.
 *
 * HOW: Emitted by [PhairPlayService] via a broadcast or LiveData.
 * Observed by [HomeFragment] to update service status cards.
 *
 * Example:
 *   when (state) {
 *       ServiceState.RUNNING  -> showStatusRunning()
 *       ServiceState.STOPPED  -> showStatusStopped()
 *       ServiceState.ERROR    -> showStatusError(state.errorMessage)
 *   }
 */
sealed class ServiceState {

    /** The service is running normally and all enabled protocols are advertising. */
    object Running : ServiceState()

    /** The service has been stopped by the user. No protocols are active. */
    object Stopped : ServiceState()

    /**
     * The service encountered an unrecoverable error.
     * @param message Human-readable error description (already localized if possible).
     */
    data class Error(val message: String) : ServiceState()

    /** The service is currently restarting (brief transition between Stopped and Running). */
    object Restarting : ServiceState()
}

/**
 * ProtocolState — Represents the state of one receiver card on the Home screen.
 *
 * WHY: One AirPlay receiver backs two cards — "AirPlay" (what is being streamed) and
 * "Apple Casting" (screen mirroring from an iPhone/iPad/Mac). They report the same enum
 * from different signals, so the cards can disagree honestly: the AirPlay card is CONNECTED
 * while a sender holds a session, the Apple Casting card only while mirror video is on
 * screen. See [PhairPlayService.appleCastingState].
 */
enum class ProtocolState {
    /** Protocol is disabled in Settings. */
    DISABLED,

    /** Protocol is enabled and advertising — waiting for a sender. */
    ADVERTISING,

    /** A sender is actively connected and streaming. */
    CONNECTED,

    /**
     * The protocol is enabled in Settings but cannot run on this hardware — a missing
     * system service, unsupported radio, or a permission the platform refuses to grant.
     *
     * WHY A SEPARATE STATE: this is not a fault the user caused or a bug to report, so it
     * must not be painted as a red ERROR the way it used to be. A TV whose mDNS responder
     * refuses the record, for example, shows grey with the reason and moves on.
     */
    UNAVAILABLE,

    /** The protocol encountered an error (e.g., port already in use). */
    ERROR
}

/**
 * ActiveConnection — Describes a currently active streaming connection.
 *
 * @param senderName   The display name of the sender (e.g., "Max's MacBook Pro").
 * @param protocol     Which protocol the connection uses.
 * @param startedAt    System clock millis when the connection was established.
 */
data class ActiveConnection(
    val senderName: String,
    val protocol: Protocol,
    val startedAt: Long = System.currentTimeMillis()
) {
    /** Returns the elapsed streaming time in seconds. */
    val durationSeconds: Long
        get() = (System.currentTimeMillis() - startedAt) / 1000L
}

/**
 * Identifies the receiver a connection belongs to.
 *
 * AirPlay is the only receiver Hearth runs. The Apple Casting card is not a second
 * protocol — it is the mirroring half of the same AirPlay session — so it shares this value
 * rather than inventing a protocol that does not exist.
 */
enum class Protocol {
    AIRPLAY
}
