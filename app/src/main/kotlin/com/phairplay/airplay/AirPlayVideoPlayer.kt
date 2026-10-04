package com.phairplay.airplay

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,
    val readyToPlay: Boolean,
)

/** Direct AirPlay HTTP/HLS video; mirroring and RAOP use separate media pipelines. */
class AirPlayVideoPlayer(
    surfaceProvider: () -> Surface?,
    onEnded: () -> Unit = {},
) {
    private val controller = UrlVideoPlaybackController(
        surfaceProvider = { surfaceProvider()?.let(::AndroidUrlVideoSurface) },
        backendFactory = UrlVideoBackendFactory { AndroidUrlVideoBackend() },
        scheduler = AndroidMainLooperScheduler(),
        clockMillis = { SystemClock.elapsedRealtime() },
        onEnded = onEnded,
    )

    fun play(url: String, startPosition: Double, seconds: Boolean = false) =
        controller.play(url, startPosition, seconds)

    /** rate ≤ 0 pauses; any positive rate resumes (variable-speed playback is not supported). */
    fun setRate(rate: Float) = controller.setRate(rate)

    /** Seeks to [positionSec] seconds, including when preparation is still in progress. */
    fun scrub(positionSec: Double) = controller.scrub(positionSec)

    /** Current immutable playback snapshot for RTSP polling. */
    fun info(): PlaybackInfo? = controller.info()

    /** Promptly re-check a newly available/replaced surface; normal polling also detects it. */
    fun attachSurface() = controller.attachSurface()

    fun release() = controller.release()
}

private class AndroidUrlVideoSurface(val surface: Surface) : UrlVideoSurface {
    override val identity: Any get() = surface
    override val isValid: Boolean get() = surface.isValid
}

private class AndroidMainLooperScheduler : UrlVideoScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else handler.post { action() }
    }

    override fun postDelayed(task: Runnable, delayMillis: Long) {
        handler.postDelayed(task, delayMillis)
    }

    override fun removeCallbacks(task: Runnable) {
        handler.removeCallbacks(task)
    }
}

/** Adapter is created by [UrlVideoPlaybackController.play] on the main Looper. */
private class AndroidUrlVideoBackend : UrlVideoBackend {
    private val player = MediaPlayer()

    init {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
    }

    override fun setOnPreparedListener(listener: (() -> Unit)?) {
        player.setOnPreparedListener(listener?.let { callback -> MediaPlayer.OnPreparedListener { callback() } })
    }

    override fun setOnCompletionListener(listener: (() -> Unit)?) {
        player.setOnCompletionListener(listener?.let { callback -> MediaPlayer.OnCompletionListener { callback() } })
    }

    override fun setOnErrorListener(listener: ((what: Int, extra: Int) -> Boolean)?) {
        player.setOnErrorListener(listener?.let { callback ->
            MediaPlayer.OnErrorListener { _, what, extra -> callback(what, extra) }
        })
    }

    override fun setDataSource(url: String) = player.setDataSource(url)
    override fun prepareAsync() = player.prepareAsync()
    override fun setSurface(surface: UrlVideoSurface?) {
        player.setSurface((surface as? AndroidUrlVideoSurface)?.surface)
    }
    override fun seekTo(positionMs: Int) = player.seekTo(positionMs)
    override fun start() = player.start()
    override fun pause() = player.pause()
    override val isPlaying: Boolean get() = player.isPlaying
    override val durationMs: Int get() = player.duration
    override val positionMs: Int get() = player.currentPosition
    override fun release() = player.release()
}
