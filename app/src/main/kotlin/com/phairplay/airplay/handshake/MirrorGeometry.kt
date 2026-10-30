package com.phairplay.airplay.handshake

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Type-1 mirror header geometry, distinct from SPS/decoded pixels. Diagnostic only:
 * source/display rectangles do not specify a rotation angle and must never rotate pixels by guess.
 * Offsets follow the observed UxPlay lib/raop_rtp_mirror.c type-1 header layout.
 */
internal data class MirrorGeometry(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
) {
    val label: String get() = "${sourceWidth}x$sourceHeight display=${displayWidth}x$displayHeight"

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
