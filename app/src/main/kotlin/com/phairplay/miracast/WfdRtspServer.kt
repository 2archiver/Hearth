package com.phairplay.miracast

import android.view.Surface
import com.phairplay.airplay.RtspRequest
import com.phairplay.airplay.RtspRequestReader
import com.phairplay.airplay.RtspResponse
import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * WfdRtspServer — Miracast WFD RTSP control plane AND media loop.
 *
 * Control plane: listens on port 7236 and answers the WFD capability
 * negotiation (OPTIONS / GET_PARAMETER / SET_PARAMETER / SETUP / PLAY).
 *
 * Media loop: after PLAY, the sender interleaves binary RTP frames with any
 * further RTSP messages (keep-alives, PAUSE, TEARDOWN) on the same TCP
 * connection. The loop peeks each message's first byte:
 *   - '$' (0x24) → interleaved RTP frame — video frames go to [WfdVideoRenderer]
 *     for H.264 hardware decode onto the streaming Surface (the actual picture —
 *     previously frames were negotiated and then discarded, leaving a blank TV).
 *   - otherwise  → an RTSP request parsed by [RtspRequestReader]; keep-alives
 *     are answered so the sender doesn't drop the session mid-stream.
 *
 * Audio: WFD carries audio inside an MPEG-2 transport stream and there is no demuxer here, so
 * the interleaved audio channel is drained and counted (so the debug HUD can say whether the
 * source is sending audio at all) but deliberately not decoded. See docs/guides/MIRACAST.md.
 */
