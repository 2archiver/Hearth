package com.phairplay.cast.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DialAppRouterTest — handing a DIAL launch to the app that is already installed on the TV.
 *
 * WHY: apps with a private Cast channel (YouTube, Netflix, Spotify) cannot be emulated by any
 * generic receiver. What a real smart TV does instead is *start the native app* and let the phone
 * drive that. These tests cover the two pieces that have to be right for that to work: the
 * DIAL-name → package table, and pulling the content id out of the DIAL POST body.
 *
 * Runs on the plain JVM: [DialApps] has no Android imports on purpose.
 */
class DialAppRouterTest {

    // ─── Name table ──────────────────────────────────────────────────────────

    @Test
    fun `DIAL names are matched case-insensitively`() {
        assertNotNull(DialApps.packagesFor("Netflix"))
        assertEquals(
            DialApps.packagesFor("netflix").map { it.packageName },
            DialApps.packagesFor("Netflix").map { it.packageName }
        )
        assertTrue(DialApps.packagesFor("netflix").any { it.packageName == "com.netflix.ninja" })
    }

    @Test
    fun `an unknown DIAL name is not handed anywhere`() {
        assertTrue(DialApps.packagesFor("CC1AD845").isEmpty())
        assertFalse(DialApps.isKnown("CC1AD845"))
    }

    @Test
    fun `the Default Media Receiver is left to the built-in player`() {
        // If we ever "recognised" CC1AD845 we would stop playing the ordinary media URLs that
        // are the whole point of the built-in receiver.
        assertFalse(DialApps.isKnown("CC1AD845"))
        assertEquals(null, DialApps.deepLinkFor("CC1AD845", "anything"))
    }

    @Test
    fun `every entry in the table has a plausible package name`() {
        for ((name, candidates) in DialApps.allEntries()) {
            assertTrue("$name has no candidates", candidates.isNotEmpty())
            for (candidate in candidates) {
                assertTrue(
                    "$name → '${candidate.packageName}' is not a dotted package name",
                    candidate.packageName.matches(Regex("[a-z0-9]+(\\.[a-z0-9_]+)+"))
                )
            }
        }
    }

    // ─── Body parsing ────────────────────────────────────────────────────────

    @Test
    fun `YouTube's DIAL body is a bare v=videoId`() {
        assertEquals("dQw4w9WgXcQ", DialApps.contentIdFrom("v=dQw4w9WgXcQ"))
    }

    @Test
    fun `Netflix's dial= id is recognised`() {
        assertEquals("81234567", DialApps.contentIdFrom("dial=81234567"))
    }

    @Test
    fun `ids are URL-decoded`() {
        assertEquals("a=b", DialApps.contentIdFrom("v=a%3Db"))
    }

    @Test
    fun `an empty body just opens the app`() {
        assertEquals("", DialApps.contentIdFrom(""))
        assertEquals("", DialApps.contentIdFrom("   "))
    }

    @Test
    fun `a body with no key is treated as the id itself`() {
        // Some senders POST the raw id rather than key=value.
        assertEquals("dQw4w9WgXcQ", DialApps.contentIdFrom("dQw4w9WgXcQ"))
    }

    // ─── Deep links ──────────────────────────────────────────────────────────

    @Test
    fun `YouTube builds a watch URL from the video id`() {
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            DialApps.deepLinkFor("youtube", "dQw4w9WgXcQ")
        )
    }

    @Test
    fun `Netflix builds a title URL`() {
        assertEquals(
            "https://www.netflix.com/title/81234567",
            DialApps.deepLinkFor("Netflix", "81234567")
        )
    }

    @Test
    fun `an app with no deep link opens its front page instead`() {
        assertEquals(null, DialApps.deepLinkFor("spotify", "anything"))
    }

    @Test
    fun `a blank id never produces a link`() {
        assertEquals(null, DialApps.deepLinkFor("youtube", ""))
    }
}
