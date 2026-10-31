package com.phairplay.airplay.handshake

import org.junit.Assert.*
import org.junit.Test

class MirrorOrientationTest {
    private fun sei(angle: Int, flips: Int = 0): ByteArray {
        val bits = (angle shl 5) or (flips shl 16)
        return byteArrayOf(0, 0, 0, 1, 6, 47, 3, (bits ushr 16).toByte(),
            (bits ushr 8).toByte(), bits.toByte(), 0x80.toByte())
    }

    @Test fun `explicit orientation distinguishes both landscape directions and flips`() {
        assertEquals(MirrorOrientation(90), MirrorOrientation.parse(sei(49152)))
        assertEquals(MirrorOrientation(270), MirrorOrientation.parse(sei(16384)))
        assertEquals(MirrorOrientation(180, true, true), MirrorOrientation.parse(sei(32768, 96)))
        assertEquals(MirrorOrientation(0), MirrorOrientation.parse(sei(0)))
    }

    @Test fun `cancellation clears earlier orientation`() {
        val cancel = byteArrayOf(0, 0, 1, 6, 47, 1, 0x80.toByte(), 0x80.toByte())
        assertEquals(MirrorOrientation(0), MirrorOrientation.parse(sei(16384) + cancel))
    }

    @Test fun `truncated and unrelated SEI never rotate the view`() {
        assertNull(MirrorOrientation.parse(byteArrayOf(0, 0, 1, 6, 47, 3, 0)))
        assertNull(MirrorOrientation.parse(byteArrayOf(0, 0, 1, 6, 5, 1, 64, 0x80.toByte())))
        assertNull(MirrorOrientation.parse(byteArrayOf(0, 0, 1, 0x65, 47, 3, 64, 0, 0)))
        assertNull(MirrorOrientation.parse(ByteArray(0)))
    }

    @Test fun `Telegram display-only geometry requests quarter turn without changing decoder size`() {
        assertTrue(MirrorGeometry(888, 1920, 1920, 886).quarterTurnFor(888, 1920))
        assertFalse(MirrorGeometry(888, 1920, 500, 1080).quarterTurnFor(888, 1920))
        assertFalse(MirrorGeometry(1920, 1080, 1920, 1080).quarterTurnFor(1920, 1080))
        assertFalse(MirrorGeometry(888, 1920, 1920, 886).quarterTurnFor(1920, 1080))
        assertFalse(MirrorGeometry(888, 1920, 1920, 1080).quarterTurnFor(888, 1920))
    }
}
