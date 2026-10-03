package com.phairplay.cast.bridge

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * CastMessage — the one protobuf message type of Google's Cast V2 ("castv2") protocol.
 *
 * WHY hand-rolled protobuf: the Cast V2 channel (TLS on port 8009, 4-byte length-prefixed
 * frames) only ever carries this single, tiny message, and pulling in a protobuf runtime
 * plus a generated schema for it would add more weight than the whole rest of the bridge.
 * The wire format is fixed by `cast_channel.proto` and has not changed since 2013.
 *
 * Wire layout:
 * ```
 * field 1  varint   protocol_version   (always 0 = CASTV2_1_0)
 * field 2  string   source_id
 * field 3  string   destination_id
 * field 4  string   namespace_
 * field 5  varint   payload_type       (0 = STRING, 1 = BINARY)
 * field 6  string   payload_utf8       (when payload_type == STRING)
 * field 7  bytes    payload_binary     (when payload_type == BINARY)
 * ```
 */
internal data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String? = null,
    val payloadBinary: ByteArray? = null
) {
    val payloadType: Int
        get() = if (payloadBinary != null) CastProtocol.PAYLOAD_BINARY else CastProtocol.PAYLOAD_STRING
}

/**
 * CastProtocol — encodes and decodes [CastMessage] frames.
 *
 * Tolerant on purpose: a peer we do not fully understand (a newer Cast SDK, a Google TV
 * remote app) must not take the bridge down, so any malformed field ends decoding with
 * null instead of throwing, and the connection layer simply drops that frame.
 */
internal object CastProtocol {

    const val PROTOCOL_VERSION = 0
    const val PAYLOAD_STRING = 0
    const val PAYLOAD_BINARY = 1

    /** Encodes a CastMessage as a bare protobuf (no length prefix). */
    fun encode(message: CastMessage): ByteArray {
        val out = ByteArrayOutputStream(128)
        out.writeVarint(1, PROTOCOL_VERSION)
        out.writeString(2, message.sourceId)
        out.writeString(3, message.destinationId)
        out.writeString(4, message.namespace)
        out.writeVarint(5, message.payloadType)
        if (message.payloadBinary != null) {
            out.writeBytes(7, message.payloadBinary)
        } else {
            out.writeString(6, message.payloadUtf8 ?: "")
        }
        return out.toByteArray()
    }

    /** Encodes a CastMessage as a length-prefixed frame ready to write to the TLS socket. */
    fun encodeFrame(message: CastMessage): ByteArray {
        val body = encode(message)
        val frame = ByteArray(4 + body.size)
        frame[0] = (body.size ushr 24).toByte()
        frame[1] = (body.size ushr 16).toByte()
        frame[2] = (body.size ushr 8).toByte()
        frame[3] = body.size.toByte()
        System.arraycopy(body, 0, frame, 4, body.size)
        return frame
    }

    /** Decodes a bare protobuf body. Returns null when the payload is not usable. */
    fun decode(bytes: ByteArray): CastMessage? = try {
        var sourceId: String? = null
        var destinationId: String? = null
        var namespace: String? = null
        var payloadType = PAYLOAD_STRING
        var payloadUtf8: String? = null
        var payloadBinary: ByteArray? = null

        var position = 0
        while (position < bytes.size) {
            val (tag, consumed) = bytes.readVarint(position) ?: break
            position = consumed
            val field = tag ushr 3
            val wireType = tag and 0x07

            when (wireType) {
                WIRE_VARINT -> {
                    val (value, next) = bytes.readVarint(position) ?: break
                    position = next
                    if (field == 5) payloadType = value
                    // field 1 (protocol_version) is always 0; ignore it.
                }

                WIRE_LENGTH_DELIMITED -> {
                    val (length, afterLength) = bytes.readVarint(position) ?: break
                    position = afterLength
                    if (length < 0 || length > bytes.size - position) return null
                    val slice = bytes.copyOfRange(position, position + length)
                    position += length
                    when (field) {
                        2 -> sourceId = String(slice, StandardCharsets.UTF_8)
                        3 -> destinationId = String(slice, StandardCharsets.UTF_8)
                        4 -> namespace = String(slice, StandardCharsets.UTF_8)
                        6 -> payloadUtf8 = String(slice, StandardCharsets.UTF_8)
                        7 -> payloadBinary = slice
                        else -> Unit          // unknown field — skip, stay compatible
                    }
                }

                else -> return null           // groups/fixed types are not used by castv2
            }
        }

        if (sourceId == null || destinationId == null || namespace == null) return null
        CastMessage(
            sourceId = sourceId,
            destinationId = destinationId,
            namespace = namespace,
            payloadUtf8 = if (payloadType == PAYLOAD_BINARY) null else payloadUtf8,
            payloadBinary = payloadBinary
        )
    } catch (e: Exception) {
        null
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2

    private fun ByteArrayOutputStream.writeVarint(field: Int, value: Int) {
        write((field shl 3) or WIRE_VARINT)
        var remaining = value
        while (true) {
            val byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) write(byte or 0x80) else { write(byte); break }
        }
    }

    private fun ByteArrayOutputStream.writeBytes(field: Int, value: ByteArray) {
        write((field shl 3) or WIRE_LENGTH_DELIMITED)
        writeLength(value.size)
        write(value)
    }

    private fun ByteArrayOutputStream.writeString(field: Int, value: String) =
        writeBytes(field, value.toByteArray(StandardCharsets.UTF_8))

    private fun ByteArrayOutputStream.writeLength(value: Int) {
        var remaining = value
        while (true) {
            val byte = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) write(byte or 0x80) else { write(byte); break }
        }
    }

    /** Reads a base-128 varint at [offset]; returns (value, nextOffset) or null on overflow. */
    private fun ByteArray.readVarint(offset: Int): Pair<Int, Int>? {
        var result = 0
        var shift = 0
        var index = offset
        while (index < size) {
            val byte = this[index].toInt() and 0xFF
            result = result or ((byte and 0x7F) shl shift)
            index++
            if (byte and 0x80 == 0) return result to index
            shift += 7
            if (shift > 28) return null
        }
        return null
    }
}
