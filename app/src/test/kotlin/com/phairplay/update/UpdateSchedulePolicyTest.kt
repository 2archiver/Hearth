package com.phairplay.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSchedulePolicyTest {
    private val hour = UpdatePreferences.CHECK_INTERVAL_MS
    private val retry = UpdatePreferences.RETRY_INTERVAL_MS

    @Test
    fun `first automatic check is eligible and successful checks use an hourly cadence`() {
        assertTrue(UpdateSchedulePolicy.isAutomaticCheckDue(UpdateScheduleStatus(), nowMillis = 10_000L))

        val success = UpdateScheduleStatus(lastCheckMillis = 20_000L, lastSuccessMillis = 20_000L)
        assertFalse(UpdateSchedulePolicy.isAutomaticCheckDue(success, 20_000L + hour - 1L))
        assertTrue(UpdateSchedulePolicy.isAutomaticCheckDue(success, 20_000L + hour))
        assertTrue(UpdateSchedulePolicy.nextEligibleAtMillis(success, 20_000L) == 20_000L + hour)
    }

    @Test
    fun `retry deadline suppresses automatic checks but permits them once due`() {
        val failed = UpdateScheduleStatus(
            lastCheckMillis = 100_000L,
            lastAttemptMillis = 100_000L,
            lastSuccessMillis = 1L,
            lastFailureMillis = 100_000L,
            retryAfterMillis = 100_000L + retry,
        )
        assertFalse(UpdateSchedulePolicy.isAutomaticCheckDue(failed, 100_000L + retry - 1L))
        assertTrue(UpdateSchedulePolicy.isAutomaticCheckDue(failed, 100_000L + retry))
    }

    @Test
    fun `GitHub rate-limit deadline takes precedence over the hourly schedule`() {
        val limited = UpdateScheduleStatus(
            lastSuccessMillis = 1L,
            retryAfterMillis = 200_000L,
            rateLimitUntilMillis = 250_000L,
        )
        assertFalse(UpdateSchedulePolicy.isAutomaticCheckDue(limited, 249_999L))
        assertTrue(UpdateSchedulePolicy.isAutomaticCheckDue(limited, 250_000L))
        assertTrue(UpdateSchedulePolicy.nextEligibleAtMillis(limited, 100_000L) == 250_000L)
    }

    @Test
    fun `legacy last-check timestamps remain hourly throttled without fabricating a success`() {
        val legacy = UpdateScheduleStatus(
            lastCheckMillis = 500_000L,
            lastAttemptMillis = 500_000L,
            legacyTimestampOnly = true,
        )
        assertFalse(UpdateSchedulePolicy.isAutomaticCheckDue(legacy, 500_000L + hour - 1L))
        assertTrue(UpdateSchedulePolicy.isAutomaticCheckDue(legacy, 500_000L + hour))
    }

    @Test
    fun `install safety is active only when receiver playback is marked active`() {
        UpdateInstallSafety.setPlaybackActive(false)
        assertFalse(UpdateInstallSafety.isPlaybackActive())
        UpdateInstallSafety.setPlaybackActive(true)
        assertTrue(UpdateInstallSafety.isPlaybackActive())
        UpdateInstallSafety.setPlaybackActive(false)
    }
}
