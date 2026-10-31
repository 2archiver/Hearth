package com.phairplay.airplay.handshake

/** H.264 display_orientation SEI (payload type 47). Angles are counterclockwise in the bitstream,
 * clockwise in Android View.rotation. Preserve flips and cancellation as explicit metadata.
 */
internal data class MirrorOrientation(val clockwiseDegrees: Int, val horizontalFlip: Boolean = false,
    val verticalFlip: Boolean = false) {
    companion object {
        fun parse(annexB: ByteArray): MirrorOrientation? {
            var result: MirrorOrientation? = null
            var start = nalStart(annexB, 0)
            while (start >= 0) {
                val next = nalStart(annexB, start + 1)
                val end = if (next < 0) annexB.size else next - 3
                if ((annexB[start].toInt() and 31) == 6) {
                    // Unescape RBSP, excluding the NAL header. Never read across the NAL boundary.
                    val rbsp = ByteArray((end - start - 1).coerceAtLeast(0))
                    var size = 0
                    var zeros = 0
                    for (i in start + 1 until end) {
                        val value = annexB[i].toInt() and 255
                        if (zeros >= 2 && value == 3) { zeros = 0; continue }
                        rbsp[size++] = annexB[i]
                        zeros = if (value == 0) zeros + 1 else 0
                    }
                    var pos = 0
                    while (pos < size && (rbsp[pos].toInt() and 255) != 128) {
                        fun number(): Int? {
                            var value = 0
                            while (pos < size) {
                                val part = rbsp[pos++].toInt() and 255
                                if (value > size + 255) return null
                                value += part
                                if (part != 255) return value
                            }
                            return null
                        }
                        val type = number() ?: break
                        val length = number() ?: break
                        if (length > size - pos) break
                        if (type == 47 && length >= 1) {
                            val first = rbsp[pos].toInt() and 255
                            if (first and 128 != 0) {
                                result = MirrorOrientation(0)
                            } else if (length >= 3) {
                                val bits = (first shl 16) or ((rbsp[pos + 1].toInt() and 255) shl 8) or
                                    (rbsp[pos + 2].toInt() and 255)
                                val angle = (bits ushr 5) and 65535
                                if (angle % 16384 == 0) {
                                    result = MirrorOrientation((360 - angle / 16384 * 90) % 360,
                                        first and 64 != 0, first and 32 != 0)
                                }
                            }
                        }
                        pos += length
                    }
                }
                start = next
            }
            return result
        }

        /** Returns the byte after a three- or four-byte Annex-B start code. */
        private fun nalStart(data: ByteArray, from: Int): Int {
            for (i in from until data.size - 3) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) return i + 3
            }
            return -1
        }
    }
}
