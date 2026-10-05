package com.phairplay.airplay

import com.phairplay.util.Logger

/** A render target whose identity stays stable while its Android wrapper is recreated. */
internal interface UrlVideoSurface {
    val identity: Any
    val isValid: Boolean

    /** The platform surface when this target wraps one; null for test doubles. */
    val platformSurface: android.view.Surface? get() = null
}

/**
 * Narrow media-player boundary, so session lifecycle can be tested without a native player.
 *
 * [setOnFirstFrameListener] and [hasVideoTrack] exist because "the player is prepared and playing"
 * is not the same claim as "a frame reached the surface": the UI must not say *Playing video* while
 * the first frame has not been rendered yet, and a session whose media has no video track at all is
 * an audio-only session, not a stalled one.
 */
internal interface UrlVideoBackend {
    fun setOnPreparedListener(listener: (() -> Unit)?)
    fun setOnCompletionListener(listener: (() -> Unit)?)
    fun setOnErrorListener(listener: ((what: Int, extra: Int) -> Boolean)?)
    fun setDataSource(url: String)
    fun prepareAsync()
    fun setSurface(surface: UrlVideoSurface?)
    fun seekTo(positionMs: Int)
    fun start()
    fun pause()
    val isPlaying: Boolean
    val durationMs: Int
    val positionMs: Int
    fun release()

    /** Called when the first decoded frame is actually rendered; never called for audio-only media. */
    fun setOnFirstFrameListener(listener: (() -> Unit)?) {}

    /** False once the player knows the media carries no video track (audio-only HLS, audio file). */
    val hasVideoTrack: Boolean get() = true

    /**
     * True/False once the player knows whether the media has its own audio track; null while it is
     * unknown (nothing is loaded yet, or the backend cannot report tracks).
     *
     * This is what decides *who owns the sound*: media with its own audio must not play at the same
     * time as the AirPlay audio stream, and media without it must not silence that stream.
     */
    val hasAudioTrack: Boolean? get() = null

    /**
     * Mutes or unmutes only this player's own output — never the system volume and never the AirPlay
     * audio stream. A video session starts muted so that the moment its audio would begin (which is
     * typically *before* the first video frame), it cannot overlap the AirPlay audio that still owns
     * the output; it is unmuted only when the handover decision says the media owns the audio.
     */
    fun setMuted(muted: Boolean) {}
}

internal fun interface UrlVideoBackendFactory {
    /**
     * @param source media access for a sender-mediated session; null for ordinary network URLs, which
     *   the player fetches itself
     */
    fun create(source: UrlVideoSessionSource?): UrlVideoBackend
}

/** All controller state and backend calls are confined to this dispatcher. */
internal interface UrlVideoScheduler {
    fun dispatch(action: () -> Unit)
    fun postDelayed(task: Runnable, delayMillis: Long)
    fun removeCallbacks(task: Runnable)
}

/**
 * Main-thread URL-video session state machine. It owns one backend at a time; RTSP callers only
 * enqueue controls and read the immutable volatile playback snapshot.
 */
