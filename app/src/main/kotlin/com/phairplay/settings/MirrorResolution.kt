package com.phairplay.settings

/**
 * Effective mirroring size Hearth advertises to AirPlay senders in `GET /info`.
 *
 */
data class MirrorResolution(val width: Int, val height: Int, val label: String) {
    companion object {
        /** 1080p — safe baseline and the normal fallback when a device probe is unavailable. */
        val FHD = MirrorResolution(1920, 1080, "1080p")
        /** 720p — standard lower resolution. */
        val HD = MirrorResolution(1280, 720, "720p")
        /** 1440p — higher-resolution opt-in. */
        val QHD = MirrorResolution(2560, 1440, "1440p")
        /** 4K UHD — only when both panel and H.264 decoder report support. */
        val UHD = MirrorResolution(3840, 2160, "4K")

        /** Named candidates retained for callers that need the standard advertised sizes. */
        fun values(): Array<MirrorResolution> = arrayOf(FHD, QHD, UHD)

        /** Safe fallback: 1080p is the documented baseline when a capability probe is unknown. */
        val DEFAULT: MirrorResolution = FHD

        /** Picks the largest supported mode when Higher resolution is enabled. */
        fun pick(
            preferHighResolution: Boolean,
            panelWidth: Int,
            panelHeight: Int,
            decodeWidth: Int,
            decodeHeight: Int,
        ): MirrorResolution {
            if (!preferHighResolution) return DEFAULT
            return values()
                .filter {
                    it.width <= panelWidth && it.height <= panelHeight &&
                        it.width <= decodeWidth && it.height <= decodeHeight
                }
                .maxByOrNull { it.width }
                ?: DEFAULT
        }

    }
}
