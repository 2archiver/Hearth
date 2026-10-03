package com.phairplay.miracast

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import android.view.Surface
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MiracastReceiver — Miracast (Wi-Fi Display / WFD) receiver service advertiser.
 *
 * WHY: Miracast allows Windows 10+ and Android devices to wirelessly mirror
 * their screen without being on the same Wi-Fi network. It uses Wi-Fi Direct
 * (P2P) to create a direct device-to-device connection.
 *
 * HOW:
 * - Check the TV can do Wi-Fi Direct at all (system feature, radio on, permission granted)
 * - Initialize WifiP2pManager + Channel
 * - Register a `_wfd._tcp` local service AND keep Wi-Fi Direct discovery
 *   running (local services are only broadcast while discovery runs — without
 *   discoverPeers() the receiver was never findable by senders)
 * - Serve the WFD RTSP control plane on port 7236 ([WfdRtspServer])
 * - After PLAY, decode incoming RTP/H.264 video onto the streaming Surface
 *   ([WfdVideoRenderer]) so a connected sender produces a real picture
 *
 * Miracast protocol stack:
 *   Wi-Fi Direct (P2P) → WFD RTSP → RTP/H.264 → MediaCodec → SurfaceView
 *
 * ─── Why this reports UNAVAILABLE rather than ERROR ──────────────────────────────────────
 *
 * Receiving Miracast needs to own a Wi-Fi Direct group, and on Google TV that is the system's
 * job, not an app's: a TV wired with Ethernet usually has Wi-Fi switched off entirely, and many
 * Android TV builds keep `WifiP2pManager` reserved for the platform. In both cases there is
 * nothing the user did wrong and nothing in PhairPlay to fix — so this says "not available on
 * this TV" and leaves the AirPlay path alone. PhairPlay deliberately does not start this
 * receiver unless it is switched on in Settings (see [com.phairplay.settings.AppSettings.miracastEnabled]).
 *
 * IMPORTANT LIMITATIONS (see ADR-001):
 * - Miracast requires Wi-Fi Direct, which some Android TV devices disable
 * - The WFD stack on Android TV is partly hidden (system APIs)
 * - Real-world compatibility must be tested on actual hardware
 * - Video only — WFD audio is negotiated but not rendered (see docs/guides/MIRACAST.md)
 *
 * Example:
 *   val receiver = MiracastReceiver(context, { surface }) { state -> updateUI(state) }
 *   receiver.start()  // begins P2P service advertisement
 *   receiver.stop()   // stops advertisement and closes session
 */
