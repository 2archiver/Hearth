package com.phairplay.airplay

import com.phairplay.util.Logger

/** A render target whose identity stays stable while its Android wrapper is recreated. */
internal interface UrlVideoSurface {
    val identity: Any
    val isValid: Boolean
}

/** Narrow MediaPlayer boundary so session lifecycle can be tested without Android's native player. */
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
}

internal fun interface UrlVideoBackendFactory {
    fun create(): UrlVideoBackend
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
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    private val tickMillis: Long = DEFAULT_TICK_MS,
) {
    private var backend: UrlVideoBackend? = null
    private var prepared = false
    private var started = false
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
                        reportState(AirPlayPlaybackState.PLAYING)
                    } else if (current.isPlaying) {
                        current.pause()
                    }
                    if (desiredRate <= 0f) reportState(AirPlayPlaybackState.PAUSED)
                    else if (surface == null) reportState(AirPlayPlaybackState.LOADING)
                    playbackSnapshot = PlaybackInfo(
                        durationSec = current.durationMs.coerceAtLeast(0) / 1000.0,
                        positionSec = current.positionMs.coerceAtLeast(0) / 1000.0,
                        rate = if (current.isPlaying) 1.0 else 0.0,
                        readyToPlay = surface != null,
                    )
                }

                if ((!prepared || (!started && surface == null)) && clockMillis() >= deadlineMillis) {
                    fail(current, "prepare/surface timeout")
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
        deadlineMillis = clockMillis() + timeoutMillis
        playbackSnapshot = PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        reportState(AirPlayPlaybackState.LOADING)

        try {
            val player = backendFactory.create()
            backend = player
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

    private fun fail(player: UrlVideoBackend, reason: String, error: Throwable? = null) {
        if (backend !== player) return
        logFailure(reason, error)
        AirPlayTrace.record("URL video failed: $reason")
        reportState(AirPlayPlaybackState.FAILED, safeFailure(reason))
        finish(failed = true)
    }

    private fun safeFailure(reason: String): String = when (reason) {
        "prepare/surface timeout" -> "prepare/surface timeout"
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
        desiredRate = 1f
        pendingSeekMs = null
        attachedSurface = null
        playbackSnapshot = null
        lastReportedState = null
        if (old != null) {
            runCatching { old.setOnPreparedListener(null) }
            runCatching { old.setOnCompletionListener(null) }
            runCatching { old.setOnErrorListener(null) }
            runCatching { old.release() }
        }
    }

    private fun onMain(action: () -> Unit) = scheduler.dispatch(action)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEFAULT_TICK_MS = 250L
    }
}
