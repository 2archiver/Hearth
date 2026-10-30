package com.phairplay.airplay

/** Convert raw or Annex-B SPS to RBSP before reading H.264 bit fields. */
internal fun spsRbsp(input: ByteArray): ByteArray? {
    val offset = when {
        input.size >= 4 && input[0] == 0.toByte() && input[1] == 0.toByte() &&
            input[2] == 0.toByte() && input[3] == 1.toByte() -> 4
        input.size >= 3 && input[0] == 0.toByte() && input[1] == 0.toByte() &&
            input[2] == 1.toByte() -> 3
        else -> 0
    }
    if (input.size - offset < 4 || (input[offset].toInt() and 0x1f) != 7) return null
    val output = ArrayList<Byte>(input.size - offset)
    var zeros = 0
    for (i in offset until input.size) {
        val value = input[i].toInt() and 0xff
        if (zeros >= 2 && value == 3 && i + 1 < input.size &&
            (input[i + 1].toInt() and 0xff) <= 3) {
            zeros = 0
            continue
        }
        output.add(input[i])
        zeros = if (value == 0) zeros + 1 else 0
    }
    return output.toByteArray()
}
