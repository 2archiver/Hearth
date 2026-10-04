package com.phairplay.airplay.handshake

/**
 * Validates AirPlay timing replies before they can change the playback clock.
 * Delayed, truncated or unrelated UDP packets must not become synchronization samples.
 * Call matchesRequest with the received length and the last transmitted timing request.
 */
internal object NtpReplyValidator {
    /** AirPlay NTP replies echo our transmit timestamp at byte 8 (UxPlay raop_ntp.c). */
    fun matchesRequest(reply: ByteArray, length: Int, request: ByteArray): Boolean {
        if (length < 32 || length > reply.size || request.size < 32) return false
        if ((reply[0].toInt() and 0xC0) != 0x80 || (reply[1].toInt() and 0x7F) != 0x53) return false
        for (i in 0 until 8) if (reply[8 + i] != request[24 + i]) return false
        // Both sender timestamps must exist; an all-zero time produces a bogus clock jump.
        return (16 until 24).any { reply[it] != 0.toByte() } &&
            (24 until 32).any { reply[it] != 0.toByte() }
    }
}
