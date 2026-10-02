package com.phairplay.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppSettingsTest — Unit tests for [AppSettings] data class.
 *
 * WHY: [AppSettings] contains computed properties and constants that are
 * used throughout the app. Regression tests ensure that default values,
 * validation logic, and computed properties always behave correctly.
 *
 * WHAT WE TEST:
 * - Default values match the design spec
 * - [AppSettings.effectiveDisplayName] trims whitespace correctly
 * - [AppSettings.anyProtocolEnabled] returns correct results for all combinations
 * - [AppSettings.DISPLAY_NAME_MAX_LENGTH] constant value
 * - copy() semantics (standard data class behaviour)
 */
class AppSettingsTest {

    // ─── Default values ──────────────────────────────────────────────────────

    /**
     * The spoofed name now defaults to "Apple TV" instead of empty.
     *
     * Empty used to mean "fall back to the Android device name", which put the name a
     * sender showed outside the app's control.
     */
    @Test
    fun `default settings advertise the Apple TV name`() {
        assertEquals("Apple TV", AppSettings.DEFAULT.displayName)
        assertEquals("Apple TV", AppSettings.DEFAULT.effectiveDisplayName)
    }

    @Test
    fun `default settings have all protocols enabled`() {
        assertTrue(AppSettings.DEFAULT.airPlayEnabled)
        assertTrue(AppSettings.DEFAULT.miracastEnabled)
        assertTrue(AppSettings.DEFAULT.castEnabled)
    }

    @Test
    fun `default settings have pin auth disabled`() {
        assertFalse(AppSettings.DEFAULT.airPlayPinAuthEnabled)
    }

    @Test
    fun `default settings have start on boot disabled`() {
        assertFalse(AppSettings.DEFAULT.startOnBoot)
    }

    @Test
    fun `default settings have debug overlay disabled`() {
        assertFalse(AppSettings.DEFAULT.showDebugOverlay)
    }

    @Test
    fun `DISPLAY_NAME_MAX_LENGTH is 63`() {
        assertEquals(63, AppSettings.DISPLAY_NAME_MAX_LENGTH)
    }

    // ─── effectiveDisplayName ─────────────────────────────────────────────────

    @Test
    fun `effectiveDisplayName returns trimmed name`() {
        val settings = AppSettings(displayName = "  Living Room TV  ")
        assertEquals("Living Room TV", settings.effectiveDisplayName)
    }

    /**
     * A blank name must not silently hand the name back to the Android device name — it
     * resolves to the default, so the app always controls what senders see.
     */
    @Test
    fun `effectiveDisplayName falls back to the default for a blank name`() {
        val settings = AppSettings(displayName = "   ")
        assertEquals("Apple TV", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName returns name unchanged when no surrounding whitespace`() {
        val settings = AppSettings(displayName = "PhairPlay")
        assertEquals("PhairPlay", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName handles empty string`() {
        val settings = AppSettings(displayName = "")
        assertEquals("Apple TV", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName strips characters that break mDNS records`() {
        val settings = AppSettings(displayName = "Living <Room> TV!")
        assertEquals("Living Room TV", settings.effectiveDisplayName)
    }

    @Test
    fun `effectiveDisplayName is never empty for a punctuation-only name`() {
        val settings = AppSettings(displayName = "!!!")
        assertEquals("Apple TV", settings.effectiveDisplayName)
    }

    // ─── anyProtocolEnabled ───────────────────────────────────────────────────

    @Test
    fun `anyProtocolEnabled is true when all protocols are enabled`() {
        val settings = AppSettings(airPlayEnabled = true, miracastEnabled = true, castEnabled = true)
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only AirPlay is enabled`() {
        val settings = AppSettings(airPlayEnabled = true, miracastEnabled = false, castEnabled = false)
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only Miracast is enabled`() {
        val settings = AppSettings(airPlayEnabled = false, miracastEnabled = true, castEnabled = false)
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is true when only Cast is enabled`() {
        val settings = AppSettings(airPlayEnabled = false, miracastEnabled = false, castEnabled = true)
        assertTrue(settings.anyProtocolEnabled)
    }

    @Test
    fun `anyProtocolEnabled is false when all protocols are disabled`() {
        val settings = AppSettings(airPlayEnabled = false, miracastEnabled = false, castEnabled = false)
        assertFalse(settings.anyProtocolEnabled)
    }

    // ─── copy() and equality ──────────────────────────────────────────────────

    @Test
    fun `copy preserves unchanged fields`() {
        val original = AppSettings(displayName = "TV", airPlayEnabled = true, startOnBoot = false)
        val updated = original.copy(startOnBoot = true)

        assertEquals("TV", updated.displayName)
        assertTrue(updated.airPlayEnabled)
        assertTrue(updated.startOnBoot)
    }

    @Test
    fun `two instances with same values are equal`() {
        val a = AppSettings(displayName = "TV", airPlayEnabled = true)
        val b = AppSettings(displayName = "TV", airPlayEnabled = true)
        assertEquals(a, b)
    }

    @Test
    fun `two instances with different values are not equal`() {
        val a = AppSettings(displayName = "TV1")
        val b = AppSettings(displayName = "TV2")
        assertTrue(a != b)
    }
}
