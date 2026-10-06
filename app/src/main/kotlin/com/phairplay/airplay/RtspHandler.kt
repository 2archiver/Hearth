package com.phairplay.airplay

import com.phairplay.airplay.handshake.FairPlay
import com.phairplay.airplay.handshake.InfoResponder
import com.phairplay.airplay.handshake.PairingKeys
import com.phairplay.airplay.handshake.PairingSession
import com.phairplay.airplay.handshake.PlistCodec
import com.phairplay.util.Logger
import java.io.OutputStream
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    private val onStreamingStartedDetailed: ((session: SessionDescription, token: SessionToken?, connectionId: String) -> Unit)? = null,
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
    /** Generation-aware form preferred by the receiver; stale keys cannot replace a newer pipeline. */
    private val onMirrorSetupKeysSession: ((ByteArray, ByteArray, ByteArray, java.net.InetAddress, Int, SessionToken?) -> Pair<Int, Int>)? = null,
    /** AirPlay 2 mirror SETUP: start the video data server (type 110); returns its data port. */
    private val onMirrorStreamStart: (streamConnectionId: Long) -> Int = { 0 },
    private val onMirrorStreamStartSession: ((streamConnectionId: Long, token: SessionToken?) -> Int)? = null,
    /** AirPlay 2 SETUP: start the audio server (type 96; ct 8 AAC-ELD mirror / 4 AAC-LC / 2 ALAC). spf = samples/frame. */
    private val onMirrorAudioStart: (sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int) -> Pair<Int, Int> = { _, _, _, _ -> 0 to 0 },
    private val onMirrorAudioStartSession: ((Int, Int, Int, Int, SessionToken?) -> Pair<Int, Int>)? = null,
    /** AirPlay 2 mirror TEARDOWN of just the audio stream (type 96) — stop audio, keep video. */
    private val onMirrorAudioStop: () -> Unit = {},
    private val onMirrorAudioStopSession: ((SessionToken?) -> Unit)? = null,
    /** AirPlay 2 mirror TEARDOWN of just the video stream (type 110) — stop video, keep audio. */
    private val onMirrorVideoStop: () -> Unit = {},
    private val onMirrorVideoStopSession: ((SessionToken?) -> Unit)? = null,
    /** AirPlay 2 buffered audio-only SETUP (type 103, Apple Music → TV); returns the TCP data port. */
    private val onBufferedAudioStart: () -> Int = { 0 },
    private val onBufferedAudioStartSession: ((SessionToken?) -> Int)? = null,
    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private val onBufferedAudioStop: () -> Unit = {},
    private val onBufferedAudioStopSession: ((SessionToken?) -> Unit)? = null,
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
    /** Session-aware form used by the receiver to reject stale playback callbacks. */
    private val onVideoPlaySession: ((connectionId: String, url: String, start: Double, seconds: Boolean, token: SessionToken?) -> Unit)? = null,
    /** AirPlay video transport: POST /rate (≤0 pause, >0 resume). */
    private val onVideoRate: (rate: Float) -> Unit = {},
    private val onVideoRateSession: ((rate: Float, token: SessionToken?) -> Unit)? = null,
    /** AirPlay video transport: POST /scrub — seek to position (seconds). */
    private val onVideoScrub: (positionSec: Double) -> Unit = {},
    private val onVideoScrubSession: ((positionSec: Double, token: SessionToken?) -> Unit)? = null,
    /** AirPlay video transport: POST /stop — stop URL playback. */
    private val onVideoStop: () -> Unit = {},
    private val onVideoStopSession: ((token: SessionToken?) -> Unit)? = null,
    /** Current URL-video playback snapshot for GET /playback-info and GET /scrub. */
    private val onPlaybackInfo: () -> com.phairplay.airplay.PlaybackInfo? = { null },
    private val onPlaybackInfoSession: ((token: SessionToken?) -> com.phairplay.airplay.PlaybackInfo?)? = null,
    /** Sender's DACP reverse-control identity from RTSP headers (DACP-ID + Active-Remote token). */
    private val onRemoteControlInfo: (dacpId: String?, activeRemote: String?) -> Unit = { _, _ -> },
    /** Sender's human-readable device name/model extracted from AirPlay 2 SETUP plist (`name`/`model`). */
    private val onMirrorSenderName: (senderName: String) -> Unit = {},
    /** RTSP FLUSH notification with optional next RTP sequence number from `RTP-Info: seq=...`. */
    private val onAudioFlush: (nextSeq: Int) -> Unit = {},
    /** Initial AirPlay volume in dB (`-30..0`) carried over from receiver state (UxPlay commit `412c5b7`). */
    initialVolume: Float = 0f,
    /**
     * Preferred over [onStreamingStopped] when supplied: the same "session over" signal, plus a
     * plain-language reason ("sender closed the control connection", "TEARDOWN", …) so the
     * on-TV log says *why* a session ended. [AirPlayReceiver] also uses it to ignore a stop
     * coming from a connection that no longer owns the session.
     */
    private val onSessionStopped: ((reason: String) -> Unit)? = null,
    /**
     * The accepted socket this handler serves. Supplied by [RtspServer] through the connection
     * factory; null in unit tests, which drive the request handlers directly.
     */
    private val socket: Socket? = null,
    /** Opaque receiver-generated label, never derived from the sender address. */
    private val traceConnectionId: String = "C0",
    /** Called after a protocol session becomes real (mirror keys, RECORD, or valid /play). */
    private val onSessionClaim: ((
        connectionId: String,
        protocolSessionFingerprint: String?,
        role: AirPlayConnectionRole,
        mode: AirPlaySessionMode,
    ) -> SessionToken?)? = null,
    /** Associates a secondary channel only if its protocol fingerprint matches an active session. */
    private val onSessionAssociate: ((
        connectionId: String,
        protocolSessionFingerprint: String?,
        role: AirPlayConnectionRole,
    ) -> SessionToken?)? = null,
    /** Session-aware cleanup; old handlers can continue to use [onSessionStopped]. */
    private val onSessionStoppedDetailed: ((
        connectionId: String,
        token: SessionToken?,
        reason: String,
        explicitTeardown: Boolean,
        activeStreams: Set<Int>,
    ) -> Unit)? = null,
    /** Reports confirmed media setup/teardown for the session ownership model. */
    private val onMediaRoleChanged: (
        (connectionId: String, token: SessionToken?, role: AirPlayMediaRole, active: Boolean) -> Unit
    ) = { _, _, _, _ -> },
    /** URL-player status is reported with its generation; stale callbacks are ignored by the receiver. */
    private val onVideoPlaybackState: (
        (connectionId: String, token: SessionToken?, state: AirPlayPlaybackState, failureReason: String?) -> Unit
    ) = { _, _, _, _ -> },
    /**
     * The receiver's sender-mediated (FCUP) HLS transport: the PTTH reverse channel, the `/play`
     * that needs it, and the `POST /action` replies that answer it. Null when the receiver cannot
     * host a bridge, in which case a sender-mediated `/play` is refused with 400 as before.
     */
    private val hlsHost: SenderMediatedHlsHost? = null,
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

    /** Makes socket shutdown and session callbacks idempotent across close()/serve() races. */
    private val closeProcessed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Serializes every write to this connection's socket. Two writers exist once a connection is
     * upgraded to PTTH: the request/response loop, and the reverse channel (which writes FCUP
     * requests to the same socket from the bridge's threads). A frame must never interleave with
     * another frame's header block.
     */
    private val writeLock = Any()

    /** True once `POST /reverse` upgraded this connection to the PTTH reverse-HTTP channel. */
    @Volatile
    private var reverseUpgraded = false

    /** Writer barrier: FCUP frames cannot race ahead of the 101 response on the same socket. */
    private val reverseUpgradeReady = CountDownLatch(1)
    private val ptthResponseReader = PtthResponseReader()

    private val stopReported = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Why this connection is closing — reported with the session stop (diagnostics). */
    @Volatile
    private var closeReason: String = "the sender closed the control connection"

    /** Reports a session end with generation and protocol semantics when the receiver supports it. */
    private fun stopSession(reason: String, explicit: Boolean = false) {
        if (!stopReported.compareAndSet(false, true)) return
        val detailed = onSessionStoppedDetailed
        if (detailed != null) {
            detailed(traceConnectionId, sessionToken, reason, explicit, activeStreamTypes.toSet())
        } else {
            val callback = onSessionStopped
            if (callback != null) callback(reason) else onStreamingStopped()
        }
    }

    /** Associates a protocol-backed session claim with this exact RTSP connection generation. */
    private fun claimSession(mode: AirPlaySessionMode, role: AirPlayConnectionRole = currentRole) {
        currentMode = mode
        currentRole = role
        stopReported.set(false)
        explicitTeardown = false
        val initialState = if (mode == AirPlaySessionMode.URL_VIDEO || mode == AirPlaySessionMode.SENDER_MEDIATED_HLS) {
            AirPlayPlaybackState.LOADING
        } else {
            AirPlayPlaybackState.NEGOTIATING
        }
        sessionToken = onSessionClaim?.invoke(
            traceConnectionId,
            protocolSessionFingerprint,
            role,
            mode,
        ) ?: sessionToken
        reportPlaybackState(initialState)
    }

    private fun associateSession(role: AirPlayConnectionRole): Boolean {
        currentRole = role
        val token = onSessionAssociate?.invoke(traceConnectionId, protocolSessionFingerprint, role)
            ?: return false
        sessionToken = token
        return true
    }

    private fun reportPlaybackState(state: AirPlayPlaybackState, failureReason: String? = null) {
        playbackState = state
        if (currentMode == AirPlaySessionMode.URL_VIDEO || currentMode == AirPlaySessionMode.SENDER_MEDIATED_HLS) {
            onVideoPlaybackState(traceConnectionId, sessionToken, state, failureReason)
        }
    }

    private fun reportMediaRole(role: AirPlayMediaRole, active: Boolean) =
        onMediaRoleChanged(traceConnectionId, sessionToken, role, active)

    private fun sessionStateLabel(): String = when {
        playbackState == AirPlayPlaybackState.FAILED -> "failed"
        currentMode == AirPlaySessionMode.URL_VIDEO || currentMode == AirPlaySessionMode.SENDER_MEDIATED_HLS ->
            "${currentMode.name.lowercase()}/${playbackState.name.lowercase()}"
        isMirrorSession -> "mirroring/${activeStreamTypes.sorted().joinToString("+").ifBlank { "negotiating" }}"
        currentSession?.isAudioOnly == true -> "audio_only/${playbackState.name.lowercase()}"
        currentSession != null -> "legacy/${playbackState.name.lowercase()}"
        else -> "negotiating"
    }

    /** Normalizes request targets before diagnostics; queries and unknown path values are omitted. */
    private fun normalizedEndpoint(uri: String): String {
        val path = runCatching {
            val parsed = java.net.URI(uri)
            if (parsed.isAbsolute) parsed.rawPath.orEmpty() else uri.substringBefore('?').substringBefore('#')
        }.getOrElse { uri.substringBefore('?').substringBefore('#') }
        val normalized = path.ifBlank { "/" }.lowercase(Locale.US)
        return if (normalized in KNOWN_ENDPOINTS) normalized else "/unsupported"
    }

    private fun protocolFingerprint(request: RtspRequest): String? {
        // RTSP `Session` is receiver-controlled here (Hearth returns a fixed compatibility value),
        // so it is not safe identity evidence. Only fingerprint an explicit sender session ID.
        return AirPlaySessionFingerprint.of(request.header("X-Apple-Session-ID"))
    }

    /**
     * True while this connection carries a live media session. [RtspServer] uses it so that,
     * when it must retire a connection, it retires an idle probe instead of the control channel.
     */
    override val holdsSession: Boolean
        get() = !closed && (isSessionActive() || reverseUpgraded || (
            currentRole == AirPlayConnectionRole.DIRECT_VIDEO_CONTROL &&
                (currentMode == AirPlaySessionMode.URL_VIDEO ||
                    currentMode == AirPlaySessionMode.SENDER_MEDIATED_HLS) &&
                playbackState !in setOf(AirPlayPlaybackState.FAILED, AirPlayPlaybackState.DISCONNECTED)
            ))

    /** Last volume the sender set (AirPlay dB); returned to GET_PARAMETER and GET /info queries. */
    @Volatile private var currentVolume: Float = initialVolume

    /** The live socket; set from the constructor, cleared on close. */
    @Volatile
    private var client: Socket? = socket

    private var currentCSeq: String? = null

    @Volatile
    private var currentSession: SessionDescription? = null

    /** Current session generation, assigned by the receiver only after protocol evidence exists. */
    @Volatile
    private var sessionToken: SessionToken? = null

    @Volatile
    private var protocolSessionFingerprint: String? = null

    @Volatile
    private var currentRole: AirPlayConnectionRole = AirPlayConnectionRole.CONTROL

    @Volatile
    private var currentMode: AirPlaySessionMode = AirPlaySessionMode.NEGOTIATING

    @Volatile
    private var playbackState: AirPlayPlaybackState = AirPlayPlaybackState.NEGOTIATING

    @Volatile
    private var explicitTeardown = false

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

    /** True while a mirror SETUP that first marked this connection as a session is running. */
    private var mirrorSetupRollback = false

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

    /** Generation-aware interleaved RTP callback used by the shared receiver decoder. */
    @Volatile
    var onVideoNalUnitWithSession: ((nalUnit: ByteArray, ptsUs: Long, token: SessionToken?, connectionId: String) -> Unit)? = null

    /**
     * True once this connection is past "a peer opened a socket" — i.e. a stream has been set up
     * or a legacy SDP session exists. Used to decide whether an idle control connection is a live
     * session (leave it alone) or an abandoned one (close it — see [serve]).
     */
    private fun isSessionActive(): Boolean =
        isMirrorSession || setupCount > 0 || activeStreamTypes.isNotEmpty() || currentSession != null ||
            (currentMode in URL_VIDEO_MODES &&
                playbackState !in setOf(AirPlayPlaybackState.FAILED, AirPlayPlaybackState.DISCONNECTED))

    /**
     * Serves one accepted connection until the peer goes away.
     *
     * Runs on a coroutine of its own ([RtspServer] launches it), which is what lets the event
     * channel and the control channel be open at the same time.
     */
    override fun serve() {
        val socket = client ?: return
        var sawRequest = false

        try {
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

            while (!closed && !socket.isClosed) {
                if (reverseUpgraded) {
                    // The sender's PTTH acknowledgements are responses on this socket, not new RTSP
                    // requests. Consume their bounded framing and keep the reverse channel alive.
                    when (val response = ptthResponseReader.read(inputStream)) {
                        is PtthResponseReader.ReadOutcome.Response -> {
                            trace(
                                "PTTH response consumed (${response.protocol} " +
                                    "status=${response.statusCode ?: "unknown"}, body=${response.bodyBytes} B)",
                                role = AirPlayConnectionRole.REVERSE_EVENT.name,
                            )
                            if (response.closeRequested) {
                                closeReason = "sender closed the upgraded PTTH channel"
                                break
                            }
                            continue
                        }
                        is PtthResponseReader.ReadOutcome.End -> {
                            closeReason = if (response.cleanEof) {
                                "sender EOF on the upgraded PTTH channel"
                            } else {
                                "PTTH response framing failed: ${response.reason}"
                            }
                            trace(
                                "Connection $traceConnectionId ended on PTTH channel " +
                                    if (response.cleanEof) "(EOF)" else "(${response.reason})",
                                role = AirPlayConnectionRole.REVERSE_EVENT.name,
                                kind = if (response.cleanEof) AirPlayTrace.Kind.INFO else AirPlayTrace.Kind.FAILURE,
                            )
                            break
                        }
                    }
                }

                val outcome = requestReader.readDetailed(inputStream)
                if (outcome is RtspRequestReader.ReadOutcome.End) {
                    closeReason = when {
                        outcome.cleanEof && isSessionActive() -> "sender EOF on this RTSP connection without TEARDOWN"
                        outcome.cleanEof -> "sender EOF before a session started"
                        else -> "request parse/read failure: ${outcome.reason}"
                    }
                    trace(
                        "Connection $traceConnectionId ended: " +
                            if (outcome.cleanEof) "EOF ($closeReason)" else "request read failed ($closeReason)",
                        kind = if (outcome.cleanEof) AirPlayTrace.Kind.INFO else AirPlayTrace.Kind.FAILURE,
                    )
                    break
                }
                val request = (outcome as RtspRequestReader.ReadOutcome.Request).value

                if (reverseUpgraded) {
                    // This socket is the PTTH reverse channel now: it is write-only from the receiver
                    // to the sender, and anything arriving on it is not a control request. Answering
                    // would interleave a response with the bridge's own frames, so the channel is
                    // released with a stated reason instead — which also ends any sender-mediated
                    // session that was using it, rather than leaving a player waiting for playlists.
                    hlsHost?.releaseReverseChannel(traceConnectionId)
                    closeReason = "request received on the upgraded PTTH reverse channel"
                    trace(closeReason, kind = AirPlayTrace.Kind.FAILURE)
                    break
                }

                if (!sawRequest) {
                    sawRequest = true
                    // A silent reverse/event socket is classified when its upgrade request arrives.
                    socket.soTimeout = HANDSHAKE_IDLE_TIMEOUT_MS
                    trace("Control request channel active", role = currentRole.name)
                }

                currentCSeq = request.header("CSeq")?.takeIf { it.isNotBlank() }
                val response = routeRequest(request)
                sendResponse(outputStream, response)
                if (reverseUpgraded) {
                    // Publish the reverse writer only after the peer has received the complete 101.
                    // A parallel /play may already be waiting to send FCUP on this socket. Keep a
                    // long idle timeout so a dead PTTH peer cannot occupy one of the bounded slots
                    // forever; each incoming framed response restarts the socket's read timeout.
                    runCatching { socket.soTimeout = PTTH_IDLE_TIMEOUT_MS }
                    reverseUpgradeReady.countDown()
                }

                // Once a normal session exists its control channel may legitimately go quiet for
                // minutes (video flows over its own data channel, audio over UDP), so stop timing it.
                if (isSessionActive() && !reverseUpgraded) socket.soTimeout = 0

                if (response.headers.entries.any { it.key.equals("Connection", true) && it.value.equals("close", true) }) {
                    closeReason = if (explicitTeardown) "sender closed the RTSP connection after TEARDOWN" else "peer requested connection close"
                    break
                }

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
                    onVideoNalUnit = { nalUnit, ptsUs ->
                        val detailed = onVideoNalUnitWithSession
                        if (detailed != null) detailed(nalUnit, ptsUs, sessionToken, traceConnectionId)
                        else onVideoNalUnit?.invoke(nalUnit, ptsUs)
                    },
                    onStreamEnded = { Logger.i("RTP stream ended") }
                )
            }
        } catch (e: java.net.SocketTimeoutException) {
            closeReason = when {
                reverseUpgraded -> "upgraded PTTH channel idle timeout"
                !sawRequest -> "silent connection idle timeout"
                else -> "control handshake idle timeout"
            }
            trace(
                "Connection $traceConnectionId timed out: $closeReason",
                role = if (reverseUpgraded) AirPlayConnectionRole.REVERSE_EVENT.name else currentRole.name,
                kind = AirPlayTrace.Kind.FAILURE,
            )
            Logger.i("RTSP $traceConnectionId timed out ($closeReason)")
        } catch (e: java.net.SocketException) {
            if (!closed) {
                closeReason = "socket exception (${e.javaClass.simpleName})"
                trace("Connection $traceConnectionId socket exception", kind = AirPlayTrace.Kind.FAILURE)
                Logger.w("RTSP $traceConnectionId socket exception (${e.javaClass.simpleName})")
            }
        } catch (e: Exception) {
            if (!closed) {
                closeReason = "request handler exception (${e.javaClass.simpleName})"
                trace("Connection $traceConnectionId failed ($closeReason)", kind = AirPlayTrace.Kind.FAILURE)
                Logger.e("Error handling RTSP connection $traceConnectionId", e)
            }
        } finally {
            Logger.i("RTSP connection $traceConnectionId closed")
            closeQuietly()
        }
    }

    /** The session coordinator can revoke an obsolete PTTH offer without waiting for sender EOF. */
    private fun closeForReason(reason: String) {
        if (closed) return
        closeReason = reason.take(MAX_CLOSE_REASON_CHARS)
        closed = true
        closeQuietly()
    }

    /** Closes this connection. Called by [RtspServer.stop] and by [AirPlayReceiver.stop]. */
    override fun close() {
        if (!closed) closeReason = "receiver shutdown closed this connection"
        closed = true
        closeQuietly()
    }

    private fun closeQuietly() {
        if (!closeProcessed.compareAndSet(false, true)) return
        if (reverseUpgraded) {
            hlsHost?.releaseReverseChannel(traceConnectionId)
        }
        closed = true
        val hadSession = sessionToken != null || isSessionActive()
        runCatching { client?.close() }
        client = null
        if (hadSession) stopSession(closeReason, explicit = explicitTeardown)
        currentSession = null
        pairingSession = null
        fairPlay = null
        isMirrorSession = false
        activeStreamTypes.clear()
        setupCount = 0
        currentRole = AirPlayConnectionRole.PROBE
    }

    /** Records a step with safe session/connection labels; no address or protocol token is included. */
    private fun trace(
        message: String,
        kind: AirPlayTrace.Kind = AirPlayTrace.Kind.INFO,
        role: String = currentRole.name,
    ) = AirPlayTrace.record(
        message = message,
        sessionId = sessionToken?.sessionId,
        connectionId = traceConnectionId,
        role = role,
        kind = kind,
    )

    /** One-line description of an SDP session for the connection log. */
    private fun sessionSummary(session: SessionDescription): String {
        val parts = mutableListOf<String>()
        if (session.hasVideo) parts += "screen video"
        if (session.hasAudio) parts += "${session.audioCodec} audio"
        if (parts.isEmpty()) parts += "no media"
        return parts.joinToString(" + ")
    }

    internal fun routeRequest(request: RtspRequest): RtspResponse {
        val startedNanos = System.nanoTime()
        currentCSeq = request.header("CSeq")?.let(::safeCSeq)
        protocolFingerprint(request)?.let { protocolSessionFingerprint = it }
        val endpoint = normalizedEndpoint(request.uri)
        val before = sessionStateLabel()
        val response = try {
            dispatchRequest(request)
        } catch (error: Exception) {
            AirPlayTrace.record(
                "Request handler failed for ${safeMethod(request.method)} $endpoint (${error.javaClass.simpleName})",
                sessionId = sessionToken?.sessionId,
                connectionId = traceConnectionId,
                role = currentRole.name,
                kind = AirPlayTrace.Kind.FAILURE,
            )
            Logger.e("AirPlay request handler failed for ${safeMethod(request.method)} $endpoint (${error.javaClass.simpleName})")
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        val durationMillis = (System.nanoTime() - startedNanos) / 1_000_000L
        val contentType = request.header("Content-Type")
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.US)
            ?.takeIf { it.matches(CONTENT_TYPE_PATTERN) }
            ?.take(80)
        AirPlayTrace.request(
            method = safeMethod(request.method),
            endpoint = endpoint,
            cseq = currentCSeq,
            contentType = contentType,
            bodyBytes = request.bodyBytes.size,
            durationMillis = durationMillis,
            status = response.statusCode,
            before = before,
            after = sessionStateLabel(),
            sessionId = sessionToken?.sessionId,
            connectionId = traceConnectionId,
            role = currentRole.name,
        )
        return response
    }

    private fun safeCSeq(value: String): String? =
        value.takeIf { it.length <= 32 && it.all(Char::isDigit) }

    private fun safeMethod(value: String): String =
        value.uppercase(Locale.US).takeIf { it in KNOWN_METHODS } ?: "OTHER"

    private fun dispatchRequest(request: RtspRequest): RtspResponse {
        // DACP values are used by the remote-control client, but are never written to diagnostics.
        request.header("Active-Remote")?.let { onRemoteControlInfo(request.header("DACP-ID"), it) }
        if (request.method.equals("POST", true) && normalizedEndpoint(request.uri) == "/reverse") {
            associateSession(AirPlayConnectionRole.REVERSE_EVENT)
        }
        return when (request.method.uppercase(Locale.US)) {
            "OPTIONS"       -> handleOptionsInternal(request)
            "ANNOUNCE"      -> handleAnnounceInternal(request)
            "SETUP"         -> if (request.isPlistBody()) handleMirrorSetup(request) else handleSetupInternal(request)
            "RECORD"        -> handleRecordInternal(request)
            "TEARDOWN"      -> handleTeardownInternal(request)
            "GET_PARAMETER" -> handleGetParameter(request)
            "SET_PARAMETER" -> handleSetParameter(request)
            "FLUSH"         -> handleFlush(request)
            "PAUSE"         -> handlePauseInternal(request)
            "AUDIOMODE"     -> handleAudioMode(request)
            "SETRATEANCHORTIME", "SETRATEANCHORTIM" -> handleBufferedControl(request, "SETRATEANCHORTIME")
            "SETPEERS", "SETPEERSX"                 -> handleBufferedControl(request, "SETPEERS")
            "FLUSHBUFFERED"                         -> handleBufferedControl(request, "FLUSHBUFFERED")
            "PUT"           -> routePut(request)
            "DELETE"        -> handlePhotoDeleteInternal(request)
            "GET"           -> routeGet(request)
            "POST"          -> routePost(request)
            else            -> handleUnknownInternal(request)
        }
    }

    /** Routes PUT requests (photo sharing `PUT /photo` and video session `PUT /setProperty?...`). */
    private fun routePut(request: RtspRequest): RtspResponse = when (normalizedEndpoint(request.uri)) {
        "/setproperty" -> handlePropertyXmlOk(request, "PUT /setProperty")
        "/photo" -> handlePhotoPutInternal(request)
        else -> handleUnknownInternal(request)
    }

    /** Routes AirPlay 2 GET requests by normalized, query-free URI path. */
    private fun routeGet(request: RtspRequest): RtspResponse = when (normalizedEndpoint(request.uri)) {
        "/info"          -> handleInfo(request)
        "/playback-info" -> handlePlaybackInfo(request)
        "/scrub"         -> handleScrubGet(request)
        "/server-info"   -> handleServerInfo(request)
        "/getproperty"   -> handlePropertyXmlOk(request, "GET /getProperty")
        else             -> handleUnknownInternal(request)
    }

    /** Routes AirPlay 2 POST requests by normalized, query-free URI path. */
    private fun routePost(request: RtspRequest): RtspResponse = when (normalizedEndpoint(request.uri)) {
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
        "/audiomode"   -> handleAudioMode(request)
        "/reverse"     -> handleReverse(request)
        "/action"      -> handleAction(request)
        "/getproperty" -> handlePropertyXmlOk(request, "POST /getProperty")
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
        val purpose = when (request.header("X-Apple-Purpose")?.lowercase(Locale.US)) {
            null, "event" -> "event"
            "display" -> "display"
            else -> "other"
        }
        val senderSessionId = request.header("X-Apple-Session-ID")?.trim()?.takeIf { it.isNotEmpty() }
        // An offer may be published before the 101 is written, but its writer is gated until then:
        // a concurrent /play cannot put a POST /event ahead of the protocol upgrade response.
        val writer: (ByteArray) -> Boolean = { frame ->
            val ready = try {
                reverseUpgradeReady.await(PTTH_UPGRADE_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            ready && writeRaw(frame)
        }
        reverseUpgraded = true
        val accepted = hlsHost?.registerReverseChannel(
            connectionId = traceConnectionId,
            senderSessionId = senderSessionId,
            writer = writer,
            revoke = ::closeForReason,
        ) == true
        if (accepted) {
            trace(
                "Reverse PTTH channel registered (purpose=$purpose, senderSessionIdPresent=${senderSessionId != null})",
                role = AirPlayConnectionRole.REVERSE_EVENT.name,
            )
            Logger.i("POST /reverse (purpose=$purpose) — 101 Switching Protocols; reverse channel registered")
        } else {
            trace(
                "Reverse upgrade acknowledged but no bridge is available to host it (purpose=$purpose)",
                role = AirPlayConnectionRole.REVERSE_EVENT.name,
                kind = AirPlayTrace.Kind.FAILURE,
            )
            Logger.i("POST /reverse (purpose=$purpose) — 101 Switching Protocols; no bridge host")
        }
        return RtspResponse(
            statusCode = 101,
            statusMessage = "Switching Protocols",
            headers = mapOf("Connection" to "Upgrade", "Upgrade" to "PTTH/1.0"),
            protocol = "HTTP/1.1",
        )
    }

    /**
     * POST /action — the sender's answer to a reverse-channel FCUP request.
     *
     * The body is a binary plist whose `params` carry the request id Hearth sent and the fetched
     * playlist/segment bytes. It is handed to the session bridge, which matches the id, refuses a
     * mismatched URL, and either accepts the bytes or reports a URL-free reason. Without a bridge
     * (no reverse channel was ever upgraded, or the receiver has no host) the request is refused
     * with 501 and a trace line — never acknowledged as success.
     */
    private fun handleAction(request: RtspRequest): RtspResponse {
        val host = hlsHost
        val senderSessionId = request.header("X-Apple-Session-ID")?.trim()?.takeIf { it.isNotEmpty() }
        if (host == null) {
            return handleUnsupportedAction(request)
        }
        val result = host.deliverAction(senderSessionId, request.bodyBytes)
        trace(
            "FCUP/action ${if (result.httpStatus < 400) "accepted" else "rejected"}: ${result.summary}",
            kind = if (result.httpStatus < 400) AirPlayTrace.Kind.INFO else AirPlayTrace.Kind.FAILURE,
        )
        return RtspResponse(
            result.httpStatus,
            if (result.httpStatus < 400) "OK" else "Bad Request",
            protocol = request.responseProtocol(),
        )
    }

    /**
     * `POST /action` with no bridge host: a build (or a test) without the FCUP transport, or an
     * answer that arrived after its session had already ended.
     *
     * It is deliberately **not** answered with 200: a sender whose playlist action was accepted
     * keeps a session in a state Hearth cannot serve, and the user is left with a connection and no
     * video. The reply is 501, the reason is traced, and the *field names* (never the values, which
     * can carry signed URLs) are recorded so the log shows which action arrived.
     */
    private fun handleUnsupportedAction(request: RtspRequest): RtspResponse {
        val decoded = runCatching { PlistCodec.decode(request.bodyBytes) }.getOrNull()
        val type = (decoded?.get("type") as? String)?.take(MAX_ACTION_TYPE_CHARS)
        val fields = decoded?.keys
            ?.map { it.lowercase(Locale.US) }
            ?.filter { it in SAFE_ACTION_FIELDS }
            ?.sorted()
        trace(
            "FCUP/action rejected: no sender-mediated session is active" +
                (type?.let { " (type=$it)" } ?: "") +
                (fields?.takeIf { it.isNotEmpty() }?.let { " (fields=${it.joinToString(",")})" } ?: ""),
            kind = AirPlayTrace.Kind.FAILURE,
        )
        return RtspResponse(501, "Not Implemented", protocol = request.responseProtocol())
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
        Logger.d(label)
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
                val modePresent = PlistCodec.decode(request.bodyBytes).containsKey("audioMode")
                if (modePresent) Logger.d("audioMode request parsed")
            }
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    // ─── AirPlay video URL mode (POST /play, /rate, /scrub, /stop; GET /playback-info, /scrub) ──

    /** POST /play — direct HTTP(S) video URL (binary/XML plist or legacy text body). */
    private fun handleVideoPlay(request: RtspRequest): RtspResponse = when (
        val parsed = VideoPlayRequest.decodeBody(request.bodyBytes, request.header("Content-Type"))
    ) {
        is BodyParse.Success -> {
            claimSession(AirPlaySessionMode.URL_VIDEO, AirPlayConnectionRole.DIRECT_VIDEO_CONTROL)
            trace(
                "URL video /play accepted (${parsed.encoding.name.lowercase(Locale.US)}, " +
                    "${if (parsed.request.seconds) "seconds" else "fraction"} offset)",
                kind = AirPlayTrace.Kind.LIFECYCLE,
            )
            val sessionCallback = onVideoPlaySession
            if (sessionCallback != null) {
                sessionCallback(
                    traceConnectionId,
                    parsed.request.url,
                    parsed.request.start,
                    parsed.request.seconds,
                    sessionToken,
                )
            } else if (parsed.request.seconds) {
                onVideoPlaySeconds(parsed.request.url, parsed.request.start)
            } else {
                onVideoPlay(parsed.request.url, parsed.request.start)
            }
            RtspResponse(200, "OK", protocol = request.responseProtocol())
        }
        is BodyParse.SenderMediated -> handleSenderMediatedPlay(request, parsed)
        is BodyParse.UnsupportedScheme -> {
            val scheme = parsed.scheme.takeIf { it.matches(SCHEME_PATTERN) } ?: "unknown"
            trace(
                "URL video /play rejected: scheme=$scheme requires unsupported sender-mediated transport",
                kind = AirPlayTrace.Kind.FAILURE,
            )
            // 400 Bad Request, not 501: this is the answer 1.8.1/1.8.2 senders already get for a
            // location this receiver cannot play, and the regression suite pins it. The trace line
            // above is what distinguishes "internal HLS transport we do not implement" from a
            // malformed body.
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        is BodyParse.Invalid -> {
            trace(
                "URL video /play rejected: invalid request (${parsed.reason.take(96)})",
                kind = AirPlayTrace.Kind.FAILURE,
            )
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    /**
     * `POST /play` for a sender-mediated location (the YouTube app's `mlhls://…/master.m3u8`).
     *
     * The location is only resolvable by the sender, so the receiver needs a live PTTH reverse
     * channel to ask it for the playlists. If there is none (or the receiver has no bridge host),
     * the honest answer is 400 with a stated reason: Hearth cannot play this, and pretending
     * otherwise would leave the sender believing video is on its way. No capability bit is flipped.
     */
    private fun handleSenderMediatedPlay(request: RtspRequest, parsed: BodyParse.SenderMediated): RtspResponse {
        val senderSessionId = request.header("X-Apple-Session-ID")?.trim()?.takeIf { it.isNotEmpty() }
        claimSession(AirPlaySessionMode.SENDER_MEDIATED_HLS, AirPlayConnectionRole.DIRECT_VIDEO_CONTROL)
        trace(
            "Sender-mediated /play received (${parsed.encoding.name.lowercase(Locale.US)})",
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        val host = hlsHost
        if (host == null) {
            trace(
                "Sender-mediated /play (${parsed.scheme}) rejected: no FCUP bridge host in this build",
                kind = AirPlayTrace.Kind.FAILURE,
            )
            reportPlaybackState(AirPlayPlaybackState.FAILED, "play-negotiation:no FCUP bridge host")
            return RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
        val result = host.startSenderMediatedPlay(
            SenderMediatedPlayRequest(
                connectionId = traceConnectionId,
                senderSessionId = senderSessionId,
                location = parsed.request.url,
                startSeconds = parsed.request.start,
                seconds = parsed.request.seconds,
                token = sessionToken,
            )
        )
        return if (result.accepted) {
            trace(
                "Sender-mediated /play accepted (${parsed.encoding.name.lowercase(Locale.US)}, " +
                    "${if (parsed.request.seconds) "seconds" else "fraction"} offset)",
                kind = AirPlayTrace.Kind.LIFECYCLE,
            )
            RtspResponse(200, "OK", protocol = request.responseProtocol())
        } else {
            val reason = result.reason?.take(96) ?: "unsupported"
            val stage = when (result.failureStage) {
                AirPlayPlaybackFailureStage.MANIFEST -> "manifest"
                AirPlayPlaybackFailureStage.PLAYER_SETUP -> "player-setup"
                AirPlayPlaybackFailureStage.PLAYER_PREPARATION -> "player-preparation"
                AirPlayPlaybackFailureStage.FIRST_FRAME -> "first-frame"
                AirPlayPlaybackFailureStage.SEEK -> "seek"
                AirPlayPlaybackFailureStage.NETWORK -> "network"
                AirPlayPlaybackFailureStage.DECODER -> "decoder"
                AirPlayPlaybackFailureStage.DRM -> "drm"
                AirPlayPlaybackFailureStage.PLAYBACK -> "playback"
                AirPlayPlaybackFailureStage.AUDIO_SETUP -> "audio-setup"
                AirPlayPlaybackFailureStage.PLAY_NEGOTIATION, null -> "play-negotiation"
                AirPlayPlaybackFailureStage.UNKNOWN -> "playback"
            }
            trace("Sender-mediated /play rejected at $stage: $reason", kind = AirPlayTrace.Kind.FAILURE)
            reportPlaybackState(AirPlayPlaybackState.FAILED, "$stage:$reason")
            RtspResponse(400, "Bad Request", protocol = request.responseProtocol())
        }
    }

    /** POST /rate?value=X — X=0 pause, X≥1 resume. */
    private fun handleVideoRate(request: RtspRequest): RtspResponse {
        val parsed = queryParam(request.uri, "value")?.toFloatOrNull()
        val rate = parsed?.takeIf { it.isFinite() } ?: 1f
        Logger.d("POST /rate received (pause=${rate <= 0f})")
        onVideoRateSession?.invoke(rate, sessionToken) ?: onVideoRate(rate)
        if (currentMode in URL_VIDEO_MODES) {
            reportPlaybackState(if (rate <= 0f) AirPlayPlaybackState.PAUSED else AirPlayPlaybackState.LOADING)
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** POST /scrub?position=N — seek to N seconds. */
    private fun handleVideoScrubPost(request: RtspRequest): RtspResponse {
        queryParam(request.uri, "position")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.let {
            Logger.d("POST /scrub accepted")
            onVideoScrubSession?.invoke(it, sessionToken) ?: onVideoScrub(it)
        }
        return RtspResponse(200, "OK", protocol = request.responseProtocol())
    }

    /** GET /scrub — current position + duration as text/parameters. */
    private fun handleScrubGet(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfoSession?.invoke(sessionToken) ?: onPlaybackInfo()
        val body = "duration: %.6f\r\nposition: %.6f\r\n".format(info?.durationSec ?: 0.0, info?.positionSec ?: 0.0)
        return RtspResponse(200, "OK", body = body, contentType = "text/parameters", protocol = request.responseProtocol())
    }

    /** POST /stop — stop URL playback and close the session deliberately. */
    private fun handleVideoStop(request: RtspRequest): RtspResponse {
        Logger.i("POST /stop (URL video)")
        reportPlaybackState(AirPlayPlaybackState.STOPPING)
        onVideoStopSession?.invoke(sessionToken) ?: onVideoStop()
        explicitTeardown = true
        reportPlaybackState(AirPlayPlaybackState.DISCONNECTED)
        stopSession("sender sent POST /stop", explicit = true)
        return RtspResponse(
            200,
            "OK",
            headers = mapOf("Connection" to "close"),
            protocol = request.responseProtocol(),
        )
    }

    /** GET /playback-info — XML plist describing current position/duration/rate/ready state. */
    private fun handlePlaybackInfo(request: RtspRequest): RtspResponse {
        val info = onPlaybackInfoSession?.invoke(sessionToken) ?: onPlaybackInfo()
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
                val fieldCount = PlistCodec.decode(request.bodyBytes).size
                Logger.d("/feedback plist received ($n B, $fieldCount fields)")
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
                val fieldCount = PlistCodec.decode(request.bodyBytes).size
                Logger.d("$label plist received ($n B, $fieldCount fields)")
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
                Logger.e("pair-setup is the HomeKit TLV8 dialect (${request.bodyBytes.size} bytes) — not implemented")
                trace(
                    "Pairing FAILED: HomeKit pairing is not implemented",
                    kind = AirPlayTrace.Kind.FAILURE,
                )
            } else {
                Logger.e("pair-setup failed on a ${request.bodyBytes.size}-byte body (${e.javaClass.simpleName})")
                trace("Pairing FAILED at pair-setup (${e.javaClass.simpleName})", kind = AirPlayTrace.Kind.FAILURE)
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
            Logger.e("pair-verify failed (${e.javaClass.simpleName})")
            trace("Pairing FAILED at pair-verify (${e.javaClass.simpleName})", kind = AirPlayTrace.Kind.FAILURE)
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
        Logger.w("Sender asked for HomeKit/PIN pairing — this receiver only implements legacy pairing")
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
        Logger.e("fp-setup failed (${e.javaClass.simpleName})")
        trace("Encryption FAILED at fp-setup (${e.javaClass.simpleName})", kind = AirPlayTrace.Kind.FAILURE)
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
        Logger.i("mirror SETUP plist parsed (${req.size} fields)")
        val response = mutableMapOf<String, Any?>()

        val wasMirrorSession = isMirrorSession
        isMirrorSession = true
        mirrorSetupRollback = !wasMirrorSession
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
            val userAgent = request.header("User-Agent")
            val ecdhSecret = pairingSession?.sharedSecret ?: if (isOldProtocolClient(userAgent)) {
                Logger.i("mirror SETUP: legacy-client compatibility path without pair-verify")
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
            claimSession(AirPlaySessionMode.MIRRORING, AirPlayConnectionRole.CONTROL)
            val (eventPort, timingPort) = onMirrorSetupKeysSession?.invoke(
                aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort, sessionToken,
            ) ?: onMirrorSetupKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort)
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
                        claimSession(AirPlaySessionMode.MIRRORING, AirPlayConnectionRole.CONTROL)
                        val scid = (stream["streamConnectionID"] as? Long) ?: 0L
                        val dataPort = onMirrorStreamStartSession?.invoke(scid, sessionToken)
                            ?: onMirrorStreamStart(scid)
                        activeStreamTypes.add(110)
                        reportMediaRole(AirPlayMediaRole.MIRROR_VIDEO, true)
                        Logger.i("mirror stream type=110 data server ready on port $dataPort")
                        trace("Mirroring: video stream set up — waiting for the sender's data connection")
                        mapOf("type" to 110L, "dataPort" to dataPort.toLong())
                    }
                    96 -> {
                        if (!audioEnabled) {
                            Logger.i("mirror stream type=96 ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        val sr = (stream["sr"] as? Long)?.toInt() ?: 44100
                        val ch = (stream["channels"] as? Long)?.toInt() ?: 2
                        val ct = (stream["ct"] as? Long)?.toInt() ?: 8   // 8 = AAC-ELD (mirror), 4 = AAC-LC, 2 = ALAC
                        val spf = (stream["spf"] as? Long)?.toInt() ?: 352   // ALAC frameLength (samples/frame)
                        val hasVideoStream = activeStreamTypes.contains(110) || streams.any { item ->
                            ((item as? Map<*, *>)?.get("type") as? Long)?.toInt() == 110
                        }
                        claimSession(
                            if (hasVideoStream) AirPlaySessionMode.MIRRORING else AirPlaySessionMode.AUDIO_ONLY,
                            AirPlayConnectionRole.CONTROL,
                        )
                        val (dataPort, controlPort) = onMirrorAudioStartSession?.invoke(sr, ch, ct, spf, sessionToken)
                            ?: onMirrorAudioStart(sr, ch, ct, spf)
                        activeStreamTypes.add(96)
                        reportMediaRole(AirPlayMediaRole.MIRROR_AUDIO, true)
                        Logger.i("audio stream type=96 (ct=$ct ${sr}Hz x$ch spf=$spf) dataPort=$dataPort controlPort=$controlPort")
                        trace("Mirroring: audio stream set up (codec type $ct, ${sr}Hz)")
                        mapOf("type" to 96L, "dataPort" to dataPort.toLong(), "controlPort" to controlPort.toLong())
                    }
                    103 -> {
                        // Accepted as a transport probe only; the FairPlay-encrypted stream is not decoded.
                        if (!audioEnabled) {
                            Logger.i("buffered audio (type=103) ignored (audio disabled in settings)")
                            return@mapNotNull null
                        }
                        claimSession(AirPlaySessionMode.AUDIO_ONLY, AirPlayConnectionRole.CONTROL)
                        val dataPort = onBufferedAudioStartSession?.invoke(sessionToken) ?: onBufferedAudioStart()
                        activeStreamTypes.add(103)
                        reportMediaRole(AirPlayMediaRole.MIRROR_AUDIO, true)
                        Logger.i("buffered audio stream type=103 data server ready on port $dataPort")
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

        mirrorSetupRollback = false   // SETUP succeeded: this connection now really holds a session
        RtspResponse(
            200, "OK",
            bodyBytes = PlistCodec.encode(response),
            contentType = "application/x-apple-binary-plist",
            protocol = request.responseProtocol()
        )
    } catch (e: Exception) {
        // A SETUP that failed did not start a session; leaving the flag set would make this
        // connection's eventual close tear down whichever session *is* running.
        if (mirrorSetupRollback && activeStreamTypes.isEmpty()) isMirrorSession = false
        mirrorSetupRollback = false
        Logger.e("mirror SETUP failed (${e.javaClass.simpleName})")
        trace("Mirroring FAILED at SETUP (${e.javaClass.simpleName})", kind = AirPlayTrace.Kind.FAILURE)
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

        currentSession = parsed.copy(senderName = extractSenderName(request.header("User-Agent")))
        val s = currentSession!!
        trace("Session announced: ${sessionSummary(s)}")
        Logger.i("Session announced (video=${s.hasVideo}, audio=${s.hasAudio}, codec=${s.audioCodec})")

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
        if (session != null) {
            claimSession(
                if (session.hasVideo) AirPlaySessionMode.MIRRORING else AirPlaySessionMode.AUDIO_ONLY,
                AirPlayConnectionRole.CONTROL,
            )
        }

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
            val mode = if (activeStreamTypes.contains(110)) AirPlaySessionMode.MIRRORING else AirPlaySessionMode.AUDIO_ONLY
            claimSession(mode, AirPlayConnectionRole.CONTROL)
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
                .onFailure { Logger.w("RAOP FairPlay audio-key decrypt failed (${fpKey.size}B, ${it.javaClass.simpleName})") }
                .getOrNull()
            if (realKey != null) {
                Logger.i("RAOP FairPlay (v0x%02x) audio key decrypted → ${realKey.size}B AES key, iv=${session.aesIv?.size ?: 0}B"
                    .format(fairPlay?.negotiatedVersion ?: 0))
                session = session.copy(aesKey = realKey)
                currentSession = session
            }
        }
        val mode = if (session.hasVideo) AirPlaySessionMode.MIRRORING else AirPlaySessionMode.AUDIO_ONLY
        claimSession(mode, AirPlayConnectionRole.CONTROL)
        if (session.hasVideo) reportMediaRole(AirPlayMediaRole.MIRROR_VIDEO, true)
        if (session.hasAudio) reportMediaRole(AirPlayMediaRole.MIRROR_AUDIO, true)
        Logger.i("RECORD — media pipeline starting (audioOnly=${session.isAudioOnly}, encrypted=${session.isAudioEncrypted})")
        trace("Streaming: RECORD received — media pipeline starting", kind = AirPlayTrace.Kind.LIFECYCLE)
        val detailedCallback = onStreamingStartedDetailed
        if (detailedCallback != null) detailedCallback(session, sessionToken, traceConnectionId) else onStreamingStarted(session)
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
            if (streamTypes.contains(96)) {
                onMirrorAudioStopSession?.invoke(sessionToken) ?: onMirrorAudioStop()
                activeStreamTypes.remove(96)
                reportMediaRole(AirPlayMediaRole.MIRROR_AUDIO, false)
            }
            if (streamTypes.contains(110)) {
                onMirrorVideoStopSession?.invoke(sessionToken) ?: onMirrorVideoStop()
                activeStreamTypes.remove(110)
                reportMediaRole(AirPlayMediaRole.MIRROR_VIDEO, false)
            }
            if (streamTypes.contains(103)) {
                onBufferedAudioStopSession?.invoke(sessionToken) ?: onBufferedAudioStop()
                activeStreamTypes.remove(103)
                reportMediaRole(AirPlayMediaRole.MIRROR_AUDIO, false)
            }
            if (activeStreamTypes.isNotEmpty()) {
                Logger.i("TEARDOWN streams=$streamTypes — stopped those, session continues (active=$activeStreamTypes)")
                return RtspResponse(statusCode = 200, statusMessage = "OK", protocol = request.responseProtocol())
            }
            Logger.i("TEARDOWN streams=$streamTypes — last stream removed, ending session")
        } else {
            // General session TEARDOWN (or iOS >= 27 stopping mirroring without sending TEARDOWN 110,
            // UxPlay commit 546820c): ensure any remaining active streams are explicitly torn down.
            Logger.i("TEARDOWN session request (body=${request.bodyBytes.size}B, activeTypes=${activeStreamTypes.size})")
            if (activeStreamTypes.remove(96)) {
                onMirrorAudioStopSession?.invoke(sessionToken) ?: onMirrorAudioStop()
            }
            if (activeStreamTypes.remove(110)) {
                onMirrorVideoStopSession?.invoke(sessionToken) ?: onMirrorVideoStop()
            }
            if (activeStreamTypes.remove(103)) {
                onBufferedAudioStopSession?.invoke(sessionToken) ?: onBufferedAudioStop()
            }
            reportMediaRole(AirPlayMediaRole.MIRROR_AUDIO, false)
            reportMediaRole(AirPlayMediaRole.MIRROR_VIDEO, false)
        }
        activeStreamTypes.clear()
        explicitTeardown = true
        reportPlaybackState(AirPlayPlaybackState.DISCONNECTED)
        stopSession(
            if (streamTypes.isNullOrEmpty()) "sender sent TEARDOWN"
            else "sender sent TEARDOWN for last stream (count=${streamTypes.size})",
            explicit = true,
        )
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
        Logger.i("GET_PARAMETER received (${request.bodyBytes.size} bytes)")
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
        val rawContentType = request.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.US).orEmpty()
        val contentType = rawContentType.takeIf { it.matches(CONTENT_TYPE_PATTERN) }.orEmpty()
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
                Logger.i("SET_PARAMETER now-playing metadata received")
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
                            Logger.d("SET_PARAMETER playback progress received")
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
        Logger.w("Unknown/unhandled RTSP request: ${safeMethod(request.method)} ${normalizedEndpoint(request.uri)} (${request.bodyBytes.size}B)")
        return RtspResponse(statusCode = 501, statusMessage = "Not Implemented", protocol = request.responseProtocol())
    }

    /**
     * Handles FLUSH — macOS requests we discard buffered media data (seek/pause), carrying
     * `RTP-Info: seq=<nextSeq>;rtptime=<rtptime>` (UxPlay `raop_handler_flush`).
     */
    private fun handleFlush(request: RtspRequest): RtspResponse {
        val nextSeq = parseFlushSeq(request.header("RTP-Info"))
        Logger.d("FLUSH (RTP-Info='${request.header("RTP-Info") ?: ""}' → nextSeq=$nextSeq)")
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
            request.header("Content-Type")
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
        val headBytes = head.toString().toByteArray(Charsets.US_ASCII)
        synchronized(writeLock) {
            outputStream.write(headBytes)
            if (wire.isNotEmpty()) {
                outputStream.write(wire)
            }
            outputStream.flush()
        }
    }

    /**
     * Writes one already-framed request to this connection's socket (used by the PTTH reverse
     * channel). Returns false when the socket is gone; bytes are never logged.
     */
    private fun writeRaw(frame: ByteArray): Boolean {
        val stream = synchronized(this) { if (closed) null else client?.getOutputStream() } ?: return false
        return try {
            synchronized(writeLock) {
                if (closed) return false
                stream.write(frame)
                stream.flush()
            }
            true
        } catch (e: Exception) {
            // The channel notices the false return and stops trying; the connection's own read loop
            // reports the socket failure with its reason.
            false
        }
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
        private const val PTTH_UPGRADE_WRITE_TIMEOUT_MS = 5_000L
        private const val PTTH_IDLE_TIMEOUT_MS = 10 * 60 * 1000
        private const val MAX_CLOSE_REASON_CHARS = 128

        private const val MAX_MESSAGE_BYTES = 65536
        private const val OCTET_STREAM = "application/octet-stream"
        private const val TIMING_PORT = 6002   // matches TimingHandler's UDP NTP port
        private const val SESSION_ID = "HearthSession"
        private const val AUDIO_RTP_PORT = 6001
        private const val DEFAULT_SENDER_NAME = "AirPlay Sender"

        /**
         * Session modes in which a URL player is (or may become) the picture on screen. Used by the
         * idle-connection rules and the URL-video control verbs, so a sender-mediated session is
         * treated exactly like a direct one once its `/play` was accepted.
         */
        private val URL_VIDEO_MODES =
            setOf(AirPlaySessionMode.URL_VIDEO, AirPlaySessionMode.SENDER_MEDIATED_HLS)

        private val KNOWN_ENDPOINTS = setOf(
            "/", "/1", "/action", "/audiomode", "/feedback", "/fp-setup", "/fp-setup2",
            "/getproperty", "/info", "/pair-pin-start", "/pair-setup", "/pair-setup-pin",
            "/pair-verify", "/photo", "/play", "/playback-info", "/rate", "/reverse",
            "/scrub", "/server-info", "/setproperty", "/stop",
        )
        private val KNOWN_METHODS = setOf(
            "OPTIONS", "ANNOUNCE", "SETUP", "RECORD", "TEARDOWN", "GET_PARAMETER",
            "SET_PARAMETER", "FLUSH", "PAUSE", "AUDIOMODE", "SETRATEANCHORTIME",
            "SETRATEANCHORTIM", "SETPEERS", "SETPEERSX", "FLUSHBUFFERED", "PUT", "DELETE",
            "GET", "POST",
        )
        private val SCHEME_PATTERN = Regex("[a-z][a-z0-9+.-]{0,15}")
        /** A `/action` type is a short tag (`unhandledURLResponse`); anything longer is not one. */
        private const val MAX_ACTION_TYPE_CHARS = 48

        private val SAFE_ACTION_FIELDS = setOf(
            "action", "content-location", "contentlocation", "contenttype", "id", "metadata",
            "mediatype", "params", "playback-mode", "protocolversion", "request-id",
            "requestid", "start-position", "start-position-seconds", "streamingprotocol", "type", "url",
        )
        private val CONTENT_TYPE_PATTERN = Regex("""[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+""")

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
