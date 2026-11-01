package com.phairplay.airplay.handshake

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class MirrorDisplayTransformTest {
    private fun map(matrix: FloatArray, x: Float, y: Float) = floatArrayOf(
        matrix[0] * x + matrix[1] * y + matrix[2],
        matrix[3] * x + matrix[4] * y + matrix[5],
    )

    @Test fun `portrait pixels rotate clockwise into upright landscape without stretching`() {
        val m = MirrorDisplayTransform.matrix(888, 1920, 1920, 1080, MirrorOrientation(90))
        assertArrayEquals(floatArrayOf(1920f, 96f), map(m, 0f, 0f), 0.01f)
        assertArrayEquals(floatArrayOf(1920f, 984f), map(m, 1920f, 0f), 0.01f)
        assertArrayEquals(floatArrayOf(0f, 96f), map(m, 0f, 1080f), 0.01f)
    }

    @Test fun `all quarter turns and flips preserve source pixel proportions and stay inside TV`() {
        for ((w, h) in listOf(888 to 1920, 1920 to 888, 1920 to 1080, 1080 to 1080)) {
            for ((cw, ch) in listOf(1920 to 1080, 3840 to 2160, 1280 to 720)) {
                for (rotation in listOf(0, 90, 180, 270)) for (flipH in listOf(false, true)) {
                    for (flipV in listOf(false, true)) {
                        val m = MirrorDisplayTransform.matrix(w, h, cw, ch, MirrorOrientation(rotation, flipH, flipV))
                        val origin = map(m, 0f, 0f)
                        val x = map(m, cw.toFloat(), 0f)
                        val y = map(m, 0f, ch.toFloat())
                        val xScale = hypot(x[0] - origin[0], x[1] - origin[1]) / w
                        val yScale = hypot(y[0] - origin[0], y[1] - origin[1]) / h
                        assertEquals(xScale, yScale, 0.001f)
                        for (px in listOf(0f, cw.toFloat())) for (py in listOf(0f, ch.toFloat())) {
                            val point = map(m, px, py)
                            assertTrue(point[0] in -0.01f..cw + 0.01f)
                            assertTrue(point[1] in -0.01f..ch + 0.01f)
                        }
                        assertArrayEquals(floatArrayOf(cw / 2f, ch / 2f), map(m, cw / 2f, ch / 2f), 0.01f)
                    }
                }
            }
        }
    }

    @Test fun `invalid dimensions leave an identity transform`() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            MirrorDisplayTransform.matrix(0, 1920, 1920, 1080, MirrorOrientation(90)), 0f)
    }
}
