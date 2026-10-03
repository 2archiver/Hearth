package com.phairplay.cast.bridge

import com.phairplay.util.Logger
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * CastV2Server — the TLS channel Cast senders connect to on port 8009.
 *
 * Protocol in one paragraph: a sender opens a TLS connection (it accepts any certificate,
 * including the self-signed one we ship), then exchanges `CastMessage` protobufs as
 * 4-byte big-endian length-prefixed frames. There is no HTTP and no WebSocket — this is
 * the raw "castv2" channel, the same one pychromecast and node-castv2 speak.
 *
 * One thread per connection: a TV sees one or two senders at a time and each connection is
 * long-lived but mostly idle, so threads are simpler here than a selector loop and cannot
 * deadlock the protocol logic.
 */
internal class CastV2Server(
    private val port: Int,
    private val keystoreOpener: () -> InputStream,
    private val keystorePassword: CharArray,
    private val app: CastReceiverApp
) {

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    /**
     * Binds the port and starts accepting.
     *
     * @throws java.net.BindException when something else (usually the TV's built-in
     *   Chromecast) already owns port 8009 — the caller turns that into an honest error
     *   instead of pretending Cast works.
     */
    fun start() {
        val context = buildSslContext() ?: throw IOException("Could not load the Cast TLS keystore")
        // Bind explicitly (rather than createServerSocket(port)) so SO_REUSEADDR is set
        // *before* the bind — a restart of the bridge must not be blocked by TIME_WAIT.
        val socket = context.serverSocketFactory.createServerSocket()
        socket.reuseAddress = true
        socket.bind(java.net.InetSocketAddress(port))
        serverSocket = socket
        running = true
        thread(name = "castv2-accept", isDaemon = true) { acceptLoop(socket) }
        Logger.i("Cast: castv2 channel listening on TLS port $port")
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.d("Cast: error closing the castv2 server socket (non-fatal)")
        }
        serverSocket = null
        Logger.i("Cast: castv2 channel stopped")
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client: Socket = try {
                socket.accept()
            } catch (e: IOException) {
                if (running) Logger.w("Cast: accept() failed: ${e.message}")
                break
            }
            if (!running) {
                runCatching { client.close() }
                break
            }
            thread(name = "castv2-conn", isDaemon = true) { handleClient(client) }
        }
    }

    private fun handleClient(socket: Socket) {
        val sink = SocketSink(socket)
        var opened = false
        try {
            val ssl = socket as SSLSocket
            ssl.soTimeout = 0            // Cast connections idle for minutes between messages
            ssl.startHandshake()
            val input = DataInputStream(ssl.inputStream)
            opened = true
            app.addSink(sink)
            Logger.i("Cast: sender connected from ${ssl.inetAddress?.hostAddress}")

            while (running) {
                val length = try {
                    input.readInt()
                } catch (e: EOFException) {
                    break                        // sender hung up — normal end of session
                } catch (e: IOException) {
                    break
                }
                if (length <= 0 || length > MAX_FRAME_BYTES) {
                    Logger.w("Cast: implausible frame length $length — closing connection")
                    break
                }
                val body = ByteArray(length)
                input.readFully(body)
                val message = CastProtocol.decode(body)
                if (message == null) {
                    Logger.w("Cast: could not decode a $length-byte frame — dropping it")
                    continue
                }
                // Pass the connection the message arrived on so replies go back to that sender
                // alone — broadcasting every reply to every phone meant a second sender was
                // bombarded with statuses addressed to the first.
                runCatching { app.handle(message, sink) }
                    .onFailure { Logger.e("Cast: error handling ${message.namespace}", it) }
            }
        } catch (e: Exception) {
            if (running) Logger.d("Cast: connection ended (${e.message})")
        } finally {
            if (opened) app.removeSink(sink)
            runCatching { socket.close() }
        }
    }

    private fun buildSslContext(): SSLContext? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keystoreOpener().use { keyStore.load(it, keystorePassword) }
        val factory = keyManagerFactory()
        factory.init(keyStore, keystorePassword)
        SSLContext.getInstance("TLS").apply {
            init(factory.keyManagers, null, null)
        }
    } catch (e: Exception) {
        Logger.e("Cast: could not build the TLS context for the castv2 channel", e)
        null
    }

    /**
     * Android's JSSE providers disagree on the default KeyManagerFactory algorithm name
     * across versions, so try the ones that exist rather than hard-coding one.
     */
    private fun keyManagerFactory(): KeyManagerFactory {
        val candidates = listOf(
            KeyManagerFactory.getDefaultAlgorithm(),
            "PKIX",
            "X509",
            "SunX509"
        )
        val errors = ArrayList<String>()
        for (candidate in candidates) {
            if (candidate.isNullOrBlank()) continue
            try {
                return KeyManagerFactory.getInstance(candidate)
            } catch (e: Exception) {
                errors += candidate
            }
        }
        throw IOException("No KeyManagerFactory available (tried ${errors.joinToString()})")
    }

    /** Writes frames to one sender socket; serialised so concurrent sends cannot interleave. */
    private class SocketSink(private val socket: Socket) : CastSink {
        override fun send(message: CastMessage) {
            val frame = CastProtocol.encodeFrame(message)
            val output = socket.getOutputStream()
            synchronized(output) {
                output.write(frame)
                output.flush()
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8009
        private const val KEYSTORE_TYPE = "PKCS12"
        private const val MAX_FRAME_BYTES = 16 * 1024 * 1024
    }
}
