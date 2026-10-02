package com.phairplay.settings

/**
 * MirrorResolution — the video size PhairPlay advertises to AirPlay senders in `GET /info`.
 *
 * WHAT: The three mirroring sizes PhairPlay can ask for (1080p / 1440p / 4K), plus the pure
 *       policy that picks one for a given TV.
 *
 * WHY:  The sender renders and encodes the mirror at whatever size we advertise. A 4K Google TV
 *       (e.g. Google TV Streamer 4K, Chromecast with Google TV 4K, Android TV OS 14 sets) can be
 *       offered 4K — the extra detail survives the downscale to the app surface, so text looks
 *       sharper. A 1080p panel, or a TV whose H.264 decoder tops out below 4K, must never be
 *       offered more than it can decode: the stream would fail to configure and mirroring would
 *       show a black screen. Keeping the decision in one dependency-free object makes it
 *       unit-testable without a device; the device probes live in
 *       [com.phairplay.util.DisplayCaps].
 *
 * HOW:
 *   val res = MirrorResolution.pick(
 *       preferHighResolution = settings.forceHighResolution,
 *       panelWidth = 3840, panelHeight = 2160,     // DisplayCaps.panelSize(context)
 *       decodeWidth = 3840, decodeHeight = 2160    // DisplayCaps.maxH264Size()
 *   )                                              // → UHD
 *   AirPlayReceiver(..., mirrorWidth = res.width, mirrorHeight = res.height)
 */
enum class MirrorResolution(val width: Int, val height: Int, val label: String) {

    /** 1080p — what every Google TV can show and decode; the default. */
    FHD(1920, 1080, "1080p"),

    /** 1440p — supersampled on a 1080p surface, sharper text at the cost of decode work. */
    QHD(2560, 1440, "1440p"),

    /** 4K UHD — only on a 4K panel whose H.264 decoder reports 3840x2160 support. */
    UHD(3840, 2160, "4K");

    companion object {

        /** Safe fallback: 1080p is the mandatory baseline in docs/spec/REQUIREMENTS.md. */
        val DEFAULT: MirrorResolution = FHD

        /**
         * Picks the size to advertise.
         *
         * Rules, in order:
         *  1. `preferHighResolution` off (the default) → always 1080p. Mirroring stays safe on
         *     weak SoCs, which is the behaviour PhairPlay has always had.
         *  2. Never advertise more than the panel can show (`panelWidth`/`panelHeight`).
         *  3. Never advertise more than the hardware H.264 decoder reports it can decode.
         *  4. Otherwise take the largest candidate that satisfies both.
         *
         * A 16:9 candidate on a portrait/odd panel is not a thing on Android TV, so the panel
         * comparison is a plain width/height bound; if a TV reports 0x0 (some projectors and
         * HDMI-less setups do) nothing passes and the answer is 1080p.
         *
         * @param preferHighResolution user toggle (Settings → Higher resolution)
         * @param panelWidth         real display width in pixels, e.g. 3840 on a 4K Google TV
         * @param panelHeight        real display height in pixels, e.g. 2160
         * @param decodeWidth        largest width the H.264 decoder claims to support
         * @param decodeHeight       largest height it claims to support
         */
        fun pick(
            preferHighResolution: Boolean,
            panelWidth: Int,
            panelHeight: Int,
            decodeWidth: Int,
            decodeHeight: Int
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
