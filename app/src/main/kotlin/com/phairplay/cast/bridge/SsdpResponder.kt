package com.phairplay.cast.bridge

import android.content.Context
import android.net.wifi.WifiManager
import com.phairplay.util.Logger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import kotlin.concurrent.thread

/**
 * SsdpResponder — answers UPnP/SSDP `M-SEARCH`, the way the **DIAL** half of a Chromecast does.
 *
 * WHY THIS EXISTS
 * ---------------
 * PhairPlay advertised itself over mDNS as `_googlecast._tcp` and served DIAL over HTTP on
 * 8008 — but it never answered SSDP. That combination is half a receiver:
 *
 *  - **Google Cast senders** (the iPhone's Cast SDK, Chrome, VLC, Plex…) browse
 *    `_googlecast._tcp` over mDNS. They found us.
 *  - **DIAL senders** — Netflix, YouTube, Windows 10/11 "Connect", a lot of Android apps —
 *    do **not** browse mDNS. They send a UPnP `M-SEARCH` to `239.255.255.250:1900` asking for
 *    `urn:dial-multiscreen-org:service:dial:1`, and only talk to devices that answer.
 *
 * We never answered, so as far as every DIAL sender was concerned the TV did not exist —
 * which is exactly what "casting doesn't work" looks like from the phone.
 *
 * HOW: join the SSDP multicast group, and for every `M-SEARCH` whose `ST` we serve, unicast a
 * `200 OK` pointing the sender at the DIAL device description on port 8008. Everything after
 * that is plain HTTP and already worked.
 *
 * This is best-effort by design. Port 1900 is often already bound (by the TV's own Chromecast
 * or another media app), multicast can be filtered by the router, and Wi-Fi multicast needs an
 * explicit lock on Android. Any of those failing is logged and Cast keeps working for the
 * senders that use mDNS — it must never take the receiver down.
 */
