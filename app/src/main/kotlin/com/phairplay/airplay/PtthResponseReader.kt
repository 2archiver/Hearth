package com.phairplay.airplay

import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Bounded frame reader for responses written by an AirPlay sender on an upgraded PTTH socket.
 *
 * After `POST /reverse` the sender can write `HTTP/1.1` (and, on some implementations,
 * `EVENT/1.0`) responses on the same connection that Hearth uses for its reverse `POST /event`
 * requests. They are not RTSP requests. This reader consumes their framing without retaining or
 * logging the response body, so the persistent PTTH connection stays available for later FCUP work.
 */
internal class PtthResponseReader(
    private val maxHeaderBytes: Int = DEFAULT_MAX_HEADER_BYTES,
    private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
) {
    sealed interface ReadOutcome {
        data class Response(
            val protocol: String,
            val statusCode: Int?,
            val bodyBytes: Int,
            val closeRequested: Boolean,
        ) : ReadOutcome

        data class End(val reason: String, val cleanEof: Boolean = false) : ReadOutcome
    }

    fun read(input: InputStream): ReadOutcome {
        val first = readLine(input, MAX_STATUS_LINE_BYTES)
        val statusLine = first.line ?: return ReadOutcome.End(
            reason = first.failure ?: "unexpected end of PTTH response",
            cleanEof = first.cleanEof,
        )
        if (statusLine.isEmpty()) return ReadOutcome.End("blank PTTH response line")

        val parts = statusLine.trim().split(WHITESPACE, limit = 3)
        val protocol = parts.firstOrNull()?.uppercase() ?: return ReadOutcome.End("missing PTTH response protocol")
        if (protocol != HTTP_11 && protocol != EVENT_10) {
            return ReadOutcome.End("unexpected PTTH response protocol")
        }
        val statusCode = parts.getOrNull(1)?.toIntOrNull()
        if ((protocol == HTTP_11 && statusCode == null) || (statusCode != null && statusCode !in 100..599)) {
            return ReadOutcome.End("malformed PTTH response status")
        }

        val headers = LinkedHashMap<String, String>()
        var totalHeaderBytes = statusLine.toByteArray(StandardCharsets.ISO_8859_1).size + 2
        while (true) {
            val result = readLine(input, maxHeaderBytes)
            val line = result.line ?: return ReadOutcome.End(result.failure ?: "truncated PTTH headers")
            if (line.isEmpty()) break
            totalHeaderBytes += line.toByteArray(StandardCharsets.ISO_8859_1).size + 2
            if (totalHeaderBytes > maxHeaderBytes) return ReadOutcome.End("PTTH headers exceed limit")

            val colon = line.indexOf(':')
            if (colon <= 0) return ReadOutcome.End("malformed PTTH header")
            val name = line.substring(0, colon).trim()
            if (name.isEmpty() || name.any { it <= ' ' || it == ':' }) {
                return ReadOutcome.End("malformed PTTH header name")
            }
            val key = name.lowercase()
            val value = line.substring(colon + 1).trim()
            val previous = headers[key]
            if (previous != null && !previous.equals(value, ignoreCase = true)) {
                return ReadOutcome.End("conflicting PTTH headers")
            }
            headers[key] = value
        }

        val contentLength = headers["content-length"]?.let { raw ->
            val parsed = raw.toLongOrNull() ?: return ReadOutcome.End("invalid PTTH Content-Length")
            if (parsed < 0L || parsed > maxBodyBytes.toLong()) {
                return ReadOutcome.End("PTTH body exceeds limit")
            }
            parsed.toInt()
        }
        val transferEncoding = headers["transfer-encoding"]
        if (contentLength != null && transferEncoding != null) {
            return ReadOutcome.End("ambiguous PTTH body framing")
        }

        val bodyBytes = when {
            transferEncoding == null && contentLength != null -> {
                if (!discardExactly(input, contentLength)) return ReadOutcome.End("truncated PTTH body")
                contentLength
            }
            transferEncoding != null -> {
                if (!transferEncoding.equals("chunked", ignoreCase = true)) {
                    return ReadOutcome.End("unsupported PTTH transfer encoding")
                }
                when (val result = discardChunked(input)) {
                    is ChunkResult.Bytes -> result.value
                    is ChunkResult.Error -> return ReadOutcome.End(result.reason)
                }
            }
            // HTTP no-body status codes are self-delimiting. For other replies an absent length is
            // treated as an empty body, which is the common PTTH acknowledgement; any unexpected
            // bytes will fail the next frame's protocol check instead of being parsed as RTSP.
            statusCode in NO_BODY_STATUS_CODES -> 0
            else -> 0
        }

        return ReadOutcome.Response(
            protocol = protocol,
            statusCode = statusCode,
            bodyBytes = bodyBytes,
            closeRequested = headers["connection"]
                ?.split(',')
                ?.any { it.trim().equals("close", ignoreCase = true) } == true,
        )
    }

    private fun discardChunked(input: InputStream): ChunkResult {
        var total = 0
        var trailerBytes = 0
        while (true) {
            val result = readLine(input, MAX_CHUNK_LINE_BYTES)
            val line = result.line ?: return ChunkResult.Error(result.failure ?: "truncated PTTH chunk size")
            val sizeText = line.substringBefore(';').trim()
            val size = sizeText.toLongOrNull(16)
                ?: return ChunkResult.Error("invalid PTTH chunk size")
            if (size < 0L || size > (maxBodyBytes - total).toLong()) {
                return ChunkResult.Error("PTTH body exceeds limit")
            }
            if (size == 0L) {
                while (true) {
                    val trailer = readLine(input, MAX_CHUNK_LINE_BYTES)
                    val trailerLine = trailer.line
                        ?: return ChunkResult.Error(trailer.failure ?: "truncated PTTH trailers")
                    trailerBytes += trailerLine.toByteArray(StandardCharsets.ISO_8859_1).size + 2
                    if (trailerBytes > maxHeaderBytes) return ChunkResult.Error("PTTH trailers exceed limit")
                    if (trailerLine.isEmpty()) return ChunkResult.Bytes(total)
                    val colon = trailerLine.indexOf(':')
                    if (colon <= 0) return ChunkResult.Error("malformed PTTH trailer")
                }
            }
            val chunkBytes = size.toInt()
            if (!discardExactly(input, chunkBytes)) return ChunkResult.Error("truncated PTTH chunk")
            if (!readCrlf(input)) return ChunkResult.Error("malformed PTTH chunk terminator")
            total += chunkBytes
        }
    }

    private fun discardExactly(input: InputStream, byteCount: Int): Boolean {
        var remaining = byteCount
        val scratch = ByteArray(minOf(DISCARD_BUFFER_BYTES, byteCount.coerceAtLeast(1)))
        while (remaining > 0) {
            val count = try {
                input.read(scratch, 0, minOf(scratch.size, remaining))
            } catch (timeout: java.net.SocketTimeoutException) {
                throw timeout
            } catch (_: Exception) {
                return false
            }
            if (count < 0) return false
            if (count == 0) continue
            remaining -= count
        }
        return true
    }

    private fun readCrlf(input: InputStream): Boolean {
        val first = readOne(input)
        if (first == '\r'.code) return readOne(input) == '\n'.code
        // A few older HTTP stacks emit LF-only chunk boundaries; accept that without weakening
        // response-line framing or allowing the following response bytes to be swallowed.
        return first == '\n'.code
    }

    private fun readLine(input: InputStream, limit: Int): LineResult {
        val bytes = ByteArray(limit.coerceAtLeast(1))
        var length = 0
        while (true) {
            val value = readOne(input)
            if (value == -1) {
                return if (length == 0) LineResult(null, "peer EOF", cleanEof = true)
                else LineResult(null, "truncated PTTH line")
            }
            if (value == '\n'.code) {
                if (length > 0 && bytes[length - 1] == '\r'.code.toByte()) length--
                return LineResult(String(bytes, 0, length, StandardCharsets.ISO_8859_1))
            }
            if (length >= limit) return LineResult(null, "PTTH line exceeds limit")
            bytes[length++] = value.toByte()
        }
    }

    private fun readOne(input: InputStream): Int = try {
        input.read()
    } catch (timeout: java.net.SocketTimeoutException) {
        throw timeout
    } catch (_: Exception) {
        -1
    }

    private sealed interface ChunkResult {
        data class Bytes(val value: Int) : ChunkResult
        data class Error(val reason: String) : ChunkResult
    }

    private data class LineResult(
        val line: String?,
        val failure: String? = null,
        val cleanEof: Boolean = false,
    )

    private companion object {
        const val HTTP_11 = "HTTP/1.1"
        const val EVENT_10 = "EVENT/1.0"
        const val DEFAULT_MAX_HEADER_BYTES = 64 * 1024
        const val DEFAULT_MAX_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_STATUS_LINE_BYTES = 8 * 1024
        const val MAX_CHUNK_LINE_BYTES = 1024
        const val DISCARD_BUFFER_BYTES = 4096
        val NO_BODY_STATUS_CODES = setOf(204, 304)
        val WHITESPACE = Regex("\\s+")
    }
}
