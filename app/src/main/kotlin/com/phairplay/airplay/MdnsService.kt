package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.os.Build
import android.net.nsd.NsdServiceInfo
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.MdnsNames
import com.phairplay.util.NetworkUtils

/**
 * MdnsService — Advertises PhairPlay as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS/iOS to show PhairPlay in the AirPlay menu, the device must announce
 * itself using mDNS (Multicast DNS, the same protocol as Apple's Bonjour).
 * Without this advertisement, no sender would know PhairPlay exists.
 *
 * HOW: Registers two mDNS services using Android's [NsdManager]:
 * - `_airplay._tcp` — main AirPlay service with feature flags and device info
 * - `_raop._tcp`    — audio streaming service (required even for screen mirroring)
 *
 * Both services use port [AIRPLAY_PORT] (7000), which is where [RtspHandler] listens.
 *
 * The service name shown in AirPlay pickers is determined by [displayNameOverride]:
 * - If set: uses the user-configured name from Settings
 * - If blank/null: falls back to [NetworkUtils.getDeviceName]
 *
 * State changes are reported via [onStateChange] callback.
 *
 * Example:
 *   val mdns = MdnsService(context, onStateChange = { state -> /* update UI */ })
 *   mdns.start(displayNameOverride = "Living Room TV")
 *   mdns.stop()
 *   mdns.restart(displayNameOverride = "Living Room TV")
 */
