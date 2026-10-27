package com.phairplay.airplay

import java.io.IOException

/**
 * Validates partial HTTP media responses before bytes reach a decoder.
 * Safari seeks and HLS byte ranges must receive exactly the requested starting byte;
 * accepting a malformed 206 can feed an MP4 header or the wrong fragment to Media3.
 * Used by the guarded HTTP data source after redirects, without retaining media URLs.
 */
internal object MediaResponseRange {
    private val pattern = Regex("(?i)^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$")

    /** Returns the available response window, throwing on contradictory range metadata. */
    fun partialLength(contentRange: String?, position: Long): Long {
        val match = pattern.matchEntire(contentRange.orEmpty().trim())
            ?: throw IOException("partial media response has no valid Content-Range")
        val start = match.groupValues[1].toLongOrNull()
            ?: throw IOException("partial media start exceeds supported range")
        val end = match.groupValues[2].toLongOrNull()
            ?: throw IOException("partial media end exceeds supported range")
        val totalText = match.groupValues[3]
        val total = if (totalText == "*") null else {
            totalText.toLongOrNull() ?: throw IOException("partial media total exceeds supported range")
        }
        if (start != position || end < start || end == Long.MAX_VALUE ||
            (total != null && end >= total)
        ) throw IOException("partial media response does not match requested range")
        return end - start + 1L
    }

    /** A bounded read may finish at the resource end, before its requested maximum. */
    fun readLength(requested: Long, available: Long): Long = when {
        requested < 0L -> available
        available < 0L -> requested
        else -> minOf(requested, available)
    }
}
