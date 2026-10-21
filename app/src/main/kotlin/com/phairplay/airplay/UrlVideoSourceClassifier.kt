package com.phairplay.airplay

import java.nio.charset.StandardCharsets
import java.util.Locale

/** Small pure classifier used by the guarded extensionless-URL HLS probe. */
internal object UrlVideoSourceClassifier {
    internal data class Result(val isHls: Boolean, val evidence: String)

    private val hlsMimeTypes = setOf(
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/mpegurl",
        "audio/mpegurl",
        "audio/x-mpegurl",
    )

    fun classify(contentType: String?, prefix: ByteArray = ByteArray(0)): Result {
        val mimeType = contentType.orEmpty()
            .substringBefore(';')
            .trim()
            .lowercase(Locale.ROOT)
        if (mimeType in hlsMimeTypes) return Result(true, "response Content-Type")
        if (hasHlsHeader(prefix)) return Result(true, "response body prefix")
        return Result(false, "non-HLS response")
    }

    private fun hasHlsHeader(prefix: ByteArray): Boolean {
        if (prefix.isEmpty()) return false
        val text = prefix.toString(StandardCharsets.UTF_8)
            .removePrefix("\uFEFF")
            .trimStart(' ', '\t', '\r', '\n')
        return text.startsWith("#EXTM3U")
    }
}
