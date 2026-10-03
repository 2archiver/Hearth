package com.phairplay.settings

import com.phairplay.util.MdnsNames

/**
 * AppSettings — Immutable data model for all user-configurable PhairPlay settings.
 *
 * WHY: Centralizing all settings in one data class gives a single source of truth.
 * Any component that needs a setting reads from here. Any component that changes a
 * setting creates a new copy via [copy]. This makes settings changes explicit and
 * easy to test.
 *
 * HOW: Settings are persisted via [SettingsRepository]. Get the current settings
 * from [SettingsRepository.settingsFlow] and update them via [SettingsRepository.update].
 *
 * Example:
 *   // Read settings
 *   val settings = settingsRepository.settingsFlow.first()
 *   if (settings.airPlayEnabled) { ... }
 *
 *   // Change a setting
 *   settingsRepository.update { it.copy(displayName = "My TV") }
 */
data class AppSettings(

    // ─── Display ───────────────────────────────────────────────────────────
    /**
     * The name shown in sender pickers (AirPlay menu on Mac, the iPhone/iPad screen-mirroring
     * list, Cast picker in Chrome, etc.) — i.e. the **spoofed** name.
     *
     * It defaults to [MdnsNames.DEFAULT_DISPLAY_NAME] ("Apple TV") rather than to empty:
     * a blank value used to fall back to the Android device name, which meant the name a
     * sender showed was whatever the TV was called in Android settings and could not be
     * controlled from inside PhairPlay.
     *
     * Validated via [effectiveDisplayName]: trimmed, stripped of characters that break
     * mDNS/pickers, and capped at 63 UTF-8 bytes. Setting it back to blank restores the
     * [MdnsNames.DEFAULT_DISPLAY_NAME].
     */
    val displayName: String = MdnsNames.DEFAULT_DISPLAY_NAME,

    // ─── Protocols ─────────────────────────────────────────────────────────
    /**
     * Whether the AirPlay 2 receiver is enabled.
     * When false: mDNS advertisement is stopped, RTSP port 7000 is not opened.
     */
    val airPlayEnabled: Boolean = true,

    /**
     * Whether the Miracast (Wi-Fi Display) receiver is enabled.
     *
     * OFF by default, on purpose. Receiving Miracast means owning a Wi-Fi Direct group, and a
     * Google TV either has no Wi-Fi radio switched on at all (wired sets) or keeps `WifiP2pManager`
     * for the system. Enabled it used to start at every launch, fail to register, and leave a red
     * "Wi-Fi Direct unavailable or permission denied" error on the Home screen of every TV where
     * it can never work. Switch it on if this set genuinely takes Miracast.
     */
    val miracastEnabled: Boolean = false,

    // ─── AirPlay specific ──────────────────────────────────────────────────
    /**
     * Whether AirPlay connections require PIN authentication.
     * When true: the user must confirm a 4-digit PIN shown on screen.
     * When false (default): any nearby Mac can connect without confirmation.
     */
    val airPlayPinAuthEnabled: Boolean = false,

    // ─── Service behavior ──────────────────────────────────────────────────
    /**
     * Whether PhairPlayService starts automatically on device boot.
     * Requires the RECEIVE_BOOT_COMPLETED permission to be effective.
     */
    val startOnBoot: Boolean = false,

    // ─── Developer / Debug ─────────────────────────────────────────────────
    /**
     * Overlays a debug HUD on the streaming screen showing:
     * - Current frames per second
     * - Estimated A/V latency (ms)
     * - Active protocol name
     * Only useful for development and testing.
     */
    val showDebugOverlay: Boolean = false,

    // ─── Video ─────────────────────────────────────────────────────────────
    /**
     * When true (the default), advertise a higher mirroring resolution than 1080p in the AirPlay
     * `/info` `displays` record — up to 4K on a 4K Google TV, 1440p where that is the ceiling — so the
     * sender renders and encodes a sharper image. Frames are then downscaled to the app surface
     * (supersampling: sharper text) at the cost of more decode work.
     *
     * It ships ON because the panel is what a wired Google TV 4K owner is looking at: a 1080p
     * mirror on a 4K set is visibly soft, and every Google TV in PhairPlay's test matrix decodes
     * 4K. The size actually advertised is still capped by what this TV can show and decode —
     * see [advertisedMirrorResolution] — so a 1080p or 1440p panel never gets asked to decode 4K,
     * and a slow SoC can opt out here without touching anything else.
     */
    val forceHighResolution: Boolean = true,

    /**
     * When true, accept the mirroring audio stream (type 96, AAC-ELD). EXPERIMENTAL: macOS uses
     * realtime audio clock-sync (RTCP) that isn't fully implemented yet, which can make macOS tear
     * the whole mirror session down after a couple of seconds — so this defaults OFF to keep video
     * mirroring rock-solid. Turn on to experiment with audio.
     */
    val mirrorAudioEnabled: Boolean = true,

    // ─── Updates ────────────────────────────────────────────────────────────
    /**
     * Check GitHub for a newer PhairPlay build in the background while the receiver service
     * is running, at most once every few hours.
     */
    val autoCheckForUpdates: Boolean = true,

    /**
     * Download a found update without asking, then show "Update ready — Install".
     *
     * Android still shows its own confirmation unless the platform allows PhairPlay to
     * replace itself silently (Android 12+ normally does). Nothing is ever installed
     * without the user pressing Install except in that platform-supported self-update case.
     */
    val autoDownloadUpdates: Boolean = true,

    /**
     * Install a downloaded update as soon as it is verified, without another prompt.
     * Off = show "Update ready — Install" and wait.
     */
    val autoInstallUpdates: Boolean = false
) {

    /**
     * The mirroring resolution to advertise to AirPlay senders for this TV.
     *
     * With [forceHighResolution] off (the default) the answer is always 1080p. With it on the
     * answer is the largest of 1080p / 1440p / 4K that both the panel and the hardware H.264
     * decoder support — so a 4K Google TV gets a 4K mirror and a 1080p TV never gets one it
     * could not decode. Policy lives in [MirrorResolution]; device facts come from
     * `com.phairplay.util.DisplayCaps`.
     *
     * @param panelWidth   real panel width, e.g. 3840 on a 4K Google TV
     * @param panelHeight  real panel height, e.g. 2160
     * @param decodeWidth  largest width the H.264 decoder reports supporting
     * @param decodeHeight largest height it reports supporting
     */
    fun advertisedMirrorResolution(
        panelWidth: Int,
        panelHeight: Int,
        decodeWidth: Int,
        decodeHeight: Int
    ): MirrorResolution = MirrorResolution.pick(
        preferHighResolution = forceHighResolution,
        panelWidth = panelWidth,
        panelHeight = panelHeight,
        decodeWidth = decodeWidth,
        decodeHeight = decodeHeight
    )

    /**
     * Returns the validated, trimmed display name — the single value every caller should
     * advertise (mDNS `_airplay._tcp`, the `GET /info` reply, the Home screen).
     *
     * Never empty: a blank or all-punctuation stored name resolves to
     * [MdnsNames.DEFAULT_DISPLAY_NAME] ("Apple TV") so senders always get a usable name
     * instead of silently falling back to the Android device name.
     */
    val effectiveDisplayName: String
        get() = MdnsNames.sanitize(displayName)

    /**
     * Returns true if at least one protocol is enabled.
     * If both are disabled, the service has nothing to do.
     */
    val anyProtocolEnabled: Boolean
        get() = airPlayEnabled || miracastEnabled

    companion object {
        /** The default settings instance used on first launch. */
        val DEFAULT = AppSettings()

        /** Maximum allowed length for the display name (mDNS limit). */
        const val DISPLAY_NAME_MAX_LENGTH = 63
    }
}
