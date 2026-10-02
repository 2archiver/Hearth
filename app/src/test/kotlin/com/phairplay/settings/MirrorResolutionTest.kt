package com.phairplay.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MirrorResolutionTest — Unit tests for the mirroring-resolution policy in [MirrorResolution].
 *
 * WHY: The size PhairPlay advertises in `GET /info` decides what the sender encodes. Asking a
 * 1080p TV (or a weak decoder) for 4K produces a stream it cannot decode — a black screen with
 * audio, the single most confusing AirPlay failure. These tests pin the rules down so the 4K
 * path can never widen without an explicit change here.
 *
 * WHAT WE TEST:
 * - the default is always 1080p, whatever the TV reports
 * - a 4K Google TV with a 4K-capable decoder gets 4K when the user opts in
 * - the panel and the decoder each cap the answer independently
 * - degenerate probes (0x0 panel, unknown decoder) fall back to 1080p
 */
class MirrorResolutionTest {

    // ─── Candidate sizes ─────────────────────────────────────────────────────

    @Test
    fun `candidates carry the sizes they advertise`() {
        assertEquals(1920, MirrorResolution.FHD.width)
        assertEquals(1080, MirrorResolution.FHD.height)
        assertEquals(2560, MirrorResolution.QHD.width)
        assertEquals(1440, MirrorResolution.QHD.height)
        assertEquals(3840, MirrorResolution.UHD.width)
        assertEquals(2160, MirrorResolution.UHD.height)
    }

    @Test
    fun `default is 1080p`() {
        assertEquals(MirrorResolution.FHD, MirrorResolution.DEFAULT)
    }

    // ─── Opt-in off: never more than 1080p ───────────────────────────────────

    @Test
    fun `opting out advertises 1080p even on a 4K TV`() {
        val res = MirrorResolution.pick(
            preferHighResolution = false,
            panelWidth = 3840, panelHeight = 2160,
            decodeWidth = 3840, decodeHeight = 2160
        )
        assertEquals(MirrorResolution.FHD, res)
    }

    // ─── Opt-in on: the 4K Google TV case ────────────────────────────────────

    @Test
    fun `4K Google TV with a 4K decoder advertises 4K`() {
        val res = MirrorResolution.pick(
            preferHighResolution = true,
            panelWidth = 3840, panelHeight = 2160,
            decodeWidth = 3840, decodeHeight = 2160
        )
        assertEquals(MirrorResolution.UHD, res)
        assertEquals("4K", res.label)
    }

    @Test
    fun `1440p panel caps the answer at 1440p`() {
        val res = MirrorResolution.pick(
            preferHighResolution = true,
            panelWidth = 2560, panelHeight = 1440,
            decodeWidth = 3840, decodeHeight = 2160
        )
        assertEquals(MirrorResolution.QHD, res)
    }

    @Test
    fun `1080p panel caps the answer at 1080p even when the decoder could do 4K`() {
        val res = MirrorResolution.pick(
            preferHighResolution = true,
            panelWidth = 1920, panelHeight = 1080,
            decodeWidth = 3840, decodeHeight = 2160
        )
        assertEquals(MirrorResolution.FHD, res)
    }

    @Test
    fun `decoder ceiling caps the answer below the panel size`() {
        // A 4K panel whose H.264 decoder tops out at 1080p must not be offered 4K.
        val res = MirrorResolution.pick(
            preferHighResolution = true,
            panelWidth = 3840, panelHeight = 2160,
            decodeWidth = 1920, decodeHeight = 1080
        )
        assertEquals(MirrorResolution.FHD, res)
    }

    @Test
    fun `height-limited decoder caps the answer`() {
        // Decoder claims 3840 wide but only 1088 tall (a real H.264 macroblock ceiling).
        val res = MirrorResolution.pick(
            preferHighResolution = true,
            panelWidth = 3840, panelHeight = 2160,
            decodeWidth = 3840, decodeHeight = 1088
        )
        assertEquals(MirrorResolution.FHD, res)
    }

    // ─── Degenerate device reports ───────────────────────────────────────────

    @Test
    fun `unknown panel size falls back to 1080p`() {
        val res = MirrorResolution.pick(
            preferHighResolution = true,
            panelWidth = 0, panelHeight = 0,
            decodeWidth = 3840, decodeHeight = 2160
        )
        assertEquals(MirrorResolution.DEFAULT, res)
    }

    @Test
    fun `unknown decoder capability falls back to 1080p`() {
        val res = MirrorResolution.pick(
            preferHighResolution = true,
            panelWidth = 3840, panelHeight = 2160,
            decodeWidth = 0, decodeHeight = 0
        )
        assertEquals(MirrorResolution.DEFAULT, res)
    }

    // ─── AppSettings integration ─────────────────────────────────────────────

    @Test
    fun `settings advertise 1080p by default on a 4K TV`() {
        val res = AppSettings.DEFAULT.advertisedMirrorResolution(
            panelWidth = 3840, panelHeight = 2160,
            decodeWidth = 3840, decodeHeight = 2160
        )
        assertEquals(MirrorResolution.FHD, res)
    }

    @Test
    fun `settings advertise 4K on a 4K TV when high resolution is on`() {
        val settings = AppSettings(forceHighResolution = true)
        val res = settings.advertisedMirrorResolution(
            panelWidth = 3840, panelHeight = 2160,
            decodeWidth = 3840, decodeHeight = 2160
        )
        assertEquals(MirrorResolution.UHD, res)
    }
}
