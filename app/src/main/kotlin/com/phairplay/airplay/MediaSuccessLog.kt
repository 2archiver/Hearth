package com.phairplay.airplay

/** Aggregates routine media reads before trace/log allocation and UI history updates. */
internal class MediaSuccessLog {
    private var lastReportMillis: Long? = null
    private var requests = 0L
    private var bytes = 0L
    private var maxReadMillis = 0L

    @Synchronized
    fun completed(route: String, resource: String, readBytes: Long, durationMillis: Long, nowMillis: Long): String? {
        requests++
        bytes += readBytes.coerceAtLeast(0)
        maxReadMillis = maxOf(maxReadMillis, durationMillis)
        val last = lastReportMillis
        if (last != null && nowMillis - last < 30_000L) return null
        val summary = "Media reads route=$route latest=$resource completed=$requests readBytes=$bytes maxRead=${maxReadMillis}ms"
        lastReportMillis = nowMillis
        requests = 0
        bytes = 0
        maxReadMillis = 0
        return summary
    }
}