internal class WfdRtspServer(
    private val onSessionStarted: () -> Unit,
    private val onSessionStopped: () -> Unit,
    private val videoSurfaceProvider: () -> Surface? = { null }
) {
    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = MAX_MESSAGE_BYTES
    )

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activeClient: Socket? = null
    @Volatile private var videoRenderer: WfdVideoRenderer? = null
    private var currentCSeq = 0
    private var sessionStarted = false

    /**
     * Parameters the source has driven via `SET_PARAMETER` (presentation URL, trigger method),
     * echoed back in later `GET_PARAMETER` replies so the source sees its own choices honoured.
     */
    private val sourceValues = LinkedHashMap<String, String>()

    /** Last `wfd_trigger_method` the source asked us to use, kept for diagnostics. */
    @Volatile private var pendingTrigger: String? = null

    /** Counts audio payloads so the debug HUD can say whether the source is sending any. */
    private var audioPayloads = 0

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            runServer(this)
        }
    }

    fun stop() {
        running = false
        try {
            activeClient?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing WFD RTSP sockets (non-fatal)", e)
        }
        activeClient = null
        serverSocket = null
        // Close the socket first so the client loop exits, then release the decoder
        // defensively here too (release() is idempotent).
        videoRenderer?.release()
        videoRenderer = null
        if (sessionStarted) {
            sessionStarted = false
            onSessionStopped()
        }
    }

    private fun runServer(scope: CoroutineScope) {
        try {
            serverSocket = ServerSocket(MiracastReceiver.WFD_RTSP_PORT)
            Logger.i("WFD RTSP server listening on port ${MiracastReceiver.WFD_RTSP_PORT}")
            while (running && scope.isActive) {
                val client = serverSocket!!.accept()
                if (activeClient != null && !activeClient!!.isClosed) {
                    sendServiceUnavailable(client)
                    client.close()
                    continue
                }
                activeClient = client
                handleClient(client)
            }
        } catch (e: Exception) {
            if (running) Logger.e("WFD RTSP server error", e)
        }
    }

    private fun handleClient(socket: Socket) {
        var renderer: WfdVideoRenderer? = null
        try {
            val output = socket.getOutputStream()
            // 1-byte pushback lets us peek the first byte of each message so that
            // binary '$'-framed RTP and text-based RTSP can share this connection.
            val stream = PushbackInputStream(socket.getInputStream(), 1)

            while (running && !socket.isClosed) {
                val first = stream.read()
                if (first == -1) break  // clean EOF — sender disconnected

                if (first == INTERLEAVED_MARKER) {
                    // Binary RTP/RTCP frame interleaved on the RTSP connection
                    val (channel, frameData) = readInterleavedFrame(stream) ?: break
                    when (channel) {
                        CHANNEL_VIDEO_RTP -> {
                            StreamStats.noteVideoPayload(frameData.size)
                            renderer?.onRtpVideoFrame(frameData)
                        }
                        CHANNEL_AUDIO_RTP -> {
                            // Consumed so the connection keeps draining, but not decoded: WFD
                            // carries audio inside an MPEG-2 transport stream, and demuxing that
                            // is out of scope here. Counted so the HUD can say whether the
                            // source is sending audio at all.
                            audioPayloads++
                            if (audioPayloads == 1) {
                                Logger.i("WFD: source is sending audio on the interleaved audio " +
                                    "channel (video only is decoded — see docs/guides/MIRACAST.md)")
                            }
                        }
                        else -> Unit   // RTCP sender reports
                    }
                    continue
                }

                // Not '$' → an RTSP request (keep-alive, PAUSE, TEARDOWN, …)
                stream.unread(first)
                val request = requestReader.read(stream) ?: break
                currentCSeq = request.headers["CSeq"]?.toIntOrNull() ?: 0
                val response = routeRequest(request)
                sendResponse(output, response)

                if (request.method == "PLAY" && response.statusCode == 200 && renderer == null) {
                    // Handshake done — bring up the H.264 decode pipeline so the
                    // incoming RTP stream actually reaches the screen.
                    renderer = WfdVideoRenderer(videoSurfaceProvider)
                    videoRenderer = renderer
                    Logger.i("WFD media session active — decoding video")
                }
                if (request.method == "TEARDOWN") break
            }
        } catch (e: Exception) {
            if (running) Logger.e("Error handling WFD RTSP client", e)
        } finally {
            renderer?.release()
            videoRenderer = null
            StreamStats.videoDecoderReady = false
            StreamStats.videoQueue = 0
            if (audioPayloads > 0) {
                Logger.i("WFD: session ended — $audioPayloads audio payload(s) received and not decoded")
            }
            audioPayloads = 0
            sourceValues.clear()
            pendingTrigger = null
            try {
                socket.close()
            } catch (e: Exception) {
                Logger.e("Error closing WFD RTSP client socket (non-fatal)", e)
            }
            activeClient = null
            if (sessionStarted) {
                sessionStarted = false
                onSessionStopped()
            }
        }
    }

    /**
     * Reads one interleaved frame after its '$' marker was already consumed.
     * Returns channel + payload, or null on EOF / malformed length.
     *
     * Frame layout (RFC 2326 §10.12): channel (1B) + length (2B, big-endian) + payload.
     */
    private fun readInterleavedFrame(stream: InputStream): Pair<Int, ByteArray>? {
        val channel = stream.read()
        val lenHigh = stream.read()
        val lenLow = stream.read()
        if (channel == -1 || lenHigh == -1 || lenLow == -1) return null

        val frameLength = (lenHigh shl 8) or lenLow
        if (frameLength <= 0 || frameLength > MAX_INTERLEAVED_FRAME_BYTES) {
            Logger.w("WFD: invalid interleaved frame length $frameLength — closing session")
            return null
        }

        val frameData = ByteArray(frameLength)
        var bytesRead = 0
        while (bytesRead < frameLength) {
            val n = stream.read(frameData, bytesRead, frameLength - bytesRead)
            if (n == -1) return null
            bytesRead += n
        }
        return channel to frameData
    }

    internal fun routeRequest(request: RtspRequest): RtspResponse {
        Logger.d("WFD RTSP ${request.method} ${request.uri}")
        return when (request.method) {
            "OPTIONS" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                // Every method this receiver implements. Sources read this before using PLAY /
                // SETUP, and a list that omits them reads as "this sink cannot stream".
                headers = mapOf("Public" to WfdParameters.PUBLIC_METHODS)
            )

            "GET_PARAMETER" -> {
                // Answer exactly the parameters the source asked for. It validates the reply
                // against its own request list; a fixed blob that omits wfd_uibc_capability or
                // wfd_standby_resume_capability is a well-known reason Windows drops the session.
                val body = WfdParameters.renderResponse(request.body, sourceValues)
                Logger.d("WFD GET_PARAMETER → ${body.lineSequence().count { it.isNotBlank() }} parameter(s)")
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    headers = mapOf("Content-Type" to "text/parameters"),
                    body = body
                )
            }

            "SET_PARAMETER" -> {
                val applied = WfdParameters.applySetParameter(request.body, sourceValues)
                for ((name, value) in applied) {
                    Logger.i("WFD SET_PARAMETER $name: $value")
                    if (name == "wfd_trigger_method") pendingTrigger = value
                }
                // Anything the source tries to set that we do not echo (its video formats, audio
                // codecs or RTP ports) is deliberately ignored: promising a codec or transport we
                // do not implement would have it stream in a format we then drop.
                RtspResponse(statusCode = 200, statusMessage = "OK")
            }

            "SETUP" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf(
                    "Session" to "$WFD_SESSION_ID;timeout=60",
                    "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"
                )
            )

            "PLAY" -> {
                if (!sessionStarted) {
                    sessionStarted = true
                    onSessionStarted()
                }
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    headers = mapOf(
                        "Session" to WFD_SESSION_ID,
                        "Range" to "npt=now-",
                        "RTP-Info" to "url=${request.uri};seq=0;rtptime=0"
                    )
                )
            }

            "PAUSE" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Session" to WFD_SESSION_ID)
            )

            "TEARDOWN" -> RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Session" to WFD_SESSION_ID)
            )

            else -> RtspResponse(statusCode = 501, statusMessage = "Not Implemented")
        }
    }

    private fun sendResponse(outputStream: OutputStream, response: RtspResponse) {
        val sb = StringBuilder()
        sb.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        sb.append("CSeq: $currentCSeq\r\n")
        sb.append("Server: PhairPlay/1.1\r\n")
        response.headers.forEach { (key, value) -> sb.append("$key: $value\r\n") }
        if (response.body.isNotEmpty()) {
            sb.append("Content-Length: ${response.body.toByteArray(Charsets.UTF_8).size}\r\n")
        }
        sb.append("\r\n")
        sb.append(response.body)
        outputStream.write(sb.toString().toByteArray(Charsets.UTF_8))
        outputStream.flush()
    }

    private fun sendServiceUnavailable(socket: Socket) {
        val response = "RTSP/1.0 503 Service Unavailable\r\nCSeq: 0\r\n\r\n"
        socket.outputStream.write(response.toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
    }

    companion object {
        private const val MAX_MESSAGE_BYTES = 65536
        private const val MAX_INTERLEAVED_FRAME_BYTES = 2 * 1024 * 1024  // 2 MB (matches AirPlay)
        private const val WFD_SESSION_ID = "PhairPlayWfdSession"

        /** '$' marks the start of an interleaved binary frame (RFC 2326 §10.12). */
        private const val INTERLEAVED_MARKER = 0x24

        /** Interleaved channel assignment negotiated in SETUP: 0 = video RTP, 1 = video RTCP. */
        private const val CHANNEL_VIDEO_RTP = 0

        /** 2 = audio RTP, 3 = audio RTCP (RFC 2326 pairs them). Not decoded — see [handleClient]. */
        private const val CHANNEL_AUDIO_RTP = 2
    }
}
