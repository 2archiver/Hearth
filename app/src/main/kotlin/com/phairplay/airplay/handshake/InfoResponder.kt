package com.phairplay.airplay.handshake

import android.content.Context
import com.phairplay.airplay.AirPlayIdentity
import com.phairplay.util.MdnsNames

/**
 * InfoResponder — builds the binary-plist body for `GET /info`, the first request an AirPlay
 * sender makes once it has found the TV over mDNS.
 *
 * WHY it is worth getting exactly right: a sender decides everything it is going to do from this
 * reply — whether to pair at all, in which dialect, which audio formats to offer, and which
 * display size to mirror into. A reply that is merely *plausible* gets the connection as far as
 * the next request and then leaves the user with a spinner. Every field here therefore comes from
 * [AirPlayIdentity], the same object [com.phairplay.airplay.MdnsService] advertises over mDNS, so
 * the browse record and the probe reply can never describe two different devices.
 *
 * ## The two `/info` shapes
 *
 * 1. **Qualifier request.** The very first `/info` a modern Apple sender sends carries a small
 *    plist body — `{qualifier: ["txtAirPlay"]}` — and it expects a *reply containing that same
 *    TXT record as a data blob* ([buildTxtResponse]). Answering it with the full capability
 *    dictionary instead (which is what this app used to do, simply because it ignored the body)
 *    leaves the sender without the record it asked for at the exact moment it is deciding how to
 *    pair.
 * 2. **Capability request.** The ordinary `/info`: identity, feature bits, audio formats,
 *    latencies and the display list ([build]).
 *
 * `name` matters more than it looks: after browsing, a sender asks `GET /info` and then *displays*
 * the `name` it gets back — not the mDNS service name it browsed. Returning the Android device
 * name here (which is what this class used to do) is why an iPhone kept showing the TV's Android
 * name no matter what was typed into Settings.
 */
object InfoResponder {

    /** The qualifier strings an Apple sender may ask for in a `GET /info` body. */
    const val QUALIFIER_TXT_AIRPLAY = "txtAirPlay"
    const val QUALIFIER_TXT_RAOP = "txtRAOP"

    /**
     * `GET /info` with a `{qualifier: [...]}` body: the TXT record the sender asked for, and
     * nothing else.
     *
     * @param qualifier one of [QUALIFIER_TXT_AIRPLAY] / [QUALIFIER_TXT_RAOP], or null when the
     *   body did not name one (in which case the reply carries the AirPlay record, which is what
     *   a sender that sends a qualifier without a value is looking for).
     */
    fun buildTxtResponse(context: Context, qualifier: String?): ByteArray =
        buildTxtResponse(
            context = context,
            qualifiers = listOf(qualifier ?: QUALIFIER_TXT_AIRPLAY)
        )

    /**
     * Multi-qualifier `GET /info` response (supporting `{qualifier: ["txtAirPlay", "txtRAOP"]}`
     * as well as `GET /info?txtAirPlay&txtRAOP` BLE discovery queries, matching UxPlay's
     * `raop_handler_info`).
     */
    fun buildTxtResponse(context: Context, qualifiers: Collection<String>): ByteArray {
        val map = linkedMapOf<String, Any?>()
        if (QUALIFIER_TXT_AIRPLAY in qualifiers || qualifiers.isEmpty()) {
            map[QUALIFIER_TXT_AIRPLAY] = AirPlayIdentity.txtRecordBytes(AirPlayIdentity.airPlayTxt(context))
        }
        if (QUALIFIER_TXT_RAOP in qualifiers) {
            map[QUALIFIER_TXT_RAOP] = AirPlayIdentity.txtRecordBytes(AirPlayIdentity.raopTxt(context))
        }
        if (map.isEmpty()) {
            map[QUALIFIER_TXT_AIRPLAY] = AirPlayIdentity.txtRecordBytes(AirPlayIdentity.airPlayTxt(context))
        }
        return PlistCodec.encode(map)
    }

    /**
     * @param displayName The spoofed name from Settings; defaults to
     *   [MdnsNames.DEFAULT_DISPLAY_NAME] so a caller that has no settings handy still
     *   advertises something coherent with the mDNS record.
     * @param initialVolume Current receiver AirPlay volume in dB (`-30.0..0.0`), keeping the
     *   client's volume slider consistent across sessions (UxPlay commit `412c5b7`).
     */
    fun build(
        context: Context,
        displayName: String = MdnsNames.DEFAULT_DISPLAY_NAME,
        width: Int = 1920,
        height: Int = 1080,
        initialVolume: Double = 0.0,
    ): ByteArray {
        val mac = com.phairplay.util.NetworkUtils.getMacAddress()
        val info = mapOf(
            "ID" to mac,
            "deviceID" to mac,
            "macAddress" to mac,
            "manufacturer" to AirPlayIdentity.MANUFACTURER,
            "features" to AirPlayIdentity.FEATURES,
            "statusFlags" to AirPlayIdentity.STATUS_FLAGS,
            "model" to AirPlayIdentity.MODEL,
            // The spoofed name — must match the mDNS service name, or the picker shows one
            // name while the sender internally uses another.
            "name" to MdnsNames.sanitize(displayName),
            "sourceVersion" to AirPlayIdentity.SOURCE_VERSION,
            "protovers" to AirPlayIdentity.PROTOCOL_VERSION,
            "pi" to com.phairplay.util.NetworkUtils.getPersistentUuid(context),
            "pk" to PairingKeys.get(context).edPublic,
            "vv" to AirPlayIdentity.VERSION,
            "keepAliveLowPower" to true,
            "keepAliveSendStatsAsBody" to true,
            // Volume the sender should adopt at session start (UxPlay commit 412c5b7).
            "initialVolume" to initialVolume,
            // NOTE: macOS IGNORES this for system-audio AirPlay — it sends ALAC (ct=2) regardless of
            // what we advertise (verified: advertising AAC-only still got ALAC). So we keep the broad
            // set (mirroring negotiates AAC-ELD from it, which works).
            "audioFormats" to listOf(
                mapOf("type" to 100L, "audioInputFormats" to 67108860L, "audioOutputFormats" to 67108860L),
                mapOf("type" to 101L, "audioInputFormats" to 67108860L, "audioOutputFormats" to 67108860L)
            ),
            "audioLatencies" to listOf(
                mapOf(
                    "type" to 100L,
                    "audioType" to "default",
                    "inputLatencyMicroSeconds" to 0L,
                    "outputLatencyMicroSeconds" to 0L,
                    "inputLatencyMicros" to 0L,
                    "outputLatencyMicros" to 0L,
                ),
                mapOf(
                    "type" to 101L,
                    "audioType" to "default",
                    "inputLatencyMicroSeconds" to 0L,
                    "outputLatencyMicroSeconds" to 0L,
                    "inputLatencyMicros" to 0L,
                    "outputLatencyMicros" to 0L,
                )
            ),
            // Screen the sender can mirror to — without this, macOS aborts after key setup.
            "displays" to listOf(
                mapOf(
                    "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925",
                    "widthPhysical" to 0L,
                    "heightPhysical" to 0L,
                    "width" to width.toLong(),
                    "height" to height.toLong(),
                    "widthPixels" to width.toLong(),
                    "heightPixels" to height.toLong(),
                    "rotation" to true,
                    "refreshRate" to 60L,
                    "maxFPS" to 60L,
                    "overscanned" to false,   // false = macOS uses the full advertised resolution
                    "features" to 14L
                )
            )
        )
        return PlistCodec.encode(info)
    }
}