internal class SsdpResponder(
    private val context: Context,
    /** Absolute URL of the DIAL device description, e.g. http://192.168.1.42:8008/ssdp/device-desc.xml */
    private val locationUrl: () -> String,
    /** Stable per-device UUID used in the USN header. */
    private val deviceUuid: () -> String
) {

    @Volatile private var running = false
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null

    /** True once the responder actually got a socket on 1900. */
    @Volatile var isListening = false
        private set

    /** Human-readable reason the responder is not listening, for the Cast card. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Binds port 1900 and starts answering.
     *
     * @return true when a socket was bound; false when SSDP is not possible on this network
     *         (someone else owns the port, or multicast is unavailable).
     */
    @Synchronized
    fun start(): Boolean {
        if (running) return isListening
        lastError = null
        acquireMulticastLock()
        val udp = try {
            val s = MulticastSocket(null)
            // SO_REUSEADDR before bind: the TV's built-in Chromecast usually holds 1900 already.
            // Without this the bind fails and no DIAL sender can ever discover us.
            s.reuseAddress = true
            s.bind(InetSocketAddress(SSDP_PORT))
            s
        } catch (e: Exception) {
            lastError = "Could not listen for SSDP discovery on port $SSDP_PORT (${e.message}). " +
                "Google Cast senders still work; DIAL-only senders will not see this TV."
            Logger.w("Cast: SSDP responder could not bind port $SSDP_PORT — ${e.message}")
            releaseMulticastLock()
            return false
        }

        try {
            joinSsdpGroup(udp)
        } catch (e: Exception) {
            Logger.w("Cast: could not join the SSDP multicast group — ${e.message}")
        }

        socket = udp
        running = true
        isListening = true
        thread(name = "cast-ssdp", isDaemon = true) { receiveLoop(udp) }
        thread(name = "cast-ssdp-notify", isDaemon = true) { announce(udp) }
        Logger.i("Cast: SSDP/DIAL discovery answering on port $SSDP_PORT")
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        isListening = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Logger.d("Cast: error closing the SSDP socket (non-fatal)")
        }
        socket = null
        releaseMulticastLock()
        Logger.i("Cast: SSDP responder stopped")
    }

    // ─── Discovery loop ──────────────────────────────────────────────────────

    private fun receiveLoop(udp: MulticastSocket) {
        val buffer = ByteArray(2048)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                val request = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                val searchTargets = matchingSearchTargets(request) ?: continue
                // MX asks us to spread replies out so we don't stampede a busy sender; a short
                // random delay is enough and keeps the discovery timeout happy.
                Thread.sleep((0..MAX_REPLY_DELAY_MS).random().toLong())
                for (target in searchTargets) {
                    sendSearchResponse(udp, packet.address, packet.port, target)
                }
            } catch (e: Exception) {
                if (running) Logger.d("Cast: SSDP receive ended (${e.message})")
                break
            }
        }
    }

    /**
     * Returns the ST values to answer with, or null when this datagram is not a search we serve.
     *
     * `ssdp:all` and `upnp:rootdevice` are answered with every service we publish, which is what
     * a real DIAL device does; anything else must name one of our STs exactly.
     */
    internal fun matchingSearchTargets(request: String): List<String>? {
        if (!request.startsWith("M-SEARCH", ignoreCase = true)) return null
        val headers = parseHeaders(request)
        val st = headers["ST"]?.trim().orEmpty()
        if (st.isEmpty()) return null
        return when (st) {
            "ssdp:all" -> ALL_SEARCH_TARGETS
            "upnp:rootdevice" -> ALL_SEARCH_TARGETS
            in ALL_SEARCH_TARGETS -> listOf(st)
            else -> null
        }
    }

    internal fun searchResponse(searchTarget: String, location: String, uuid: String): String {
        val (deviceType, usn) = when (searchTarget) {
            ST_DIAL_DEVICE -> ST_DIAL_DEVICE to "uuid:$uuid::$ST_DIAL_DEVICE"
            else -> ST_DIAL_SERVICE to "uuid:$uuid::$ST_DIAL_SERVICE"
        }
        return "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=$CACHE_CONTROL_SECONDS\r\n" +
            "EXT:\r\n" +
            "LOCATION: $location\r\n" +
            "SERVER: $SERVER_HEADER\r\n" +
            "ST: $deviceType\r\n" +
            "USN: $usn\r\n" +
            "\r\n"
    }

    private fun sendSearchResponse(udp: MulticastSocket, address: InetAddress?, port: Int, target: String) {
        if (address == null || port <= 0) return
        val body = searchResponse(target, locationUrl(), deviceUuid())
        val bytes = body.toByteArray(Charsets.ISO_8859_1)
        try {
            udp.send(DatagramPacket(bytes, bytes.size, address, port))
        } catch (e: Exception) {
            Logger.d("Cast: could not answer an SSDP search from $address (${e.message})")
        }
    }

    /**
     * A couple of `ssdp:alive` announcements on startup: senders that cache device lists see us
     * without waiting for their next periodic search. Purely advisory — if it fails we are still
     * fully discoverable through M-SEARCH.
     */
    private fun announce(udp: MulticastSocket) {
        val group = try {
            InetAddress.getByName(SSDP_ADDRESS)
        } catch (e: Exception) {
            return
        }
        repeat(ANNOUNCE_COUNT) {
            if (!running) return
            for (target in ALL_SEARCH_TARGETS) {
                val (deviceType, usn) = if (target == ST_DIAL_DEVICE) {
                    ST_DIAL_DEVICE to "uuid:${deviceUuid()}::$ST_DIAL_DEVICE"
                } else {
                    ST_DIAL_SERVICE to "uuid:${deviceUuid()}::$ST_DIAL_SERVICE"
                }
                val body = "NOTIFY * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                    "CACHE-CONTROL: max-age=$CACHE_CONTROL_SECONDS\r\n" +
                    "LOCATION: ${locationUrl()}\r\n" +
                    "SERVER: $SERVER_HEADER\r\n" +
                    "NTS: ssdp:alive\r\n" +
                    "NT: $deviceType\r\n" +
                    "USN: $usn\r\n" +
                    "\r\n"
                val bytes = body.toByteArray(Charsets.ISO_8859_1)
                try {
                    udp.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
                } catch (e: Exception) {
                    Logger.d("Cast: SSDP announce failed (non-fatal): ${e.message}")
                }
            }
            Thread.sleep(ANNOUNCE_INTERVAL_MS)
        }
    }

    // ─── Multicast lock ──────────────────────────────────────────────────────

    /**
     * Android drops multicast packets unless an app holds a MulticastLock. Without this the
     * responder binds fine and then simply never receives an M-SEARCH.
     */
    private fun acquireMulticastLock() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            Logger.w("Cast: no WifiManager — SSDP multicast may be filtered on this TV")
            return
        }
        try {
            val lock = wifi.createMulticastLock(MULTICAST_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Logger.w("Cast: could not take a Wi-Fi multicast lock — ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (e: Exception) {
            Logger.d("Cast: error releasing the multicast lock (non-fatal)")
        }
        multicastLock = null
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun parseHeaders(request: String): Map<String, String> {
        val headers = HashMap<String, String>()
        for (line in request.lineSequence()) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().uppercase()] = line.substring(colon + 1).trim()
        }
        return headers
    }

    /**
     * `MulticastSocket.joinGroup(InetAddress)` is the only overload available on this app's
     * minSdk (the `joinGroup(SocketAddress, NetworkInterface)` replacement arrived in API 34),
     * so the deprecation is suppressed rather than branched around.
     */
    @Suppress("DEPRECATION")
    private fun joinSsdpGroup(udp: MulticastSocket) {
        udp.joinGroup(InetAddress.getByName(SSDP_ADDRESS))
    }

    companion object {
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900

        const val ST_DIAL_SERVICE = "urn:dial-multiscreen-org:service:dial:1"
        const val ST_DIAL_DEVICE = "urn:dial-multiscreen-org:device:dial:1"

        /** Every search target PhairPlay publishes an `ssdp:alive` for. */
        val ALL_SEARCH_TARGETS: List<String> = listOf(ST_DIAL_SERVICE, ST_DIAL_DEVICE)

        private const val CACHE_CONTROL_SECONDS = 1800
        private const val SERVER_HEADER = "Linux/4.9.125, UPnP/1.0, PhairPlay/1.5"
        private const val MAX_REPLY_DELAY_MS = 200
        private const val ANNOUNCE_COUNT = 3
        private const val ANNOUNCE_INTERVAL_MS = 500L
        private const val MULTICAST_LOCK_TAG = "phairplay-ssdp"
    }
}
