package com.phairplay.airplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.phairplay.airplay.handshake.AirPlayNtpClient
import com.phairplay.airplay.handshake.AudioStreamServer
import com.phairplay.airplay.handshake.BufferedAudioServer
import com.phairplay.airplay.handshake.MirrorStreamServer
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.MdnsNames
import com.phairplay.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AirPlayReceiver — Top-level orchestrator for the AirPlay 2 receiver pipeline.
 *
 * WHY: Coordinates all AirPlay components into a single lifecycle:
 * - [MdnsService]: mDNS advertising (makes device visible in sender pickers)
 * - [RtspHandler]: RTSP handshake (OPTIONS → ANNOUNCE → SETUP → RECORD)
 * - [VideoDecoder]: H.264 hardware decode via MediaCodec → SurfaceView
 * - [AudioPlayer]: AES-128-CTR decrypt + AAC/ALAC decode → AudioTrack
 *
 * HOW: [PhairPlayService] creates this receiver and calls [start]/[stop].
 * The pipeline activates lazily — VideoDecoder and AudioPlayer are created
 * only after RECORD is received, when [SessionDescription] is available.
 *
 * For audio-only streams (music, podcasts), only [AudioPlayer] is started —
 * no [VideoDecoder] and no fullscreen streaming surface is needed.
 *
 * State changes are reported via [onStateChanged] to [PhairPlayService].
 *
 * Example:
 *   val receiver = AirPlayReceiver(
 *       context = context,
 *       displayName = settings.effectiveDisplayName,
 *       videoSurfaceProvider = { streamingScreen.getSurface() },
 *       onStateChanged = { state -> /* update UI */ }
 *   )
 *   receiver.start()
 *   receiver.stop()
 */
