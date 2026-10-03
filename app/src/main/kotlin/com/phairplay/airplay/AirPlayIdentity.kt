package com.phairplay.airplay

import android.content.Context
import com.phairplay.util.NetworkUtils

/**
 * AirPlayIdentity — the **one** description of what PhairPlay claims to be on the network.
 *
 * WHY THIS FILE EXISTS: mDNS advertises one set of facts, `GET /info` answers a second, and the
 * sender decides how to pair, which clock to use and which streams to open **from those facts
 * alone**. When the two disagree — or when they describe a device whose pairing PhairPlay does
 * not implement — a real iPhone/Mac connects and then stops: the picker shows the TV, the
 * spinner runs, and the session dies before a single frame arrives. Every value a sender can
 * read is therefore defined here, once, and used by [MdnsService] and
 * [com.phairplay.airplay.handshake.InfoResponder] alike.
 *
 * ─── The profile: a legacy-pairing AirPlay 2 receiver ───────────────────────────────────
 *
 * PhairPlay implements AirPlay's **legacy** pairing (a raw 32-byte `POST /pair-setup` answered
 * with our Ed25519 public key, then the X25519 `POST /pair-verify` signature exchange). Apple's
 * newer HomeKit/SRP pairing (`pair-setup` carried as TLV8, SRP-6a over the 3072-bit group)
 * is *not* implemented, and pretending otherwise is what makes a modern sender give up.
 *
 * Two advertised facts decide which of the two a sender picks:
 *
 *  - **Feature bit 27 — `SupportsLegacyPairing`.** UxPlay turns this bit on precisely because
 *    "legacy pairing" is what it offers (`dnssdint.h`), and turns it off to force Apple's
 *    HomeKit path. It is ON here, and the rest of the bitmask is kept to the same value the
 *    working open-source receivers advertise: `0x5A7FFEE6`.
 *  - **The model.** `AppleTV5,3` is an AirPlay **2** Apple TV — a device that pairs with
 *    HomeKit and runs an encrypted control channel. A sender that sees it expects `pair-setup`
 *    in the HomeKit dialect, and a receiver that answers 400 never gets a session. PhairPlay
 *    therefore reports `AppleTV3,2`, the model the reference legacy-pairing receivers use, so
 *    the sender takes the legacy path this app actually implements.
 *
 * Advertising `pk` (the long-lived public key) is fine and expected — the reference receivers
 * publish it as a **hex string** in the TXT record, and iOS reads it while browsing. It is the
 * pairing *bits*, not the key, that select the dialect.
 *
 * Values are deliberately identical to UxPlay's legacy profile (model `AppleTV3,2`, srcvers
 * `220.68`, `flags 0x4`, features `0x5A7FFEE6`) because that combination is the one with
 * millions of real-device hours behind it.
 */
object AirPlayIdentity {

    /** Advertised model. **Not** an AirPlay 2 Apple TV — see the class docs. */
    const val MODEL = "AppleTV3,2"

    /** Manufacturer, as Apple TV 3 reports it. */
    const val MANUFACTURER = "Apple"

    /** `srcvers` — Apple's AirTunes server version string. */
    const val SOURCE_VERSION = "220.68"

    /** `protovers` — AirPlay protocol version string senders expect on a v2-capable receiver. */
    const val PROTOCOL_VERSION = "1.1"

    /** `vv` — protocol major version. */
    const val VERSION = 2L

    /**
     * AirPlay feature bitmask, low 32 bits. Bit 27 (`SupportsLegacyPairing`) is ON; bits 0, 4, 8
     * and 12 are OFF, exactly as the proven legacy-pairing receivers advertise them. Video-URL
     * playback (`POST /play`) does not need those bits — the sender only offers it after
     * `GET /server-info`, which is served separately.
     */
    const val FEATURES = 0x5A7FFEE6L

    /** Same value as the TXT record, in the `0x…` form senders parse out of mDNS. */
    const val FEATURES_TXT = "0x5A7FFEE6"

    /**
     * `statusFlags` — 0x44 = AudioLink (0x04) + SupportsAirPlayFromCloud (0x40).
     *
     * Deliberately **not** set: PasswordNeeded (bit 7), PairingPIN (bit 9) and
     * Enable_HK_Access_Control (bit 10). Any of those tells the sender "this receiver wants
     * HomeKit access control", and it will start the SRP pairing this app cannot answer.
     */
    const val STATUS_FLAGS = 68L

    /** `flags` — 0x4 = this receiver accepts screen mirroring. */
    const val FLAGS = "0x4"

    /**
     * Raop (= audio-only) TXT record. A sender reads these before it will stream audio, and
     * macOS refuses a device whose `_raop._tcp` record is missing or has no `pk`.
     */
    fun raopTxt(context: Context): Map<String, String> = linkedMapOf(
        "txtvers" to "1",
        "ch" to "2",                     // 2 channels
        "cn" to "0,1,2,3",               // codecs: PCM, ALAC, AAC, AAC-ELD
        "da" to "true",                  // digest authentication supported
        "et" to "0,3,5",                 // encryption types: none, FairPlay, FairPlay SAPv2.5
        "ft" to FEATURES_TXT,            // feature bits, same profile as _airplay._tcp
        "md" to "0,1,2",                 // metadata: text, artwork, progress
        "pk" to publicKeyHex(context),   // long-lived Ed25519 public key, hex as Apple publishes it
        "rhd" to "5.6.0.0",
        "sf" to "0x4",
        "sr" to "44100",
        "ss" to "16",
        "sv" to "false",                 // no software volume control
        "tp" to "UDP",
        "vn" to "65537",
        "vs" to SOURCE_VERSION,
        "vv" to VERSION.toString(),
    )

    /**
     * `_airplay._tcp` TXT record — the record a sender browses to decide whether the TV is worth
     * connecting to, and how to pair with it.
     */
    fun airPlayTxt(context: Context): Map<String, String> = linkedMapOf(
        "deviceid" to NetworkUtils.getMacAddress(),
        "features" to FEATURES_TXT,
        "flags" to FLAGS,
        "model" to MODEL,
        "pk" to publicKeyHex(context),
        "pi" to NetworkUtils.getPersistentUuid(context),
        "srcvers" to SOURCE_VERSION,
        "protovers" to PROTOCOL_VERSION,
        "vv" to VERSION.toString(),
    )

    /**
     * DNS-SD TXT wire format: `len key=value` entries, terminated by a zero byte.
     *
     * This is the exact byte string [com.phairplay.airplay.handshake.InfoResponder] returns for
     * a `GET /info` that asks for `txtAirPlay`/`txtRAOP` — iOS asks for it during discovery and
     * reads the same keys out of it that it would have read from mDNS.
     */
    fun txtRecordBytes(record: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((key, value) in record) {
            val entry = "$key=$value".toByteArray(Charsets.UTF_8)
            // A TXT string is length-prefixed with a single byte, so an entry longer than 255
            // bytes cannot be encoded. Skip rather than truncate: a sender that sees a mangled
            // value treats the whole record as broken.
            if (entry.size > 255) continue
            out.write(entry.size)
            out.write(entry, 0, entry.size)
        }
        out.write(0)
        return out.toByteArray()
    }

    /** The receiver's Ed25519 public key as lowercase hex — what Apple publishes in `pk`. */
    fun publicKeyHex(context: Context): String =
        com.phairplay.airplay.handshake.PairingKeys.get(context).edPublic.toHexLower()

    private fun ByteArray.toHexLower(): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }
}
