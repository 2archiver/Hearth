package com.phairplay.util

/**
 * MdnsNames — the single source of truth for "what is this receiver called".
 *
 * WHY THIS EXISTS: three places used to disagree about the name, which is exactly why
 * changing the name in Settings did not change what an iPhone showed in its AirPlay
 * picker:
 *
 *   1. [com.phairplay.settings.AppSettings] held the user's spoofed name,
 *   2. [com.phairplay.airplay.MdnsService] registered it over mDNS, and
 *   3. [com.phairplay.airplay.handshake.InfoResponder] answered `GET /info` with the
 *      **Android system device name** — and `GET /info` is the value iOS actually
 *      displays once it has probed a device it found by browsing.
 *
 * The default name and the cleaning rules now live here so all three agree, and a
 * rename takes effect everywhere at once.
 *
 * HOW: [sanitize] is a pure function (no Android APIs) so the JVM test suite can pin
 * its behaviour, and [DEFAULT_DISPLAY_NAME] is what a fresh install advertises.
 */
object MdnsNames {

    /**
     * The name a fresh install — and "Reset to default" — advertises.
     *
     * Hearth already answers `GET /info` as an Apple TV (`AppleTV5,3`) so senders
     * treat it as one. Matching that model string with the name an Apple TV would show
     * keeps the iPhone picker, the macOS menu and the `/info` reply consistent, and it
     * is the name most senders' users expect to pick from a list.
     */
    const val DEFAULT_DISPLAY_NAME = "Apple TV"

    /** DNS-SD caps a service name at 63 bytes of UTF-8 (RFC 6763 §6.2). */
    const val MAX_NAME_BYTES = 63

    /** Anything outside this set can corrupt a Bonjour record or vanish from a picker. */
    private val DISALLOWED = Regex("[^A-Za-z0-9 _\\-]")

    private val WHITESPACE = Regex("\\s+")

    /**
     * Cleans [raw] into a value that is safe to register as an mDNS service name *and* to
     * show in a sender's AirPlay picker: disallowed characters are dropped, runs of
     * whitespace collapse to a single space, the result is trimmed and truncated to
     * [MAX_NAME_BYTES] UTF-8 bytes on a character boundary.
     *
     * @param raw      Candidate name — the Settings value, or the Android device name.
     * @param fallback Returned when nothing usable is left after cleaning.
     */
    fun sanitize(raw: String?, fallback: String = DEFAULT_DISPLAY_NAME): String =
        clean(raw).ifEmpty { fallback }

    /**
     * Same as [sanitize] but returns null when the input is blank *or* cleans down to
     * nothing (e.g. a name made entirely of punctuation), so a caller can fall back to
     * something else — the Android device name, say — instead of [DEFAULT_DISPLAY_NAME].
     */
    fun sanitizeOrNull(raw: String?): String? = clean(raw).ifEmpty { null }

    /**
     * The shared cleaning pass: drop disallowed characters, collapse whitespace, trim, and
     * cap at [MAX_NAME_BYTES]. Returns "" when nothing usable is left.
     */
    private fun clean(raw: String?): String {
        val cleaned = WHITESPACE.replace(DISALLOWED.replace(raw ?: "", " "), " ").trim()
        return truncateUtf8(cleaned, MAX_NAME_BYTES)
    }

    /**
     * Truncates [value] so its UTF-8 encoding is at most [maxBytes] bytes, never splitting
     * a multi-byte character in half (a half-written character renders as garbage — or is
     * rejected outright — by the sender).
     */
    fun truncateUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val out = StringBuilder()
        var used = 0
        for (ch in value) {
            val size = ch.toString().toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            out.append(ch)
            used += size
        }
        return out.toString().trimEnd()
    }
}
