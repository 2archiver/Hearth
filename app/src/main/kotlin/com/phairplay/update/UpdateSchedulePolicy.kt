package com.phairplay.update

/**
 * Persisted timing facts for the updater. WorkManager is intentionally best-effort; this state is
 * the authority for eligibility when Android delays, coalesces, or restarts a scheduled worker.
 */
data class UpdateScheduleStatus(
    val lastCheckMillis: Long = 0L,
    val lastAttemptMillis: Long = 0L,
    val lastSuccessMillis: Long = 0L,
    val lastFailureMillis: Long = 0L,
    val retryAfterMillis: Long = 0L,
    val rateLimitUntilMillis: Long = 0L,
    val lastFailureMessage: String? = null,
    /** Older releases kept only one timestamp; preserve its throttle without claiming it succeeded. */
    val legacyTimestampOnly: Boolean = false,
) {
    fun nextEligibleAtMillis(nowMillis: Long): Long {
        val cadenceAt = when {
            legacyTimestampOnly && lastCheckMillis > 0L -> safeAdd(lastCheckMillis, UpdatePreferences.CHECK_INTERVAL_MS)
            // A newer in-flight, interrupted, or failed attempt supersedes an older success.
            // Otherwise the hourly-success cadence could hide the shorter retry deadline.
            lastAttemptMillis > lastSuccessMillis && lastAttemptMillis > 0L ->
                safeAdd(lastAttemptMillis, UpdatePreferences.RETRY_INTERVAL_MS)
            lastFailureMillis > lastSuccessMillis && lastFailureMillis > 0L ->
                safeAdd(lastFailureMillis, UpdatePreferences.RETRY_INTERVAL_MS)
            lastSuccessMillis > 0L -> safeAdd(lastSuccessMillis, UpdatePreferences.CHECK_INTERVAL_MS)
            lastAttemptMillis > 0L -> safeAdd(lastAttemptMillis, UpdatePreferences.RETRY_INTERVAL_MS)
            lastCheckMillis > 0L -> safeAdd(lastCheckMillis, UpdatePreferences.RETRY_INTERVAL_MS)
            else -> nowMillis
        }
        return maxOf(nowMillis, cadenceAt, retryAfterMillis, rateLimitUntilMillis)
    }

    fun isAutomaticCheckDue(nowMillis: Long): Boolean =
        nowMillis >= nextEligibleAtMillis(nowMillis)

    private fun safeAdd(value: Long, delta: Long): Long =
        if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta
}

/** Pure scheduling rules shared by WorkManager, Settings, and JVM regression tests. */
object UpdateSchedulePolicy {
    fun isAutomaticCheckDue(status: UpdateScheduleStatus, nowMillis: Long): Boolean =
        status.isAutomaticCheckDue(nowMillis)

    fun nextEligibleAtMillis(status: UpdateScheduleStatus, nowMillis: Long): Long =
        status.nextEligibleAtMillis(nowMillis)
}

/** Process-local install guard; automatic and manual entry points both consult it. */
object UpdateInstallSafety {
    @Volatile
    private var playbackActive: Boolean = false

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    fun isPlaybackActive(): Boolean = playbackActive
}
