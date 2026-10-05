package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import java.net.URI

/** Direct HTTP(S) URL video; internal sender-mediated schemes are not accepted as playable URLs. */
internal data class VideoPlayRequest(val url: String, val start: Double, val seconds: Boolean) {
    val scheme: String get() = runCatching { URI(url).scheme?.lowercase().orEmpty() }.getOrDefault("")

    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_FIELD_COUNT = 128
        private const val MAX_LOCATION_CHARS = 16 * 1024

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
            if (scheme !in DIRECT_SCHEMES) {
                return FieldParse.UnsupportedScheme(scheme)
            }
            if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
                return FieldParse.Invalid("HTTP(S) media URL requires a host and may not embed credentials")
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
            return FieldParse.Success(VideoPlayRequest(rawUrl, start, seconds))
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
    data class UnsupportedScheme(val scheme: String) : FieldParse
    data class Invalid(val reason: String) : FieldParse
}

internal sealed interface BodyParse {
    data class Success(
        val request: VideoPlayRequest,
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
