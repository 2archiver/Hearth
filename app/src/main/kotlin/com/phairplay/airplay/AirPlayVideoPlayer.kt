package com.phairplay.airplay

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

/**
 * AirPlay URL video (direct `http(s)` locations, direct HLS, and sender-mediated HLS).
 *
 * The Android player implementation lives behind [UrlVideoBackends] (Media3/ExoPlayer in its own
 * file, so the offline JVM test runner can compile this class). Everything here is a thin,
 * main-Looper-confined wrapper: the controller owns the state machine, this class only supplies the
 * Android surface, the clock and the scheduler.
 *
 * @param onFirstFrameRendered the first frame actually reached the surface — this, and not
 *   "the player is ready", is the moment video can take over audio output
 * @param onMediaAudioOwnership called once per generation when it is known whether the media's own
 *   audio should own the sound (true) or the AirPlay audio stream keeps it (false)
 * @param sessionSource bridge media access for a sender-mediated session; null for direct URLs
 */
class AirPlayVideoPlayer(
    surfaceProvider: () -> Surface?,
    onEnded: () -> Unit = {},
    onStateChanged: (AirPlayPlaybackState, String?) -> Unit = { _, _ -> },
    onFirstFrameRendered: () -> Unit = {},
    onMediaAudioOwnership: (Boolean) -> Unit = {},
    sessionSource: UrlVideoSessionSource? = null,
    traceSessionId: String? = null,
    traceConnectionId: String? = null,
    traceRole: String = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL.name,
) {
    private val controller = UrlVideoPlaybackController(
        surfaceProvider = { surfaceProvider()?.let(::AndroidUrlVideoSurface) },
        backendFactory = UrlVideoBackends.resolve(),
        scheduler = AndroidMainLooperScheduler(),
        clockMillis = { SystemClock.elapsedRealtime() },
        onEnded = onEnded,
        onStateChanged = onStateChanged,
        onFirstFrame = onFirstFrameRendered,
        onAudioOwnership = onMediaAudioOwnership,
        sessionSource = sessionSource,
        traceContext = UrlVideoTraceContext(traceSessionId, traceConnectionId, traceRole),
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
    override val platformSurface: Surface get() = surface
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
