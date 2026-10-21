package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import java.net.URI
import java.util.Locale
import java.util.TreeMap

/** Schemes that mean "the sender must fetch this; the receiver cannot". */
internal val SENDER_MEDIATED_SCHEMES = setOf("mlhls")

/**
 * One `POST /play` location and offset.
 *
 * Two families arrive here, and they are not the same request:
 *  - **direct** (`http`/`https`) — a URL this receiver can fetch itself. Played as-is.
 *  - **sender-mediated** (`mlhls`, the YouTube app's AirPlay video transport) — a location only the
 *    *sender* can resolve, which needs the FCUP reverse-channel bridge. It is parsed here and
 *    classified separately so the handler can decide whether a bridge is available; a sender-
 *    mediated location is never handed to the player as if it were a URL.
 */
internal data class VideoPlayRequest(
    val url: String,
    val start: Double,
    val seconds: Boolean,
    /** Validated HTTP request properties; never included in trace/log output. */
    val requestHeaders: Map<String, String> = emptyMap(),
) {
    val scheme: String get() = runCatching { URI(url).scheme?.lowercase().orEmpty() }.getOrDefault("")

    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_FIELD_COUNT = 128
        private const val MAX_LOCATION_CHARS = 16 * 1024
        private const val MAX_REQUEST_HEADERS = 32
        private const val MAX_HEADER_NAME_CHARS = 128
        private const val MAX_HEADER_VALUE_CHARS = 4096
        private const val MAX_REQUEST_HEADER_CHARS = 16 * 1024
        private val HEADER_NAME_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val FORBIDDEN_REQUEST_HEADERS = setOf(
            "accept-encoding", "connection", "content-length", "host", "keep-alive", "range",
            "te", "trailer", "transfer-encoding", "upgrade", "proxy-authenticate", "proxy-authorization",
        )

        fun parse(fields: Map<String, Any?>): VideoPlayRequest? = when (val result = parseDetailed(fields)) {
            is FieldParse.Success -> result.request
            else -> null
        }

        fun parseDetailed(fields: Map<String, Any?>): FieldParse {
            if (fields.size > MAX_FIELD_COUNT) return FieldParse.Invalid("too many plist fields")
            fun field(name: String) = fields.entries.firstOrNull { it.key.equals(name, true) }?.value

            val rawUrl = field("Content-Location") as? String
                ?: return FieldParse.Invalid("Content-Location is missing or is not a string")
            if (rawUrl.length > MAX_LOCATION_CHARS) return FieldParse.Invalid("Content-Location is too long")
            if (rawUrl.isBlank() || rawUrl != rawUrl.trim()) {
                return FieldParse.Invalid("Content-Location is empty or contains outer whitespace")
            }
            val uri = runCatching { URI(rawUrl) }.getOrNull()
                ?: return FieldParse.Invalid("Content-Location is not a valid URI")
            val scheme = uri.scheme?.lowercase()
                ?: return FieldParse.Invalid("Content-Location has no scheme")
            val senderMediated = scheme in SENDER_MEDIATED_SCHEMES
            if (scheme !in DIRECT_SCHEMES && !senderMediated) {
                return FieldParse.UnsupportedScheme(scheme)
            }
            val host = uri.host
            if (host.isNullOrBlank() || uri.rawUserInfo != null) {
                return FieldParse.Invalid(
                    if (senderMediated) "sender-mediated location requires a host and may not embed credentials"
                    else "HTTP(S) media URL requires a host and may not embed credentials"
                )
            }
            if (!senderMediated && LocalMediaAddressPolicy.isLoopbackHost(host)) {
                return FieldParse.Invalid("direct media host is loopback and may belong to the sender, not this receiver")
            }

            val rawHeaders = field("Headers") ?: field("Request-Headers") ?: field("httpHeaders")
            val requestHeaders = if (rawHeaders == null) {
                emptyMap()
            } else {
                parseRequestHeaders(rawHeaders)
                    ?: return FieldParse.Invalid("request headers are invalid or exceed the limit")
            }
            if (senderMediated && requestHeaders.isNotEmpty()) {
                return FieldParse.Invalid("sender-mediated requests do not accept receiver HTTP headers")
            }

            // Explicit seconds always take precedence. The older Start-Position field is fractional.
            val secondsValue = field("Start-Position-Seconds")
            val seconds = secondsValue != null
            val value = secondsValue ?: field("Start-Position")
            val start = when (value) {
                null -> 0.0
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: return FieldParse.Invalid("offset is not numeric")
                else -> return FieldParse.Invalid("offset has an unsupported plist type")
            }
            if (!start.isFinite() || start < 0.0 || (!seconds && start > 1.0)) {
                return FieldParse.Invalid("offset is outside the supported range")
            }
            val request = VideoPlayRequest(rawUrl, start, seconds, requestHeaders)
            return if (senderMediated) FieldParse.SenderMediated(request, scheme) else FieldParse.Success(request)
        }

        /** Decodes only known AirPlay /play body encodings; never logs plist values. */
        fun decodeBody(body: ByteArray, contentType: String?): BodyParse {
            if (body.size > MAX_BODY_BYTES) return BodyParse.Invalid("/play body exceeds ${MAX_BODY_BYTES} bytes")
            val trimmed = body.toString(Charsets.UTF_8).trimStart()
            val binaryPlist = body.size >= BINARY_PLIST_MAGIC.size &&
                body.copyOfRange(0, BINARY_PLIST_MAGIC.size).contentEquals(BINARY_PLIST_MAGIC)
            val xmlPlist = trimmed.startsWith("<?xml", ignoreCase = true) ||
                trimmed.startsWith("<plist", ignoreCase = true) ||
                contentType.orEmpty().contains("plist", ignoreCase = true)
            val encoding = when {
                binaryPlist -> BodyEncoding.BINARY_PLIST
                xmlPlist -> BodyEncoding.XML_PLIST
                body.isEmpty() -> BodyEncoding.EMPTY
                else -> BodyEncoding.TEXT
            }
            val fields = when (encoding) {
                BodyEncoding.BINARY_PLIST, BodyEncoding.XML_PLIST -> runCatching { PlistCodec.decode(body) }
                    .getOrElse { return BodyParse.Invalid("plist parse failed (${it.javaClass.simpleName})", encoding) }
                BodyEncoding.TEXT -> parseText(body.toString(Charsets.UTF_8))
                BodyEncoding.EMPTY -> emptyMap()
            }
            if (fields.size > MAX_FIELD_COUNT) return BodyParse.Invalid("too many /play fields", encoding)
            val parsed = parseDetailed(fields)
            return when (parsed) {
                is FieldParse.Success -> BodyParse.Success(parsed.request, encoding, fields.keys.map { it.take(80) })
                is FieldParse.SenderMediated -> BodyParse.SenderMediated(
                    parsed.request,
                    parsed.scheme,
                    encoding,
                    fields.keys.map { it.take(80) },
                )
                is FieldParse.UnsupportedScheme -> BodyParse.UnsupportedScheme(
                    parsed.scheme,
                    encoding,
                    fields.keys.map { it.take(80) },
                )
                is FieldParse.Invalid -> BodyParse.Invalid(
                    parsed.reason,
                    encoding,
                    fields.keys.map { it.take(80) },
                )
            }
        }

        private fun parseRequestHeaders(raw: Any?): Map<String, String>? {
            val entries = raw as? Map<*, *> ?: return null
            if (entries.size > MAX_REQUEST_HEADERS) return null
            val accepted = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
            var totalChars = 0
            for ((rawName, rawValue) in entries) {
                val name = rawName as? String ?: return null
                val value = rawValue as? String ?: return null
                val lowerName = name.lowercase(Locale.ROOT)
                if (name.length !in 1..MAX_HEADER_NAME_CHARS || !HEADER_NAME_PATTERN.matches(name) ||
                    value.length > MAX_HEADER_VALUE_CHARS ||
                    !value.all { it.code in 0x20..0x7E || it.code in 0x80..0xFF } ||
                    lowerName in FORBIDDEN_REQUEST_HEADERS || lowerName.startsWith("proxy-") ||
                    accepted.containsKey(name)
                ) return null
                totalChars += name.length + value.length
                if (totalChars > MAX_REQUEST_HEADER_CHARS) return null
                accepted[name] = value
            }
            return accepted.toMap()
        }

        private fun parseText(text: String): Map<String, Any?> {
            val fields = linkedMapOf<String, Any?>()
            text.lineSequence().take(MAX_FIELD_COUNT + 1).forEach { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (key.isNotEmpty()) fields[key] = value
            }
            return fields
        }

        private val DIRECT_SCHEMES = setOf("http", "https")
        private val BINARY_PLIST_MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)
    }
}