internal class UrlVideoPlaybackController(
    private val surfaceProvider: () -> UrlVideoSurface?,
    private val backendFactory: UrlVideoBackendFactory,
    private val scheduler: UrlVideoScheduler,
    private val clockMillis: () -> Long,
    private val onEnded: () -> Unit,
    private val onStateChanged: (AirPlayPlaybackState, String?) -> Unit = { _, _ -> },
    /**
     * Called exactly once per playback generation, when the first frame has really been rendered.
     * This is the only honest trigger for "video owns the output now" (audio handover).
     */
    private val onFirstFrame: () -> Unit = {},
    /**
     * Called exactly once per playback generation, when it is known whether the media carries its own
     * audio: true means the media now owns the sound (the AirPlay audio stream must be suspended),
     * false means the AirPlay audio stream keeps it (the player's own output stays muted).
     *
     * Never called for a stale generation; never called twice for one generation.
     */
    private val onAudioOwnership: (mediaOwnsAudio: Boolean) -> Unit = {},
    /**
     * Media access for a sender-mediated session (the FCUP bridge). Null for a direct URL, where the
     * player fetches the media itself.
     */
    private val sessionSource: UrlVideoSessionSource? = null,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    private val tickMillis: Long = DEFAULT_TICK_MS,
) {
    private var backend: UrlVideoBackend? = null
    private var prepared = false
    private var started = false
    /** Set by the backend's first-frame callback; the only evidence that video really rendered. */
    private var firstFrameRendered = false

    /** When the first frame arrived; the media-audio decision is given a bounded grace from here. */
    private var firstFrameAtMillis: Long? = null

    /** When the player became ready; the decision clock for audio-only media (no frame ever comes). */
    private var preparedAtMillis: Long? = null

    /** The audio-ownership decision for the current generation; null until it is decided. */
    private var mediaOwnsAudio: Boolean? = null
    private var desiredRate = 1f
    private var pendingSeekMs: Int? = null
    private var startPosition = 0.0
    private var startInSeconds = false
    private var attachedSurface: UrlVideoSurface? = null
    private var deadlineMillis = 0L
    private var lastReportedState: AirPlayPlaybackState? = null

    @Volatile
    private var playbackSnapshot: PlaybackInfo? = null

    private val tick = object : Runnable {
        override fun run() {
            val current = backend ?: return
            runCatching {
                val surface = surfaceProvider()?.takeIf { it.isValid }
                if (surface?.identity !== attachedSurface?.identity) {
                    current.setSurface(surface)
                    attachedSurface = surface
                }

                if (prepared) {
                    if (surface != null && desiredRate > 0f) {
                        if (!current.isPlaying) current.start()
                        if (!started) AirPlayTrace.record("URL video: playback started")
                        started = true
                    } else if (current.isPlaying) {
                        current.pause()
                    }
                    val audioOnly = !current.hasVideoTrack
                    if (firstFrameRendered || audioOnly) decideAudioOwnership(current)
                    reportState(
                        when {
                            desiredRate <= 0f -> AirPlayPlaybackState.PAUSED
                            surface == null -> AirPlayPlaybackState.LOADING
                            audioOnly -> AirPlayPlaybackState.AUDIO_ONLY
                            firstFrameRendered -> AirPlayPlaybackState.PLAYING
                            // Started, but nothing has been drawn yet: still loading, and the
                            // deadline below turns "never draws" into a stated failure.
                            else -> AirPlayPlaybackState.LOADING
                        }
                    )
                    playbackSnapshot = PlaybackInfo(
                        durationSec = current.durationMs.coerceAtLeast(0) / 1000.0,
                        positionSec = current.positionMs.coerceAtLeast(0) / 1000.0,
                        rate = if (current.isPlaying) 1.0 else 0.0,
                        readyToPlay = surface != null,
                    )
                }

                val neverPrepared = !prepared
                val neverStarted = started && surface == null
                val neverRendered = started && surface != null && current.hasVideoTrack &&
                    !firstFrameRendered && desiredRate > 0f
                if ((neverPrepared || neverStarted || neverRendered) && clockMillis() >= deadlineMillis) {
                    fail(
                        current,
                        if (neverRendered) "first frame timeout" else "prepare/surface timeout",
                    )
                }
            }.onFailure { fail(current, "playback state", it) }

            if (backend === current) scheduler.postDelayed(this, tickMillis)
        }
    }

    fun play(url: String, position: Double, seconds: Boolean = false) = onMain {
        releaseMain()
        startPosition = when {
            !position.isFinite() || position < 0.0 -> 0.0
            seconds -> position
            else -> position.coerceIn(0.0, 1.0)
        }
        startInSeconds = seconds
        desiredRate = 1f
        firstFrameRendered = false
        firstFrameAtMillis = null
        preparedAtMillis = null
        mediaOwnsAudio = null
        deadlineMillis = clockMillis() + timeoutMillis
        playbackSnapshot = PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        reportState(AirPlayPlaybackState.LOADING)

        try {
            val player = backendFactory.create(sessionSource)
            backend = player
            // Start muted: the player can begin rendering audio before its first video frame, and
            // until the handover decision the AirPlay audio stream (the sound the user already has)
            // must not be doubled. See [decideAudioOwnership].
            runCatching { player.setMuted(true) }
                .onFailure { Logger.w("URL video: could not mute the player before start") }
            player.setOnPreparedListener {
                onMain {
                    if (backend === player) onPrepared(player)
                }
            }
            player.setOnCompletionListener {
                onMain {
                    if (backend === player) finish()
                }
            }
            player.setOnErrorListener { what, extra ->
                onMain {
                    if (backend === player) fail(player, "decoder/network error $what/$extra")
                }
                true
            }
            player.setOnFirstFrameListener {
                onMain {
                    if (backend === player && !firstFrameRendered) {
                        firstFrameRendered = true
                        firstFrameAtMillis = clockMillis()
                        AirPlayTrace.record("URL video: first frame rendered")
                        runCatching { onFirstFrame() }
                            .onFailure { Logger.e("URL video first-frame callback failed") }
                        reportState(AirPlayPlaybackState.PLAYING)
                    }
                }
            }
            player.setDataSource(url)
            player.prepareAsync()
            scheduler.postDelayed(tick, 0)
        } catch (error: Throwable) {
            val current = backend
            if (current == null) {
                logFailure("setup", error)
                AirPlayTrace.record("URL video failed: setup")
                reportState(AirPlayPlaybackState.FAILED, "player setup failed")
                finish(failed = true)
            } else {
                fail(current, "setup", error)
            }
        }
    }

    fun setRate(rate: Float) = onMain {
        if (!rate.isFinite()) return@onMain
        desiredRate = rate
        val current = backend
        if (rate <= 0f && prepared) {
            if (current?.isPlaying == true) current.pause()
            reportState(AirPlayPlaybackState.PAUSED)
        } else if (!prepared) {
            reportState(AirPlayPlaybackState.LOADING)
        }
    }

    fun scrub(positionSec: Double) = onMain {
        if (!positionSec.isFinite() || positionSec < 0.0) return@onMain
        val targetMs = (positionSec * 1000.0)
            .coerceIn(0.0, Int.MAX_VALUE.toDouble())
            .toInt()
        val current = backend ?: return@onMain
        if (!prepared) {
            pendingSeekMs = targetMs
        } else {
            runCatching { current.seekTo(targetMs) }
                .onFailure { fail(current, "seek", it) }
        }
    }

    fun info(): PlaybackInfo? = playbackSnapshot

    fun attachSurface() = onMain {
        scheduler.removeCallbacks(tick)
        if (backend != null) scheduler.postDelayed(tick, 0)
    }

    fun release() = onMain { releaseMain() }

    private fun onPrepared(player: UrlVideoBackend) {
        prepared = true
        preparedAtMillis = clockMillis()
        reportState(AirPlayPlaybackState.LOADING)
        runCatching {
            val initialMs = if (startInSeconds) {
                (startPosition * 1000.0).coerceAtMost(Int.MAX_VALUE.toDouble())
            } else {
                startPosition * player.durationMs.coerceAtLeast(0)
            }
            val seekMs = pendingSeekMs ?: initialMs.toInt()
            if (seekMs > 0) player.seekTo(seekMs)
            pendingSeekMs = null
        }.onFailure { fail(player, "initial seek", it) }
    }

    /**
     * Decides, once per generation, whether the media or the AirPlay audio stream owns the sound.
     *
     * The three answers are all evidence-based:
     *  - media reports an audio track → take over: unmute the player, tell the receiver to suspend
     *    the AirPlay audio output (one timeline owner, no echo);
     *  - media reports no audio track (or is audio-only with none) → the AirPlay audio stream keeps
     *    the output; the player stays muted, which is inaudible because it has nothing to play;
     *  - the backend cannot tell within [AUDIO_DECISION_GRACE_MS] → keep the AirPlay audio and leave
     *    the player muted. That is stated in the trace rather than guessed: a guessed takeover that
     *    silenced an audio-only stream would be worse than an extra muted track.
     */
    private fun decideAudioOwnership(current: UrlVideoBackend) {
        if (mediaOwnsAudio != null) return
        if (backend !== current) return
        when (current.hasAudioTrack) {
            true -> {
                mediaOwnsAudio = true
                runCatching { current.setMuted(false) }
                    .onFailure { Logger.w("URL video: could not unmute the player after takeover") }
                AirPlayTrace.record("URL video: media carries audio — the player owns the soundtrack")
                notifyAudioOwnership(true)
            }
            false -> {
                mediaOwnsAudio = false
                AirPlayTrace.record(
                    "URL video: media has no audio track — the AirPlay audio stream keeps the sound"
                )
                notifyAudioOwnership(false)
            }
            null -> {
                // Audio-only media never renders a first frame, so its decision clock starts when
                // the player became ready instead.
                val decidedFrom = firstFrameAtMillis ?: preparedAtMillis ?: return
                if (clockMillis() - decidedFrom < AUDIO_DECISION_GRACE_MS) return
                mediaOwnsAudio = false
                AirPlayTrace.record(
                    "URL video: could not tell whether the media carries audio within " +
                        "${AUDIO_DECISION_GRACE_MS / 1000}s — keeping the AirPlay audio stream and " +
                        "the player muted"
                )
                notifyAudioOwnership(false)
            }
        }
    }

    private fun notifyAudioOwnership(owns: Boolean) {
        runCatching { onAudioOwnership(owns) }
            .onFailure { Logger.e("URL video audio-ownership callback failed (${it.javaClass.simpleName})") }
    }

    private fun fail(player: UrlVideoBackend, reason: String, error: Throwable? = null) {
        if (backend !== player) return
        logFailure(reason, error)
        // The bridge knows *why* the sender could not serve media (no reverse channel, protected
        // stream, a playlist the sender refused). That reason is URL-free and belongs in the trace:
        // otherwise a failed sender-mediated session and a dead network look identical.
        val sourceReason = runCatching { sessionSource?.failureReason() }.getOrNull()
        AirPlayTrace.record(
            "URL video failed: $reason" + (sourceReason?.let { " ($it)" } ?: "")
        )
        reportState(AirPlayPlaybackState.FAILED, safeFailure(reason))
        finish(failed = true)
    }

    private fun safeFailure(reason: String): String = when (reason) {
        "prepare/surface timeout" -> "prepare/surface timeout"
        "first frame timeout" -> "video did not start"
        "setup" -> "player setup failed"
        "playback state" -> "player state error"
        "seek", "initial seek" -> "seek failed"
        else -> "media playback error"
    }

    private fun reportState(state: AirPlayPlaybackState, failureReason: String? = null) {
        if (lastReportedState == state && failureReason == null) return
        lastReportedState = state
        runCatching { onStateChanged(state, failureReason) }
            .onFailure { Logger.e("AirPlay URL video state callback failed (${it.javaClass.simpleName})") }
    }

    private fun logFailure(reason: String, error: Throwable? = null) {
        // MediaPlayer exceptions can include the data-source string. Keep the stack frames and type,
        // but omit its message/cause so signed URLs and query tokens cannot leak into logs.
        val safeThrowable = error?.let { source ->
            RuntimeException(source.javaClass.name).apply { stackTrace = source.stackTrace }
        }
        val type = error?.let { " (${it.javaClass.simpleName})" }.orEmpty()
        Logger.e("AirPlay URL video: $reason$type", safeThrowable)
    }

    private fun finish(failed: Boolean = false) {
        if (!failed) reportState(AirPlayPlaybackState.DISCONNECTED)
        releaseMain()
        runCatching { onEnded() }
            .onFailure { Logger.e("AirPlay URL video end callback failed (${it.javaClass.simpleName})") }
    }

    private fun releaseMain() {
        scheduler.removeCallbacks(tick)
        val old = backend
        backend = null // Invalidate callback closures before touching native resources.
        prepared = false
        started = false
        firstFrameRendered = false
        firstFrameAtMillis = null
        preparedAtMillis = null
        mediaOwnsAudio = null
        desiredRate = 1f
        pendingSeekMs = null
        attachedSurface = null
        playbackSnapshot = null
        lastReportedState = null
        if (old != null) {
            runCatching { old.setOnPreparedListener(null) }
            runCatching { old.setOnCompletionListener(null) }
            runCatching { old.setOnErrorListener(null) }
            runCatching { old.setOnFirstFrameListener(null) }
            runCatching { old.release() }
        }
    }

    private fun onMain(action: () -> Unit) = scheduler.dispatch(action)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEFAULT_TICK_MS = 250L

        /**
         * How long "does the media carry audio?" may stay unknown after the first frame before the
         * AirPlay audio stream is kept (and the player left muted).
         */
        const val AUDIO_DECISION_GRACE_MS = 5_000L
    }
}
