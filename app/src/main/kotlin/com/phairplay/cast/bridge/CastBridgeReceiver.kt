package com.phairplay.cast.bridge

import android.content.Context
import android.media.AudioManager
import android.view.Surface
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import java.io.IOException
import java.net.BindException
import java.net.ServerSocket

/**
 * CastBridgeReceiver — PhairPlay's own Google Cast receiver.
 *
 * WHY THIS EXISTS
 * ---------------
 * Google's Cast Connect SDK (see com.phairplay.cast.CastReceiver) refuses to start without
 * a Cast Application ID, which Google only issues after you register the app in the Cast
 * SDK Developer Console and associate the package name `com.phairplay.googletv` with it.
 * Until that happens the Cast card in PhairPlay can only ever show an error — which is
 * exactly what happened in 1.2.
 *
 * So PhairPlay ships a receiver that speaks the Cast protocol itself, the way a Chromecast
 * does:
 *
 *   mDNS `_googlecast._tcp`  ← how the iPhone's Cast picker finds us
 *   DIAL over HTTP :8008     ← "what kind of device are you, and what is running?"
 *   castv2 over TLS :8009    ← CONNECT → GET_STATUS → LAUNCH → LOAD <url> → PLAY
 *   MediaPlayer              ← actually plays the URL on the TV
 *
 * No registration, no Google account, no app ID. Senders that cast a media URL (VLC, Plex,
 * Chrome, file and photo apps, and anything using the Default Media Receiver) work as-is;
 * senders that require their own registered receiver with a private namespace — YouTube is
 * the big one — can launch here but speak a protocol we cannot emulate.
 *
 * ETHernet is not special here: nothing in this receiver assumes Wi-Fi. If discovery fails
 * on a wired Google TV the cause is almost always multicast not crossing between the wired
 * and wireless segments of the network, which no app can fix — PhairPlay's Home screen
 * shows the interface and IP it is advertising on so that is diagnosable.
 *
 * @param displayName    the name shown in the sender's Cast picker
 * @param surfaceProvider supplies the Surface to render video onto
 */
