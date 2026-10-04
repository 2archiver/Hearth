package com.phairplay.update

import android.app.PendingIntent
import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the 1.8.1 updater defects fixed in 1.8.2. */
class InstallPolicyTest {

    @Test
    fun `install result PendingIntent is mutable on Android 12 and later`() {
        for (sdk in listOf(31, 33, 35)) {
            val flags = InstallPolicy.installResultPendingIntentFlags(sdk)
            assertTrue("FLAG_MUTABLE required on API $sdk", flags and PendingIntent.FLAG_MUTABLE != 0)
            assertEquals("never IMMUTABLE on API $sdk", 0, flags and PendingIntent.FLAG_IMMUTABLE)
        }
    }

    @Test
    fun `install result PendingIntent sets no mutability flag before Android 12`() {
        val flags = InstallPolicy.installResultPendingIntentFlags(29)
        assertEquals(PendingIntent.FLAG_UPDATE_CURRENT, flags)
    }

    @Test
    fun `staged build that is installed or older is obsolete`() {
        assertTrue(InstallPolicy.isStagedObsolete(stagedVersionCode = 100, installedVersionCode = 100))
        assertTrue(InstallPolicy.isStagedObsolete(stagedVersionCode = 99, installedVersionCode = 100))
        assertFalse(InstallPolicy.isStagedObsolete(stagedVersionCode = 101, installedVersionCode = 100))
        assertFalse("0 means nothing staged", InstallPolicy.isStagedObsolete(0, 100))
    }

    @Test
    fun `a release already proven not newer is not offered again`() {
        assertTrue(InstallPolicy.isKnownStaleRelease(500, rejectedPublishedVersionCode = 500))
        assertFalse("a newer publish clears it", InstallPolicy.isKnownStaleRelease(501, 500))
        assertFalse("nothing rejected", InstallPolicy.isKnownStaleRelease(500, 0))
    }

    @Test
    fun `installer statuses map to visible states`() {
        assertEquals(InstallState.AWAITING_CONFIRMATION, InstallPolicy.stateFor(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(InstallState.SUCCEEDED, InstallPolicy.stateFor(PackageInstaller.STATUS_SUCCESS))
        assertEquals(InstallState.CANCELLED, InstallPolicy.stateFor(PackageInstaller.STATUS_FAILURE_ABORTED))
        assertEquals(InstallState.FAILED, InstallPolicy.stateFor(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(InstallState.FAILED, InstallPolicy.stateFor(PackageInstaller.STATUS_FAILURE))
    }

    @Test
    fun `failure message includes the platform detail when present`() {
        val msg = InstallPolicy.failureMessage(PackageInstaller.STATUS_FAILURE_STORAGE, " INSTALL_FAILED_INSUFFICIENT_STORAGE ")
        assertTrue(msg.startsWith("Not enough storage"))
        assertTrue(msg.endsWith("(INSTALL_FAILED_INSUFFICIENT_STORAGE)"))
        assertEquals("Android could not install the update.", InstallPolicy.failureMessage(PackageInstaller.STATUS_FAILURE, null))
    }
}