class MiracastReceiver(
    private val context: Context,
    // Supplies the activity's streaming Surface for hardware video decode.
    // Defaults to { null } so unit tests (and headless starts) stay surface-free.
    private val videoSurfaceProvider: () -> Surface? = { null },
    /** Why the state is what it is, for the status card. Declared before [onStateChanged]
     *  so the trailing-lambda call sites keep binding to the state callback. */
    private val onNotice: (String?) -> Unit = {},
    private val onStateChanged: (ProtocolState) -> Unit
) {

    // Android's Wi-Fi P2P manager — the entry point for all Wi-Fi Direct operations
    private var wifiP2pManager: WifiP2pManager? = null

    // The communication channel between the app and the P2P framework
    private var channel: Channel? = null

    // The local DNS-SD service record advertised through Wi-Fi Direct.
    private var serviceInfo: WifiP2pDnsSdServiceInfo? = null

    // Whether the P2P service advertisement is currently active
    @Volatile
    private var isAdvertising = false

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val rtspServer = WfdRtspServer(
        onSessionStarted = {
            Logger.i("Miracast WFD session connected")
            onStateChanged(ProtocolState.CONNECTED)
        },
        onSessionStopped = {
            Logger.i("Miracast WFD session stopped")
            if (isAdvertising) onStateChanged(ProtocolState.ADVERTISING)
        },
        videoSurfaceProvider = videoSurfaceProvider
    )

    /**
     * Starts the Miracast receiver.
     *
     * - Checks this TV can host a Wi-Fi Direct group at all
     * - Initializes the WifiP2pManager and Channel
     * - Registers a local Wi-Fi Direct DNS-SD WFD service
     * - Starts Wi-Fi Direct peer discovery (required for the service to be visible)
     * - Opens the WFD RTSP control server on port 7236
     */
    fun start() {
        Logger.i("MiracastReceiver starting")
        unavailableReason()?.let { reason ->
            // Not an error: the TV simply cannot play this part. Say why, and stop.
            Logger.w("Miracast not started — $reason")
            onNotice(reason)
            onStateChanged(ProtocolState.UNAVAILABLE)
            return
        }
        initializeWifiP2p()
    }

    /**
     * Stops the Miracast receiver.
     *
     * Unregisters P2P service, stops peer discovery, disconnects any active WFD
     * session, and releases the WifiP2pManager channel.
     */
    fun stop() {
        Logger.i("MiracastReceiver stopping")
        try {
            stopP2pAdvertisement()
            stopPeerDiscovery()
            rtspServer.stop()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                channel?.close()
            }
        } catch (e: Exception) {
            Logger.e("Error stopping MiracastReceiver (non-fatal)", e)
        } finally {
            wifiP2pManager = null
            channel = null
            isAdvertising = false
            job.cancel()
            onNotice(null)
            onStateChanged(ProtocolState.DISABLED)
        }
    }

    // ─── Availability ────────────────────────────────────────────────────────

    /**
     * Explains why Miracast cannot run here, or null when it can try.
     *
     * Ordered from "this device has no such radio" down to "the user has not granted it yet",
     * because the first is permanent and the last is fixable from the TV's settings screen.
     */
    private fun unavailableReason(): String? {
        if (!hasWifiDirectFeature()) {
            return "This TV has no Wi-Fi Direct, so it cannot receive Miracast."
        }
        if (!isWifiRadioOn()) {
            return "Wi-Fi is off, and Miracast needs the Wi-Fi radio for its direct connection — " +
                "a TV wired with Ethernet has no way to receive Miracast."
        }
        if (!hasWifiP2pPermission()) {
            return "Waiting for the nearby-Wi-Fi permission — allow it in the TV's app settings."
        }
        return null
    }

    /**
     * Whether the hardware even claims Wi-Fi Direct.
     *
     * Every probe here defaults to "yes, go ahead", because the alternative — telling the user
     * their TV cannot do something because a lookup was unreadable — is the worse failure. The
     * real answer comes from the P2P calls below, and their failure codes are reported as-is.
     */
    private fun hasWifiDirectFeature(): Boolean = runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    }.getOrDefault(true)

    private fun isWifiRadioOn(): Boolean = runCatching {
        (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled ?: true
    }.getOrDefault(true)

    /**
     * NEARBY_WIFI_DEVICES (Android 13+) or ACCESS_FINE_LOCATION (below it) — either is enough
     * for the Wi-Fi P2P API, which is what this app declares in its manifest.
     */
    private fun hasWifiP2pPermission(): Boolean = runCatching {
        context.checkSelfPermission(PERMISSION_NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(PERMISSION_ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(true)

    // ─── P2P setup ───────────────────────────────────────────────────────────

    /**
     * Initializes the WifiP2pManager and Channel.
     *
     * The [WifiP2pManager] is retrieved from Android's system services.
     * The [Channel] is the app's communication link to the P2P framework.
     *
     * If Wi-Fi Direct is not available on this device (some Android TV boxes
     * don't support it), the state goes [ProtocolState.UNAVAILABLE] with the reason —
     * never a red error the user cannot act on.
     */
    private fun initializeWifiP2p() {
        val manager = runCatching {
            context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        }.getOrNull()
        if (manager == null) {
            Logger.w("WifiP2pManager not available on this device — Miracast not supported")
            val reason = "This TV's Android build does not expose Wi-Fi Direct to apps."
            onNotice(reason)
            onStateChanged(ProtocolState.UNAVAILABLE)
            return
        }
        wifiP2pManager = manager

        // Initialize the channel: connects the app to the Wi-Fi P2P framework
        // The looper parameter specifies which thread receives P2P framework callbacks
        channel = runCatching {
            manager.initialize(
                context,
                context.mainLooper,
                object : WifiP2pManager.ChannelListener {
                    override fun onChannelDisconnected() {
                        // P2P framework disconnected — this happens if Wi-Fi is turned off
                        // while we advertise. The radio, not PhairPlay, is in charge here.
                        Logger.w("WifiP2p channel disconnected")
                        isAdvertising = false
                        onNotice("Wi-Fi Direct was switched off by the TV while Miracast was advertising.")
                        onStateChanged(ProtocolState.UNAVAILABLE)
                    }
                }
            )
        }.getOrNull()

        if (channel == null) {
            val reason = "The TV refused to open a Wi-Fi Direct channel, so Miracast cannot run."
            Logger.w("Miracast: WifiP2pManager.initialize returned no channel")
            onNotice(reason)
            onStateChanged(ProtocolState.UNAVAILABLE)
            return
        }

        Logger.i("WifiP2pManager initialized — registering P2P service")
        registerP2pService()
    }

    /**
     * Stops the P2P service advertisement.
     */
    private fun stopP2pAdvertisement() {
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        val activeService = serviceInfo ?: return
        if (!isAdvertising) return

        try {
            manager.removeLocalService(
                activeChannel,
                activeService,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Logger.d("P2P service advertisement stopped")
                    }

                    override fun onFailure(reason: Int) {
                        Logger.w("P2P service removal failed, reason=$reason (non-fatal)")
                    }
                }
            )
        } catch (e: SecurityException) {
            Logger.e("Missing Wi-Fi P2P permission while removing Miracast service", e)
        }
        serviceInfo = null
        isAdvertising = false
    }

    /**
     * Registers the WFD local service record used by Wi-Fi Direct discovery.
     *
     * Android exposes Wi-Fi Direct service discovery through DNS-SD TXT records.
     * Miracast senders look for `_wfd._tcp` and then continue with WFD capability
     * negotiation over RTSP after the P2P group is formed.
     */
    private fun registerP2pService() {
        val manager = wifiP2pManager
        val activeChannel = channel
        if (manager == null || activeChannel == null) {
            Logger.w("Cannot register Miracast P2P service before Wi-Fi P2P initialization")
            onNotice("Wi-Fi Direct is not ready yet — try Restart.")
            onStateChanged(ProtocolState.UNAVAILABLE)
            return
        }
        if (!hasWifiP2pPermission()) {
            val reason = "Waiting for the nearby-Wi-Fi permission — allow it in the TV's app settings."
            Logger.w("Cannot register Miracast P2P service: missing Wi-Fi Direct permission")
            onNotice(reason)
            onStateChanged(ProtocolState.UNAVAILABLE)
            return
        }

        val txtRecord = mapOf(
            "wfd_device_type" to "primary_sink",
            "wfd_session_available" to "1",
            "wfd_rtsp_port" to WFD_RTSP_PORT.toString(),
            "wfd_video_formats" to "h264-chp,h264-cbp",
            "wfd_audio_codecs" to "lpcm"
        )
        val localService = WifiP2pDnsSdServiceInfo.newInstance(
            SERVICE_INSTANCE_NAME,
            SERVICE_TYPE_WFD,
            txtRecord
        )

        try {
            manager.addLocalService(
                activeChannel,
                localService,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        serviceInfo = localService
                        isAdvertising = true
                        rtspServer.start(scope)
                        startPeerDiscovery()
                        Logger.i("Miracast WFD P2P service advertised")
                        onNotice(null)
                        onStateChanged(ProtocolState.ADVERTISING)
                    }

                    override fun onFailure(reason: Int) {
                        serviceInfo = null
                        isAdvertising = false
                        Logger.e("Miracast WFD P2P service registration failed, reason=$reason")
                        // A rejected registration is the platform saying no to an app owning a
                        // P2P group — the usual answer on Android TV. Report it as unavailable
                        // with the reason, not as a PhairPlay failure.
                        onNotice("The TV's Wi-Fi Direct stack rejected the Miracast service " +
                                 "(reason $reason). Most Google TVs only let the system open a " +
                                 "Miracast group.")
                        onStateChanged(ProtocolState.UNAVAILABLE)
                    }
                }
            )
        } catch (e: SecurityException) {
            serviceInfo = null
            isAdvertising = false
            Logger.e("Missing Wi-Fi P2P permission while registering Miracast service", e)
            onNotice("Waiting for the nearby-Wi-Fi permission — allow it in the TV's app settings.")
            onStateChanged(ProtocolState.UNAVAILABLE)
        }
    }

    /**
     * Starts (and keeps refreshing) Wi-Fi Direct peer discovery.
     *
     * WHY: a registered local service is only broadcast while discovery is running —
     * without discoverPeers() senders never see the `_wfd._tcp` record and the
     * receiver was "advertising" to nobody. Android also stops discovery
     * periodically, so it is re-triggered on an interval while advertising.
     *
     * Discovery failures are non-fatal: the local service stays registered and the
     * next refresh retries (no fake ERROR state for a transient scan hiccup).
     */
    private fun startPeerDiscovery() {
        requestPeerDiscovery()
        scope.launch {
            while (isActive && isAdvertising) {
                delay(DISCOVERY_REFRESH_MS)
                if (!isAdvertising) break
                requestPeerDiscovery()
            }
        }
    }

    private fun requestPeerDiscovery() {
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        if (!hasWifiP2pPermission()) return  // reported during registration
        try {
            manager.discoverPeers(
                activeChannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Logger.d("Wi-Fi Direct discovery running")
                    }

                    override fun onFailure(reason: Int) {
                        // Transient — retried by the refresh loop; keep ADVERTISING.
                        Logger.w("Wi-Fi Direct discoverPeers failed, reason=$reason (will retry)")
                    }
                }
            )
        } catch (e: SecurityException) {
            Logger.e("Missing Wi-Fi P2P permission while starting discovery", e)
        }
    }

    private fun stopPeerDiscovery() {
        val manager = wifiP2pManager ?: return
        val activeChannel = channel ?: return
        if (!hasWifiP2pPermission()) return
        try {
            // WifiP2pManager's API is stopPeerDiscovery(Channel, ActionListener) — there is no
            // cancelDiscoverPeers (that name belongs to NsdManager/Bluetooth discovery).
            manager.stopPeerDiscovery(
                activeChannel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Logger.d("Wi-Fi Direct discovery stopped")
                    }

                    override fun onFailure(reason: Int) {
                        Logger.d("Wi-Fi Direct discovery cancel failed, reason=$reason (non-fatal)")
                    }
                }
            )
        } catch (e: SecurityException) {
            Logger.e("Missing Wi-Fi P2P permission while stopping discovery", e)
        }
    }

    companion object {
        const val WFD_RTSP_PORT = 7236

        /** Android stops P2P discovery on its own — refresh while advertising. */
        private const val DISCOVERY_REFRESH_MS = 30_000L

        private const val SERVICE_INSTANCE_NAME = "PhairPlay"
        private const val SERVICE_TYPE_WFD = "_wfd._tcp"
        private const val PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
        private const val PERMISSION_NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    }
}
