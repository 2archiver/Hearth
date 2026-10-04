package com.phairplay.airplay

import java.net.URI

/** Direct HTTP video support reviewed against UxPlay 1.74 http_handler_play. */
internal data class VideoPlayRequest(val url: String, val start: Double, val seconds: Boolean) {
    companion object {
        fun parse(fields: Map<String, Any?>): VideoPlayRequest? {
            fun field(name: String) = fields.entries.firstOrNull { it.key.equals(name, true) }?.value
            val url = (field("Content-Location") as? String)?.trim() ?: return null
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            // Internal FCUP locations need a reverse-channel playlist proxy, not MediaPlayer.
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
            val secondsValue = field("Start-Position-Seconds")
            val seconds = secondsValue != null
            val value = secondsValue ?: field("Start-Position")
            val start = when (value) {
                null -> 0.0
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull() ?: return null
                else -> return null
            }
            if (!start.isFinite() || start < 0.0 || (!seconds && start > 1.0)) return null
            return VideoPlayRequest(url, start, seconds)
        }
    }
}
