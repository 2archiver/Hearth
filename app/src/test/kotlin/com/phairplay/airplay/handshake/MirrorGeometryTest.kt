package com.phairplay.airplay.handshake

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class MirrorGeometryTest {
    private fun header(w: Float, h: Float): ByteArray = ByteArray(128).also {
        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(40, w); putFloat(44, h)
            putFloat(56, w); putFloat(60, h)
        }
    }

    @Test fun `sender geometry follows portrait landscape and portrait again`() {
        for ((w, h) in listOf(888 to 1920, 1920 to 888, 888 to 1920)) {
            assertEquals(MirrorGeometry(w, h, w, h), MirrorGeometry.parse(header(w.toFloat(), h.toFloat())))
        }
    }

    @Test fun `legacy source fields work without duplicated source or display fields`() {
        val bytes = ByteArray(128)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(16, 888f); putFloat(20, 1920f)
        }
        assertEquals(MirrorGeometry(888, 1920, 888, 1920), MirrorGeometry.parse(bytes))
    }

    @Test fun `invalid geometry cannot become layout dimensions`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 100000f)) {
            assertNull(MirrorGeometry.parse(header(bad, 1920f)))
        }
        assertNull(MirrorGeometry.parse(ByteArray(63)))
        assertNull(MirrorGeometry.parse(ByteArray(128)))
    }
}
