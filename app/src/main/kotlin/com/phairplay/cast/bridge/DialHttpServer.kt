package com.phairplay.cast.bridge

import com.phairplay.util.Logger
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.concurrent.thread

/**
 * DialHttpServer — the DIAL/SSDP front door on port 8008.
 *
 * WHY: before a Cast sender opens the castv2 channel it often asks "is this device a
 * Chromecast and what is it called?" over plain HTTP. DIAL is that protocol: a UPnP device
 * description plus `GET/POST/DELETE /apps/<appId>`. Chrome's Cast extension and a number of
 * sender apps refuse to show a device that does not answer here, so the castv2 channel
 * alone is not enough.
 *
 * Minimal on purpose: one request per connection, `Connection: close`, only the handful of
 * routes DIAL and Chromecast clients actually use.
 */
internal class DialHttpServer(
    private val port: Int,
    private val deviceName: () -> String,
    private val localIpAddress: () -> String,
    private val onLaunch: (appId: String) -> Unit,
    private val onStop: () -> Unit
) {

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null

    /** The DIAL instance name for the currently running app, or null when idle. */
    @Volatile private var instanceId: String? = null
    @Volatile private var runningAppId: String? = null

    /** Marks an app as running so `GET /apps/<id>` reports `running` (called after a launch). */
    fun markRunning(appId: String) {
        runningAppId = appId
        instanceId = INSTANCE_ID
    }

    /** Marks everything as stopped. */
    fun markStopped() {
        runningAppId = null
        instanceId = null
    }

    /**
     * Binds and starts serving.
     *
     * @throws java.net.BindException when the port is taken (built-in Chromecast).
     */
    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        serverSocket = socket
        running = true
        thread(name = "dial-accept", isDaemon = true) { acceptLoop(socket) }
        Logger.i("Cast: DIAL/HTTP server listening on port $port")
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.d("Cast: error closing the DIAL server socket (non-fatal)")
        }
        serverSocket = null
        Logger.i("Cast: DIAL server stopped")
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client: Socket = try {
                socket.accept()
            } catch (e: IOException) {
                if (running) Logger.w("Cast: DIAL accept() failed: ${e.message}")
                break
            }
            if (!running) {
                runCatching { client.close() }
                break
            }
            thread(name = "dial-conn", isDaemon = true) { handleClient(client) }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty().uppercase()
            val rawPath = parts.getOrNull(1).orEmpty()

            // Drain headers; only Content-Length matters (POST bodies are tiny).
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            val body = if (contentLength > 0 && contentLength < MAX_BODY_BYTES) {
                val buffer = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buffer, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read)
            } else {
                ""
            }

            val path = rawPath.substringBefore("?")
            respond(socket, method, path, body)
        } catch (e: Exception) {
            Logger.d("Cast: DIAL request ended (${e.message})")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun respond(socket: Socket, method: String, path: String, body: String) {
        val appId = appIdFromPath(path)
        when {
            method == "OPTIONS" -> writeResponse(
                socket, 200, "OK", "text/plain", "",
                extraHeaders = "Allow: GET, POST, DELETE, OPTIONS\r\nAccess-Control-Allow-Methods: GET, POST, DELETE, OPTIONS"
            )

            path == "/ssdp/device-desc.xml" || path == "/device-desc.xml" ->
                writeResponse(socket, 200, "OK", "application/xml", deviceDescription())

            path == "/setup/eureka_info" || path.startsWith("/setup/") ->
                writeResponse(socket, 200, "OK", "application/json", eurekaInfo(),
                    extraHeaders = "Access-Control-Allow-Origin: *")

            path == "/apps" ->
                writeResponse(socket, 200, "OK", "application/xml", appList())

            appId != null && method == "GET" ->
                writeResponse(socket, 200, "OK", "application/xml", appState(appId))

            appId != null && method == "POST" -> {
                Logger.i("Cast: DIAL launch $appId${if (body.isNotBlank()) " body=${body.take(200)}" else ""}")
                markRunning(appId)
                onLaunch(appId)
                writeResponse(
                    socket, 201, "Created", "text/plain", "",
                    extraHeaders = "Location: ${appLocation(appId)}"
                )
            }

            appId != null && method == "DELETE" -> {
                Logger.i("Cast: DIAL stop $appId")
                markStopped()
                onStop()
                writeResponse(socket, 200, "OK", "text/plain", "")
            }

            else -> writeResponse(socket, 404, "Not Found", "text/plain", "Not Found")
        }
    }

    /** `/apps/<appId>` or `/apps/<appId>/<instance>` → the appId. */
    private fun appIdFromPath(path: String): String? {
        if (!path.startsWith("/apps/")) return null
        val segments = path.removePrefix("/apps/").split("/").filter { it.isNotEmpty() }
        return segments.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun appLocation(appId: String): String =
        "http://${localIpAddress()}:$port/apps/$appId/${instanceId ?: INSTANCE_ID}"

    // ─── Response bodies ─────────────────────────────────────────────────────

    private fun deviceDescription(): String {
        val name = deviceName()
        val udn = UUID.nameUUIDFromBytes(name.toByteArray(StandardCharsets.UTF_8))
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <URLBase>http://${localIpAddress()}:$port/</URLBase>
  <device>
    <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
    <friendlyName>$name</friendlyName>
    <manufacturer>PhairPlay</manufacturer>
    <modelName>Chromecast</modelName>
    <UDN>uuid:$udn</UDN>
    <serviceList>
      <service>
        <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
        <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>
        <controlURL>/ssdp/notfound</controlURL>
        <eventSubURL>/ssdp/notfound</eventSubURL>
        <SCPDURL>/ssdp/notfound</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"""
    }

    private fun appList(): String {
        val appId = runningAppId
        return if (appId == null) {
            """<?xml version="1.0" encoding="utf-8"?><apps/>"""
        } else {
            """<?xml version="1.0" encoding="utf-8"?><apps><app>$appId</app></apps>"""
        }
    }

    private fun appState(appId: String): String {
        val running = (runningAppId == appId)
        val state = if (running) "running" else "stopped"
        val link = if (running) """<link rel="run" href="run"/>""" else ""
        return """<?xml version="1.0" encoding="utf-8"?>
<service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="1.7">
  <name>$appId</name>
  <options allowStop="true"/>
  <state>$state</state>
  $link
</service>"""
    }

    private fun eurekaInfo(): String =
        """{"name":"${deviceName()}","build_version":"1.36.159268","connected":true,"has_update":false,"locale":"en","opt_in":{"crash":false,"stats":false},"release_track":"stable-channel","setup_state":60,"ssdp":true,"uptime":0.0,"version":11}"""

    // ─── Response writer ─────────────────────────────────────────────────────

    private fun writeResponse(
        socket: Socket,
        code: Int,
        reason: String,
        contentType: String,
        body: String,
        extraHeaders: String? = null
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = StringBuilder()
        header.append("HTTP/1.1 $code $reason\r\n")
        header.append("Content-Type: $contentType\r\n")
        header.append("Content-Length: ${bytes.size}\r\n")
        header.append("Connection: close\r\n")
        if (!extraHeaders.isNullOrBlank()) header.append(extraHeaders).append("\r\n")
        header.append("\r\n")
        val output = socket.getOutputStream()
        output.write(header.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    companion object {
        const val DEFAULT_PORT = 8008
        private const val READ_TIMEOUT_MS = 10_000
        private const val MAX_BODY_BYTES = 64 * 1024

        /** DIAL instance id — Chromecasts use a fixed per-device string; one is plenty here. */
        private const val INSTANCE_ID = "PhairPlay"
    }
}
