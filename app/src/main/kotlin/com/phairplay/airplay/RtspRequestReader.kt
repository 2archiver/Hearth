package com.phairplay.airplay

import com.phairplay.util.Logger
import java.io.InputStream

/**
 * Binary-safe bounded reader for the persistent RTSP/HTTP connection. [readDetailed] distinguishes
 * peer EOF from malformed/truncated/oversized input so diagnostics do not report every close as
 * the same unexplained EOF.
 */
internal class RtspRequestReader(
    private val maxMessageBytes: Int,
    private val maxPhotoBytes: Int,
) {
    sealed interface ReadOutcome {
        data class Request(val value: RtspRequest) : ReadOutcome
        data class End(val reason: String, val cleanEof: Boolean = false) : ReadOutcome
    }

    /** Compatibility helper for tests/callers that only need a request or null. */
    fun read(inputStream: InputStream): RtspRequest? =
        (readDetailed(inputStream) as? ReadOutcome.Request)?.value

    fun readDetailed(inputStream: InputStream): ReadOutcome {
        while (true) {
            val requestLineResult = readLine(inputStream, maxMessageBytes)
            val requestLine = requestLineResult.line ?: return ReadOutcome.End(
                reason = requestLineResult.failure ?: "unexpected end of request line",
                cleanEof = requestLineResult.cleanEof,
            )
            if (requestLine.isBlank()) continue // Iterative: hostile blank lines cannot recurse.

            val parts = requestLine.trim().split(WHITESPACE, limit = 3)
            if (parts.size != 3 || parts.any { it.isBlank() }) {
                Logger.w("Malformed RTSP request line — rejecting")
                return ReadOutcome.End("malformed request line")
            }
            val headersResult = readHeaders(inputStream, requestLine.length)
            val headers = headersResult.value
                ?: return ReadOutcome.End(headersResult.failure ?: "malformed headers")

            val method = parts[0]
            val uri = parts[1]
            val protocol = parts[2]
            val bodyResult = readBody(inputStream, method, uri, headers)
            val bodyBytes = bodyResult.value
                ?: return ReadOutcome.End(bodyResult.failure ?: "malformed request body")

            return ReadOutcome.Request(
                RtspRequest(
                    method = method,
                    uri = uri,
                    headers = headers,
                    body = String(bodyBytes, Charsets.UTF_8),
                    bodyBytes = bodyBytes,
                    protocol = protocol,
                )
            )
        }
    }

    private fun readHeaders(inputStream: InputStream, requestLineBytes: Int): ValueResult<Map<String, String>> {
        val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        var totalBytes = requestLineBytes
        while (true) {
            val result = readLine(inputStream, maxMessageBytes)
            val line = result.line
                ?: return ValueResult.failure(result.failure ?: "truncated headers")
            if (line.isEmpty()) return ValueResult.success(headers)
            totalBytes += line.toByteArray(Charsets.UTF_8).size + 2
            if (totalBytes > maxMessageBytes) {
                Logger.w("RTSP headers too large — rejecting")
                return ValueResult.failure("headers exceed message limit")
            }
            val colon = line.indexOf(':')
            if (colon <= 0) {
                return ValueResult.failure("malformed header line")
            }
            val name = line.substring(0, colon).trim()
            if (name.isEmpty() || name.any { it <= ' ' || it == ':' }) {
                return ValueResult.failure("malformed header name")
            }
            headers[name] = line.substring(colon + 1).trim()
        }
    }

    private fun readBody(
        inputStream: InputStream,
        method: String,
        uri: String,
        headers: Map<String, String>,
    ): ValueResult<ByteArray> {
        val rawLength = headers["Content-Length"]
        val contentLength = when {
            rawLength == null -> 0
            rawLength.toIntOrNull() == null -> return ValueResult.failure("invalid Content-Length")
            rawLength.toInt() < 0 -> return ValueResult.failure("negative Content-Length")
            else -> rawLength.toInt()
        }
        val isPhoto = method.equals("PUT", ignoreCase = true) &&
            uri.substringBefore('?') == PhotoHandler.PHOTO_PATH
        val bodyLimit = if (isPhoto) maxPhotoBytes else maxMessageBytes
        if (contentLength > bodyLimit) {
            Logger.w("Request body exceeds the configured limit ($contentLength bytes) — rejecting")
            return ValueResult.failure("request body exceeds limit")
        }
        if (contentLength == 0) return ValueResult.success(ByteArray(0))

        val buffer = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val count = try {
                inputStream.read(buffer, offset, contentLength - offset)
            } catch (e: Exception) {
                return ValueResult.failure("body read failed (${e.javaClass.simpleName})")
            }
            if (count < 0) return ValueResult.failure("truncated request body")
            if (count == 0) continue
            offset += count
        }
        return ValueResult.success(buffer)
    }

    private fun readLine(inputStream: InputStream, lineLimit: Int): LineResult {
        val sb = StringBuilder()
        while (true) {
            val byte = try {
                inputStream.read()
            } catch (e: Exception) {
                return LineResult(null, "line read failed (${e.javaClass.simpleName})")
            }
            if (byte == -1) {
                return if (sb.isEmpty()) LineResult(null, "peer EOF", cleanEof = true)
                else LineResult(null, "truncated line")
            }
            if (byte == '\r'.code) continue
            if (byte == '\n'.code) return LineResult(sb.toString())
            if (sb.length >= lineLimit) return LineResult(null, "line exceeds message limit")
            sb.append(byte.toChar())
        }
    }

    private data class LineResult(val line: String?, val failure: String? = null, val cleanEof: Boolean = false)
    private data class ValueResult<T>(val value: T?, val failure: String? = null) {
        companion object {
            fun <T> success(value: T) = ValueResult(value)
            fun <T> failure(reason: String) = ValueResult<T>(null, reason)
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
