package com.phairplay.cast.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CastProtocolTest — the wire format PhairPlay's built-in Cast receiver speaks.
 *
 * If these encode/decode routines drift from `cast_channel.proto`, senders and PhairPlay
 * simply stop understanding each other with no error anywhere, so the round-trip is pinned
 * here rather than being discovered by an iPhone that "cannot connect".
 */
class CastProtocolTest {

    @Test
    fun `round-trips a string payload`() {
        val message = CastMessage(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = CastReceiverApp.NS_RECEIVER,
            payloadUtf8 = """{"type":"GET_STATUS","requestId":1}"""
        )
        val decoded = requireNotNull(CastProtocol.decode(CastProtocol.encode(message)))
        assertEquals(message.sourceId, decoded.sourceId)
        assertEquals(message.destinationId, decoded.destinationId)
        assertEquals(message.namespace, decoded.namespace)
        assertEquals(message.payloadUtf8, decoded.payloadUtf8)
        assertNull(decoded.payloadBinary)
    }

    @Test
    fun `round-trips a binary payload and marks it BINARY`() {
        val message = CastMessage(
            sourceId = "sender-1",
            destinationId = "web-1",
            namespace = "urn:x-cast:com.google.cast.test",
            payloadBinary = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte())
        )
        val decoded = requireNotNull(CastProtocol.decode(CastProtocol.encode(message)))
        assertEquals(CastProtocol.PAYLOAD_BINARY, decoded.payloadType)
        assertArrayEquals(message.payloadBinary, decoded.payloadBinary)
        assertNull(decoded.payloadUtf8)
    }

    @Test
    fun `frames are length-prefixed with a 4-byte big-endian length`() {
        val message = CastMessage("a", "b", CastReceiverApp.NS_HEARTBEAT, payloadUtf8 = "{}")
        val frame = CastProtocol.encodeFrame(message)
        val body = CastProtocol.encode(message)
        assertEquals(body.size + 4, frame.size)
        val declared = ((frame[0].toInt() and 0xFF) shl 24) or
            ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or
            (frame[3].toInt() and 0xFF)
        assertEquals(body.size, declared)
        assertArrayEquals(body, frame.copyOfRange(4, frame.size))
    }

    @Test
    fun `a length-prefixed frame bigger than 127 bytes still reports its size`() {
        // varint length encoding is the classic place to get this wrong.
        val longPayload = "x".repeat(500)
        val frame = CastProtocol.encodeFrame(
            CastMessage("sender-0", "receiver-0", CastReceiverApp.NS_MEDIA, payloadUtf8 = """{"p":"$longPayload"}""")
        )
        val declared = ((frame[0].toInt() and 0xFF) shl 24) or
            ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or
            (frame[3].toInt() and 0xFF)
        assertEquals(frame.size - 4, declared)
        assertTrue(declared > 500)
    }

    @Test
    fun `a truncated or malformed frame decodes to null rather than throwing`() {
        assertNull(CastProtocol.decode(byteArrayOf()))
        assertNull(CastProtocol.decode(byteArrayOf(0x12)))                 // key with no length
        assertNull(CastProtocol.decode(byteArrayOf(0x12, 0x05, 0x41)))     // length past the end
    }

    @Test
    fun `unknown protobuf fields are skipped instead of failing`() {
        // Forward compatibility: a newer Cast SDK may add fields we do not know.
        val withExtra = CastProtocol.encode(
            CastMessage("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER, payloadUtf8 = "{}")
        )
        // field 9, length-delimited: 0x4A 0x02 'h' 'i'
        val padded = withExtra + byteArrayOf(0x4A, 0x02, 0x68, 0x69)
        val decoded = requireNotNull(CastProtocol.decode(padded))
        assertEquals("{}", decoded.payloadUtf8)
    }
}
