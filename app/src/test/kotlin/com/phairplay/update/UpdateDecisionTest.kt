package com.phairplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateDecisionTest — the rule that stops Hearth offering a downgrade.
 *
 * WHY THIS FILE EXISTS: the updater reads the published build's versionCode out of the release
 * *notes* and compares it with the one baked into the running APK. Every way that comparison can
 * be wrong has a user-visible consequence, and the worst one is a real downgrade: Android refuses
 * to install an APK whose versionCode is lower than the installed one
 * (`INSTALL_FAILED_VERSION_DOWNGRADE`), so the TV would download an "update" it can never install.
 * These tests pin the four outcomes — newer, same, older, unreadable — so that can't regress.
 */
class UpdateDecisionTest {

    private fun info(versionCode: Int, versionName: String = "1.6.1-main.7") = UpdateInfo(
        versionName = versionName,
        versionCode = versionCode,
        tagName = "latest",
        htmlUrl = "https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest",
        apkName = "Hearth-$versionName-googletv.apk",
        apkUrl = "https://example.invalid/apk",
        apkSizeBytes = 1,
        sha256 = null,
        notes = null
    )

    @Test
    fun `a newer published build is offered`() {
        val decision = UpdateDecision.evaluate(info(versionCode = 1_451_000), installedVersionCode = 1_450_000)
        assertTrue(decision is UpdateCheck.Available)
    }

    @Test
    fun `the same build is not an update`() {
        val decision = UpdateDecision.evaluate(info(versionCode = 1_450_000), installedVersionCode = 1_450_000)
        assertTrue("an equal versionCode must never be offered as an update", decision is UpdateCheck.UpToDate)
        assertFalse((decision as UpdateCheck.UpToDate).newerThanPublished)
    }

    @Test
    fun `an older published build is a downgrade and is never offered`() {
        val decision = UpdateDecision.evaluate(info(versionCode = 1_440_000), installedVersionCode = 1_450_000)
        assertTrue("a lower versionCode must never be Available", decision is UpdateCheck.UpToDate)
        val upToDate = decision as UpdateCheck.UpToDate
        assertTrue("the install is ahead of the published build", upToDate.newerThanPublished)
        // ... and it must not be mistaken for the "release says nothing" case.
        assertFalse(upToDate.publishedVersionUnknown)
    }

    @Test
    fun `a release with no readable versionCode is not offered either way`() {
        val decision = UpdateDecision.evaluate(info(versionCode = 0), installedVersionCode = 1_450_000)
        assertTrue(decision is UpdateCheck.UpToDate)
        val upToDate = decision as UpdateCheck.UpToDate
        assertTrue("the UI has to be able to say the release is unidentifiable", upToDate.publishedVersionUnknown)
        assertFalse(upToDate.newerThanPublished)
    }

    @Test
    fun `a skipped build stays available but is marked skipped`() {
        // The service marks it; the UI relies on the flag to explain why nothing was announced.
        val available = UpdateDecision.evaluate(info(versionCode = 1_451_000), 1_450_000)
        val marked = (available as UpdateCheck.Available).copy(skipped = true)
        assertTrue(marked.skipped)
        assertEquals(1_451_000L, marked.info.versionCode.toLong())
    }
}
