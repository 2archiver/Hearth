package com.phairplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream

/** Exercises the actual packet processor and TCP reader, including recovery after overflow. */
class RtpFuaBudgetTest {
    private fun packet(flags: Int, size: Int): ByteArray = ByteArray(14 + size).apply {
        this[0] = 0x80.toByte()
        this[12] = 0x7c
        this[13] = (flags or 5).toByte()
        for (i in 14 until this.size) this[i] = 0x42
    }

    @Test fun `unit exactly at budget is delivered intact`() {
        val received = mutableListOf<ByteArray>()
        val processor = VideoRtpProcessor { nal, _ -> received.add(nal) }
        processor.processRtpFrame(packet(0x80, 32767))
        repeat(255) { processor.processRtpFrame(packet(if (it == 254) 0x40 else 0, 32768)) }
        assertEquals(1, received.size)
        assertEquals(RtpInterleaved.MAX_FUA_BYTES, received.single().size)
        assertEquals(0x65.toByte(), received.single()[0])
        for (i in 1 until received.single().size) assertEquals(0x42.toByte(), received.single()[i])
    }

    @Test fun `end fragment crossing budget drops entire unit and recovers on next start`() {
        val received = mutableListOf<ByteArray>()
        val processor = VideoRtpProcessor { nal, _ -> received.add(nal) }
        processor.processRtpFrame(packet(0x80, 32768))
        repeat(255) { processor.processRtpFrame(packet(if (it == 254) 0x40 else 0, 32768)) }
        assertTrue(received.isEmpty())
        processor.processRtpFrame(packet(0x40, 3)) // orphan tail after overflow
        assertTrue(received.isEmpty())
        processor.processRtpFrame(packet(0x80, 2))
        processor.processRtpFrame(packet(0x40, 2))
        assertArrayEquals(byteArrayOf(0x65, 0x42, 0x42, 0x42, 0x42), received.single())
    }

    @Test fun `TCP stream without end bit stays bounded and resumes with a new unit`() {
        val stream = ByteArrayOutputStream()
        fun append(packet: ByteArray) {
            stream.write(byteArrayOf(0x24, 0, (packet.size shr 8).toByte(), packet.size.toByte()))
            stream.write(packet)
        }
        append(packet(0x80, 32768))
        repeat(300) { append(packet(0, 32768)) }
        append(packet(0x80, 1))
        append(packet(0x40, 1))
        val received = mutableListOf<ByteArray>()
        var ended = 0
        RtpInterleaved.readLoop(ByteArrayInputStream(stream.toByteArray()), { nal, _ -> received.add(nal) }, { ended++ })
        assertArrayEquals(byteArrayOf(0x65, 0x42, 0x42), received.single())
        assertEquals(1, ended)
    }
}