internal class CastBridgeReceiver(
    private val context: Context,
    private val displayName: String,
    private val surfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    /** Mirrors the player's state to the service, which feeds the debug HUD and the video overlay. */
    private val onMediaChanged: (state: String, positionSec: Double, durationSec: Double) -> Unit = { _, _, _ -> },
    /** Good news / explanations worth showing on the Cast card (e.g. "handed YouTube to the TV"). */
    private val onNotice: (String) -> Unit = {}
) {

    @Volatile private var running = false

    private var player: CastMediaPlayer? = null
    private var app: CastReceiverApp? = null
    private var dialServer: DialHttpServer? = null
    private var castServer: CastV2Server? = null
    private var advertiser: CastMdnsAdvertiser? = null
    private var ssdp: SsdpResponder? = null

    /** User-facing explanation of the last failure, shown on the Cast card. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Good news worth showing on the Cast card — see [occupiedPorts].
     *
     * Not an error: on a Google TV the built-in Chromecast owns the Cast ports, which means
     * the TV is already a Cast target and PhairPlay simply does not need to run a receiver.
     * Reporting that as a red "Error" would be alarming *and* wrong, so it travels as a note
     * on a healthy (advertising) state instead.
     */
    @Volatile var lastNotice: String? = null
        private set

    @Volatile var lastRegisteredName: String? = null
        private set

    fun isRunning(): Boolean = running

    /** Re-attaches the streaming surface after the Activity recreates it. */
    fun attachSurface() {
        player?.attachSurface()
    }

    /**
     * Starts every piece of the bridge.
     *
     * Thread-safe and idempotent. On failure the reason is recorded in [lastError] and the
     * state reported is [ProtocolState.ERROR] — never a silent "advertising" that would
     * leave the user wondering why their iPhone cannot see the TV.
     */
    @Synchronized
    fun start() {
        if (running) return
        lastError = null
        lastNotice = null

        val blocked = occupiedPorts()
        if (blocked.isNotEmpty()) {
            // Almost always the TV's own Chromecast. Senders always dial 8008/8009, so
            // there is no port we could move to — and there is no need to: Cast already
            // works on this TV, just not through PhairPlay.
            lastNotice = "Ports ${blocked.joinToString()} are already in use — this TV's " +
                "built-in Chromecast is handling Cast, so PhairPlay's receiver stays off. " +
                "To receive through PhairPlay instead, disable 'Chromecast built-in' in the " +
                "TV's app settings."
            Logger.i("Cast bridge: ports ${blocked.joinToString()} busy — native Chromecast present")
            onStateChanged(ProtocolState.ADVERTISING)
            return
        }

        try {
            val mediaPlayer = CastMediaPlayer(
                surfaceProvider = surfaceProvider,
                onStateChanged = { state, positionSec, durationSec ->
                    app?.broadcastMediaStatus()
                    // The debug HUD shows player state + position for a Cast session (there are
                    // no frames to count — ExoPlayer/MediaPlayer renders straight to the Surface).
                    onMediaChanged(state, positionSec, durationSec)
                }
            )
            val receiverApp = CastReceiverApp(
                displayName = displayName,
                player = mediaPlayer,
                onVolumeChanged = { level, muted -> applyVolume(level, muted) },
                onSessionChanged = { active ->
                    if (active) onStateChanged(ProtocolState.CONNECTED)
                    else onStateChanged(ProtocolState.ADVERTISING)
                },
                // Reported on the Cast card: an app that launched here and then spoke a private
                // channel is not a PhairPlay bug, and saying so is better than a black screen.
                onUnsupportedNamespace = { notice ->
                    Logger.i("Cast: unsupported private channel — $notice")
                    onNotice(notice)
                }
            )

            // Hand a DIAL launch to an app that is already installed on this TV when we
            // recognise the name; everything else is played by PhairPlay itself.
            val appLauncher: DialAppLauncher = DialAppRouter(context)

            val dial = DialHttpServer(
                port = DialHttpServer.DEFAULT_PORT,
                deviceName = { displayName },
                localIpAddress = { NetworkUtils.getLocalIpAddress() ?: "127.0.0.1" },
                onLaunch = { appId, handedOff ->
                    if (handedOff) {
                        val message = "\"$appId\" is being played by this TV's own app — " +
                            "PhairPlay only started it."
                        Logger.i("Cast: DIAL launch handed to the TV's own app ($appId)")
                        onNotice(message)
                    } else {
                        receiverApp.launch(appId)
                    }
                },
                onStop = { receiverApp.stopSession() },
                appLauncher = appLauncher
            )
            dial.start()

            // DIAL discovery: mDNS `_googlecast._tcp` is what the Cast SDK browses, but
            // Netflix/YouTube/Windows find devices by sending SSDP M-SEARCH to port 1900.
            val discovery = SsdpResponder(
                context = context,
                locationUrl = { dial.deviceDescriptionUrl },
                deviceUuid = { dial.deviceUuid }
            )
            val ssdpListening = discovery.start()
            discovery.lastError?.let { onNotice(it) }
            if (!ssdpListening) {
                Logger.w("Cast: DIAL-only senders will not discover this TV over SSDP")
            }
            ssdp = discovery

            val cast = CastV2Server(
                port = CastV2Server.DEFAULT_PORT,
                keystoreOpener = { context.assets.open(KEYSTORE_ASSET) },
                keystorePassword = KEYSTORE_PASSWORD.toCharArray(),
                app = receiverApp
            )
            cast.start()

            val mdns = CastMdnsAdvertiser(
                context = context,
                onRegistered = { name ->
                    lastRegisteredName = name
                    onStateChanged(ProtocolState.ADVERTISING)
                },
                onError = { code ->
                    lastError = "Could not advertise Cast on this network (mDNS error $code)."
                    onStateChanged(ProtocolState.ERROR)
                }
            )
            if (!mdns.start(displayName)) {
                lastError = "Could not advertise Cast on this network."
            }

            player = mediaPlayer
            app = receiverApp
            dialServer = dial
            castServer = cast
            advertiser = mdns
            running = true
            Logger.i("Cast bridge started as '$displayName' (DIAL ${DialHttpServer.DEFAULT_PORT}, castv2 ${CastV2Server.DEFAULT_PORT})")
            if (lastError == null) onStateChanged(ProtocolState.ADVERTISING)
        } catch (e: BindException) {
            lastError = "Another app on this TV is already using the Cast ports."
            Logger.e("Cast bridge: port already bound", e)
            stopQuietly()
            onStateChanged(ProtocolState.ERROR)
        } catch (e: IOException) {
            lastError = "Could not start the Cast receiver: ${e.message}"
            Logger.e("Cast bridge: failed to start", e)
            stopQuietly()
            onStateChanged(ProtocolState.ERROR)
        } catch (e: Exception) {
            lastError = "Could not start the Cast receiver: ${e.message}"
            Logger.e("Cast bridge: failed to start", e)
            stopQuietly()
            onStateChanged(ProtocolState.ERROR)
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        Logger.i("Cast bridge stopping")
        stopQuietly()
        onStateChanged(ProtocolState.DISABLED)
    }

    private fun stopQuietly() {
        try {
            advertiser?.stop()
            castServer?.stop()
            dialServer?.stop()
            ssdp?.stop()
            player?.release()
        } catch (e: Exception) {
            Logger.w("Cast bridge: error while stopping (non-fatal): ${e.message}")
        }
        advertiser = null
        castServer = null
        dialServer = null
        ssdp = null
        player = null
        app = null
    }

    /** Maps a Cast volume (0..1) onto the TV's music stream. Best-effort only. */
    private fun applyVolume(level: Double, muted: Boolean) {
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return
            val target = if (muted) 0 else (level * max).toInt().coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: SecurityException) {
            Logger.w("Cast: this TV does not let PhairPlay change the volume")
        } catch (e: Exception) {
            Logger.w("Cast: could not apply volume: ${e.message}")
        }
    }

    /**
     * Returns the Cast ports something else already owns.
     *
     * A Google TV with Chromecast built-in has a real receiver on 8008/8009; binding
     * "successfully" is impossible there and a bare BindException is an awful error message,
     * so check first and say what the clash actually is.
     */
    private fun occupiedPorts(): List<Int> {
        val ports = listOf(DialHttpServer.DEFAULT_PORT, CastV2Server.DEFAULT_PORT)
        return ports.filter { port ->
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(java.net.InetSocketAddress(port))
                }
                false
            } catch (e: IOException) {
                true
            }
        }
    }

    companion object {
        /** Self-signed TLS keystore for the castv2 channel (senders accept any certificate). */
        private const val KEYSTORE_ASSET = "castv2-server.p12"
        private const val KEYSTORE_PASSWORD = "phairplay"
    }
}