internal enum class BodyEncoding { EMPTY, TEXT, BINARY_PLIST, XML_PLIST }

internal sealed interface FieldParse {
    data class Success(val request: VideoPlayRequest) : FieldParse

    /** A location with a sender-mediated scheme (`mlhls`): valid, but only the sender can fetch it. */
    data class SenderMediated(val request: VideoPlayRequest, val scheme: String) : FieldParse

    data class UnsupportedScheme(val scheme: String) : FieldParse
    data class Invalid(val reason: String) : FieldParse
}

internal sealed interface BodyParse {
    data class Success(
        val request: VideoPlayRequest,
        val encoding: BodyEncoding,
        val fieldNames: List<String>,
    ) : BodyParse

    /** `/play` for a sender-mediated (`mlhls`) HLS session; needs the FCUP reverse-channel bridge. */
    data class SenderMediated(
        val request: VideoPlayRequest,
        val scheme: String,
        val encoding: BodyEncoding,
        val fieldNames: List<String>,
    ) : BodyParse

    data class UnsupportedScheme(
        val scheme: String,
        val encoding: BodyEncoding,
        val fieldNames: List<String>,
    ) : BodyParse

    data class Invalid(
        val reason: String,
        val encoding: BodyEncoding? = null,
        val fieldNames: List<String> = emptyList(),
    ) : BodyParse
}
