package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI
import java.util.ArrayDeque
import kotlinx.coroutines.channels.Channel

/**
 * DacpClient — sends transport commands from the TV back to the AirPlay sender (DACP, Digital Audio
 * Control Protocol). This is the "reverse" control channel: the TV remote can play/pause/skip what
 * the Mac/iPhone is streaming.
 *
 * The sender includes two RTSP headers during the session:
 *   • `DACP-ID`       — identifies its DACP service, advertised over mDNS as `iTunes_Ctrl_<DACP-ID>`
 *   • `Active-Remote` — an auth token we must echo on every command
 *
 * We discover + resolve that `_dacp._tcp` service to an `host:port`, then issue
 * `GET http://host:port/ctrl-int/1/<command>` with the `Active-Remote` header. Commands include
 * `playpause`, `nextitem`, `previtem`, `beginff`, `beginrew`, `volumeup`, `volumedown`, `mutetoggle`.
 *
 * Reference: DACP (iTunes remote) over the AirPlay reverse channel.
 */
class DacpClient(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * NsdManager binds its discovery/resolve callbacks to the Looper of the calling thread and
     * throws on a thread that has none — so every NsdManager call runs here, on the main
     * looper. The callbacks only read a resolved address and stop a scan; the HTTP commands
     * themselves stay on [scope].
     */
    private val nsdScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    @Volatile private var dacpId: String? = null
    @Volatile private var activeRemote: String? = null
    @Volatile private var resolvedHost: String? = null
    @Volatile private var resolvedPort: Int = 0
    private val commands = Channel<() -> Unit>(capacity = 8)
    private val pending = ArrayDeque<Pair<Long, String>>()
    @Volatile private var generation = 0L
    init { scope.launch { for (send in commands) send() } }
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Supplies the sender's DACP identity (from RTSP `DACP-ID` / `Active-Remote` headers). Kicks off
     * mDNS discovery the first time a new sender is seen; cheap no-op on repeats.
     */
    fun configure(newDacpId: String?, newActiveRemote: String?) {
        nsdScope.launch {
            val id = newDacpId?.let(DacpServiceIdentity::normalize) ?: dacpId
            val token = newActiveRemote?.trim()?.takeIf { it.isNotEmpty() }
                ?: activeRemote.takeIf { dacpId == null || id == dacpId }
            if (id == dacpId && token == activeRemote && discoveryListener != null) return@launch
            if (id != dacpId || token != activeRemote) {
                generation++
                pending.clear()
                resolvedHost = null; resolvedPort = 0
            }
            dacpId = id; activeRemote = token
            if (id != null && token != null && resolvedHost == null) {
                startDiscoveryOnLooperThread("iTunes_Ctrl_$id")
            }
        }
    }

    val isAvailable: Boolean get() = activeRemote != null && resolvedHost != null && resolvedPort > 0

    /** A short bounded queue preserves the first press while Bonjour resolution is finishing. */
    fun sendCommand(command: String) {
        if (command !in SUPPORTED_COMMANDS) return
        nsdScope.launch {
            if (activeRemote == null || dacpId == null) {
                Logger.w("AirPlay remote unavailable: sender has not supplied DACP control")
                return@launch
            }
            if (!isAvailable) {
                val now = System.nanoTime()
                while (pending.isNotEmpty() && now - pending.first.first > PENDING_NANOS) pending.removeFirst()
                if (pending.size < 4) pending.addLast(now to command)
                if (discoveryListener == null) startDiscoveryOnLooperThread("iTunes_Ctrl_$dacpId")
                return@launch
            }
            enqueueResolved(command)
        }
    }

    private fun enqueueResolved(command: String) {
        val token = activeRemote ?: return
        val host = resolvedHost ?: return
        val port = resolvedPort
        val ticket = generation
        commands.trySend {
            if (ticket == generation) runCatching {
                val url = URI("http", null, host, port, "/ctrl-int/1/$command", null, null).toURL()
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Active-Remote", token)
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    Logger.i("AirPlay remote $command: HTTP ${conn.responseCode}")
                } finally { conn.disconnect() }
            }.onFailure {
                Logger.w("AirPlay remote $command failed (${it.javaClass.simpleName})")
                nsdScope.launch {
                    if (ticket == generation) {
                        resolvedHost = null; resolvedPort = 0
                        // Re-resolve on the next press; never replay a possibly delivered skip.
                    }
                }
            }
        }
    }

    fun reset() {
        nsdScope.launch {
            generation++
            pending.clear()
            dacpId = null; activeRemote = null; resolvedHost = null; resolvedPort = 0
            stopDiscoveryNow()
        }
    }

    fun stop() {
        // Cancel first so a queued stop cannot be dropped: the launch below would never run
        // after the scope is gone, and an abandoned scan keeps the multicast listener alive.
        generation++
        commands.close()
        stopDiscoveryNow()
        nsdScope.cancel()
        scope.cancel()
    }

    private fun startDiscoveryOnLooperThread(targetName: String) {
        stopDiscoveryNow()
        val ticket = generation
        var resolving = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (ticket == generation && !resolving && DacpServiceIdentity.matches(info.serviceName, targetName)) {
                    resolving = true
                    runCatching { nsdManager.resolveService(info, resolveListener(ticket) { resolving = false }) }
                        .onFailure { resolving = false }
                }
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (discoveryListener === this) discoveryListener = null
                Logger.w("DACP discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(DACP_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                if (discoveryListener === listener) discoveryListener = null
                Logger.w("DACP discovery failed to start (${it.javaClass.simpleName})")
            }
    }

    private fun resolveListener(ticket: Long, finished: () -> Unit) = object : NsdManager.ResolveListener {
        override fun onServiceResolved(info: NsdServiceInfo) {
            finished()
            if (ticket != generation) return
            resolvedHost = info.host?.hostAddress
            resolvedPort = info.port
            Logger.i("DACP resolved: $resolvedHost:$resolvedPort")
            if (isAvailable) {
                val now = System.nanoTime()
                while (pending.isNotEmpty()) {
                    val (created, command) = pending.removeFirst()
                    if (now - created <= PENDING_NANOS) enqueueResolved(command)
                }
                stopDiscovery()
            }
        }
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            finished()
            Logger.w("DACP resolve failed: $errorCode")
        }
    }

    private fun stopDiscovery() {
        nsdScope.launch { stopDiscoveryNow() }
    }

    /** Must run on the same looper that started the scan; [stopDiscovery] does that hop. */
    private fun stopDiscoveryNow() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    companion object {
        private const val DACP_SERVICE_TYPE = "_dacp._tcp"

        // DACP control commands used by the TV remote.
        private const val PENDING_NANOS = 3_000_000_000L
        const val CMD_PLAY = "play"
        const val CMD_PAUSE = "pause"
        const val CMD_PLAY_PAUSE = "playpause"
        const val CMD_NEXT = "nextitem"
        const val CMD_PREV = "previtem"
        const val CMD_FF = "beginff"
        const val CMD_REW = "beginrew"
        const val CMD_VOLUME_UP = "volumeup"
        const val CMD_VOLUME_DOWN = "volumedown"
        private val SUPPORTED_COMMANDS = setOf(CMD_PLAY, CMD_PAUSE, CMD_PLAY_PAUSE, CMD_NEXT, CMD_PREV, CMD_FF, CMD_REW, CMD_VOLUME_UP, CMD_VOLUME_DOWN)
    }
}
