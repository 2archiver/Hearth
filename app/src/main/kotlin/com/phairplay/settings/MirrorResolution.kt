package com.phairplay.settings

import kotlin.math.min

/**
 * Effective mirroring size Hearth advertises to AirPlay senders in `GET /info`.
 *
 * Stable FPS is a receiver-side next-session policy, not an AirPlay frame-rate negotiation: it caps
 * the mirroring offer at 1080p (or the reported panel/decoder ceiling, whichever is lower). It
 * never changes gallery bitmaps or the independent ExoPlayer URL-video pipeline.
 */
data class MirrorResolution(val width: Int, val height: Int, val label: String) {
    companion object {
        /** 1080p — safe baseline and the normal fallback when a device probe is unavailable. */
        val FHD = MirrorResolution(1920, 1080, "1080p")
        /** 720p — stable-mode fallback for a panel or decoder below 1080p. */
        val HD = MirrorResolution(1280, 720, "720p")
        /** 1440p — higher-resolution opt-in. */
        val QHD = MirrorResolution(2560, 1440, "1440p")
        /** 4K UHD — only when both panel and H.264 decoder report support. */
        val UHD = MirrorResolution(3840, 2160, "4K")

        /** Named candidates retained for callers that need the standard advertised sizes. */
        fun values(): Array<MirrorResolution> = arrayOf(FHD, QHD, UHD)

        /** Safe fallback: 1080p is the documented baseline when a capability probe is unknown. */
        val DEFAULT: MirrorResolution = FHD

        /**
         * Picks the normal or optional Stable FPS mirroring size.
         *
         * With Stable FPS off, the 1.9.x Higher resolution behavior is preserved: the user opt-in
         * selects the largest standard mode that fits the panel and H.264 decoder, and opt-out
         * remains 1080p. With Stable FPS on, the effective size is additionally capped to 1920x1080
         * and reduced to fit known lower panel/decoder ceilings. The persisted Higher resolution
         * preference is not changed.
         */
        fun pick(
            preferHighResolution: Boolean,
            panelWidth: Int,
            panelHeight: Int,
            decodeWidth: Int,
            decodeHeight: Int,
            stableFpsEnabled: Boolean = false,
        ): MirrorResolution {
            if (stableFpsEnabled) {
                return pickStable(panelWidth, panelHeight, decodeWidth, decodeHeight)
            }
            if (!preferHighResolution) return DEFAULT
            return values()
                .filter {
                    it.width <= panelWidth && it.height <= panelHeight &&
                        it.width <= decodeWidth && it.height <= decodeHeight
                }
                .maxByOrNull { it.width }
                ?: DEFAULT
        }

        private fun pickStable(
            panelWidth: Int,
            panelHeight: Int,
            decodeWidth: Int,
            decodeHeight: Int,
        ): MirrorResolution {
            // Unknown dimensions use the documented 1080p baseline as their cap, while every
            // dimension the device did report remains a hard ceiling (including a 720p panel).
            val knownPanelWidth = panelWidth.takeIf { it > 0 } ?: FHD.width
            val knownPanelHeight = panelHeight.takeIf { it > 0 } ?: FHD.height
            val knownDecodeWidth = decodeWidth.takeIf { it > 0 } ?: FHD.width
            val knownDecodeHeight = decodeHeight.takeIf { it > 0 } ?: FHD.height
            val capWidth = minOf(knownPanelWidth, knownDecodeWidth, FHD.width)
            val capHeight = minOf(knownPanelHeight, knownDecodeHeight, FHD.height)
            listOf(FHD, HD).firstOrNull { it.width <= capWidth && it.height <= capHeight }?.let {
                return it
            }

            // Non-standard low-resolution panels/decoders still get an aspect-fit request that is
            // strictly within their reported bounds instead of silently falling back to 1080p.
            val fitWidth = min(capWidth.toDouble(), capHeight * 16.0 / 9.0).toInt()
            val width = (fitWidth / 16) * 16
            val fitHeight = min(capHeight.toDouble(), width * 9.0 / 16.0).toInt()
            val height = (fitHeight / 2) * 2
            if (width <= 0 || height <= 0) return MirrorResolution(capWidth, capHeight, "${capWidth}x$capHeight")
            return MirrorResolution(width, height, "${width}x$height")
        }
    }
}
