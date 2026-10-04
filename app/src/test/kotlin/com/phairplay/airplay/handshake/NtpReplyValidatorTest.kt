package com.phairplay.airplay.handshake

import org.junit.Assert.*
import org.junit.Test

/** Tests malformed/stale timing packets so they cannot disturb a current AirPlay session. */
class NtpReplyValidatorTest {
    private val request = ByteArray(32).apply { for (i in 24..31) this[i] = i.toByte() }
    private fun reply() = ByteArray(32).apply {
        this[0] = 0x80.toByte()
        this[1] = 0xd3.toByte()
        request.copyInto(this, 8, 24, 32)
        this[16] = 1
        this[24] = 2
    }

    @Test fun acceptsMatchingTimingReply() = assertTrue(NtpReplyValidator.matchesRequest(reply(), 32, request))
    @Test fun rejectsTruncatedDatagramEvenWithStaleBytesInReceiveBuffer() {
        assertFalse(NtpReplyValidator.matchesRequest(reply(), 8, request))
        assertFalse(NtpReplyValidator.matchesRequest(reply(), 33, request))
    }
    @Test fun rejectsReplyFromPreviousRequest() {
        val bytes = reply().apply { this[8] = 0 }
        assertFalse(NtpReplyValidator.matchesRequest(bytes, 32, request))
    }
    @Test fun rejectsAudioPacketAndInvalidRtpVersion() {
        assertFalse(NtpReplyValidator.matchesRequest(reply().apply { this[1] = 0x60 }, 32, request))
        assertFalse(NtpReplyValidator.matchesRequest(reply().apply { this[0] = 0 }, 32, request))
    }
    @Test fun rejectsMissingSenderClock() {
        assertFalse(NtpReplyValidator.matchesRequest(reply().apply { this[24] = 0 }, 32, request))
        assertFalse(NtpReplyValidator.matchesRequest(reply().apply { this[16] = 0 }, 32, request))
    }
}
