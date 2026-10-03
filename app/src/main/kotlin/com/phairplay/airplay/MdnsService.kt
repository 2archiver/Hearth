package com.phairplay.airplay

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
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
 * ─── The three things that used to make discovery fail on a real TV ─────────────────────
 *
 * 1. **The calling thread needs a Looper.** [NsdManager] attaches its registration
 *    callbacks to the Looper of the thread that calls `registerService`, and throws if
 *    that thread has none. Receivers are started on a coroutine IO thread, which has no
 *    Looper, so registration never completed and AirPlay stayed in ERROR — while the RTSP
 *    port stayed shut behind it. [start] therefore has to run on a thread with a Looper;
 *    [AirPlayReceiver] hops to the main one for exactly this call.
 *
 * 2. **Wi-Fi multicast has to be switched on.** Before SDK extension T7 (Android 13 and
 *    below without the extension) the platform filters multicast packets away unless the app
 *    holds a [WifiManager.MulticastLock]. An mDNS *advertisement* needs that too: without it
 *    the registration "succeeds", the queries never reach us, and no sender ever sees the TV.
 *    The lock is held for as long as we advertise. On Ethernet it is a no-op, so wiring a TV
 *    up with a cable no longer changes the outcome.
 *
 * 3. **A wired TV boots before its link does.** With "start on boot" the service comes up
 *    around the same moment DHCP does, so the first registration can land on an interface
 *    that has no address yet — and Android will not republish it later. A
 *    [ConnectivityManager] default-network callback re-advertises whenever the default
 *    network appears or its address changes, which also covers a Wi-Fi ↔ Ethernet swap.
 *
 * The service name shown in AirPlay pickers is determined by [displayNameOverride]:
 * - If set: uses the user-configured name from Settings
 * - If blank/null: falls back to [NetworkUtils.getDeviceName]
 *
 * State changes are reported via [onStateChange].
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
    private val onActualNameRegistered: (String) -> Unit = {},
    /**
     * Called with a one-line, user-readable note about the *quality* of the advertisement —
     * which interface and address the record went out on, or what half of it failed to
     * register. Null-ish news is reported too so the UI can clear the line.
     */
    private val onAdvertiseNotice: (String?) -> Unit = {}
) {

    // Android's built-in mDNS manager — handles multicast registration
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Listeners track registration state; held to enable unregistration later
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null

    // True once the *required* half (`_airplay._tcp`) is live, which is what makes the TV
    // appear in a picker. `_raop._tcp` only adds audio-only senders, so its failure is a
    // degraded advertisement rather than a dead one.
    @Volatile
    private var airPlayRegistered = false

    @Volatile
    private var raopRegistered = false

    // Guard against double-start
    @Volatile
    private var isStarted = false

    // The name we requested to register — compared against the actual registered name
    // in onServiceRegistered to detect mDNS collision auto-renaming.
    @Volatile
    private var requestedName: String = ""

    // Wi-Fi multicast filter — see the class docs (point 2).
    private var multicastLock: WifiManager.MulticastLock? = null

    // Default-network watcher — see the class docs (point 3).
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Timestamp of the last (re)advertisement, used to debounce network callbacks. */
    @Volatile
    private var lastAdvertisedAt = 0L

    /**
     * Starts mDNS advertising.
     *
     * Registers both the `_airplay._tcp` and `_raop._tcp` services, takes the Wi-Fi
     * multicast lock, and starts watching the default network.
     *
     * Must be called on a thread with a [android.os.Looper] (see the class docs);
     * [AirPlayReceiver] does that hop. Idempotent: calling it twice without [stop] in
     * between is a no-op.
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
        airPlayRegistered = false
        raopRegistered = false

        val effectiveName = resolveDisplayName(displayNameOverride)
        Logger.i("Starting mDNS advertising as '$effectiveName' on ${NetworkUtils.getNetworkSummary(context).label()}")
        requestedName = effectiveName

        acquireMulticastLock()
        registerAirPlayService(effectiveName)
        registerRaopService(effectiveName)
        watchDefaultNetwork()
        publishNotice()
    }

    /**
     * Stops mDNS advertising.
     *
     * Unregisters both mDNS services, drops the multicast lock and stops watching the
     * network. The device disappears from sender pickers within ~5-10 seconds (mDNS goodbye
     * packet sent immediately, but senders cache briefly).
     *
     * Safe to call even if [start] was never called.
     */
    fun stop() {
        Logger.i("Stopping mDNS advertising")
        try {
            stopWatchingNetwork()
            airPlayListener?.let { nsdManager.unregisterService(it) }
            raopListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            // Unregistration errors are non-fatal: service will expire via mDNS TTL
            Logger.e("Error unregistering mDNS services (non-fatal)", e)
        } finally {
            airPlayListener = null
            raopListener = null
            airPlayRegistered = false
            raopRegistered = false
            isStarted = false
            releaseMulticastLock()
            publishNotice()
            onStateChange(ProtocolState.DISABLED)
        }
    }

    /**
     * Restarts mDNS advertising.
     *
     * Used after a streaming session ends to immediately re-advertise the device
     * in sender pickers, and whenever the network the TV is on changes.
     *
     * @param displayNameOverride Updated display name, if changed in Settings.
     */
    fun restart(displayNameOverride: String? = null) {
        Logger.d("Restarting mDNS advertising")
        val name = displayNameOverride ?: requestedName
        stop()
        start(name)
    }

    /** True while the required `_airplay._tcp` record is published. */
    fun isAdvertising(): Boolean = isStarted && airPlayRegistered

    /**
     * Re-publishes the same records without a full stop/start, so a retry does not make the
     * status card flicker through "Disabled". Used by [AirPlayReceiver]'s backoff retry.
     */
    fun readvertise() = readvertise("retry requested", bypassCooldown = true)

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

            // The identity (model, feature bits, flags, `pk`, `pi`, versions) lives in one place
            // — [AirPlayIdentity] — so the record a sender browses cannot drift from the
            // `GET /info` reply it reads a second later. See that file for why this is a
            // legacy-pairing profile (bit 27 + model `AppleTV3,2`) rather than an AirPlay 2
            // Apple TV.
            AirPlayIdentity.airPlayTxt(context).forEach { (key, value) ->
                setAttribute(key, value)
            }
        }

        airPlayListener = createRegistrationListener(
            serviceLabel = SERVICE_TYPE_AIRPLAY,
            onRegisteredName = { actualName ->
                // Detect collision auto-renaming: NsdManager appended " (2)", " (3)", etc.
                if (actualName != requestedName) {
                    Logger.w("mDNS name collision detected: requested='$requestedName' " +
                             "actual='$actualName' — NsdManager resolved automatically")
                }
                onActualNameRegistered(actualName)
            },
            onSuccess = {
                airPlayRegistered = true
                publishNotice()
                // This is the record a picker actually browses: once it is live the TV is
                // findable, so do not hold the state back for the audio-only service.
                onStateChange(ProtocolState.ADVERTISING)
            },
            onFailure = {
                airPlayRegistered = false
                publishNotice()
                onStateChange(ProtocolState.ERROR)
            }
        )
        register(SERVICE_TYPE_AIRPLAY, serviceInfo, airPlayListener!!)
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

            // Audio-only senders (Apple Music, macOS system audio) read these before they will
            // stream: codecs, encryption types, metadata, and `pk`. Defined with the rest of
            // the identity in [AirPlayIdentity].
            AirPlayIdentity.raopTxt(context).forEach { (key, value) ->
                setAttribute(key, value)
            }
        }

        raopListener = createRegistrationListener(
            serviceLabel = SERVICE_TYPE_RAOP,
            onRegisteredName = null,  // RAOP name has MAC prefix — not shown to users
            onSuccess = {
                raopRegistered = true
                publishNotice()
            },
            // A missing RAOP record costs audio-only (AirPlay 1 / speaker) senders, not the
            // mirror. Report it on the card instead of tearing the whole protocol down —
            // that used to leave the TV showing "Error" while mirroring worked fine.
            onFailure = {
                raopRegistered = false
                publishNotice()
            }
        )
        register(SERVICE_TYPE_RAOP, serviceInfo, raopListener!!)
    }

    /**
     * Hands one service record to [NsdManager].
     *
     * Every failure mode here is the platform's, not the protocol's: a thread without a
     * Looper, a daemon that already holds the name, or a TV whose mDNS responder is unhappy.
     * All of them arrive as an exception out of `registerService` (rather than through the
     * listener), and an exception escaping here would abort receiver start-up before the RTSP
     * server is opened. So: report it as a failed registration and keep going.
     */
    private fun register(label: String, serviceInfo: NsdServiceInfo, listener: NsdManager.RegistrationListener) {
        runCatching { nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { error ->
                Logger.e(
                    "mDNS $label could not be submitted for registration (${error.javaClass.simpleName}: " +
                        "${error.message}) — the RTSP port stays open and the record is retried " +
                        "on the next network change or Restart",
                    error
                )
                if (label == SERVICE_TYPE_AIRPLAY) {
                    airPlayRegistered = false
                    onStateChange(ProtocolState.ERROR)
                } else {
                    raopRegistered = false
                }
                publishNotice()
            }
    }

    /**
     * One line for the status card: where we are advertising, or what is missing.
     *
     * "AirPlay is not working" on a wired TV is nearly always a question about the network,
     * and the answer is already known here — so say it instead of showing a bare
     * "Advertising".
     */
    private fun publishNotice() {
        val notice = when {
            !isStarted -> null
            !airPlayRegistered && !raopRegistered ->
                "Not advertised yet — this TV's mDNS responder refused the record. Check the network below, then Restart."
            !raopRegistered ->
                "Advertising for mirroring; the audio-only record (`_raop._tcp`) was refused, so speakers are unavailable."
            else -> "Advertising on ${NetworkUtils.getNetworkSummary(context).label()}"
        }
        onAdvertiseNotice(notice)
    }

    // ─── Wi-Fi multicast ─────────────────────────────────────────────────────

    /**
     * Takes the Wi-Fi multicast lock for as long as we advertise.
     *
     * Without it Android drops the multicast *requests* that an mDNS advertisement has to
     * answer, so senders never see us — the classic "my Mac doesn't list the TV" on anything
     * below SDK extension T7. Best-effort: a TV that refuses the lock still advertises on
     * Ethernet, where the filter does not apply.
     */
    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        val wifi = runCatching {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull()
        if (wifi == null) {
            Logger.d("mDNS: no WifiManager on this device — skipping the multicast lock")
            return
        }
        runCatching {
            val lock = wifi.createMulticastLock(MULTICAST_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
            Logger.d("mDNS: Wi-Fi multicast lock held while advertising")
        }.onFailure { error ->
            Logger.w("mDNS: could not take the Wi-Fi multicast lock (${error.message})")
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { Logger.d("mDNS: releasing the multicast lock failed (non-fatal): ${it.message}") }
    }

    // ─── Network watching ────────────────────────────────────────────────────

    /**
     * Re-advertises when the default network appears or changes address.
     *
     * WHY: on a wired Google TV the receiver service is up seconds before Ethernet has a
     * lease (boot, cable unplugged then plugged in, DHCP renew, Wi-Fi → Ethernet failover).
     * A record registered with no usable interface is never republished by itself, which is
     * why the same TV can be invisible for a reboot and then fine forever after.
     */
    private fun watchDefaultNetwork() {
        if (networkCallback != null) return
        val manager = runCatching {
            context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()
        if (manager == null) {
            Logger.d("mDNS: ConnectivityManager unavailable — network changes will not re-advertise")
            return
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = readvertise("network available")

            override fun onLost(network: Network) = readvertise("default network lost")

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val hasAddress = runCatching {
                    linkProperties.linkAddresses.any { it.address is java.net.Inet4Address }
                }.getOrDefault(true)
                if (hasAddress) readvertise("address changed")
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess {
                networkCallback = callback
                Logger.d("mDNS: watching the default network for changes")
            }
            .onFailure { error ->
                Logger.w("mDNS: could not watch the network (${error.message}) — a cable swap may need a Restart")
            }
    }

    private fun stopWatchingNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            (context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(callback)
        }.onFailure { Logger.d("mDNS: unregistering the network callback failed (non-fatal): ${it.message}") }
    }

    /**
     * Re-registers the advertisement, at most once every [READVERTISE_COOLDOWN_MS].
     *
     * Network callbacks arrive in bursts (lost → available → link properties). The cooldown
     * keeps a flap from tearing down and rebuilding the record over and over, and the
     * `isStarted` guard makes a late callback after [stop] a no-op.
     */
    private fun readvertise(reason: String, bypassCooldown: Boolean = false) {
        if (!isStarted) return
        val now = System.currentTimeMillis()
        if (!bypassCooldown && now - lastAdvertisedAt < READVERTISE_COOLDOWN_MS) return
        lastAdvertisedAt = now
        Logger.i("mDNS: $reason — re-advertising on the current network")
        val name = requestedName
        airPlayListener?.let { runCatching { nsdManager.unregisterService(it) } }
        raopListener?.let { runCatching { nsdManager.unregisterService(it) } }
        airPlayListener = null
        raopListener = null
        airPlayRegistered = false
        raopRegistered = false
        registerAirPlayService(name)
        registerRaopService(name)
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
                lastAdvertisedAt = System.currentTimeMillis()
                onSuccess()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Error codes from NsdManager:
                //   FAILURE_ALREADY_ACTIVE (3) — already registered; treat as success
                //   FAILURE_MAX_LIMIT (4)      — too many services (should not happen)
                //   FAILURE_INTERNAL_ERROR (0) — system mDNS daemon issue
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    Logger.w("mDNS $serviceLabel already active — treating as success")
                    lastAdvertisedAt = System.currentTimeMillis()
                    onSuccess()
                } else {
                    Logger.e("mDNS registration FAILED for $serviceLabel, errorCode=$errorCode")
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

        /** Tag the multicast lock reports under, so `dumpsys wifi` names us. */
        private const val MULTICAST_LOCK_TAG = "phairplay-mdns"

        /** Minimum gap between re-advertisements, so a network flap cannot spin the daemon. */
        private const val READVERTISE_COOLDOWN_MS = 5_000L

        // Every value a sender can read about this receiver — model, feature bits, flags, `pk`,
        // `pi`, versions — comes from [AirPlayIdentity]. It used to be spread across this file
        // and InfoResponder, and the two halves disagreed; see AirPlayIdentity's docs for why the
        // profile is a legacy-pairing AirPlay 2 receiver and not an AirPlay 2 Apple TV.
    }
}