class MdnsService(
    private val context: Context,
    private val onStateChange: (ProtocolState) -> Unit = {},
    /**
     * Called with the actual mDNS service name after registration completes.
     *
     * Android's NsdManager resolves name collisions automatically: if another device
     * on the network is already registered as "PhairPlay", Android will register us as
     * "PhairPlay (2)" instead. The [onActualNameRegistered] callback delivers the name
     * that was actually registered (which may differ from the requested name).
     *
     * The caller can use this to update the UI (e.g., show "Registered as: PhairPlay (2)")
     * or log the divergence for debugging.
     *
     * Only the `_airplay._tcp` service name is reported (not the `_raop._tcp` name,
     * which has a MAC address prefix and is not shown to users).
     */
    private val onActualNameRegistered: (String) -> Unit = {}
) {

    // Android's built-in mDNS manager — handles multicast registration
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Listeners track registration state; held to enable unregistration later
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null

    // Count of how many services have confirmed registration.
    // Only when both reach 2 do we emit ProtocolState.ADVERTISING.
    @Volatile
    private var registeredCount = 0

    // Guard against double-start
    @Volatile
    private var isStarted = false

    // The name we requested to register — compared against the actual registered name
    // in onServiceRegistered to detect mDNS collision auto-renaming.
    @Volatile
    private var requestedName: String = ""

    /**
     * Starts mDNS advertising.
     *
     * Registers both the `_airplay._tcp` and `_raop._tcp` services.
     * The device will appear in the macOS/iOS AirPlay menu within ~1-3 seconds.
     *
     * Idempotent: calling it twice without [stop] in between is a no-op.
     *
     * @param displayNameOverride User-configured display name from Settings.
     *   Pass `null` or blank to use the Android system device name.
     */
    fun start(displayNameOverride: String? = null) {
        if (isStarted) {
            Logger.w("MdnsService.start() called but already registered — ignoring")
            return
        }
        isStarted = true
        registeredCount = 0

        val effectiveName = resolveDisplayName(displayNameOverride)
        Logger.i("Starting mDNS advertising as '$effectiveName'")
        requestedName = effectiveName

        registerAirPlayService(effectiveName)
        registerRaopService(effectiveName)
    }

    /**
     * Stops mDNS advertising.
     *
     * Unregisters both mDNS services. The device disappears from sender pickers
     * within ~5-10 seconds (mDNS goodbye packet sent immediately, but senders cache briefly).
     *
     * Safe to call even if [start] was never called.
     */
    fun stop() {
        Logger.i("Stopping mDNS advertising")
        try {
            airPlayListener?.let { nsdManager.unregisterService(it) }
            raopListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            // Unregistration errors are non-fatal: service will expire via mDNS TTL
            Logger.e("Error unregistering mDNS services (non-fatal)", e)
        } finally {
            airPlayListener = null
            raopListener = null
            registeredCount = 0
            isStarted = false
            onStateChange(ProtocolState.DISABLED)
        }
    }

    /**
     * Restarts mDNS advertising.
     *
     * Used after a streaming session ends to immediately re-advertise the device
     * in sender pickers.
     *
     * @param displayNameOverride Updated display name, if changed in Settings.
     */
    fun restart(displayNameOverride: String? = null) {
        Logger.d("Restarting mDNS advertising")
        stop()
        start(displayNameOverride)
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Determines the effective name to advertise.
     *
     * Uses [override] (the Settings value) when it is non-blank, otherwise falls back to
     * the Android device name. Either way the result goes through [MdnsNames.sanitize],
     * because a name with a stray emoji or 80 characters of text either fails to register
     * or comes back mangled in the picker.
     */
    private fun resolveDisplayName(override: String?): String {
        val fromSettings = MdnsNames.sanitizeOrNull(override)
        if (fromSettings != null) return fromSettings
        return NetworkUtils.getDeviceName(context)
    }

    /**
     * Registers the `_airplay._tcp` mDNS service.
     *
     * TXT records tell senders what features PhairPlay supports.
     * See TECHNICAL_SPEC.md §8 for bit-level breakdown of the `features` value.
     *
     * @param displayName The name shown in sender AirPlay pickers.
     */
    private fun registerAirPlayService(displayName: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE_AIRPLAY
            port = AIRPLAY_PORT

            // Core identity TXT records
            setAttribute("deviceid", NetworkUtils.getMacAddress())
            setAttribute("features", AIRPLAY_FEATURES)
            setAttribute("model", AIRPLAY_MODEL)
            setAttribute("manufacturer", AIRPLAY_MANUFACTURER)
            setAttribute("srcvers", AIRPLAY_SERVER_VERSION)
            setAttribute("protovers", AIRPLAY_PROTOCOL_VERSION)
            setAttribute("vv", "2")                             // AirPlay protocol version 2
            setAttribute("pi", NetworkUtils.getPersistentUuid(context))
            setAttribute("flags", "0x4")                        // Screen-mirroring receiver
        }

        // A real Apple TV advertises `pk` (the receiver's 32-byte Ed25519 public key) here
        // and iOS reads it *while browsing*, before it opens a connection at all. Publishing
        // it makes an iPhone treat us as an AirPlay 2 receiver during discovery rather than
        // after `GET /info`, which is what makes the iPhone's picker and video-casting path
        // behave the way they do with an Apple TV.
        //
        // The catch: NsdServiceInfo only grew a public setAttribute(String, byte[]) in
        // Android 12 (API 31). Below that the only public setter takes a String, which
        // re-encodes the value as UTF-8 and would mangle 32 raw key bytes — worse than
        // omitting the record. So: publish on 12+, skip (and let `GET /info` carry `pk`,
        // which it does) below that. Resolved reflectively so the compile SDK version does
        // not decide whether older devices can build.
        advertisePublicKey(serviceInfo)

        airPlayListener = createRegistrationListener(
            serviceLabel = "_airplay._tcp",
            onRegisteredName = { actualName ->
                // Detect collision auto-renaming: NsdManager appended " (2)", " (3)", etc.
                if (actualName != requestedName) {
                    Logger.w("mDNS name collision detected: requested='$requestedName' " +
                             "actual='$actualName' — NsdManager resolved automatically")
                }
                onActualNameRegistered(actualName)
            },
            onSuccess = { incrementAndCheckBothRegistered() },
            onFailure = { onStateChange(ProtocolState.ERROR) }
        )
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, airPlayListener!!)
    }

    /**
     * Registers the `_raop._tcp` mDNS service.
     *
     * RAOP (Remote Audio Output Protocol) is the audio component of AirPlay.
     * macOS and iOS require it even for screen mirroring — not only for audio-only streams.
     *
     * RAOP service name format required by the AirPlay protocol:
     *   `"<MACADDRESS_NOCOLONS>@<DeviceName>"`
     *   e.g., `"AABBCCDDEEFF@Living Room TV"`
     *
     * @param displayName The device name portion of the RAOP service name.
     */
    private fun registerRaopService(displayName: String) {
        val macHex = NetworkUtils.getMacAddress().replace(":", "").uppercase()

        val serviceInfo = NsdServiceInfo().apply {
            // The MAC prefix eats 13 of the 63 bytes a DNS-SD name may use, so the display
            // name has to be shortened to fit rather than truncated by the mDNS daemon
            // (which would silently produce an unrecognisable name).
            serviceName = MdnsNames.truncateUtf8("$macHex@$displayName", MdnsNames.MAX_NAME_BYTES)
            serviceType = SERVICE_TYPE_RAOP
            port = AIRPLAY_PORT

            setAttribute("cn", "0,1,2,3")        // Cipher numbers (encryption types)
            setAttribute("da", "true")             // Digest authentication capable
            setAttribute("et", "0,3,5")            // Encryption types supported
            setAttribute("md", "0,1,2")            // Metadata types supported
            setAttribute("sv", "false")            // Software volume control
            setAttribute("tp", "UDP")              // Transport for audio RTP
            setAttribute("vn", "65537")            // Version number (required)
            setAttribute("vs", AIRPLAY_SERVER_VERSION)
            setAttribute("am", AIRPLAY_MODEL)
        }

        raopListener = createRegistrationListener(
            serviceLabel = "_raop._tcp",
            onRegisteredName = null,  // RAOP name has MAC prefix — not shown to users
            onSuccess = { incrementAndCheckBothRegistered() },
            onFailure = { onStateChange(ProtocolState.ERROR) }
        )
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, raopListener!!)
    }

    /**
     * Emits [ProtocolState.ADVERTISING] only after both services have confirmed registration.
     * This prevents a brief "advertising" state where only one of the two required services
     * is live.
     */
    @Synchronized
    private fun incrementAndCheckBothRegistered() {
        registeredCount++
        if (registeredCount >= 2) {
            onStateChange(ProtocolState.ADVERTISING)
        }
    }

    /**
     * Adds the `pk` TXT record (the receiver's Ed25519 public key) when the platform can
     * carry raw bytes in an mDNS TXT record — Android 12+ only.
     *
     * Best-effort by design: a device whose NsdServiceInfo lacks the byte[] setter, or a
     * raw key the mDNS daemon rejects, must not stop the AirPlay service from advertising.
     * `GET /info` carries the same key, so iOS still gets it over that path.
     */
    private fun advertisePublicKey(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val publicKey = com.phairplay.airplay.handshake.PairingKeys.get(context).edPublic
        if (publicKey.isEmpty()) return
        runCatching {
            val setter = NsdServiceInfo::class.java.getMethod(
                "setAttribute", String::class.java, ByteArray::class.java
            )
            setter.invoke(serviceInfo, "pk", publicKey)
            Logger.d("mDNS: advertising the AirPlay `pk` TXT record (${publicKey.size} bytes)")
        }.onFailure { error ->
            Logger.w("mDNS: could not advertise `pk` — iOS will read it from GET /info instead (${error.message})")
        }
    }

    /**
     * Creates an [NsdManager.RegistrationListener] with logging and callbacks.
     *
     * @param serviceLabel     Human-readable service type for log messages.
     * @param onRegisteredName Called with the actual registered service name (may differ from
     *   requested due to collision resolution). Pass null if the name is not user-visible.
     * @param onSuccess        Called on [onServiceRegistered].
     * @param onFailure        Called on [onRegistrationFailed].
     */
    private fun createRegistrationListener(
        serviceLabel: String,
        onRegisteredName: ((String) -> Unit)?,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // NsdManager may append " (2)" to resolve name conflicts.
                // Log the actual name so we can debug picker-visibility issues.
                Logger.i("mDNS registered: $serviceLabel as '${serviceInfo.serviceName}'")
                onRegisteredName?.invoke(serviceInfo.serviceName)
                onSuccess()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Error codes from NsdManager:
                //   FAILURE_ALREADY_ACTIVE (3) — already registered; treat as success
                //   FAILURE_MAX_LIMIT (4)      — too many services (should not happen)
                //   FAILURE_INTERNAL_ERROR (0) — system mDNS daemon issue
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    Logger.w("mDNS $serviceLabel already active — treating as success")
                    onSuccess()
                } else {
                    Logger.e("mDNS registration FAILED for $serviceLabel, errorCode=$errorCode")
                    isStarted = false
                    onFailure()
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Logger.d("mDNS unregistered: $serviceLabel")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Non-fatal: the service will expire via mDNS TTL (~4500ms by default)
                Logger.w("mDNS unregistration failed for $serviceLabel, errorCode=$errorCode (non-fatal)")
            }
        }
    }

    companion object {
        /** Standard mDNS service type for AirPlay receivers. */
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp"

        /** Standard mDNS service type for RAOP (audio). Required alongside AirPlay. */
        private const val SERVICE_TYPE_RAOP = "_raop._tcp"

        /** AirPlay RTSP port — [RtspHandler] must listen on this port. */
        const val AIRPLAY_PORT = 7000

        /**
         * AirPlay feature bitmask: advertise screen mirroring, video, and audio support.
         * See TECHNICAL_SPEC.md §8 for the full bit-level breakdown.
         */
        private const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"

        /** Pretend to be an Apple TV so macOS uses the screen mirroring protocol. */
        private const val AIRPLAY_MODEL = "AppleTV5,3"

        /** Kept consistent with [AIRPLAY_MODEL] — senders pair the two when they probe a device. */
        private const val AIRPLAY_MANUFACTURER = "Apple"

        /** AirPlay 2 protocol version, as advertised by a real Apple TV. */
        private const val AIRPLAY_PROTOCOL_VERSION = "1.1"

        /** AirPlay server version — matches a real Apple TV for maximum compatibility. */
        private const val AIRPLAY_SERVER_VERSION = "220.68"
    }
}
