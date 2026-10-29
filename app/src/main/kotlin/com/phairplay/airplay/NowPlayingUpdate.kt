package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec

/** The npi-text envelope used by iOS over POST /command, separate from RAOP DMAP. */
data class NowPlayingUpdate(
    val fields: Map<String, Any?>,
    val replace: Boolean,
) {
    fun applyTo(previous: NowPlayingInfo): NowPlayingInfo {
        fun text(key: String, old: String?): String? =
            if (fields.containsKey(PREFIX + key)) (fields[PREFIX + key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            else if (replace) null else old
        val artwork = when {
            fields.containsKey(PREFIX + "ArtworkData") ->
                (fields[PREFIX + "ArtworkData"] as? ByteArray)?.takeIf { it.isNotEmpty() }
            replace -> null
            else -> previous.artwork
        }
        return previous.copy(
            title = text("Title", previous.title), artist = text("Artist", previous.artist),
            album = text("Album", previous.album), artwork = artwork,
        )
    }

    companion object {
        const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private const val PREFIX = "kMRMediaRemoteNowPlayingInfo"

        fun decode(body: ByteArray): NowPlayingUpdate? {
            require(body.size <= MAX_BODY_BYTES) { "now-playing body exceeds limit" }
            val root = PlistCodec.decode(body)
            if (root["type"] != "updateMRNowPlayingInfo") return null
            val wrapper = root["params"] as? Map<*, *> ?: error("missing now-playing parameters")
            require(wrapper["type"] == "npi-text") { "unsupported now-playing representation" }
            val policy = wrapper["mergePolicy"]
            require(policy == "replace" || policy == "update") { "unsupported merge policy" }
            val values = wrapper["params"] as? Map<*, *> ?: error("missing now-playing fields")
            val fields = values.entries.associate { (key, value) -> key.toString() to value }
            for (key in listOf("Title", "Artist", "Album")) {
                val value = fields[PREFIX + key]
                require(value == null || (value is String && value.length <= 4096))
            }
            val art = fields[PREFIX + "ArtworkData"]
            require(art == null || art is ByteArray) { "artwork must be data" }
            return NowPlayingUpdate(fields, policy == "replace")
        }
    }
}
