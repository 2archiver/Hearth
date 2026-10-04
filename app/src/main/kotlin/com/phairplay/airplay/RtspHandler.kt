package com.phairplay.airplay

import com.phairplay.airplay.handshake.FairPlay
import com.phairplay.airplay.handshake.InfoResponder
import com.phairplay.airplay.handshake.PairingKeys
import com.phairplay.airplay.handshake.PairingSession
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.Logger
import java.io.OutputStream
import java.net.Socket

/**
 * RtspHandler — the RTSP conversation with **one** AirPlay sender connection.
 *
 * AirPlay uses RTSP to negotiate codecs, ports and encryption before media flows. This class
 * parses ANNOUNCE SDP, acknowledges SETUP and RECORD, answers the AirPlay 2 pairing and key
 * exchange, and hands binary interleaved RTP frames to [RtpInterleaved].
 *
 * One instance per connection: [RtspServer] accepts every socket a sender opens (control
 * channel, event channel, probes, reconnects) and builds a handler for each. State that belongs
 * to the *media session* rather than the socket — mirror stream keys, the data server, the event
 * channel, the NTP client — lives in [AirPlayReceiver], because the receiver has to shut it all
 * down as one unit.
 */
open class RtspHandler(
    private val context: android.content.Context,
    /**
     * The spoofed name answered in `GET /info`. Senders display this value after they have
     * probed us, so it has to be the same name [com.phairplay.airplay.MdnsService]
     * advertises — otherwise the picker shows one name and the session uses another.
     */
    private val displayName: String = com.phairplay.util.MdnsNames.DEFAULT_DISPLAY_NAME,
    private val displayWidth: Int = 1920,
    private val displayHeight: Int = 1080,
    private val audioEnabled: Boolean = false,
    private val videoSurfaceProvider: () -> android.view.Surface?,
    private val onStreamingStarted: (session: SessionDescription) -> Unit,
    private val onStreamingStopped: () -> Unit,
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    private val onPhotoCleared: () -> Unit = {},
    /**
     * AirPlay 2 mirror SETUP msg 1: supply decrypted AES key + pairing secret + the sender's
     * address and timing port (so the receiver can start NTP). Returns (eventPort, timingPort).
     */
    private val onMirrorSetupKeys: (
        aesKey: ByteArray, ecdhSecret: ByteArray, aesIv: ByteArray,
        remoteAddress: java.net.InetAddress, senderTimingPort: Int
    ) -> Pair<Int, Int> = { _, _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror SETUP: start the video data server (type 110); returns its data port. */
    private val onMirrorStreamStart: (streamConnectionId: Long) -> Int = { 0 },
    /** AirPlay 2 SETUP: start the audio server (type 96; ct 8 AAC-ELD mirror / 4 AAC-LC / 2 ALAC). spf = samples/frame. */
    private val onMirrorAudioStart: (sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int) -> Pair<Int, Int> = { _, _, _, _ -> 0 to 0 },
    /** AirPlay 2 mirror TEARDOWN of just the audio stream (type 96) — stop audio, keep video. */
    private val onMirrorAudioStop: () -> Unit = {},
    /** AirPlay 2 mirror TEARDOWN of just the video stream (type 110) — stop video, keep audio. */
    private val onMirrorVideoStop: () -> Unit = {},
    /** AirPlay 2 buffered audio-only SETUP (type 103, Apple Music → TV); returns the TCP data port. */
    private val onBufferedAudioStart: () -> Int = { 0 },
    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private val onBufferedAudioStop: () -> Unit = {},
    /** Sender volume change (AirPlay dB: −30…0, or ≤ −144 = mute) via SET_PARAMETER. */
    private val onVolume: (Float) -> Unit = {},
    /** Now-playing track metadata (DMAP) from SET_PARAMETER — any field may be null. */
    private val onNowPlayingMetadata: (title: String?, artist: String?, album: String?) -> Unit = { _, _, _ -> },
    /** Album artwork (JPEG/PNG bytes) from SET_PARAMETER; empty bytes = artwork cleared. */
    private val onArtwork: (ByteArray) -> Unit = {},
    /** Legacy AirPlay video URL path: `Start-Position` is a fraction of media duration (0..1). */
    private val onVideoPlay: (url: String, startFraction: Double) -> Unit = { _, _ -> },
    /** Modern direct-video path: `Start-Position-Seconds` is an absolute offset in seconds. */
    private val onVideoPlaySeconds: (url: String, startSeconds: Double) -> Unit = onVideoPlay,
    /** AirPlay video transport: POST /rate (≤0 pause, >0 resume). */
    private val onVideoRate: (rate: Float) -> Unit = {},
    /** AirPlay video transport: POST /scrub — seek to position (seconds). */
    private val onVideoScrub: (positionSec: Double) -> Unit = {},
    /** AirPlay video transport: POST /stop — stop URL playback. */
    private val onVideoStop: () -> Unit = {},
    /** Current URL-video playback snapshot for GET /playback-info and GET /scrub. */
    private val onPlaybackInfo: () -> com.phairplay.airplay.PlaybackInfo? = { null },
    /** Sender's DACP reverse-control identity from RTSP headers (DACP-ID + Active-Remote token). */
    private val onRemoteControlInfo: (dacpId: String?, activeRemote: String?) -> Unit = { _, _ -> },
    /** Sender's human-readable device name/model extracted from AirPlay 2 SETUP plist (`name`/`model`). */
    private val onMirrorSenderName: (senderName: String) -> Unit = {},
    /** RTSP FLUSH notification with optional next RTP sequence number from `RTP-Info: seq=...`. */
    private val onAudioFlush: (nextSeq: Int) -> Unit = {},
    /** Initial AirPlay volume in dB (`-30..0`) carried over from receiver state (UxPlay commit `412c5b7`). */
    initialVolume: Float = 0f,
    /**
     * The accepted socket this handler serves. Supplied by [RtspServer] through the connection
     * factory; null in unit tests, which drive the request handlers directly.
     */
    private val socket: Socket? = null
) : RtspConnection {

    /**
     * One connection = one instance of this class.
     *
     * That is the whole concurrency model: [RtspServer] accepts every socket a sender opens and
     * builds a handler for it, so pairing state, FairPlay state and the SDP session of one
     * connection can never leak into another. Media that belongs to the *session* rather than the
     * socket (symbol keys, the mirror data server, the event channel, the timing client) lives in
     * [AirPlayReceiver]; everything below is per-connection by construction.
     */

    /** Set once the connection is closed, so late callbacks stop writing to a dead socket. */
    @Volatile
    private var closed = false

    /** Last volume the sender set (AirPlay dB); returned to GET_PARAMETER and GET /info queries. */
    @Volatile private var currentVolume: Float = initialVolume

    /** The live socket; set from the constructor, cleared on close. */
    @Volatile
    private var client: Socket? = socket

    private var currentCSeq: String? = null

    @Volatile
    private var currentSession: SessionDescription? = null

    /** Per-connection AirPlay pairing state (pair-setup / pair-verify). */
    @Volatile
    private var pairingSession: PairingSession? = null

    /** Per-connection FairPlay state (fp-setup handshake + stream-key decrypt). */
    @Volatile
    private var fairPlay: FairPlay? = null

    /** Remote (sender) address of the active control connection — needed for AirPlay 2 NTP. */
    @Volatile
    private var currentRemoteAddress: java.net.InetAddress? = null

    /** True once an AirPlay 2 mirroring SETUP has run on this connection (no ANNOUNCE/SDP). */
    @Volatile
    private var isMirrorSession = false

    /** Mirror stream types currently active (96 = audio, 110 = video). Drives TEARDOWN routing.
     *  `protected` so tests can seed it without driving the full FairPlay SETUP handshake. */
    protected val activeStreamTypes = mutableSetOf<Int>()

    private var setupCount = 0

    private val requestReader = RtspRequestReader(
        maxMessageBytes = MAX_MESSAGE_BYTES,
        maxPhotoBytes = PhotoHandler.MAX_PHOTO_BYTES
    )

    /**
     * Callback for decoded H.264 NAL units from the RTP stream.
     * Set by [AirPlayReceiver] after RECORD — wires to [VideoDecoder.decodeNalUnit].
     * Null for audio-only streams.
     */
    @Volatile
    var onVideoNalUnit: ((nalUnit: ByteArray, ptsUs: Long) -> Unit)? = null

    /**
     * True once this connection is past "a peer opened a socket" — i.e. a stream has been set up
     * or a legacy SDP session exists. Used to decide whether an idle control connection is a live
     * session (leave it alone) or an abandoned one (close it — see [serve]).
     */
    private fun isSessionActive(): Boolean =
        isMirrorSession || setupCount > 0 || activeStreamTypes.isNotEmpty() || currentSession != null

    /**
     * Serves one accepted connection until the peer goes away.
     *
     * Runs on a coroutine of its own ([RtspServer] launches it), which is what lets the event
     * channel and the control channel be open at the same time.
     */
    override fun serve() {
        val socket = client ?: return
        val inputStream = socket.getInputStream()
        val outputStream = socket.getOutputStream()

        // The first read is the one that has to be patient.
        //
        // WHY: an Apple sender's *event channel* is a second TCP connection that sends **nothing**
        // — the receiver writes to it. If the port is not "connected" for the sender, the session
        // fails, so an idle connection here is normal and must not be dropped; it only has to be
        // released when the session truly ends. Ten minutes of complete silence on a socket that
        // has never sent a request is a dead peer.
        socket.soTimeout = NO_REQUEST_IDLE_TIMEOUT_MS

        currentRemoteAddress = socket.inetAddress
        // Fresh pairing and FairPlay state per connection. Apple opens several connections to
        // this port, so none of this may be shared: a pair-verify on the control connection and
        // a probe on another must not see each other's handshake half-finished.
        pairingSession = PairingSession(PairingKeys.get(context))
        fairPlay = FairPlay()
        var sawRequest = false

        try {
            while (!closed && !socket.isClosed) {
                val request = requestReader.read(inputStream) ?: break

                if (!sawRequest) {
                    sawRequest = true
                    // From here on this is a control connection: short patience while the
                    // handshake is in progress, none once a session exists.
                    socket.soTimeout = HANDSHAKE_IDLE_TIMEOUT_MS
                    trace("Control channel opened by ${request.headers["User-Agent"]?.takeIf { it.isNotBlank() } ?: "sender"}")
                }

                currentCSeq = request.headers["CSeq"]?.takeIf { it.isNotBlank() }
                val response = routeRequest(request)
                sendResponse(outputStream, response)

                // Once a session exists the control channel may legitimately go quiet for
                // minutes (video flows over its own data channel, audio over UDP), so stop
                // timing the connection out.
                if (isSessionActive()) socket.soTimeout = 0

                // After RECORD on a legacy SDP session: a session WITH video switches to
                // interleaved RTP (video arrives $-framed over this TCP socket). An audio-only
                // session (e.g. Apple Music) keeps the RTSP control loop — audio arrives on the
                // UDP port, and the sender keeps sending metadata / volume / FLUSH / TEARDOWN as
                // RTSP requests here that we must keep handling (switching to interleaved mode
                // would skip them → no metadata).
                if (request.method == "RECORD" && response.statusCode == 200 && !isMirrorSession &&
                    currentSession?.hasVideo == true
                ) {
                    Logger.d("RTSP handshake complete — switching to interleaved RTP (video)")
                    break
                }
            }

            val session = currentSession
            if (session != null && session.hasVideo && !closed) {
                RtpInterleaved.readLoop(
                    inputStream = inputStream,
                    onVideoNalUnit = { nalUnit, ptsUs -> onVideoNalUnit?.invoke(nalUnit, ptsUs) },
                    onStreamEnded = { Logger.i("RTP stream ended") }
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            if (!sawRequest) {
                // A connection that never said anything for ten minutes: either an event channel
                // from a session that has long since ended, or a probe. Either way it is dead.
                Logger.d("RTSP: idle connection with no requests — closing it")
            } else {
                Logger.i("RTSP control connection idle for ${HANDSHAKE_IDLE_TIMEOUT_MS / 1000}s with no session — closing it")
                trace("Connection timed out mid-handshake")
            }
        } catch (e: Exception) {
            if (!closed) Logger.e("Error handling RTSP client", e)
        } finally {
            Logger.i("RTSP connection closed (${client?.inetAddress?.hostAddress ?: "unknown"})")
            closeQuietly()
        }
    }

    /** Closes this connection. Called by [RtspServer.stop] and by [AirPlayReceiver.stop]. */
    override fun close() {
        closed = true
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { client?.close() }
        client = null
        val wasMirror = isMirrorSession
        currentSession = null
        pairingSession = null
        fairPlay = null
        isMirrorSession = false
        activeStreamTypes.clear()
        setupCount = 0
        if (wasMirror) {
            // A closed control connection ends the mirroring session (the sender does not always
            // send TEARDOWN — locking the phone or losing Wi-Fi just drops the socket).
            onStreamingStopped()
        }
    }

    /** Records a step for the on-TV connection log. */
    private fun trace(message: String) = AirPlayTrace.record(message)

    /** One-line description of an SDP session for the connection log. */
    private fun sessionSummary(session: SessionDescription): String {
        val parts = mutableListOf<String>()
        if (session.hasVideo) parts += "screen video"
        if (session.hasAudio) parts += "${session.audioCodec} audio"
        if (parts.isEmpty()) parts += "no media"
        return parts.joinToString(" + ")
    }

    internal fun routeRequest(request: RtspRequest): RtspResponse {
        Logger.d("RTSP ${request.method} ${request.uri}")
        // Senders attach their DACP reverse-control identity to most requests — capture it so the TV
        // remote can drive playback (DacpClient dedups, so this is cheap to call repeatedly).
        request.headers["Active-Remote"]?.let { onRemoteControlInfo(request.headers["DACP-ID"], it) }
        return when (request.method) {
            "OPTIONS"       -> handleOptionsInternal(request)
            "ANNOUNCE"      -> handleAnnounceInternal(request)
            // AirPlay 2 mirroring SETUP carries a binary plist; legacy audio SETUP carries SDP-ish text.
            "SETUP"         -> if (request.isPlistBody()) handleMirrorSetup(request) else handleSetupInternal(request)
            "RECORD"        -> handleRecordInternal(request)
            "TEARDOWN"      -> handleTeardownInternal(request)
            "GET_PARAMETER" -> handleGetParameter(request)
            "SET_PARAMETER" -> handleSetParameter(request)
            "FLUSH"         -> handleFlush(request)
            "PAUSE"         -> handlePauseInternal(request)
            "AUDIOMODE"     -> handleAudioMode(request)
            // AirPlay 2 buffered-audio control verbs. Acknowledge them (a 501 would abort audio-only
            // playback) and log their bodies so the anchor/rate/peer formats can be implemented.
            "SETRATEANCHORTIME", "SETRATEANCHORTIM" -> handleBufferedControl(request, "SETRATEANCHORTIME")
            "SETPEERS", "SETPEERSX"                 -> handleBufferedControl(request, "SETPEERS")
            "FLUSHBUFFERED"                         -> handleBufferedControl(request, "FLUSHBUFFERED")
            "PUT"           -> routePut(request)
            "DELETE"        -> handlePhotoDeleteInternal(request)
            // AirPlay 2 handshake is HTTP-style (GET/POST with bodies) over the RTSP socket.
            "GET"           -> routeGet(request)
            "POST"          -> routePost(request)
            else            -> handleUnknownInternal(request)
        }
    }

    /** Routes PUT requests (photo sharing `PUT /photo` and video session `PUT /setProperty?...`). */
    private fun routePut(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/setProperty" -> handlePropertyXmlOk(request, "PUT /setProperty")
        else           -> handlePhotoPutInternal(request)
    }

    /** Routes AirPlay 2 GET requests by URI path. */
    private fun routeGet(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/info"          -> handleInfo(request)
        "/playback-info" -> handlePlaybackInfo(request)
        "/scrub"         -> handleScrubGet(request)
        "/server-info"   -> handleServerInfo(request)
        "/getProperty"   -> handlePropertyXmlOk(request, "GET /getProperty")
        else             -> handleUnknownInternal(request)
    }

    /** Routes AirPlay 2 POST requests by URI path. */
    private fun routePost(request: RtspRequest): RtspResponse = when (request.uri.substringBefore("?")) {
        "/pair-setup"  -> handlePairSetup(request)
        "/pair-verify" -> handlePairVerify(request)
        // Apple's HomeKit PIN flows. Hearth advertises a legacy-pairing receiver (feature bit
        // 27, model AppleTV3,2 — see [AirPlayIdentity]), so a well-behaved sender never asks for
        // these. Answer 501 with a trace line rather than 470: "not implemented" is the truth,
        // and a 470 would make the sender pop up a code prompt it can never satisfy.
        "/pair-setup-pin", "/pair-pin-start" -> handleHomeKitPairingRequest(request)
        "/fp-setup"    -> handleFpSetup(request)
        "/fp-setup2"   -> handleFpSetup2(request)
        "/feedback"    -> handleFeedback(request)
        "/audioMode"   -> handleAudioMode(request)
        "/reverse"     -> handleReverse(request)
        "/action"      -> RtspResponse(200, "OK", protocol = request.responseProtocol())
        "/getProperty" -> handlePropertyXmlOk(request, "POST /getProperty")
        // AirPlay video URL mode (non-mirroring): play a URL + drive transport.
        "/play"        -> handleVideoPlay(request)
        "/rate"        -> handleVideoRate(request)
        "/scrub"       -> handleVideoScrubPost(request)
        "/stop"        -> handleVideoStop(request)
        else           -> handleUnknownInternal(request)
    }

    /**
     * POST /reverse — upgrades an AirPlay HTTP connection to the PTTH/1.0 reverse-HTTP event
     * channel (UxPlay `http_handler_reverse` in `lib/http_handlers.h`). iOS/macOS photo and video
     * senders issue this before `PUT /photo` or `POST /play`.
     */
    private fun handleReverse(request: RtspRequest): RtspResponse {
        val purpose = request.headers["X-Apple-Purpose"] ?: "event"
        Logger.i("POST /reverse (purpose=$purpose) — 101 Switching Protocols (PTTH/1.0)")
        return RtspResponse(
            statusCode = 101,
            statusMessage = "Switching Protocols",
            headers = mapOf(
                "Connection" to "Upgrade",
                "Upgrade" to "PTTH/1.0",
            ),
            protocol = "HTTP/1.1"
        )
    }

    /**
     * POST /fp-setup2 — answered with `421 Misdirected Request`, matching UxPlay's
     * `http_handler_fpsetup2` (`lib/http_handlers.h`).
     */
    private fun handleFpSetup2(request: RtspRequest): RtspResponse {
        Logger.i("POST /fp-setup2 — returning 421 Misdirected Request")
        return RtspResponse(421, "Misdirected Request", protocol = request.responseProtocol())
    }

    /**
     * PUT /setProperty and POST/GET /getProperty — answered with XML plist `{"errorCode": 0}`
     * (`Content-Type: text/x-apple-plist+xml`), matching UxPlay's `http_handler_set_property` /
     * `http_handler_get_property` (`lib/http_handlers.h`).
     */
    private fun handlePropertyXmlOk(request: RtspRequest, label: String): RtspResponse {
        Logger.d("$label ${request.uri}")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            bodyBytes = PlistCodec.encodeXml(mapOf("errorCode" to 0L)),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** POST /audioMode or AUDIOMODE — logs the requested audioMode (if plist) and returns 200 OK. */
    private fun handleAudioMode(request: RtspRequest): RtspResponse {
        if (request.isPlistBody()) {
            runCatching {
                val mode = PlistCodec.decode(request.bodyBytes)["audioMode"] as? String
                if (mode != null) Logger.d("audioMode: $mode")
            }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    // ─── AirPlay video URL mode (POST /play, /rate, /scrub, /stop; GET /playback-info, /scrub) ──

    /** POST /play — a media URL to play (binary/XML plist or legacy text body). */
    private fun handleVideoPlay(request: RtspRequest): RtspResponse {
        val fields = if (request.isVideoPlayPlist()) {
            runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull()
        } else {
            request.body.lineSequence().filter { ":" in it }.associate {
                it.substringBefore(":").trim() to it.substringAfter(":").trim()
            }
        }
        val play = fields?.let { VideoPlayRequest.parse(it) }
        if (play == null) {
            AirPlayTrace.record("URL video: rejected invalid or unsupported /play location")
            return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        AirPlayTrace.record("URL video: /play accepted (${if (play.seconds) "seconds" else "fraction"} offset)")
        if (play.seconds) onVideoPlaySeconds(play.url, play.start)
        else onVideoPlay(play.url, play.start)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** POST /rate?value=X — X=0 pause, X≥1 resume. */
    private fun handleVideoRate(request: RtspRequest): RtspResponse {
        val rate = queryParam(request.uri, "value")?.toFloatOrNull() ?: 1f
        Logger.d("POST /rate value=$rate")
        onVideoRate(rate)
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** POST /scrub?position=N — seek to N seconds. */
    private fun handleVideoScrubPost(request: RtspRequest): RtspResponse {
        queryParam(request.uri, "position")?.toDoubleOrNull()?.let {
            Logger.d("POST /scrub position=$it")
            onVideoScrub(it)
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /scrub — current position + duration as text/parameters. */
    private fun handleScrubGet(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val body = "duration: %.6f\r\nposition: %.6f\r\n".format(info?.durationSec ?: 0.0, info?.positionSec ?: 0.0)
        return RtspResponse(200, "OK", body = body, contentType = "text/parameters", protocol = request.responseProtocol())
    }

    /** POST /stop — stop URL playback. */
    private fun handleVideoStop(request: RtspRequest): RtspResponse {
        Logger.i("POST /stop (video URL)")
        onVideoStop()
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /playback-info — XML plist describing current position/duration/rate/ready state. */
    private fun handlePlaybackInfo(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfo()
        val plist: Map<String, Any?> = if (info == null || !info.readyToPlay) {
            mapOf("readyToPlay" to false)
        } else {
            val ranges = listOf(mapOf("start" to 0.0, "duration" to info.durationSec))
            mapOf(
                "duration" to info.durationSec,
                "position" to info.positionSec,
                "rate" to info.rate,
                "readyToPlay" to true,
                "playbackBufferEmpty" to false,
                "playbackBufferFull" to true,
                "playbackLikelyToKeepUp" to true,
                "loadedTimeRanges" to ranges,
                "seekableTimeRanges" to ranges,
            )
        }
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(plist),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** GET /server-info — legacy XML plist of receiver identity for AirPlay video senders. */
    private fun handleServerInfo(request: RtspRequest): RtspResponse {
        // Same identity as the TXT record and GET /info (see [AirPlayIdentity]) — a legacy
        // video sender that sees a different model or feature set here than it read while
        // browsing is entitled to walk away, and this used to answer AppleTV5,3/0x1E5A7FFFF7.
        // Includes macAddress, osBuildVersion, and vv matching UxPlay's http_handler_server_info.
        val mac = com.phairplay.util.NetworkUtils.getMacAddress()
        val info = mapOf(
            "deviceid" to mac,
            "macAddress" to mac,
            "features" to AirPlayIdentity.FEATURES,
            "model" to AirPlayIdentity.MODEL,
            "name" to com.phairplay.util.MdnsNames.sanitize(displayName),
            "osBuildVersion" to "12B435",
            "protovers" to AirPlayIdentity.PROTOCOL_VERSION,
            "srcvers" to AirPlayIdentity.SOURCE_VERSION,
            "vv" to AirPlayIdentity.VERSION,
            "pk" to com.phairplay.airplay.handshake.PairingKeys.get(context).edPublic,
        )
        return RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encodeXml(info),
            contentType = "text/x-apple-plist+xml",
            protocol = request.responseProtocol()
        )
    }

    /** Extracts a query-string parameter (`?k=v&...`) from a request URI. */
    private fun queryParam(uri: String, key: String): String? =
        uri.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")

    /** POST /feedback — macOS health-checks the session every ~2 s; acknowledge with 200 OK. */
    private fun handleFeedback(request: RtspRequest): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("/feedback body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("/feedback body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * Acknowledges an AirPlay 2 buffered-audio control verb (SETRATEANCHORTIME / SETPEERS /
     * FLUSHBUFFERED). Returning 200 keeps an audio-only session alive (a 501 would make macOS abort).
     */
    private fun handleBufferedControl(request: RtspRequest, label: String): RtspResponse {
        val n = request.bodyBytes.size
        if (n > 0) {
            runCatching {
                val p = PlistCodec.decode(request.bodyBytes)
                Logger.d("$label body ($n B): " + p.entries.joinToString { (k, v) ->
                    "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                })
            }.onFailure { Logger.d("$label body ($n B, non-plist)") }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /**
     * GET /info — the sender's first probe.
     *
     * Two shapes arrive on this endpoint and they need different answers (see [InfoResponder]):
     * a small `{qualifier: ["txtAirPlay"]}` body asks for the TXT record as a data blob, while an
     * empty body asks for the capability dictionary. Answering the qualifier request with the
     * capability dictionary leaves the sender without the record it needs at the moment it is
     * deciding how to pair with us.
     */
    private fun handleInfo(request: RtspRequest): RtspResponse {
        val qualifiers = qualifiersFrom(request)
        if (qualifiers.isNotEmpty()) {
            Logger.i("GET /info (qualifier=$qualifiers) — answering with the TXT record(s)")
            trace("Discovery: sent the ${qualifiers.joinToString("+")} record")
            return RtspResponse(
                statusCode = 200,
                statusMessage = "OK",
                bodyBytes = InfoResponder.buildTxtResponse(context, qualifiers),
                contentType = "application/x-apple-binary-plist",
                protocol = request.responseProtocol()
            )
        }
        Logger.i("GET /info — capability record sent (${displayWidth}x$displayHeight, initialVolume=$currentVolume)")
        trace("Discovery: answered GET /info as ${com.phairplay.airplay.AirPlayIdentity.MODEL}")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            bodyBytes = InfoResponder.build(
                context = context,
                displayName = displayName,
                width = displayWidth,
                height = displayHeight,
                initialVolume = currentVolume.toDouble()
            ),
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    }

    /**
     * Reads all requested TXT qualifiers (`txtAirPlay`, `txtRAOP`) from either the URL query
     * string (`GET /info?txtAirPlay&txtRAOP`, BLE discovery) or the `qualifier` array in a binary
     * plist body (matching UxPlay's `raop_handler_info`).
     */
    private fun qualifiersFrom(request: RtspRequest): Set<String> {
        val result = linkedSetOf<String>()
        if (request.uri.contains(InfoResponder.QUALIFIER_TXT_AIRPLAY)) {
            result += InfoResponder.QUALIFIER_TXT_AIRPLAY
        }
        if (request.uri.contains(InfoResponder.QUALIFIER_TXT_RAOP)) {
            result += InfoResponder.QUALIFIER_TXT_RAOP
        }
        if (request.bodyBytes.size >= 8 && request.isPlistBody()) {
            val parsed = runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull()
            val list = parsed?.get("qualifier") as? List<*>
            if (list != null) {
                for (item in list) {
                    when (item as? String) {
                        InfoResponder.QUALIFIER_TXT_RAOP -> result += InfoResponder.QUALIFIER_TXT_RAOP
                        InfoResponder.QUALIFIER_TXT_AIRPLAY -> result += InfoResponder.QUALIFIER_TXT_AIRPLAY
                    }
                }
                if (result.isEmpty()) result += InfoResponder.QUALIFIER_TXT_AIRPLAY
            }
        }
        return result
    }

    /**
     * POST /pair-setup. With PIN auth off (default) this is the anonymous Ed25519 exchange. With PIN
     * auth on, it runs the HomeKit-style SRP pair-setup (TLV8) — showing a PIN on the TV that the
     * user types on the Mac — so only someone with screen access can pair.
     */
    private fun handlePairSetup(request: RtspRequest): RtspResponse {
        // /pair-setup is the anonymous key exchange; PIN access control runs on /pair-setup-pin.
        return try {
            val body = pairingSession!!.pairSetup(request.bodyBytes)
            Logger.i("pair-setup OK (returned ${body.size}-byte public key)")
            trace("Pairing: first-time key exchange answered (legacy pairing)")
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            // Two /pair-setup dialects exist in the wild and they are impossible to tell apart
            // from a bare "pair-setup failed" line, so name the one we were given:
            //   • raw 32-byte Ed25519 request — the anonymous exchange implemented here, what
            //     macOS sends;
            //   • HomeKit TLV8 request — opens with kTLVType_State, needs SRP-6a pair-setup
            //     with an on-screen PIN. Not implemented. If an iOS sender insists on this
            //     dialect, this log line is the evidence and HomeKit pair-setup is the fix.
            if (isHomeKitTlv8PairSetup(request.bodyBytes)) {
                Logger.e("pair-setup is the HomeKit TLV8 dialect (${request.bodyBytes.size} bytes) " +
                         "— not implemented (only the raw 32-byte Ed25519 exchange is)", e)
                trace("Pairing FAILED: sender wants HomeKit pairing, this receiver offers legacy pairing")
            } else {
                Logger.e("pair-setup failed on a ${request.bodyBytes.size}-byte body", e)
                trace("Pairing FAILED at pair-setup: ${e.message ?: e.javaClass.simpleName}")
            }
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    /**
     * True for a HomeKit-style TLV8 `/pair-setup` body: it opens with fragment type 0x06
     * (kTLVType_State), length 0x01, value 0x01 (`06 01 01`) — i.e. "state = M1".
     *
     * The dialect we support is a bare 32-byte Ed25519 public key, whose first byte is
     * arbitrary key material and only collides with `0x06` by chance, so this is a
     * diagnostic hint rather than a protocol guarantee — hence it only steers the log.
     */
    private fun isHomeKitTlv8PairSetup(body: ByteArray): Boolean =
        body.size >= 3 &&
            (body[0].toInt() and 0xFF) == 0x06 &&
            (body[1].toInt() and 0xFF) == 0x01 &&
            (body[2].toInt() and 0xFF) == 0x01

    /**
     * POST /pair-verify — the anonymous X25519 handshake.
     *
     * This is the second half of AirPlay's **legacy** pairing and the step the sender runs on
     * every session after the first `pair-setup`:
     *   M1 (`0x01 ‖ client ECDH pub ‖ client Ed25519 pub`) → `our ECDH pub ‖ encrypted signature`
     *   M2 (`0x00 ‖ encrypted signature`)                  → empty 200
     * The X25519 shared secret it produces is what the mirror stream keys are derived from, so a
     * failure here is fatal to the session — hence the 470 (the status a sender understands as
     * "you are not allowed to talk to me") and a trace line naming the step.
     */
    private fun handlePairVerify(request: RtspRequest): RtspResponse {
        val session = pairingSession
            ?: return RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        return try {
            val body = session.pairVerify(request.bodyBytes)
            val step = if (request.bodyBytes.firstOrNull()?.toInt() == 1) "M1" else "M2"
            Logger.i("pair-verify $step OK (returned ${body.size} bytes)")
            if (step == "M1") {
                trace("Pairing: verify M1 accepted — sender key exchange running")
            } else {
                trace("Pairing: verify complete — sender is trusted for this session")
            }
            RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
        } catch (e: Exception) {
            Logger.e("pair-verify failed", e)
            trace("Pairing FAILED at pair-verify: ${e.message ?: e.javaClass.simpleName}")
            RtspResponse(470, "Connection Authorization Required", protocol = request.responseProtocol())
        }
    }

    /**
     * The HomeKit (SRP/TLV8) pairing endpoints, answered honestly.
     *
     * Hearth does not implement HomeKit pairing: it advertises a legacy-pairing receiver, and
     * legacy pairing is what the "no code, connect from anywhere on the LAN" setup uses. A sender
     * only asks for these when a receiver advertises access control (a PIN or password bit in
     * `statusFlags`, or the HomeKit pairing feature bits) — so reaching this code means the
     * advertisement and the request disagree, which is exactly what the trace line should say.
     */
    private fun handleHomeKitPairingRequest(request: RtspRequest): RtspResponse {
        Logger.w("Sender asked for HomeKit/PIN pairing (${request.uri}) — this receiver only implements legacy pairing")
        trace("Sender asked for PIN/HomeKit pairing — not offered by this receiver")
        return RtspResponse(501, "Not Implemented", protocol = request.responseProtocol())
    }

    /** POST /fp-setup — FairPlay: 16-byte phase 1 → 142-byte reply; 164-byte phase 2 → 32-byte reply. */
    private fun handleFpSetup(request: RtspRequest): RtspResponse = try {
        val fp = fairPlay!!
        val b = request.bodyBytes
        // Diagnostics: byte 4 is the FairPlay version (0x03 mirroring/Safari, 0x02 Apple Music audio);
        // for phase 1, byte 14 is the mode (0..3). Confirms which path a given sender uses.
        val verMode = if (b.size >= 16) " v=0x%02x mode=%d".format(b[4].toInt() and 0xFF, b[14].toInt() and 0xFF)
                      else if (b.size >= 5) " v=0x%02x".format(b[4].toInt() and 0xFF) else ""
        val body = when (b.size) {
            16 -> fp.setup(b)
            164 -> fp.handshake(b)
            else -> throw IllegalArgumentException("unexpected fp-setup size ${b.size}")
        }
        Logger.i("fp-setup phase (${b.size}B in → ${body.size}B out)$verMode OK")
        trace("Encryption: FairPlay key exchange OK (${b.size}-byte phase)")
        RtspResponse(200, "OK", bodyBytes = body, contentType = OCTET_STREAM, protocol = request.responseProtocol())
    } catch (e: Exception) {
        Logger.e("fp-setup failed", e)
        trace("Encryption FAILED at fp-setup: ${e.message ?: e.javaClass.simpleName}")
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /**
     * AirPlay 2 mirroring SETUP (binary plist). Two messages arrive on one connection:
     *  - msg 1 carries `ekey`+`eiv`+`timingPort` → FairPlay-decrypt the AES key, hand it
     *    (with the pairing secret) to the receiver, reply with event/timing ports.
     *  - msg 2 carries `streams`[type 110] → start the mirror data server, reply with its port.
     */
    private fun handleMirrorSetup(request: RtspRequest): RtspResponse = try {
        val req = PlistCodec.decode(request.bodyBytes)
        Logger.i("mirror SETUP plist: " + req.entries.joinToString { (k, v) ->
            "$k=" + when (v) {
                is ByteArray -> "${v.size}B"
                is List<*> -> "list[${v.size}]"
                else -> v.toString()
            }
        })
        val response = mutableMapOf<String, Any?>()

        isMirrorSession = true
        // Extract sender device name / model from SETUP plist (UxPlay raop_handlers.h lines 770-795).
        val senderDeviceName = (req["name"] as? String)?.takeIf { it.isNotBlank() }
            ?: (req["model"] as? String)?.takeIf { it.isNotBlank() }
        if (senderDeviceName != null) {
            onMirrorSenderName(senderDeviceName)
        }
        val isRemoteControlOnly = (req["isRemoteControlOnly"] as? Boolean) == true
        if (isRemoteControlOnly) {
            Logger.i("mirror SETUP: isRemoteControlOnly=true")
        }

        val ekey = req["ekey"] as? ByteArray
        if (ekey != null) {
            val aesKey = fairPlay!!.decrypt(ekey)
            val userAgent = request.headers["User-Agent"]
            val ecdhSecret = pairingSession?.sharedSecret ?: if (isOldProtocolClient(userAgent)) {
                Logger.i("mirror SETUP: legacy client '$userAgent' without pair-verify — using unhashed AES key")
                ByteArray(0)
            } else {
                error("mirror SETUP before pair-verify")
            }
            val aesIv = (req["eiv"] as? ByteArray) ?: ByteArray(16)
            val timingProtocol = req["timingProtocol"] as? String
            val senderTimingPort = if (timingProtocol.equals("None", ignoreCase = true)) {
                0
            } else {
                (req["timingPort"] as? Long)?.toInt() ?: 0
            }
            val remoteAddr = currentRemoteAddress ?: error("mirror SETUP without remote address")
            val (eventPort, timingPort) = onMirrorSetupKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
            response["eventPort"] = eventPort.toLong()
            response["timingPort"] = timingPort.toLong()
            trace("Mirroring: stream key decrypted — starting the media session")
            Logger.i("mirror SETUP keys OK — eventPort=$eventPort timingPort=$timingPort (sender timing $senderTimingPort)")
        }

        val streams = req["streams"] as? List<*>
        if (streams != null) {
            val resStreams = streams.mapNotNull { s ->
                val stream = s as? Map<*, *> ?: return@mapNotNull null
                when ((stream["type"] as? Long)?.toInt()) {
                    110 -> {
                        val scid = (stream["streamConnectionID"] as? Long) ?: 0L
                        val dataPort = onMirrorStreamStart(scid)
                        activeStreamTypes.add(110)
                        Logger.i("mirror stream type=110 streamConnectionID=$scid dataPort=$dataPort")
                        trace("Mirroring: video stream set up — the sender can start sending frames")
                        mapOf("type" to 110L, "dataPort" to dataPort.toLong())
                    }
                    96 -> {
                        // Realtime-audio stream fields (codec type ct, samples-per-frame spf, latencies, …).
                        Logger.d("mirror stream type=96 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("mirror stream type=96 ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val sr = (stream["sr"] as? Long)?.toInt() ?: 44100
                        val ch = (stream["channels"] as? Long)?.toInt() ?: 2
                        val ct = (stream["ct"] as? Long)?.toInt() ?: 8   // 8 = AAC-ELD (mirror), 4 = AAC-LC, 2 = ALAC
                        val spf = (stream["spf"] as? Long)?.toInt() ?: 352   // ALAC frameLength (samples/frame)
                        val (dataPort, controlPort) = onMirrorAudioStart(sr, ch, ct, spf)
                        activeStreamTypes.add(96)
                        Logger.i("audio stream type=96 (ct=$ct ${sr}Hz x$ch spf=$spf) dataPort=$dataPort controlPort=$controlPort")
                        trace("Mirroring: audio stream set up (codec type $ct, ${sr}Hz)")
                        mapOf("type" to 96L, "dataPort" to dataPort.toLong(), "controlPort" to controlPort.toLong())
                    }
                    103 -> {
                        // Buffered (audio-only) AirPlay 2 — accepted + instrumented, but the macOS
                        // Music stream stays FairPlay-encrypted (undecryptable), so playback is not
                        // wired. Stream fields (codec ct, audioFormat, shk/shiv, latencies) logged for ref.
                        Logger.d("buffered audio stream type=103 dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; is List<*> -> "list[${v.size}]"; else -> v.toString() }
                        })
                        if (!audioEnabled) {
                            Logger.i("buffered audio (type=103) ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val dataPort = onBufferedAudioStart()
                        activeStreamTypes.add(103)
                        Logger.i("buffered audio stream type=103 dataPort=$dataPort")
                        trace("Audio: buffered stream set up (Apple Music style playback)")
                        mapOf("type" to 103L, "dataPort" to dataPort.toLong())
                    }
                    else -> {
                        Logger.i("mirror SETUP stream dict: " + stream.entries.joinToString { (k, v) ->
                            "$k=" + when (v) { is ByteArray -> "${v.size}B"; else -> v.toString() }
                        })
                        null
                    }
                }
            }
            response["streams"] = resStreams
        }

        RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encode(response),
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    } catch (e: Exception) {
        Logger.e("mirror SETUP failed", e)
        trace("Mirroring FAILED at SETUP: ${e.message ?: e.javaClass.simpleName}")
        RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
    }

    /** Handles OPTIONS — macOS asks what RTSP methods are supported. */
    open fun handleOptionsInternal(request: RtspRequest): RtspResponse {
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf(
                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            )
        )
    }

    /** Handles ANNOUNCE — macOS/iOS sends SDP describing codecs, ports, and encryption. */
    open fun handleAnnounceInternal(request: RtspRequest): RtspResponse {
        Logger.d("ANNOUNCE body (${request.body.length} bytes)")
        val parsed = SdpParser.parse(request.body)

        if (parsed == null) {
            Logger.e("ANNOUNCE: SDP parsing returned no usable session — rejecting")
            return RtspResponse(statusCode = 400, statusMessage = "Bad Request")
        }

        currentSession = parsed.copy(senderName = extractSenderName(request.headers["User-Agent"]))
        val s = currentSession!!
        trace("Session announced: ${sessionSummary(s)}")
        Logger.i("Session: hasVideo=${s.hasVideo} hasAudio=${s.hasAudio} " +
                 "codec=${s.audioCodec} encrypted=${s.isAudioEncrypted} sender='${s.senderName}'")

        setupCount = 0
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    private fun extractSenderName(userAgent: String?): String {
        if (userAgent.isNullOrBlank()) return DEFAULT_SENDER_NAME
        val name = userAgent.substringBefore("/").trim()
        return name.ifEmpty { DEFAULT_SENDER_NAME }
    }

    /** Handles SETUP — allocates a media channel. */
    open fun handleSetupInternal(request: RtspRequest): RtspResponse {
        setupCount++
        val session = currentSession

        val isVideoSetup = setupCount == 1 && session?.hasVideo == true

        val transport = if (isVideoSetup) {
            "RTP/AVP/TCP;unicast;interleaved=0-1"
        } else {
            "RTP/AVP/UDP;unicast;" +
            "client_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "server_port=$AUDIO_RTP_PORT-${AUDIO_RTP_PORT + 1};" +
            "timing-port=${TimingHandler.TIMING_PORT}"
        }

        Logger.d("SETUP #$setupCount — transport: $transport")
        trace(if (isVideoSetup) "Media: video channel set up (interleaved)" else "Media: audio channel set up (UDP $AUDIO_RTP_PORT)")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Session" to SESSION_ID, "Transport" to transport)
        )
    }

    /** Handles RECORD — macOS/iOS says start sending media now. */
    open fun handleRecordInternal(request: RtspRequest): RtspResponse {
        // AirPlay 2 mirroring has no ANNOUNCE/SDP — RECORD just acknowledges the session.
        if (isMirrorSession) {
            Logger.i("RECORD (mirror session) — OK")
            return RtspResponse(
                statusCode = 200, statusMessage = "OK",
                headers = mapOf("Audio-Latency" to "0"),
                protocol = request.responseProtocol()
            )
        }
        var session = currentSession
        if (session == null) {
            Logger.e("RECORD received but no session from ANNOUNCE — rejecting")
            return RtspResponse(statusCode = 455, statusMessage = "Method Not Valid in This State")
        }
        // RAOP audio (Apple Music) wraps the AES key with FairPlay (SDP `fpaeskey`). Unwrap it via the
        // fp-setup session into the real 16-byte key so the AudioPlayer can AES-CBC-decrypt the stream.
        val fpKey = session.fpAesKey
        if (fpKey != null && session.aesKey == null) {
            val realKey = runCatching { fairPlay?.decrypt(fpKey) }
                .onFailure { Logger.w("RAOP FairPlay audio-key decrypt failed (${fpKey.size}B): ${it.message}") }
                .getOrNull()
            if (realKey != null) {
                Logger.i("RAOP FairPlay (v0x%02x) audio key decrypted → ${realKey.size}B AES key, iv=${session.aesIv?.size ?: 0}B"
                    .format(fairPlay?.negotiatedVersion ?: 0))
                session = session.copy(aesKey = realKey)
                currentSession = session
            }
        }
        Logger.i("RECORD — streaming starting (audioOnly=${session.isAudioOnly}, encrypted=${session.isAudioEncrypted})")
        trace("Streaming: RECORD received — playback starting")
        onStreamingStarted(session)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /**
     * Handles TEARDOWN. A TEARDOWN may target SPECIFIC streams (AirPlay 2 dynamic stream removal —
     * e.g. macOS drops the audio stream when playback stops) or the whole session. If the body lists
     * streams and they're audio-only, we stop just the audio and KEEP the mirror running; otherwise
     * we tear the whole session down. (Previously any TEARDOWN killed the mirror, so stopping audio
     * on the Mac ended screen mirroring entirely.)
     */
    open fun handleTeardownInternal(request: RtspRequest): RtspResponse {
        val streamTypes = parseTeardownStreamTypes(request.bodyBytes)
        if (streamTypes != null && streamTypes.isNotEmpty()) {
            // Stream-level teardown: stop ONLY the listed streams. Keep the session (keys, NTP,
            // event channel) alive so the remaining stream keeps running and a stopped one can be
            // re-added later — e.g. audio keeps playing with video gone, or video keeps mirroring
            // with audio stopped. But if this removes the LAST active stream (e.g. macOS names both
            // 96 and 110 to end the session), fall through to a full teardown so cleanup isn't left
            // to the eventual socket close.
            if (streamTypes.contains(96)) { onMirrorAudioStop(); activeStreamTypes.remove(96) }
            if (streamTypes.contains(110)) { onMirrorVideoStop(); activeStreamTypes.remove(110) }
            if (streamTypes.contains(103)) { onBufferedAudioStop(); activeStreamTypes.remove(103) }
            if (activeStreamTypes.isNotEmpty()) {
                Logger.i("TEARDOWN streams=$streamTypes — stopped those, session continues (active=$activeStreamTypes)")
                return RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
            }
            Logger.i("TEARDOWN streams=$streamTypes — last stream removed, ending session")
        } else {
            // General session TEARDOWN (or iOS >= 27 stopping mirroring without sending TEARDOWN 110,
            // UxPlay commit 546820c): ensure any remaining active streams are explicitly torn down.
            Logger.i("TEARDOWN (session, body=${request.bodyBytes.size}B, active=$activeStreamTypes) — streaming stopping")
            if (activeStreamTypes.remove(96)) onMirrorAudioStop()
            if (activeStreamTypes.remove(110)) onMirrorVideoStop()
            if (activeStreamTypes.remove(103)) onBufferedAudioStop()
        }
        activeStreamTypes.clear()
        trace("Session ended by the sender (TEARDOWN)")
        onStreamingStopped()
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            headers = mapOf("Connection" to "close"),
            protocol = request.responseProtocol()
        )
    }

    /** Parses the `streams` list from a TEARDOWN body, returning the stream `type`s, or null. */
    private fun parseTeardownStreamTypes(body: ByteArray): List<Int>? = runCatching {
        if (body.isEmpty()) return null
        val streams = PlistCodec.decode(body)["streams"] as? List<*> ?: return null
        streams.mapNotNull { ((it as? Map<*, *>)?.get("type") as? Long)?.toInt() }
    }.getOrNull()

    private fun handleGetParameter(request: RtspRequest): RtspResponse {
        val query = request.body.trim()
        Logger.i("GET_PARAMETER body='$query'")
        // macOS queries "volume" during setup and aborts if it gets no value back. Report the
        // last value the sender set so its volume slider reflects the receiver.
        return if (query.startsWith("volume")) {
            RtspResponse(
                statusCode = 200, statusMessage = "OK",
                body = "volume: %.6f\r\n".format(currentVolume),
                contentType = "text/parameters",
                protocol = request.responseProtocol()
            )
        } else {
            RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
        }
    }

    private fun handleSetParameter(request: RtspRequest): RtspResponse {
        val body = request.body
        val contentType = request.headers["Content-Type"]?.lowercase() ?: ""
        // Text bodies carry "volume: <dB>" or "progress: <start>/<curr>/<end>"; binary bodies carry
        // DMAP now-playing metadata or artwork.
        when {
            contentType.startsWith("image/") -> {
                // Album artwork (image/jpeg, image/png). A zero-length body clears it.
                onArtwork(request.bodyBytes)
                Logger.i("SET_PARAMETER artwork (${request.bodyBytes.size}B, $contentType)")
            }
            contentType.contains("dmap") || looksLikeDmap(request.bodyBytes) -> {
                val meta = DmapParser.parseNowPlaying(request.bodyBytes)
                onNowPlayingMetadata(meta.title, meta.artist, meta.album)
                Logger.i("SET_PARAMETER now-playing: title='${meta.title}' artist='${meta.artist}' album='${meta.album}'")
            }
            contentType.contains("text/parameters") || body.trimStart().startsWith("volume") || body.trimStart().startsWith("progress") -> {
                body.lineSequence().forEach { rawLine ->
                    val line = rawLine.trim()
                    when {
                        line.startsWith("volume:", ignoreCase = true) -> {
                            line.substringAfter(":").trim().toFloatOrNull()?.let { v ->
                                currentVolume = v
                                onVolume(v)
                                Logger.d("SET_PARAMETER volume=$v")
                            }
                        }
                        line.startsWith("progress:", ignoreCase = true) -> {
                            Logger.d("SET_PARAMETER $line")
                        }
                    }
                }
            }
            else -> Logger.d("SET_PARAMETER (${request.bodyBytes.size}B, $contentType, unhandled)")
        }
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Heuristic: a DMAP body starts with the `mlit` listing-item container tag. */
    private fun looksLikeDmap(body: ByteArray): Boolean =
        body.size >= 8 && String(body, 0, 4, Charsets.US_ASCII) == "mlit"

    /** Handles any unrecognized RTSP method. */
    open fun handleUnknownInternal(request: RtspRequest): RtspResponse {
        Logger.w("Unknown/unhandled RTSP: ${request.method} ${request.uri} (${request.bodyBytes.size}B body)")
        return RtspResponse(statusCode = 501, statusMessage = "Not Implemented", protocol = request.responseProtocol())
    }

    /**
     * Handles FLUSH — macOS requests we discard buffered media data (seek/pause), carrying
     * `RTP-Info: seq=<nextSeq>;rtptime=<rtptime>` (UxPlay `raop_handler_flush`).
     */
    private fun handleFlush(request: RtspRequest): RtspResponse {
        val nextSeq = parseFlushSeq(request.headers["RTP-Info"])
        Logger.d("FLUSH (RTP-Info='${request.headers["RTP-Info"] ?: ""}' → nextSeq=$nextSeq)")
        onAudioFlush(nextSeq)
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles PAUSE — suspends media delivery. Responds 200 OK; resume arrives as RECORD. */
    open fun handlePauseInternal(request: RtspRequest): RtspResponse {
        Logger.d("PAUSE received")
        return RtspResponse(statusCode = 200, statusMessage = "OK")
    }

    /** Handles AirPlay photo sharing: HTTP `PUT /photo` with a JPEG/PNG body. */
    open fun handlePhotoPutInternal(request: RtspRequest): RtspResponse {
        if (!request.isPhotoRequest()) {
            return handleUnknownInternal(request)
        }

        return when (val validation = PhotoHandler.validatePhoto(
            request.bodyBytes,
            request.headers["Content-Type"]
        )) {
            is PhotoValidation.Valid -> {
                onPhotoReceived(request.bodyBytes, validation.imageType)
                Logger.i("Photo received (${validation.imageType.mimeType}, ${request.bodyBytes.size} bytes)")
                RtspResponse(
                    statusCode = 200,
                    statusMessage = "OK",
                    protocol = request.responseProtocol()
                )
            }
            is PhotoValidation.Invalid -> {
                Logger.w("Photo rejected: ${validation.reason}")
                RtspResponse(
                    statusCode = 400,
                    statusMessage = "Bad Request",
                    protocol = request.responseProtocol()
                )
            }
        }
    }

    /** Handles AirPlay photo clearing: HTTP `DELETE /photo`. */
    open fun handlePhotoDeleteInternal(request: RtspRequest): RtspResponse {
        if (!request.isPhotoRequest()) {
            return handleUnknownInternal(request)
        }

        onPhotoCleared()
        Logger.i("Photo cleared")
        return RtspResponse(
            statusCode = 200,
            statusMessage = "OK",
            protocol = request.responseProtocol()
        )
    }

    private fun sendResponse(outputStream: OutputStream, response: RtspResponse) {
        // Binary-safe: build the header block as ASCII, then write the raw body bytes.
        // Content-Length must be the BYTE length (not String.length) so binary plists,
        // FairPlay payloads, and encrypted bodies are framed correctly.
        val wire = response.wireBody()
        val head = StringBuilder()
        head.append("${response.protocol} ${response.statusCode} ${response.statusMessage}\r\n")
        val cseq = currentCSeq
        if (response.protocol.startsWith("RTSP")) {
            if (cseq != null) {
                head.append("CSeq: $cseq\r\n")
                if ("Audio-Jack-Status" !in response.headers) {
                    head.append("Audio-Jack-Status: connected; type=digital\r\n")
                }
            }
        }
        head.append("Server: AirTunes/${AirPlayIdentity.SOURCE_VERSION}\r\n")
        response.contentType?.let { head.append("Content-Type: $it\r\n") }
        response.headers.forEach { (key, value) ->
            head.append("$key: $value\r\n")
        }
        if (wire.isNotEmpty()) {
            head.append("Content-Length: ${wire.size}\r\n")
        }
        head.append("\r\n")
        outputStream.write(head.toString().toByteArray(Charsets.US_ASCII))
        if (wire.isNotEmpty()) {
            outputStream.write(wire)
        }
        outputStream.flush()
    }

    companion object {
        /** The AirPlay RTSP port. [MdnsService] advertises it and [RtspServer] listens on it. */
        const val RTSP_PORT = 7000

        /**
         * How long a control connection may sit idle *while the handshake is in progress*.
         * A sender that stops mid-handshake is gone, not thinking — and every later attempt
         * gets its own connection now, so closing this one costs nothing.
         */
        private const val HANDSHAKE_IDLE_TIMEOUT_MS = 120_000

        /**
         * How long a connection that has never sent a request is kept open. This is the event
         * channel, which is silent by design (the receiver writes to it), so it has to outlive
         * the whole session rather than a handshake.
         */
        private const val NO_REQUEST_IDLE_TIMEOUT_MS = 10 * 60 * 1000

        private const val MAX_MESSAGE_BYTES = 65536
        private const val OCTET_STREAM = "application/octet-stream"
        private const val TIMING_PORT = 6002   // matches TimingHandler's UDP NTP port
        private const val SESSION_ID = "HearthSession"
        private const val AUDIO_RTP_PORT = 6001
        private const val DEFAULT_SENDER_NAME = "AirPlay Sender"

        private val OLD_PROTOCOL_USER_AGENTS = listOf(
            "AirMyPC",
            "Parrot",
            "AirParrot",
            "Tryall",
            "TuneBlade",
            "TuneAero",
            "ScreenParrot",
            "Evt-Air",
        )

        /**
         * Returns `true` for legacy 3rd-party AirPlay senders that do not perform `pair-verify`
         * and use the unhashed FairPlay AES key (UxPlay `raop_handlers.h` lines 817-829).
         */
        internal fun isOldProtocolClient(userAgent: String?): Boolean {
            if (userAgent.isNullOrBlank()) return false
            return OLD_PROTOCOL_USER_AGENTS.any { userAgent.contains(it, ignoreCase = false) }
        }

        /**
         * Parses `seq=<nextSeq>` out of an RTSP `RTP-Info` header (e.g. `seq=12345;rtptime=67890`),
         * returning `-1` if absent or malformed (UxPlay `raop_handler_flush`).
         */
        internal fun parseFlushSeq(rtpInfo: String?): Int {
            if (rtpInfo.isNullOrBlank()) return -1
            for (part in rtpInfo.split(';', ',')) {
                val trimmed = part.trim()
                if (trimmed.startsWith("seq=", ignoreCase = true)) {
                    val seq = trimmed.substringAfter('=').trim().toIntOrNull()
                    if (seq != null && seq in 0..0xFFFF) return seq
                }
            }
            return -1
        }
    }
}

private fun RtspRequest.isPhotoRequest(): Boolean =
    uri.substringBefore("?") == PhotoHandler.PHOTO_PATH

private fun RtspRequest.responseProtocol(): String =
    if (protocol.startsWith("HTTP/")) protocol else "RTSP/1.0"

/** True if the body is an Apple binary plist (AirPlay 2 mirroring SETUP), vs legacy SDP. */
private fun RtspRequest.isPlistBody(): Boolean =
    bodyBytes.size >= 8 && String(bodyBytes, 0, 8, Charsets.US_ASCII) == "bplist00"

/** `/play` also accepts XML plists; keep that broader detection local to URL-video parsing. */
private fun RtspRequest.isVideoPlayPlist(): Boolean {
    val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
        ?.value.orEmpty()
    val text = body.trimStart()
    return isPlistBody() || contentType.contains("plist", ignoreCase = true) ||
        text.startsWith("<?xml", ignoreCase = true) || text.startsWith("<plist", ignoreCase = true)
}
