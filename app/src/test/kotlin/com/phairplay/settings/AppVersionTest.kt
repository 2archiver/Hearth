package com.phairplay.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AppVersionTest — pins how a raw BuildConfig.VERSION_NAME is shown to a person.
 *
 * WHY: the version string carries build-system detail (`-main.4` from the rolling release
 * workflow, `-googletv` from the product flavor) that must not leak into the changelog
 * header or the "What's new" Settings row — while the Version row keeps the full string so
 * a bug report can identify the exact build.
 *
 * WHAT WE TEST:
 * - Rolling builds (`1.3.0-main.4-googletv`) report their release version (`1.3.0`)
 * - Tagged and local builds (`1.3.0-googletv`) do the same
 * - Pre-release suffixes are preserved (`1.3.0-beta.1` is not `1.3.0`)
 * - The release train is the major.minor part, for filing changelog entries
 * - A blank name falls back to "unknown" instead of showing an empty row
 */
class AppVersionTest {

    @Test
    fun `base strips the rolling build marker and the flavor suffix`() {
        assertEquals("1.3.0", AppVersion.base("1.3.0-main.4-googletv"))
    }

    @Test
    fun `base strips the flavor suffix from a tagged build`() {
        assertEquals("1.3.0", AppVersion.base("1.3.0-googletv"))
    }

    @Test
    fun `base keeps a pre-release suffix`() {
        assertEquals("1.3.0-beta.1", AppVersion.base("1.3.0-beta.1-googletv"))
    }

    @Test
    fun `base tolerates a name without a flavor suffix`() {
        assertEquals("1.3.0", AppVersion.base("1.3.0"))
    }

    @Test
    fun `base ignores surrounding whitespace`() {
        assertEquals("1.3.0", AppVersion.base("  1.3.0-googletv  "))
    }

    @Test
    fun `base falls back to unknown for a blank name`() {
        assertEquals("unknown", AppVersion.base("   "))
    }

    @Test
    fun `train is the major and minor version`() {
        assertEquals("1.3", AppVersion.train("1.3.0-main.4-googletv"))
    }

    @Test
    fun `train ignores the patch and pre-release parts`() {
        assertEquals("1.3", AppVersion.train("1.3.2-beta.7-googletv"))
    }

    @Test
    fun `train falls back to unknown for a blank name`() {
        assertEquals("unknown", AppVersion.train(""))
    }
}
