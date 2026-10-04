package com.phairplay.airplay

import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One accepted RTSP connection. Implemented by [RtspHandler]; split out so the listener below
 * never needs to know anything about AirPlay.
 */
interface RtspConnection {
    /** Serves this connection until the peer goes away or [close] is called. Blocking; runs on IO. */
    fun serve()

    /** Closes the connection. Safe to call from any thread and more than once. */
    fun close()

    /** True while this connection carries a live media session (never retired first). */
    val holdsSession: Boolean get() = false
}

/**
 * RtspServer — owns TCP port 7000 and accepts **every** connection a sender opens, each on its
 * own coroutine.
 *
 * WHY THIS IS NOT A SINGLE-CLIENT LOOP ANY MORE: an Apple sender does not talk over one socket.
 * While it is discovering and then starting a session it opens:
 *
 *  - the RTSP control connection (OPTIONS → GET /info → pair-setup → pair-verify → fp-setup →
 *    SETUP → RECORD), which stays open for the whole session;
 *  - an **event channel** — a second TCP connection that stays silent from the sender's side,
 *    because the receiver is the one that writes to it;
 *  - further short-lived connections while probing, and a fresh control connection after every
 *    network hiccup or reconnect.
 *
 * The previous implementation served exactly one client at a time and answered every other
 * connection with `503 Service Unavailable`. So the moment a sender opened its second socket the
 * session was over — and the *first* socket (control) was still held by a coroutine blocked in
 * `read()`, which meant the next attempt at connecting got a 503 too. From the sofa that reads as
 * "the TV shows up in the list, but connecting does nothing". It also meant a sender that walked
 * away without closing (phone locked, Wi-Fi dropped) locked the port for up to two minutes.
 *
 * Accepting connections concurrently and giving each one its own [RtspHandler] removes all of
 * that: the event channel simply sits idle, a stale connection can no longer block a fresh one,
 * and a retry from the sender works immediately.
 *
 * @param port              listening port (7000 — [RtspHandler.RTSP_PORT]).
 * @param connectionFactory builds the per-connection handler. [AirPlayReceiver] supplies it so
 *                          every connection shares one media pipeline.
 */
class RtspServer(
    private val port: Int = RtspHandler.RTSP_PORT,
    private val connectionFactory: (Socket) -> RtspConnection
) {
    private var serverSocket: ServerSocket? = null

    /** Live connections, newest last. Used for shutdown and for the connection cap. */
    private val live = CopyOnWriteArrayList<RtspConnection>()

    @Volatile
    private var running = false

    /**
     * Binds the port (retrying briefly if a just-stopped instance has not released it) and starts
     * accepting. A quick service stop→start could otherwise fail with EADDRINUSE, leaving the TV
     * advertising over mDNS while port 7000 was dead — "casting but nothing shows".
     */
    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) {
            val socket = try {
                bind()
            } catch (e: IOException) {
                if (running) {
                    Logger.e("RTSP server could not bind port $port", e)
                    AirPlayTrace.record("RTSP port $port could not be opened (${e.message})")
                }
                return@launch
            }
            serverSocket = socket
            Logger.i("RTSP server listening on port $port (concurrent connections allowed)")
            AirPlayTrace.record("Listening for senders on TCP $port")

            while (running && !socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running) Logger.w("RTSP accept failed: ${e.message}")
                    break
                }
                if (!running) {
                    runCatching { client.close() }
                    break
                }
                Logger.i("RTSP connection from ${client.inetAddress.hostAddress}")
                AirPlayTrace.record("Connection from ${describe(client)}")

                // Cap the fan-out. A sender needs a handful of sockets; anything beyond that is a
                // client that has gone wrong, so retire the oldest connection instead of refusing
                // the new one — refusing is what used to break a reconnect after a dropped session.
                if (live.size >= MAX_CONNECTIONS) {
                    retireCandidate(live)?.let { victim ->
                        Logger.w("RTSP: $MAX_CONNECTIONS connections open — retiring the oldest idle one")
                        AirPlayTrace.record("Too many connections — closed an idle one")
                        live.remove(victim)
                        runCatching { victim.close() }
                    }
                }

                val connection = connectionFactory(client)
                live.add(connection)
                scope.launch(Dispatchers.IO) {
                    try {
                        connection.serve()
                    } catch (e: Exception) {
                        Logger.w("RTSP connection ended with an error: ${e.message}")
                    } finally {
                        live.remove(connection)
                    }
                }
            }
            Logger.i("RTSP server stopped accepting")
        }
    }

    /** True once the port is bound — the state the AirPlay card reports as "reachable". */
    fun isListening(): Boolean = serverSocket?.isClosed == false

    /** Number of connections currently being served (diagnostics). */
    fun connectionCount(): Int = live.size

    /** Stops the listener and closes every live connection. */
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        live.forEach { connection -> runCatching { connection.close() } }
        live.clear()
        Logger.i("RTSP server stopped")
    }

    private fun bind(): ServerSocket {
        var lastError: IOException? = null
        repeat(BIND_MAX_ATTEMPTS) { attempt ->
            if (!running) throw IOException("RTSP server stopped before bind")
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: IOException) {
                lastError = e
                Logger.w("RTSP port $port busy (attempt ${attempt + 1}/$BIND_MAX_ATTEMPTS) — retrying in ${BIND_RETRY_MS}ms")
                try {
                    Thread.sleep(BIND_RETRY_MS)
                } catch (_: InterruptedException) {
                    throw e
                }
            }
        }
        throw lastError ?: IOException("RTSP bind to $port failed")
    }

    private fun describe(socket: Socket): String {
        val address = socket.inetAddress?.hostAddress ?: "unknown address"
        return "$address:${socket.port}"
    }

    private companion object {
        /** ~3s total — covers a quick stop→start restart (SO_REUSEADDR covers TIME_WAIT). */
        const val BIND_MAX_ATTEMPTS = 12
        const val BIND_RETRY_MS = 250L

        /**
         * How many sockets one sender may hold at once. A real session uses two or three; the
         * headroom is for the short-lived probe connections a sender opens while the menu is
         * updating, and for a reconnect racing a session that has not finished tearing down.
         */
        const val MAX_CONNECTIONS = 8
    }
}

/**
 * Which connection to retire when [RtspServer]'s cap is hit: the oldest one that does NOT carry
 * the media session. Retiring "the oldest" outright usually meant the control channel — it is
 * opened first — so a burst of probe sockets ended a running session.
 */
internal fun retireCandidate(live: List<RtspConnection>): RtspConnection? =
    live.firstOrNull { !it.holdsSession } ?: live.firstOrNull()
