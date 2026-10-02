package com.phairplay.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MdnsNamesTest — pins the rules that decide the name a sender sees.
 *
 * WHY: this used to be scattered. `AppSettings` trimmed the stored value, `NetworkUtils`
 * stripped characters out of the *system* name only, and `GET /info` ignored both and
 * answered with the Android device name — so a rename changed the mDNS record but not the
 * name an iPhone displayed. Everything now funnels through [MdnsNames], so these tests
 * protect the whole rename path.
 *
 * WHAT WE TEST:
 * - The default name ("Apple TV") is used when nothing better is available
 * - Characters that corrupt a Bonjour record are removed, not merely trimmed
 * - The 63-byte DNS-SD limit is enforced on a UTF-8 character boundary
 * - [MdnsNames.sanitizeOrNull] keeps "blank" distinguishable from "use the default"
 */
class MdnsNamesTest {

    @Test
    fun `default display name is Apple TV`() {
        assertEquals("Apple TV", MdnsNames.DEFAULT_DISPLAY_NAME)
    }

    @Test
    fun `sanitize returns the default for null`() {
        assertEquals("Apple TV", MdnsNames.sanitize(null))
    }

    @Test
    fun `sanitize returns the default for a blank name`() {
        assertEquals("Apple TV", MdnsNames.sanitize("   "))
    }

    @Test
    fun `sanitize trims surrounding whitespace`() {
        assertEquals("Kitchen TV", MdnsNames.sanitize("  Kitchen TV  "))
    }

    @Test
    fun `sanitize drops characters that break mDNS records`() {
        assertEquals("Living Room TV", MdnsNames.sanitize("Living <Room> TV!"))
    }

    @Test
    fun `sanitize collapses the whitespace left behind`() {
        assertEquals("Living Room TV", MdnsNames.sanitize("Living  Room   TV"))
    }

    @Test
    fun `sanitize falls back when nothing usable remains`() {
        assertEquals("Apple TV", MdnsNames.sanitize("!!!"))
    }

    @Test
    fun `sanitize honours an explicit fallback`() {
        assertEquals("PhairPlay", MdnsNames.sanitize("###", fallback = "PhairPlay"))
    }

    @Test
    fun `sanitize keeps hyphens and underscores`() {
        assertEquals("Living-Room_TV 2", MdnsNames.sanitize("Living-Room_TV 2"))
    }

    @Test
    fun `sanitize caps the name at 63 UTF-8 bytes`() {
        val long = "A".repeat(200)
        val result = MdnsNames.sanitize(long)
        assertEquals(63, result.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `sanitize never splits a multi-byte character`() {
        // 32 × "é" = 64 bytes when encoded — one byte over the limit, so the last
        // character must be dropped whole rather than leaving a half-written byte.
        val accents = "\u00e9".repeat(32)
        val result = MdnsNames.sanitize(accents)
        assertEquals(31, result.length)
        assertTrue("result must be valid UTF-8 round-trip", result.toByteArray(Charsets.UTF_8).size <= 63)
        assertEquals("\u00e9".repeat(31), result)
    }

    @Test
    fun `sanitizeOrNull returns null for blank input`() {
        assertNull(MdnsNames.sanitizeOrNull(""))
        assertNull(MdnsNames.sanitizeOrNull("   "))
        assertNull(MdnsNames.sanitizeOrNull(null))
    }

    @Test
    fun `sanitizeOrNull returns the cleaned name otherwise`() {
        assertEquals("Kitchen TV", MdnsNames.sanitizeOrNull(" Kitchen TV "))
    }

    @Test
    fun `sanitizeOrNull returns null when only punctuation was entered`() {
        // Callers use this to fall back to the Android device name; the important part is
        // that it must not hand back a garbage one-character name.
        assertNull(MdnsNames.sanitizeOrNull("!!!"))
    }

    @Test
    fun `truncateUtf8 leaves short values untouched`() {
        assertEquals("Apple TV", MdnsNames.truncateUtf8("Apple TV", 63))
    }

    @Test
    fun `truncateUtf8 respects a smaller limit`() {
        assertEquals("App", MdnsNames.truncateUtf8("Apple TV", 3))
    }
}
