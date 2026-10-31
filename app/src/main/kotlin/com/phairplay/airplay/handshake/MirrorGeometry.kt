package com.phairplay.airplay.handshake

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Type-1 mirror header geometry, distinct from SPS/decoded pixels.
 * A reciprocal display aspect requests a quarter-turn without changing codec configuration.
 * Offsets follow the observed UxPlay lib/raop_rtp_mirror.c type-1 header layout.
 */
internal data class MirrorGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
) {
    val label: String get() = "${sourceWidth}x$sourceHeight display=${displayWidth}x$displayHeight"

    /** Only infer a quarter-turn when the display ratio closely matches the reciprocal raster.
     * Header rectangles cannot distinguish left from right; explicit SEI orientation wins.
     */
    fun quarterTurnFor(decodedWidth: Int, decodedHeight: Int): Boolean {
        if (decodedWidth <= 0 || decodedHeight <= 0 ||
            sourceWidth != decodedWidth || sourceHeight != decodedHeight) return false
        val raster = decodedWidth.toDouble() / decodedHeight
        val display = displayWidth.toDouble() / displayHeight
        return (raster < 1.0) != (display < 1.0) &&
            kotlin.math.abs(display * raster - 1.0) < 0.03
    }

    companion object {
        fun parse(header: ByteArray): MirrorGeometry? {
            if (header.size < 64) return null
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            fun sizeAt(offset: Int): Pair<Int, Int>? {
                val w = buffer.getFloat(offset)
                val h = buffer.getFloat(offset + 4)
                if (!w.isFinite() || !h.isFinite() || w !in 16f..8192f || h !in 16f..8192f) return null
                return w.toInt() to h.toInt()
            }
            val source = sizeAt(40) ?: sizeAt(16) ?: return null
            val display = sizeAt(56) ?: source
            return MirrorGeometry(source.first, source.second, display.first, display.second)
        }
    }
}
