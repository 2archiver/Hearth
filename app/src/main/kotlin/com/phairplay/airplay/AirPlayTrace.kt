package com.phairplay.airplay

import com.phairplay.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bounded, structured AirPlay diagnostics. Each entry has wall-clock time for people and monotonic
 * elapsed time for ordering/duration measurements. Session and connection labels are receiver-
 * generated opaque IDs; protocol identifiers and peer addresses are never stored in this trace.
 *
 * Export is deliberately opt-in and applies a second redaction pass so a future call site cannot
 * accidentally put a signed URL, address, pairing value, device ID, or opaque session token into an
 * exported report.
 */
object AirPlayTrace {
    enum class Kind { INFO, REQUEST, FAILURE, LIFECYCLE }

    /** Immutable diagnostic entry. [atElapsedMillis] is monotonic milliseconds since boot. */
    data class Entry(
        val atMillis: Long,
        val message: String,
        val atElapsedMillis: Long = 0L,
        val sessionId: String? = null,
        val connectionId: String? = null,
        val role: String? = null,
        val kind: Kind = Kind.INFO,
        val associationEvidence: String? = null,
    ) {
        fun timeLabel(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(atMillis))
    }

    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Records a short, value-free diagnostic event. Never pass request bodies or raw URLs here. */
    @JvmOverloads
    fun record(
        message: String,
        sessionId: String? = null,
        connectionId: String? = null,
        role: String? = null,
        kind: Kind = Kind.INFO,
        associationEvidence: String? = null,
    ) {
        val entry = Entry(
            atMillis = System.currentTimeMillis(),
            message = redact(message).take(MAX_MESSAGE_CHARS),
            atElapsedMillis = System.nanoTime() / NANOS_PER_MILLI,
            sessionId = sessionId?.takeIf { SAFE_LABEL.matches(it) },
            connectionId = connectionId?.takeIf { SAFE_LABEL.matches(it) },
            role = role?.takeIf { SAFE_ROLE.matches(it) },
            kind = kind,
            associationEvidence = associationEvidence?.takeIf { SAFE_EVIDENCE.matches(it) },
        )
        synchronized(buffer) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            _entries.value = buffer.toList()
        }
        // Call sites must provide safe summaries; exported diagnostics are independently redacted.
        Logger.d("AirPlay trace: ${redact(entry.message)}")
    }

    /** Convenience for request handlers; endpoint is already normalized and query-free. */
    fun request(
        method: String,
        endpoint: String,
        cseq: String?,
        contentType: String?,
        bodyBytes: Int,
        durationMillis: Long,
        status: Int,
        before: String,
        after: String,
        sessionId: String?,
        connectionId: String,
        role: String,
    ) {
        val cseqPart = cseq?.let { " cseq=$it" }.orEmpty()
        val typePart = contentType?.let { " type=$it" }.orEmpty()
        record(
            "$method $endpoint$cseqPart$typePart bytes=$bodyBytes " +
                "${durationMillis.coerceAtLeast(0)}ms -> $status state=$before->$after",
            sessionId = sessionId,
            connectionId = connectionId,
            role = role,
            kind = Kind.REQUEST,
        )
    }

    fun latest(): String? = entries.value.lastOrNull()?.message

    fun lastFailure(): Entry? = entries.value.lastOrNull { it.kind == Kind.FAILURE }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /** Export an explicit, privacy-filtered plain-text report; does not write files or send data. */
    fun exportText(metadata: Map<String, String> = emptyMap()): String {
        val snapshot = entries.value
        val firstElapsed = snapshot.firstOrNull()?.atElapsedMillis ?: 0L
        return buildString {
            appendLine("Hearth AirPlay diagnostics (opt-in export)")
            val utcFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            appendLine("Generated: ${utcFormat.format(Date())}")
            metadata.toSortedMap().forEach { (key, value) ->
                appendLine("${redact(key.take(80))}: ${redactMetadata(key, value).take(MAX_EXPORT_VALUE_CHARS)}")
            }
            appendLine("Last failure: ${snapshot.lastOrNull { it.kind == Kind.FAILURE }?.let { redact(it.message) } ?: "none recorded"}")
            appendLine("Trace (oldest first; ${snapshot.size}/$MAX_ENTRIES entries):")
            snapshot.forEach { entry ->
                val elapsed = (entry.atElapsedMillis - firstElapsed).coerceAtLeast(0L)
                val context = listOfNotNull(
                    entry.sessionId?.let { "session=$it" },
                    entry.connectionId?.let { "connection=$it" },
                    entry.role?.let { "role=$it" },
                    entry.associationEvidence?.let { "association=$it" },
                ).joinToString(" ")
                appendLine(
                    "${entry.timeLabel()} +${elapsed}ms ${entry.kind}" +
                        (if (context.isEmpty()) "" else " [$context]") +
                        " ${redact(entry.message)}"
                )
            }
        }
    }

    /** Public for focused privacy regression tests and support tooling. */
    fun redact(text: String): String {
        var safe = text
        safe = MEDIA_URI.replace(safe, "$1://[media location redacted]")
        safe = BEARER_TOKEN.replace(safe, "Bearer [redacted]")
        safe = SENSITIVE_PAIR.replace(safe) { match -> "${match.groupValues[1]}=[redacted]" }
        safe = MAC_ADDRESS.replace(safe, "[device address redacted]")
        safe = IPV6_ADDRESS.replace(safe, "[address redacted]")
        safe = IPV4.replace(safe, "[address redacted]")
        safe = UUID.replace(safe, "[identifier redacted]")
        safe = LONG_OPAQUE_TOKEN.replace(safe, "[opaque value redacted]")
        return safe
    }

    private fun redactMetadata(key: String, value: String): String =
        if (SENSITIVE_METADATA_KEY.containsMatchIn(key)) "[redacted]" else redact(value)

    private const val MAX_ENTRIES = 256
    private const val MAX_MESSAGE_CHARS = 512
    private const val MAX_EXPORT_VALUE_CHARS = 256
    private const val NANOS_PER_MILLI = 1_000_000L

    private val SAFE_LABEL = Regex("[A-Z][A-Z0-9_-]{0,15}", RegexOption.IGNORE_CASE)
    private val SAFE_ROLE = Regex("[A-Z][A-Z0-9_-]{0,31}", RegexOption.IGNORE_CASE)
    private val SAFE_EVIDENCE = Regex("[A-Z][A-Z0-9_-]{0,31}", RegexOption.IGNORE_CASE)
    private val MEDIA_URI = Regex("(?i)\\b(https?|mlhls)://[^\\s\\\"'<>]+")
    private val BEARER_TOKEN = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val LONG_OPAQUE_TOKEN = Regex("(?<![A-Za-z0-9])[A-Za-z0-9_-]{40,}={0,2}(?![A-Za-z0-9])")
    private val SENSITIVE_PAIR = Regex(
        "(?i)\\b(token|access[_-]?token|authorization|cookie|set-cookie|signature|sig|device[_-]?id|udid|mac(?:address)?|session[_-]?id|pairing[_-]?(?:key|material)|private[_-]?key|public[_-]?key|ecdh|ekey|eiv|shk|shiv)\\s*[:=]\\s*[^\\s,;]+"
    )
    private val SENSITIVE_METADATA_KEY = Regex(
        "(?i)(token|secret|credential|pairing|device.?id|session.?id|signed.?url|media.?url|ip.?address|mac.?address)"
    )
    private val IPV4 = Regex("(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9.])")
    private val IPV6_ADDRESS = Regex(
        "(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?:%[a-z0-9_.-]+)?(?:\\.[0-9]{1,3}){0,4}(?![0-9a-f:])"
    )
    private val MAC_ADDRESS = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val UUID = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
}