internal class AirPlayReceiver(
    private val context: Context,
    /**
     * User-configured display name from Settings — the spoofed identity. Blank or otherwise
     * unusable values resolve to [MdnsNames.DEFAULT_DISPLAY_NAME] ("Apple TV") rather than to
     * the Android device name, so the name is always under the user's control.
     */
    private val displayName: String = MdnsNames.DEFAULT_DISPLAY_NAME,
    /** Advertised mirroring resolution (from the "high resolution" setting). */
    private val mirrorWidth: Int = 1920,
    private val mirrorHeight: Int = 1080,
    /** Whether to accept the mirroring audio stream (experimental — see AppSettings.mirrorAudioEnabled). */
    private val audioEnabled: Boolean = false,
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    /**
     * Called with the sender name when a streaming session starts (RECORD received).
     *
     * The name is extracted from the RTSP `User-Agent` header. The caller
     * ([PhairPlayService]) uses this to update the [ActiveConnection] and notification
     * text with the real sender identifier instead of the generic "AirPlay Sender".
     *
     * Guaranteed to be called BEFORE [onStateChanged] is called with [ProtocolState.CONNECTED].
     */
    private val onSenderNameChanged: (String) -> Unit = {},
    /** Called when iOS/macOS sends a JPEG/PNG to the `/photo` endpoint. */
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    /** Called when iOS/macOS clears the currently displayed `/photo`. */
    private val onPhotoCleared: () -> Unit = {},
    /**
     * Called with the actual mDNS-registered name after [start].
     *
     * The name may differ from [displayName] if another device on the network already uses
     * the same name — NsdManager resolves the collision by appending " (2)", " (3)", etc.
     * The UI can use this callback to show the user the real registered name.
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    /**
     * Audio-only "now playing" state. Emits a [NowPlayingInfo] when audio is streaming WITHOUT video
     * (system audio, Apple Music, podcasts) so the UI can show a now-playing card instead of a black
     * surface; emits null when video is mirroring (the video screen takes over) or audio stops.
     */
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    /**
     * Screen mirroring started ([true]) or stopped ([false]).
     *
     * Separate from [onStateChanged] on purpose: the AirPlay card is "connected" as soon as a
     * sender has set up its keys, but the *Apple Casting* card — the one that says a phone is
     * putting its screen on the TV — should only light up when a video stream is really there.
     */
    private val onMirroringChanged: (Boolean) -> Unit = {},
    /**
     * One-line honesty about the advertisement itself — which interface and address the mDNS
     * record went out on, or which half of it the TV's responder refused.
     */
    private val onAdvertiseNotice: (String?) -> Unit = {},
    /** Direct URL-video state; errors are sanitized and never include the media URL. */
    private val onUrlPlaybackStateChanged: (AirPlayPlaybackState, AirPlayPlaybackFailure?) -> Unit = { _, _ -> },
) {

    /** Set by [stop] — cancels retries and any start still queued on the main thread. */
    @Volatile private var stopped = false
    private val shutdownStarted = AtomicBoolean(false)

    /** How many times advertising has been retried since it last worked. */
    @Volatile private var advertiseAttempts = 0

    /**
     * The one name this receiver advertises, normalised once so the mDNS record and the
     * `GET /info` reply can never drift apart (a sender that browses one name and then gets
     * a different one from /info shows confusing — sometimes duplicate — entries).
     */
    private val advertisedName: String = MdnsNames.sanitize(displayName)

    // SupervisorJob: child coroutine failures don't propagate to siblings.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Child components
    @Volatile private var mdnsService: MdnsService? = null
    /** The listener on port 7000; it builds one [RtspHandler] per connection a sender opens. */
    private var rtspServer: RtspServer? = null
    private var timingHandler: TimingHandler? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer: AudioPlayer? = null

    // UDP socket for receiving audio RTP packets — opened after RECORD, closed on TEARDOWN
    @Volatile private var audioSocket: DatagramSocket? = null

    // AirPlay 2 mirroring: data stream server + event channel + keys (set during SETUP).
    @Volatile private var mirrorServer: MirrorStreamServer? = null
    @Volatile private var audioServer: AudioStreamServer? = null
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null
    /** Session-scoped bounded cache for Photos cacheOnly/displayCached asset actions. */
    private val photoAssetCache = SessionPhotoAssetCache()
    /** True while a still is displayed or cached, so Media Stop can handle a photo-only session. */
    @Volatile private var photoStateActive = false
    /** Invalidates completion/error callbacks from a URL player replaced by a newer /play. */
    private var urlVideoGeneration = 0L

    /** Audio-ownership policy: who is allowed to own the soundtrack, per video generation. */
    private val videoAudioHandover = VideoAudioHandover()

    // Reverse remote control (TV → sender). Created lazily once a sender advertises DACP-ID.
    private val dacpClient = DacpClient(context)

    /** Generation-safe protocol/session ownership; sender addresses are never association keys. */
    private val sessionOwnership = SessionOwnership()

    /**
     * Owns FCUP bridge state separately from the Android player so startup, replacement and reply
     * routing can be tested without a TV. It is initialized when the RTSP server is constructed.
     */
    private val senderMediatedHls by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SenderMediatedHlsCoordinator(
            isStopped = { stopped },
            isSessionCurrent = { token -> sessionOwnership.isCurrent(token) },
            onPlaybackReady = { request, bridge ->
                val token = request.token
                if (token != null && bridge.isWritable && sessionOwnership.isCurrent(token)) {
                    startUrlVideo(
                        connectionId = request.connectionId,
                        url = bridge.playerUri,
                        startPosition = request.startSeconds,
                        seconds = request.seconds,
                        token = token,
                        mode = AirPlaySessionMode.SENDER_MEDIATED_HLS,
                        sessionSource = bridge,
                    )
                }
            },
        )
    }

    private val connectionSequence = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var ntpClient: AirPlayNtpClient? = null
    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: java.net.Socket? = null
    @Volatile private var mirrorAesKey: ByteArray? = null
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorAesIv: ByteArray? = null

    // ─── Now-playing (audio-only) state ──────────────────────────────────────
    // The now-playing card shows only when audio plays WITHOUT video. We track both stream kinds
    // plus the latest DMAP metadata/artwork and recompute on every change (see [emitNowPlaying]).
    @Volatile private var audioPlaying = false
    @Volatile private var videoPlaying = false
    @Volatile private var npSenderName = "AirPlay"
    @Volatile private var npTitle: String? = null
    @Volatile private var npArtist: String? = null
    @Volatile private var npAlbum: String? = null
    @Volatile private var npArtwork: ByteArray? = null

    // Tracked AirPlay volume in dB (-30..0, or <= -144 = mute) kept consistent across GET /info,
    // SET_PARAMETER, and new AudioStreamServer instances (UxPlay commit 412c5b7).
    @Volatile private var currentVolumeDb: Float = 0f

    /**
     * Starts the AirPlay receiver.
     *
     * 1. Opens the NTP timing socket and the RTSP server socket (port 7000).
     * 2. Registers the mDNS advertisement with the configured display name.
     * 3. Emits [ProtocolState.ADVERTISING] once `_airplay._tcp` is published.
     *
     * Non-blocking — all network work runs in background coroutines, except the mDNS
     * registration itself, which hops to the main looper because NsdManager requires one.
     */
    @Synchronized
    fun start() {
        if (stopped) return
        Logger.i("AirPlayReceiver starting (advertised as '$advertisedName')")
        scope.launch {
            // The two servers first, advertising second — and never in one try/catch.
            //
            // WHY THE ORDER MATTERS: discovery used to run before the RTSP bind, so a failure
            // in NsdManager (or any hiccup in the mDNS step) aborted start-up with port 7000
            // still shut. Senders that had the TV cached then connected to nothing. Opening the
            // servers first means a TV that cannot advertise is still reachable by the senders
            // that already know it, and a later advertising retry recovers fully.
            runCatching { startTimingHandler() }
                .onFailure { Logger.w("AirPlay: NTP timing server failed to start: ${it.message}") }
            runCatching { startRtspServer() }
                .onFailure {
                    Logger.e("Failed to start the AirPlay RTSP server", it)
                    emitState(ProtocolState.ERROR)
                }
            startMdnsService()
        }
    }

    /**
     * Registers the mDNS advertisement on a thread that owns a Looper.
     *
     * [NsdManager] attaches its registration callbacks to the Looper of the calling thread and
     * refuses to run on a thread that has none — and the receiver's scope is `Dispatchers.IO`,
     * whose pooled workers have none. Registering from there is what left Hearth permanently
     * in ERROR on a Google TV: the RTSP server was listening, but no Mac or iPhone could ever
     * find the address to dial. The main looper always exists, and the callback work here is
     * two flags and a state emit, so it costs that thread nothing.
     */
    private fun startMdnsService() {
        if (stopped) return
        scope.launch {
            withContext(Dispatchers.Main) {
                if (stopped) return@withContext
                if (mdnsService == null) {
                    mdnsService = MdnsService(
                        context = context,
                        onStateChange = { state -> onAdvertiseState(state) },
                        onActualNameRegistered = { actualName -> onActualNameRegistered(actualName) },
                        onAdvertiseNotice = { notice -> onAdvertiseNotice(notice) }
                    )
                }
                mdnsService?.start(advertisedName)
            }
        }
    }

    /**
     * Mirrors advertisement state to [PhairPlayService] and retries a failed one.
     *
     * The retry is what fixes a wired TV that boots before Ethernet has a lease: the first
     * registration lands on an interface with no address, and nothing in Android republishes
     * it. Backing off and re-registering does (and [MdnsService] also re-advertises on its own
     * once the network comes up). Bounded, so a TV with a broken mDNS daemon is not spammed.
     */
    private fun onAdvertiseState(state: ProtocolState) {
        when (state) {
            ProtocolState.ADVERTISING -> {
                advertiseAttempts = 0
                AirPlayTrace.record("Discovery: AirPlay service advertised")
            }
            ProtocolState.ERROR -> {
                AirPlayTrace.record("Discovery problem: this TV's mDNS responder refused the record — retrying")
                scheduleAdvertiseRetry()
            }
            else -> Unit
        }
        emitState(state)
    }

    private fun scheduleAdvertiseRetry() {
        if (stopped || advertiseAttempts >= MAX_ADVERTISE_ATTEMPTS) return
        val attempt = ++advertiseAttempts
        Logger.w(
            "AirPlay: mDNS advertisement failed — retry $attempt/$MAX_ADVERTISE_ATTEMPTS in " +
                "${ADVERTISE_RETRY_DELAY_MS}ms (wired TVs usually need exactly this after a boot)"
        )
        scope.launch {
            delay(ADVERTISE_RETRY_DELAY_MS)
            withContext(Dispatchers.Main) {
                if (stopped) return@withContext
                runCatching { mdnsService?.readvertise() }
                    .onFailure { Logger.w("AirPlay: mDNS retry failed: ${it.message}") }
            }
        }
    }

    /**
     * Stops the AirPlay receiver and releases all resources.
     *
     * Stops RTSP handler, mDNS advertising, video decoder, and audio player.
     * Cancels all background coroutines.
     *
     * MUST be called when [PhairPlayService] stops or is destroyed.
     */
    @Synchronized
    fun stop(reason: ReceiverShutdownReason = ReceiverShutdownReason.UNSPECIFIED) {
        if (!shutdownStarted.compareAndSet(false, true)) return
        AirPlayTrace.record(
            "AirPlay receiver shutdown initiated (${reason.traceLabel})",
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        Logger.i("AirPlayReceiver stopping (${reason.name.lowercase()})")
        // Invalidate claims/callbacks before closing sockets. A late media callback can no longer
        // regain ownership while the remaining resources are being released.
        stopped = true
        sessionOwnership.reset()
        photoAssetCache.clear()
        clearPhotoDisplay()
        silenceMediaOutputs("receiver shutdown: ${reason.traceLabel}")

        val server = rtspServer.also { rtspServer = null }
        val timing = timingHandler.also { timingHandler = null }
        val mdns = mdnsService.also { mdnsService = null }
        CleanupSafety.runAll(
            listOf(
                "RTSP listener" to { server?.stop(reason) },
                "NTP listener" to { timing?.stop() },
                "mDNS advertisement" to { stopMdnsOnMain(mdns) },
                "DACP remote control" to { dacpClient.stop() },
                "media pipeline" to { releaseMediaComponents() },
            )
        ) { label, error -> reportCleanupFailure(label, error) }
        // All blocking sockets have been closed before cancellation, so cancellation cannot strand
        // an active read or discard the only cleanup path for a player/network request.
        scope.cancel()
        AirPlayTrace.record(
            "Receiver stopped (${reason.traceLabel}); AirPlay listener and discovery are no longer active",
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
    }

    /**
     * End only the current cast and keep the RTSP listener, timing socket and discovery active.
     * The service publishes STOPPING/Advertising before calling this on its IO dispatcher, so UI
     * feedback is immediate while this synchronized section invalidates generations and closes
     * the sender's control/reverse sockets.
     */
    @Synchronized
    fun stopPlayback(): Boolean {
        if (stopped) return false
        val snapshot = sessionOwnership.snapshot()
        if (snapshot == null && !hasLiveMediaComponents() && !hasPhotoState()) return false
        val token = snapshot?.token
        val startedNanos = System.nanoTime()
        AirPlayTrace.record(
            "Local Stop playback requested; ending the active AirPlay generation",
            sessionId = token?.sessionId,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )

        // Mute/pause before any socket or decoder cleanup, so a slow sender or codec cannot leave
        // local sound/video running while the rest of the pipeline is being torn down.
        silenceMediaOutputs("local Stop playback")
        photoAssetCache.clear()
        clearPhotoDisplay()
        sessionOwnership.reset()
        if (token != null) {
            CleanupSafety.runAll(
                listOf("session RTSP connections" to { rtspServer?.closeSession(token) })
            ) { label, error -> reportCleanupFailure(label, error, token.sessionId) }
        }
        CleanupSafety.runAll(
            listOf("media pipeline release" to { finishSessionResources(token, "local Stop playback") })
        ) { label, error -> reportCleanupFailure(label, error, token?.sessionId) }
        runCatching { onUrlPlaybackStateChanged(AirPlayPlaybackState.STOPPED, null) }
            .onFailure { reportCleanupFailure("playback state callback", RuntimeException(it.javaClass.name)) }
        val elapsedMillis = (System.nanoTime() - startedNanos).coerceAtLeast(0L) / 1_000_000L
        AirPlayTrace.record(
            "Local Stop playback cleanup requested; listener remains active (${elapsedMillis}ms synchronous teardown)",
            sessionId = token?.sessionId,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        return true
    }

    /**
     * Sends a DACP transport command (see [DacpClient] constants) from the TV remote back to the
     * AirPlay sender — e.g. play/pause or skip what the Mac/iPhone is streaming. No-op if no sender
     * has advertised a DACP identity yet.
     */
    fun sendRemoteCommand(command: String) = dacpClient.sendCommand(command)

    /** True once a sender has advertised DACP reverse-control (so the TV remote can drive playback). */
    fun isRemoteControlAvailable(): Boolean = dacpClient.isAvailable

    // ─── Private: startup ────────────────────────────────────────────────────

    @Synchronized
    private fun startTimingHandler() {
        if (stopped) return
        timingHandler = TimingHandler().also { it.start(scope) }
        Logger.d("Timing handler started on UDP port ${TimingHandler.TIMING_PORT}")
    }

    /**
     * Opens the RTSP port with a **connection factory**, not a single handler.
     *
     * WHY: an Apple sender holds more than one socket open at a time (control channel + a silent
     * event channel + probes), and the previous single-client server answered every extra
     * connection with `503 Service Unavailable` — which is what made a discovered TV impossible to
     * connect to. [RtspServer] accepts all of them and builds one [RtspHandler] per socket, so a
     * second connection can never disturb the first.
     *
     * The media pipeline stays here (one decoder, one mirror data server, one event channel),
     * shared by whichever connection owns the session, so a reconnect cannot leave a half-shut
     * pipeline behind.
     */
    @Synchronized
    private fun startRtspServer() {
        if (stopped) return
        val server = RtspServer(RtspHandler.RTSP_PORT) { socket ->
            val connectionId = "C${connectionSequence.incrementAndGet()}"
            RtspHandler(
                context = context,
                displayName = advertisedName,
                displayWidth = mirrorWidth,
                displayHeight = mirrorHeight,
                audioEnabled = audioEnabled,
                videoSurfaceProvider = videoSurfaceProvider,
                onStreamingStarted = {},
                onStreamingStartedDetailed = { session, token, owner -> onStreamingStarted(session, token, owner) },
                onStreamingStopped = {},
                onPhotoReceived = { bytes, imageType -> onPhotoReceived(bytes, imageType) },
                onPhotoCleared = { onPhotoCleared() },
                onPhotoPut = { request, token, id, fingerprint -> handlePhotoPut(request, token, id, fingerprint) },
                onPhotoDelete = { token, id, fingerprint -> handlePhotoDelete(token, id, fingerprint) },
                onMirrorSetupKeysSession = { aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort, token ->
                    startMirrorKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort, token)
                },
                onMirrorStreamStartSession = { streamConnectionId, token -> startMirrorStream(streamConnectionId, token) },
                onMirrorAudioStartSession = { sampleRate, channels, ct, spf, token ->
                    startMirrorAudio(sampleRate, channels, ct, spf, token)
                },
                onMirrorAudioStopSession = { token -> stopMirrorAudio(token) },
                onMirrorVideoStopSession = { token -> stopMirrorVideo(token) },
                onBufferedAudioStartSession = { token -> startBufferedAudio(token) },
                onBufferedAudioStopSession = { token -> stopBufferedAudio(token) },
                onVolume = { v ->
                    currentVolumeDb = v
                    audioServer?.setVolume(v)
                },
                onNowPlayingMetadata = { title, artist, album ->
                    npTitle = title; npArtist = artist; npAlbum = album
                    emitNowPlaying()
                },
                onArtwork = { bytes ->
                    npArtwork = bytes.takeIf { it.isNotEmpty() }
                    emitNowPlaying()
                },
                onVideoPlaySession = { owner, url, start, seconds, token ->
                    startUrlVideo(owner, url, start, seconds, token)
                },
                onVideoRateSession = { rate, token ->
                    if (sessionOwnership.isCurrent(token)) urlVideoPlayer?.setRate(rate)
                },
                onVideoScrubSession = { pos, token ->
                    if (sessionOwnership.isCurrent(token)) urlVideoPlayer?.scrub(pos)
                },
                onVideoStopSession = { token -> stopUrlPlayerOnly(token) },
                onPlaybackInfoSession = { token ->
                    if (sessionOwnership.isCurrent(token)) urlVideoPlayer?.info() else null
                },
                onRemoteControlInfo = { dacpId, activeRemote -> dacpClient.configure(dacpId, activeRemote) },
                onMirrorSenderName = { senderName ->
                    npSenderName = senderName
                    onSenderNameChanged(senderName)
                },
                onAudioFlush = { nextSeq -> audioServer?.flush(nextSeq) },
                initialVolume = currentVolumeDb,
                socket = socket,
                traceConnectionId = connectionId,
                onSessionClaim = { owner, fingerprint, role, mode ->
                    claimSession(owner, fingerprint, role, mode)
                },
                onSessionAssociate = { owner, fingerprint, role ->
                    associateSession(owner, fingerprint, role)
                },
                onSessionStoppedDetailed = { owner, token, reason, explicit, streams ->
                    onSessionStopped(owner, token, reason, explicit, streams)
                },
                onMediaRoleChanged = { owner, token, role, active ->
                    onMediaRoleChanged(owner, token, role, active)
                },
                onVideoPlaybackState = { owner, token, state, failure ->
                    onPlaybackState(owner, token, state, failure)
                },
                // The receiver hosts the sender-mediated (FCUP) HLS transport: the reverse channel,
                // the /play that needs it, and the /action replies that answer it. Without this the
                // handler refuses a sender-mediated /play with a stated reason (as 1.8.3 does).
                hlsHost = senderMediatedHls,
            ).also { handler ->
                // Interleaved RTP feeds the current decoder only while this generation still owns it.
                handler.onVideoNalUnitWithSession = { nalUnit, ptsUs, token, _ ->
                    if (sessionOwnership.isCurrent(token)) videoDecoder?.decodeNalUnit(nalUnit, ptsUs)
                }
            }
        }
        rtspServer = server
        server.start(scope)
        Logger.i("RTSP server started on port ${RtspHandler.RTSP_PORT} (audioEnabled=$audioEnabled)")
    }

    // ─── Private: streaming lifecycle ────────────────────────────────────────

    /**
     * Called by [RtspHandler] when RECORD is received and [SessionDescription] is ready.
     *
     * Wires the media pipeline:
     * - video stream: creates [VideoDecoder] + wires [RtspHandler.onVideoNalUnit]
     * - audio stream: creates [AudioPlayer]
     * - audio-only:   only [AudioPlayer], app stays on HomeScreen
     */
    private fun onStreamingStarted(session: SessionDescription, token: SessionToken?, connectionId: String) {
        Logger.i("Streaming session negotiating media (video=${session.hasVideo}, audio=${session.hasAudio})")
        scope.launch {
            synchronized(this@AirPlayReceiver) {
                if (!sessionOwnership.isCurrent(token)) return@synchronized
                try {
                    if (session.hasVideo) startVideoDecoder(session)
                    if (session.hasAudio) startAudioPlayer(session, token, connectionId)
                    npSenderName = session.senderName.ifBlank { npSenderName }
                    videoPlaying = session.hasVideo
                    audioPlaying = session.hasAudio
                    emitNowPlaying()
                    onSenderNameChanged(session.senderName)
                    AirPlayTrace.record(
                        "Streaming session started — ${sessionSummary(session)}",
                        sessionId = token?.sessionId,
                        role = AirPlayConnectionRole.CONTROL.name,
                        kind = AirPlayTrace.Kind.LIFECYCLE,
                    )
                    if (session.hasAudio && !session.hasVideo) {
                        AirPlayTrace.record(
                            "SDP confirmed an audio-only stream; no video track was negotiated",
                            sessionId = token?.sessionId,
                            connectionId = connectionId,
                            role = AirPlayConnectionRole.CONTROL.name,
                            kind = AirPlayTrace.Kind.LIFECYCLE,
                        )
                        onUrlPlaybackStateChanged(AirPlayPlaybackState.AUDIO_ONLY, null)
                    }
                    emitSessionState(token, ProtocolState.CONNECTED)
                } catch (e: Exception) {
                    Logger.e("Failed to start media pipeline (${e.javaClass.simpleName})")
                    AirPlayTrace.record(
                        "Media pipeline setup failed (${e.javaClass.simpleName})",
                        sessionId = token?.sessionId,
                        kind = AirPlayTrace.Kind.FAILURE,
                    )
                    emitSessionState(token, ProtocolState.ERROR)
                }
            }
        }
    }

    /** Claims a protocol-confirmed session generation; IP addresses are never association evidence. */
    @Synchronized
    private fun claimSession(
        connectionId: String,
        fingerprint: String?,
        role: AirPlayConnectionRole,
        mode: AirPlaySessionMode,
    ): SessionToken {
        val claim = sessionOwnership.claimSession(connectionId, fingerprint, role, mode)
        if (claim.replaced != null) {
            Logger.i("AirPlay session generation replaced by a new protocol claim")
            AirPlayTrace.record(
                "New session generation replaced the previous media pipeline",
                sessionId = claim.token.sessionId,
                connectionId = connectionId,
                role = role.name,
                kind = AirPlayTrace.Kind.LIFECYCLE,
            )
            releaseMediaComponents(
                preserveReverseConnectionId = connectionId,
                preserveSessionFingerprint = fingerprint,
            )
        }
        if (claim.associatedByProtocolId) {
            AirPlayTrace.record(
                "Connection associated with the active session by protocol fingerprint",
                sessionId = claim.token.sessionId,
                connectionId = connectionId,
                role = role.name,
                associationEvidence = "PROTOCOL_ID",
            )
        } else if (claim.replaced == null) {
            AirPlayTrace.record(
                "Session claim accepted (${mode.name.lowercase()})",
                sessionId = claim.token.sessionId,
                connectionId = connectionId,
                role = role.name,
                kind = AirPlayTrace.Kind.LIFECYCLE,
            )
        }
        return claim.token
    }

    /** Secondary sockets join only through an exact sender-supplied protocol fingerprint. */
    @Synchronized
    private fun associateSession(
        connectionId: String,
        fingerprint: String?,
        role: AirPlayConnectionRole,
    ): SessionToken? {
        val token = sessionOwnership.associateConnection(connectionId, fingerprint, role) ?: return null
        AirPlayTrace.record(
            "Secondary connection associated by protocol fingerprint",
            sessionId = token.sessionId,
            connectionId = connectionId,
            role = role.name,
            associationEvidence = "PROTOCOL_ID",
        )
        return token
    }

    /** Handles one RTSP socket closing without letting a stale generation release current resources. */
    @Synchronized
    private fun onSessionStopped(
        connectionId: String,
        token: SessionToken?,
        reason: String,
        explicitTeardown: Boolean,
        activeStreams: Set<Int>,
    ) {
        val snapshot = sessionOwnership.snapshot()
        val role = snapshot?.connectionRoles?.get(connectionId) ?: AirPlayConnectionRole.PROBE
        val liveRoles = currentMediaRoles() + activeStreams.mapNotNull { type ->
            when (type) {
                96, 103 -> AirPlayMediaRole.MIRROR_AUDIO
                110 -> AirPlayMediaRole.MIRROR_VIDEO
                else -> null
            }
        }
        when (sessionOwnership.closeConnection(token, connectionId, explicitTeardown, liveRoles)) {
            SessionCloseResult.STALE -> {
                Logger.i("Ignored close from a stale AirPlay connection generation")
                AirPlayTrace.record(
                    "Ignored stale connection close ($reason)",
                    sessionId = token?.sessionId,
                    connectionId = connectionId,
                    role = role.name,
                    kind = AirPlayTrace.Kind.INFO,
                )
            }
            SessionCloseResult.KEEP_ACTIVE -> {
                Logger.i("AirPlay connection ended ($reason); confirmed media or another control remains")
                AirPlayTrace.record(
                    "Connection ended ($reason); session retained while media/control remains",
                    sessionId = token?.sessionId,
                    connectionId = connectionId,
                    role = role.name,
                    kind = AirPlayTrace.Kind.LIFECYCLE,
                )
            }
            SessionCloseResult.CLEANUP -> {
                Logger.i("AirPlay session ended ($reason)")
                finishSessionResources(token, reason)
            }
        }
    }

    /** Mirrors confirmed media lifetimes into ownership; the last stream can finish an orphaned session. */
    @Synchronized
    private fun onMediaRoleChanged(
        connectionId: String,
        token: SessionToken?,
        role: AirPlayMediaRole,
        active: Boolean,
    ) {
        val result = sessionOwnership.updateMediaRole(token, role, active)
        when (result) {
            SessionCloseResult.STALE -> AirPlayTrace.record(
                "Ignored media-role change from a stale generation",
                sessionId = token?.sessionId,
                connectionId = connectionId,
                kind = AirPlayTrace.Kind.INFO,
            )
            SessionCloseResult.CLEANUP -> finishSessionResources(token, "last confirmed media stream stopped")
            SessionCloseResult.KEEP_ACTIVE -> Unit
        }
    }

    @Synchronized
    private fun onPlaybackState(
        connectionId: String,
        token: SessionToken?,
        state: AirPlayPlaybackState,
        failureReason: String?,
    ) {
        if (!sessionOwnership.updatePlaybackState(token, state)) return
        val failure = failureReason?.let(::safePlaybackFailure)
        AirPlayTrace.record(
            "URL video playback ${state.name.lowercase()}" +
                (failure?.let { " (${it.stage.name.lowercase()}: ${it.detail})" } ?: ""),
            sessionId = token?.sessionId,
            connectionId = connectionId,
            role = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL.name,
            kind = if (state == AirPlayPlaybackState.FAILED) AirPlayTrace.Kind.FAILURE else AirPlayTrace.Kind.LIFECYCLE,
        )
        onUrlPlaybackStateChanged(state, failure)
    }

    private fun safePlaybackFailure(reason: String): AirPlayPlaybackFailure =
        AirPlayPlaybackFailures.classify(reason)

    @Synchronized
    private fun onStreamingStopped() {
        val token = sessionOwnership.snapshot()?.token
        sessionOwnership.reset()
        finishSessionResources(token, "session ended")
    }

    private fun hasLiveMediaComponents(): Boolean =
        videoDecoder != null || audioPlayer != null || audioSocket != null || mirrorServer != null ||
            audioServer != null || bufferedAudioServer != null || urlVideoPlayer != null ||
            eventSocket != null || eventClientSocket != null || ntpClient != null

    /** Mute local outputs first; network closure and codec release follow on the service IO thread. */
    private fun silenceMediaOutputs(reason: String) {
        CleanupSafety.runAll(
            listOf(
                "mirror audio output" to { audioServer?.setVolume(SILENT_VOLUME_DB) },
                "legacy AirPlay audio output" to { audioPlayer?.suspendOutput(true) },
                "URL video player pause" to { urlVideoPlayer?.setRate(0f) },
            )
        ) { label, error -> reportCleanupFailure(label, error) }
        AirPlayTrace.record("Local media outputs silenced before teardown ($reason)")
    }

    private fun stopMdnsOnMain(service: MdnsService?) {
        if (service == null) return
        val stop = Runnable {
            runCatching { service.stop() }
                .onFailure { reportCleanupFailure("mDNS advertisement", RuntimeException(it.javaClass.name)) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) stop.run()
        else Handler(Looper.getMainLooper()).post(stop)
    }

    private fun reportCleanupFailure(label: String, error: Exception, sessionId: String? = null) {
        Logger.w("AirPlay cleanup failed at $label (${error.javaClass.simpleName})")
        AirPlayTrace.record(
            "Stop cleanup failed at $label (${error.javaClass.simpleName})",
            sessionId = sessionId,
            kind = AirPlayTrace.Kind.FAILURE,
        )
    }

    @Synchronized
    private fun handlePhotoPut(
        request: PhotoPutRequest,
        token: SessionToken?,
        connectionId: String,
        protocolSessionFingerprint: String?,
    ): PhotoRequestResult {
        if (stopped || (token != null && !sessionOwnership.isCurrent(token)) ||
            (token == null && sessionOwnership.isClaimed())
        ) return PhotoRequestResult.CONFLICT
        val cacheSession = photoCacheSessionKey(token, connectionId, protocolSessionFingerprint)
        return when (request.action) {
            PhotoAssetAction.DISPLAY_CACHED -> {
                val key = request.assetKey ?: return PhotoRequestResult.BAD_REQUEST
                val cached = photoAssetCache.get(cacheSession, key) ?: return PhotoRequestResult.NOT_FOUND
                onPhotoReceived(cached.bytes, cached.imageType)
                photoStateActive = true
                PhotoRequestResult.ACCEPTED
            }
            PhotoAssetAction.CACHE_ONLY,
            PhotoAssetAction.DISPLAY -> {
                val validation = PhotoHandler.validatePhoto(request.bytes, request.contentType)
                if (validation !is PhotoValidation.Valid) return PhotoRequestResult.BAD_REQUEST
                val assetKey = request.assetKey
                if (assetKey != null) {
                    val stored = photoAssetCache.put(cacheSession, assetKey, request.bytes, validation.imageType)
                    if (request.action == PhotoAssetAction.CACHE_ONLY && !stored) {
                        return PhotoRequestResult.STORAGE_FULL
                    }
                } else if (request.action == PhotoAssetAction.CACHE_ONLY) {
                    return PhotoRequestResult.BAD_REQUEST
                }
                if (request.action == PhotoAssetAction.DISPLAY) {
                    // Request bodies are immutable after parsing; transfer ownership to the service
                    // instead of allocating another potentially 25 MiB copy while caching it.
                    onPhotoReceived(request.bytes, validation.imageType)
                    photoStateActive = true
                }
                PhotoRequestResult.ACCEPTED
            }
        }
    }

    @Synchronized
    private fun handlePhotoDelete(
        token: SessionToken?,
        connectionId: String,
        protocolSessionFingerprint: String?,
    ): Boolean {
        if (stopped || (token != null && !sessionOwnership.isCurrent(token)) ||
            (token == null && sessionOwnership.isClaimed())
        ) return false
        if (token == null && protocolSessionFingerprint == null) {
            photoAssetCache.clearSession(photoCacheSessionKey(null, connectionId, null))
        }
        clearPhotoDisplay()
        return true
    }

    private fun clearPhotoDisplay() {
        photoStateActive = false
        try {
            onPhotoCleared()
        } catch (error: Exception) {
            reportCleanupFailure("Photos display clear callback", RuntimeException(error.javaClass.name))
        }
    }

    private fun photoCacheSessionKey(
        token: SessionToken?,
        connectionId: String,
        protocolSessionFingerprint: String?,
    ): String = token?.let { "session:${it.sessionId}:${it.generation}" }
        ?: protocolSessionFingerprint?.let { "protocol:$it" }
        ?: "connection:$connectionId"

    private fun finishSessionResources(token: SessionToken?, reason: String) {
        if (token != null) photoAssetCache.clearSession(photoCacheSessionKey(token, "", null))
        AirPlayTrace.record(
            "Session stopped: $reason",
            sessionId = token?.sessionId,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        releaseMediaComponents()
        scope.launch {
            withContext(Dispatchers.Main) {
                if (!sessionOwnership.isClaimed()) onStateChanged(ProtocolState.ADVERTISING)
            }
        }
        scope.launch {
            withContext(Dispatchers.Main) {
                if (stopped || sessionOwnership.isClaimed()) return@withContext
                runCatching { mdnsService?.restart(advertisedName) }
                    .onFailure { reportCleanupFailure("mDNS rediscovery after playback", RuntimeException(it.javaClass.name), token?.sessionId) }
            }
        }
    }

    private fun currentMediaRoles(): Set<AirPlayMediaRole> = buildSet {
        if (mirrorServer != null || videoDecoder != null) add(AirPlayMediaRole.MIRROR_VIDEO)
        if (audioServer != null || bufferedAudioServer != null || audioPlayer != null) add(AirPlayMediaRole.MIRROR_AUDIO)
        if (urlVideoPlayer != null) add(AirPlayMediaRole.URL_VIDEO)
    }

    private fun emitSessionState(token: SessionToken?, state: ProtocolState) {
        scope.launch {
            withContext(Dispatchers.Main) {
                if (sessionOwnership.isCurrent(token)) onStateChanged(state)
            }
        }
    }

    // ─── Private: media pipeline ──────────────────────────────────────────────

    /**
     * Initializes [VideoDecoder] with SPS/PPS from the [SessionDescription].
     *
     * Resolution hint: AirPlay SDP does not include width/height — the actual
     * resolution is embedded in the SPS NAL unit. We pass [DEFAULT_VIDEO_WIDTH] ×
     * [DEFAULT_VIDEO_HEIGHT] as a hint; MediaCodec reads the real size from SPS.
     *
     * [RtspHandler.onVideoNalUnit] is wired here so RTP interleaved NAL units
     * flow directly into [VideoDecoder.decodeNalUnit].
     */
    private fun startVideoDecoder(session: SessionDescription) {
        val surface = videoSurfaceProvider() ?: run {
            Logger.w("VideoDecoder: no surface available — skipping video pipeline")
            return
        }
        val sps = session.spsBytes ?: run {
            Logger.w("VideoDecoder: no SPS in SDP — skipping")
            return
        }
        val pps = session.ppsBytes ?: run {
            Logger.w("VideoDecoder: no PPS in SDP — skipping")
            return
        }

        videoDecoder = VideoDecoder(surface).also { decoder ->
            decoder.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
        }
        Logger.i("VideoDecoder started (${DEFAULT_VIDEO_WIDTH}x${DEFAULT_VIDEO_HEIGHT} hint)")
    }

    /**
     * Initializes [AudioPlayer] with codec and encryption params from [SessionDescription].
     *
     * When the SDP contains no AES key/IV (unencrypted or missing keys), null is passed —
     * [AudioPlayer.initialize] skips cipher setup entirely and writes audio payload directly.
     * This prevents a zero-key cipher from producing garbage audio (S6-4 fix).
     */
    private fun startAudioPlayer(session: SessionDescription, token: SessionToken?, connectionId: String) {
        if (!sessionOwnership.isCurrent(token)) return
        audioPlayer = AudioPlayer().also { player ->
            player.initialize(
                aesKey     = session.aesKey.takeIf { session.isAudioEncrypted },
                aesIv      = session.aesIv.takeIf  { session.isAudioEncrypted },
                sampleRate = session.sampleRate,
                channels   = session.channels,
                codec      = session.audioCodec,
                alacFramesPerPacket = session.alacFramesPerPacket
            )
        }
        Logger.i("AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                 "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})")

        startAudioUdpReceiver(token, connectionId)
    }

    /**
     * Opens a UDP socket on [AUDIO_RTP_PORT] and feeds every received packet to
     * [AudioPlayer.playAudioPacket].
     *
     * WHY UDP: AirPlay audio is sent as RTP over UDP — low latency is more important
     * than guaranteed delivery. A missing packet produces a brief audio glitch,
     * which is far less disruptive than the buffering delays that TCP would introduce.
     *
     * The socket is closed in [releaseMediaComponents] when streaming ends.
     */
    private fun startAudioUdpReceiver(token: SessionToken?, connectionId: String) {
        scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                if (!sessionOwnership.isCurrent(token)) return@launch
                val newSocket = DatagramSocket(AUDIO_RTP_PORT).apply { soTimeout = AUDIO_IDLE_CHECK_MS }
                socket = newSocket
                synchronized(this@AirPlayReceiver) {
                    if (!sessionOwnership.isCurrent(token)) newSocket.close()
                    else audioSocket = newSocket
                }
                if (audioSocket !== newSocket) return@launch
                Logger.i("Audio UDP receiver listening on port $AUDIO_RTP_PORT")

                val buf = ByteArray(MAX_AUDIO_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)
                var lastPacketNanos = System.nanoTime()
                while (isActive && sessionOwnership.isCurrent(token) && !newSocket.isClosed) {
                    packet.length = buf.size
                    try {
                        newSocket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        val snapshot = sessionOwnership.snapshot()
                        val controllerRemains = snapshot?.connectionRoles?.values?.any {
                            it == AirPlayConnectionRole.CONTROL || it == AirPlayConnectionRole.DIRECT_VIDEO_CONTROL
                        } == true
                        if (!controllerRemains && System.nanoTime() - lastPacketNanos >= AUDIO_ORPHAN_TIMEOUT_NANOS) {
                            onMediaRoleChanged(connectionId, token, AirPlayMediaRole.MIRROR_AUDIO, false)
                            break
                        }
                        continue
                    }
                    lastPacketNanos = System.nanoTime()
                    synchronized(this@AirPlayReceiver) {
                        if (sessionOwnership.isCurrent(token)) {
                            audioPlayer?.playAudioPacket(packet.data.copyOf(packet.length))
                        }
                    }
                }
            } catch (e: Exception) {
                if (sessionOwnership.isCurrent(token)) {
                    Logger.e("Audio UDP receiver error (${e.javaClass.simpleName})")
                } else {
                    Logger.d("Audio UDP receiver closed with its session")
                }
            } finally {
                socket?.close()
                synchronized(this@AirPlayReceiver) {
                    if (audioSocket === socket) audioSocket = null
                }
            }
        }
    }

    // ─── Private: AirPlay 2 mirroring ─────────────────────────────────────────

    /**
     * Mirror SETUP msg 1: stash the decrypted AES key + pairing secret, open the event
     * channel (macOS connects to it), and switch the UI to the streaming surface.
     * @return the event channel's TCP port.
     */
    @Synchronized
    private fun startMirrorKeys(
        aesKey: ByteArray,
        ecdhSecret: ByteArray,
        aesIv: ByteArray,
        remoteAddress: java.net.InetAddress,
        senderTimingPort: Int,
        token: SessionToken?,
    ): Pair<Int, Int> {
        if (!sessionOwnership.isCurrent(token)) return 0 to 0
        mirrorAesKey = aesKey
        mirrorEcdhSecret = ecdhSecret
        mirrorAesIv = aesIv
        // The event channel is a fresh listener, not the RTSP port: the sender dials the port we
        // return here and then never writes to it (the receiver is the side that sends events).
        // Accept in a loop rather than once — a sender that reconnects (Wi-Fi blip, screen unlock)
        // opens a new event connection, and a listener that had already accepted one and moved on
        // would leave that reconnect hanging with no one reading it.
        runCatching { eventSocket?.close() }
        val event = ServerSocket(0)
        eventSocket = event
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive && sessionOwnership.isCurrent(token) && !event.isClosed) {
                    val s = event.accept()
                    scope.launch(Dispatchers.IO) {
                        val accepted = synchronized(this@AirPlayReceiver) {
                            if (sessionOwnership.isCurrent(token) && eventSocket === event) {
                                eventClientSocket = s
                                true
                            } else false
                        }
                        if (!accepted) {
                            runCatching { s.close() }
                            return@launch
                        }
                        try {
                            Logger.i("Event channel: sender connected")
                            AirPlayTrace.record(
                                "Mirroring: event channel connected",
                                sessionId = token?.sessionId,
                                kind = AirPlayTrace.Kind.LIFECYCLE,
                            )
                            val buf = ByteArray(4096)
                            val input = s.getInputStream()
                            while (isActive && sessionOwnership.isCurrent(token) && eventClientSocket === s &&
                                input.read(buf) != -1
                            ) { /* drain */ }
                        } catch (e: Exception) {
                            Logger.d("Event channel connection ended (${e.javaClass.simpleName})")
                        } finally {
                            runCatching { s.close() }
                            synchronized(this@AirPlayReceiver) {
                                if (eventClientSocket === s) eventClientSocket = null
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (eventSocket === event && !event.isClosed) Logger.d("Event channel listener closed (${e.javaClass.simpleName})")
            }
        }
        // AirPlay 2 NTP is receiver-initiated: poll the sender's timing port so macOS proceeds.
        val ntp = AirPlayNtpClient(remoteAddress, senderTimingPort).also { ntpClient = it; it.start(scope) }
        onSenderNameChanged(npSenderName)
        // This state belongs to the claimed mirror generation. A queued main-thread callback from
        // before local Stop must not restore CONNECTED after Stop has invalidated this token.
        emitSessionState(token, ProtocolState.CONNECTED)
        Logger.i("Mirror keys set; eventPort=${event.localPort} timingPort=${ntp.localPort}")
        return event.localPort to ntp.localPort
    }

    /**
     * Mirror SETUP msg 2: start the data-stream server for the requested stream.
     * @return the data server's TCP port (macOS connects here to send H.264).
     */
    @Synchronized
    private fun startMirrorStream(streamConnectionId: Long, token: SessionToken?): Int {
        if (!sessionOwnership.isCurrent(token)) return 0
        val aesKey = mirrorAesKey ?: run { Logger.e("mirror stream start before keys set"); return 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0
        // A second SETUP for stream 110 on the same session (the sender reconnecting after a
        // dropped data connection) must not leave the previous listener behind: two servers
        // deriving keys from the same streamConnectionID would both try to accept the socket.
        mirrorServer?.stop()
        mirrorServer = null
        return MirrorStreamServer(aesKey, ecdhSecret, streamConnectionId, videoSurfaceProvider, mirrorWidth, mirrorHeight)
            .also { mirrorServer = it; it.start(scope); videoPlaying = true; emitNowPlaying() }
            .dataPort
            .also { port ->
                Logger.i("Mirror data server started on port $port")
                AirPlayTrace.record("Mirroring: waiting for the sender's video connection on port $port")
                onMirroringChanged(true)
            }
    }

    /** Mirror SETUP audio stream (type 96): start the AAC-ELD / AAC-LC / ALAC audio server. @return (dataPort, controlPort). */
    @Synchronized
    private fun startMirrorAudio(
        sampleRate: Int,
        channels: Int,
        codecType: Int,
        framesPerPacket: Int,
        token: SessionToken?,
    ): Pair<Int, Int> {
        if (!sessionOwnership.isCurrent(token)) return 0 to 0
        val aesKey = mirrorAesKey ?: run { Logger.e("audio start before keys set"); return 0 to 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0 to 0
        val aesIv = mirrorAesIv ?: return 0 to 0
        audioServer?.stop()
        audioServer = null
        val server = AudioStreamServer(
            aesKey = aesKey,
            ecdhSecret = ecdhSecret,
            aesIv = aesIv,
            sampleRate = sampleRate,
            channels = channels,
            codecType = codecType,
            framesPerPacket = framesPerPacket,
            hashAudioKey = ecdhSecret.isNotEmpty(),
            initialVolumeDb = currentVolumeDb,
        ).also { audioServer = it; it.start(scope) }
        audioPlaying = true
        emitNowPlaying()
        Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
        return server.dataPort to server.controlPort
    }

    /** Stops ONLY the mirror audio stream (macOS dynamic-stream TEARDOWN) — video keeps running. */
    @Synchronized
    private fun stopMirrorAudio(token: SessionToken?) {
        if (!sessionOwnership.isCurrent(token)) return
        audioServer?.stop()
        audioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Mirror audio stream stopped (video mirroring continues)")
    }

    /** Stops ONLY the mirror video stream (macOS dynamic-stream TEARDOWN) — audio keeps playing. */
    @Synchronized
    private fun stopMirrorVideo(token: SessionToken?) {
        if (!sessionOwnership.isCurrent(token)) return
        mirrorServer?.stop()
        mirrorServer = null
        videoPlaying = false
        onMirroringChanged(false)
        AirPlayTrace.record("Mirroring: video stream stopped")
        emitNowPlaying()   // audio may still be playing → now-playing card can take over
        Logger.i("Mirror video stream stopped (audio playback continues)")
    }

    /** URL video (direct or sender-mediated) is tied to its generation; callbacks never include the URL. */
    @Synchronized
    private fun startUrlVideo(
        connectionId: String,
        url: String,
        startPosition: Double,
        seconds: Boolean,
        token: SessionToken?,
        mode: AirPlaySessionMode = AirPlaySessionMode.URL_VIDEO,
        sessionSource: UrlVideoSessionSource? = null,
    ) {
        if (!sessionOwnership.isCurrent(token)) return
        sessionOwnership.setMediaRole(token!!, AirPlayMediaRole.URL_VIDEO, true)
        sessionOwnership.updateMode(token, mode)
        val generation = urlVideoGeneration + 1
        urlVideoGeneration = generation
        val previous = urlVideoPlayer
        // A new item resets the soundtrack question: this video may have no audio of its own, so the
        // AirPlay audio output is restored until the player proves otherwise.
        if (videoAudioHandover.onVideoLoading(generation)) {
            resumeSessionAudioForVideo(connectionId, generation, "a new video item started")
        }
        val player = AirPlayVideoPlayer(
            surfaceProvider = videoSurfaceProvider,
            onEnded = { finishUrlVideo(connectionId, token, generation) },
            onStateChanged = { state, failure ->
                synchronized(this@AirPlayReceiver) {
                    if (generation == urlVideoGeneration && sessionOwnership.isCurrent(token)) {
                        onPlaybackState(connectionId, token, state, failure)
                    }
                }
            },
            onFirstFrameRendered = {
                synchronized(this@AirPlayReceiver) {
                    if (generation == urlVideoGeneration && sessionOwnership.isCurrent(token) &&
                        videoAudioHandover.onFirstFrame(generation)
                    ) {
                        AirPlayTrace.record(
                            "URL video: video timeline is live for this generation",
                            sessionId = token.sessionId,
                            connectionId = connectionId,
                            kind = AirPlayTrace.Kind.LIFECYCLE,
                        )
                    }
                }
            },
            onMediaAudioOwnership = { mediaOwnsAudio ->
                onMediaAudioOwnership(connectionId, token, generation, mediaOwnsAudio)
            },
            sessionSource = sessionSource,
            traceSessionId = token.sessionId,
            traceConnectionId = connectionId,
            traceRole = if (mode == AirPlaySessionMode.SENDER_MEDIATED_HLS) {
                SenderMediatedHlsBridge.ROLE
            } else {
                AirPlayConnectionRole.DIRECT_VIDEO_CONTROL.name
            },
        )
        urlVideoPlayer = player
        videoPlaying = true
        emitNowPlaying()
        onSenderNameChanged("AirPlay")
        emitSessionState(token, ProtocolState.CONNECTED)
        previous?.release()
        AirPlayTrace.record(
            "URL video player requested (mode=${mode.name.lowercase()}, " +
                "${if (seconds) "seconds" else "fraction"} offset)"
                + (sessionSource?.let { ", media fetched through the sender" } ?: ""),
            sessionId = token.sessionId,
            connectionId = connectionId,
            role = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL.name,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        player.play(url, startPosition, seconds)
    }

    /**
     * The player reported who owns the soundtrack for [generation].
     *
     * Everything here is generation-checked: a late callback from a replaced player can neither
     * silence the AirPlay audio of a newer session nor be mistaken for the current one's decision.
     */
    @Synchronized
    private fun onMediaAudioOwnership(
        connectionId: String,
        token: SessionToken,
        generation: Long,
        mediaOwnsAudio: Boolean,
    ) {
        if (generation != urlVideoGeneration || !sessionOwnership.isCurrent(token)) return
        when (sessionOwnership.snapshot()?.mode) {
            AirPlaySessionMode.URL_VIDEO, AirPlaySessionMode.SENDER_MEDIATED_HLS -> Unit
            else -> return
        }
        if (!mediaOwnsAudio) return
        if (!videoAudioHandover.onMediaAudioOwns(generation, true)) return
        suspendSessionAudioForVideo(connectionId, generation)
    }

    /**
     * Silences the AirPlay audio *output* while keeping its stream open.
     *
     * WHY "output" AND NOT "stream": upstream stops the RAOP service outright at this point, and a
     * sender that sees its audio stream die can tear the whole session down — including the video
     * this handover exists to protect. Hearth therefore keeps reading (and decoding) the incoming
     * packets and simply stops writing them to the output, which removes the duplicate audio without
     * giving the sender a reason to end the session. It is applied **only** to a generation that is
     * current, and only after the player reported that the media carries its own audio.
     */
    @Synchronized
    private fun suspendSessionAudioForVideo(connectionId: String, generation: Long): Boolean {
        if (generation != urlVideoGeneration) return false
        val server = audioServer
        val legacy = audioPlayer
        val mirrored = server != null || legacy != null
        server?.setVolume(SILENT_VOLUME_DB)
        legacy?.suspendOutput(true)
        AirPlayTrace.record(
            if (mirrored) {
                "Media audio owns the session: AirPlay audio output silenced (stream kept open)"
            } else {
                "Media audio owns the session: no AirPlay audio stream was running"
            },
            sessionId = sessionOwnership.snapshot()?.token?.sessionId,
            connectionId = connectionId,
            role = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL.name,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        return mirrored
    }

    /** Restores the AirPlay audio output for a new video item (or when a takeover is reverted). */
    @Synchronized
    private fun resumeSessionAudioForVideo(connectionId: String, generation: Long, reason: String): Boolean {
        if (generation != urlVideoGeneration) return false
        val server = audioServer
        val legacy = audioPlayer
        if (server == null && legacy == null) return false
        server?.setVolume(currentVolumeDb)
        legacy?.suspendOutput(false)
        AirPlayTrace.record(
            "AirPlay audio output restored ($reason)",
            sessionId = sessionOwnership.snapshot()?.token?.sessionId,
            connectionId = connectionId,
            role = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL.name,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
        return true
    }

    /** POST /stop releases the native player; the RTSP handler owns the terminal session callback. */
    @Synchronized
    private fun stopUrlPlayerOnly(token: SessionToken?) {
        if (!sessionOwnership.isCurrent(token)) return
        val player = urlVideoPlayer ?: return
        val generation = urlVideoGeneration
        urlVideoPlayer = null
        urlVideoGeneration++
        videoPlaying = false
        player.release()
        emitNowPlaying()
        // A user stop must not be followed by a retry of any kind: the bridge is closed (failing any
        // in-flight playlist fetch with a reason) and the reverse channel is dropped.
        closeSenderBridge("the sender stopped playback")
        val suspended = videoAudioHandover.onVideoEnded(generation)
        AirPlayTrace.record(
            if (suspended) {
                "Playback stopped by the sender; the AirPlay audio output stays suspended (its stream is stale)"
            } else {
                "Playback stopped by the sender before any audio takeover"
            },
            sessionId = token?.sessionId,
            kind = AirPlayTrace.Kind.LIFECYCLE,
        )
    }

    /** Completion and decoder/network failure end only the generation that created this player. */
    @Synchronized
    private fun finishUrlVideo(connectionId: String, token: SessionToken, generation: Long) {
        if (generation != urlVideoGeneration || !sessionOwnership.isCurrent(token)) return
        val player = urlVideoPlayer ?: return
        val failed = sessionOwnership.snapshot()?.playbackState == AirPlayPlaybackState.FAILED
        urlVideoPlayer = null
        urlVideoGeneration++
        videoPlaying = false
        player.release()
        closeSenderBridge(if (failed) "video playback failed" else "video playback completed")
        videoAudioHandover.onVideoEnded(generation)
        if (!failed) onUrlPlaybackStateChanged(AirPlayPlaybackState.DISCONNECTED, null)
        sessionOwnership.end(token)
        AirPlayTrace.record(
            (if (failed) "URL video session ended after playback failure" else "URL video session completed") +
                (if (videoAudioHandover.isAudioSuspended) {
                    "; the AirPlay audio output remains suspended rather than resuming a stale timeline"
                } else ""),
            sessionId = token.sessionId,
            connectionId = connectionId,
            role = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL.name,
            kind = if (failed) AirPlayTrace.Kind.FAILURE else AirPlayTrace.Kind.LIFECYCLE,
        )
        finishSessionResources(token, if (failed) "URL video playback failed" else "URL video completed")
    }

    /** Releases active and warming sender bridges while keeping the PTTH offer available for retry. */
    private fun closeSenderBridge(reason: String): Boolean = senderMediatedHls.closeBridges(reason)

    /** Starts the AirPlay 2 buffered audio-only stream (type 103, Apple Music → TV); returns its TCP port. */
    @Synchronized
    private fun startBufferedAudio(token: SessionToken?): Int {
        if (!sessionOwnership.isCurrent(token)) return 0
        bufferedAudioServer?.stop()
        val server = BufferedAudioServer().also { bufferedAudioServer = it; it.start(scope) }
        audioPlaying = true   // buffered audio (type 103) is always audio-only
        emitNowPlaying()
        Logger.i("Buffered audio server started: dataPort=${server.dataPort}")
        return server.dataPort
    }

    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    @Synchronized
    private fun stopBufferedAudio(token: SessionToken?) {
        if (!sessionOwnership.isCurrent(token)) return
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Buffered audio stream stopped")
    }

    /** Clears media pipelines and either preserves the newly offered PTTH socket or resets all. */
    @Synchronized
    private fun releaseMediaComponents(
        preserveReverseConnectionId: String? = null,
        preserveSessionFingerprint: String? = null,
    ) {
        // Detach before calling any native/socket close so late callbacks cannot see these as the
        // current generation or accidentally tear down resources installed by a later session.
        urlVideoGeneration++
        val oldAudioSocket = audioSocket.also { audioSocket = null }
        val oldMirrorServer = mirrorServer.also { mirrorServer = null }
        val oldAudioServer = audioServer.also { audioServer = null }
        val oldBufferedAudioServer = bufferedAudioServer.also { bufferedAudioServer = null }
        val oldUrlPlayer = urlVideoPlayer.also { urlVideoPlayer = null }
        val oldNtpClient = ntpClient.also { ntpClient = null }
        val oldEventClient = eventClientSocket.also { eventClientSocket = null }
        val oldEventServer = eventSocket.also { eventSocket = null }
        val oldVideoDecoder = videoDecoder.also { videoDecoder = null }
        val oldAudioPlayer = audioPlayer.also { audioPlayer = null }
        val oldAesKey = mirrorAesKey.also { mirrorAesKey = null }
        val oldEcdhSecret = mirrorEcdhSecret.also { mirrorEcdhSecret = null }
        val oldAesIv = mirrorAesIv.also { mirrorAesIv = null }
        audioPlaying = false
        videoPlaying = false
        clearNowPlayingMetadata()

        CleanupSafety.runAll(
            listOf(
                "audio RTP socket" to { oldAudioSocket?.close() },
                "mirror video server" to { oldMirrorServer?.stop() },
                "mirror audio server" to { oldAudioServer?.stop() },
                "buffered audio server" to { oldBufferedAudioServer?.stop() },
                "URL video player" to { oldUrlPlayer?.release() },
                "sender-mediated HLS bridge" to {
                    if (preserveReverseConnectionId != null || preserveSessionFingerprint != null) {
                        senderMediatedHls.replaceSession(
                            reason = "media components released for a replacement session",
                            connectionId = preserveReverseConnectionId.orEmpty(),
                            protocolSessionFingerprint = preserveSessionFingerprint,
                        )
                    } else {
                        senderMediatedHls.reset("media components released")
                    }
                },
                "video audio handover" to { videoAudioHandover.onSessionReplaced() },
                "AirPlay timing client" to { oldNtpClient?.stop() },
                "event channel client" to { oldEventClient?.close() },
                "event channel listener" to { oldEventServer?.close() },
                "mirror video decoder" to { oldVideoDecoder?.release() },
                "AirPlay audio player" to { oldAudioPlayer?.release() },
                "mirror AES key clear" to { oldAesKey?.fill(0) },
                "mirror ECDH secret clear" to { oldEcdhSecret?.fill(0) },
                "mirror AES IV clear" to { oldAesIv?.fill(0) },
                "mirroring state callback" to { onMirroringChanged(false) },
                "now-playing state callback" to { onNowPlayingChanged(null) },
            )
        ) { label, error -> reportCleanupFailure(label, error) }

    }

    /** Pushes the current now-playing state out: a [NowPlayingInfo] when audio plays without video, else null. */
    private fun emitNowPlaying() {
        val show = audioPlaying && !videoPlaying
        onNowPlayingChanged(
            if (show) NowPlayingInfo(npSenderName, npTitle, npArtist, npAlbum, npArtwork) else null
        )
    }

    /** One-line description of a legacy SDP session, for the connection log. */
    private fun sessionSummary(session: SessionDescription): String {
        val parts = mutableListOf<String>()
        if (session.hasVideo) parts += "screen video"
        if (session.hasAudio) parts += "${session.audioCodec} audio"
        if (parts.isEmpty()) parts += "no media"
        return parts.joinToString(" + ")
    }

    /** Drops stale track metadata/artwork when an audio stream ends (so it can't bleed into the next). */
    private fun clearNowPlayingMetadata() {
        npTitle = null; npArtist = null; npAlbum = null; npArtwork = null
    }

    // ─── Private: state emission ─────────────────────────────────────────────

    /** Dispatches [state] on the Main thread (Android UI rule). */
    private fun emitState(state: ProtocolState) {
        scope.launch {
            withContext(Dispatchers.Main) {
                onStateChanged(state)
            }
        }
    }

    companion object {
        // Hint dimensions for MediaCodec configuration.
        // Real resolution is encoded in the H.264 SPS NAL unit.
        /**
         * AirPlay volume that means "muted" (the sender's own convention: see [AudioStreamServer]).
         * Used to silence the *output* of a running AirPlay audio stream while its packets keep
         * arriving, so a video session's audio can own the timeline without the sender noticing a
         * dead stream and tearing the session down.
         */
        private const val SILENT_VOLUME_DB = -144f

        private const val DEFAULT_VIDEO_WIDTH  = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080

        /**
         * UDP port for receiving audio RTP packets.
         * Advertised in the RTSP SETUP response so the sender knows where to send audio.
         * Must not conflict with the RTSP port (7000) or timing port ([TimingHandler.TIMING_PORT]).
         */
        internal const val AUDIO_RTP_PORT = 6001

        /** Backoff for a failed mDNS advertisement, and how many times to try. */
        private const val ADVERTISE_RETRY_DELAY_MS = 4_000L
        private const val MAX_ADVERTISE_ATTEMPTS = 5

        /**
         * Maximum UDP audio packet size in bytes.
         * ALAC frames are typically ≤ 8 KB. 16 KB is a safe upper bound.
         */
        private const val MAX_AUDIO_PACKET_BYTES = 16 * 1024
        private const val AUDIO_IDLE_CHECK_MS = 10_000
        private const val AUDIO_ORPHAN_TIMEOUT_NANOS = 30_000_000_000L
    }
}
