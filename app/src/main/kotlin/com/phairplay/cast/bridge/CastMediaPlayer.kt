package com.phairplay.cast.bridge

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import com.phairplay.util.Logger

/**
 * CastMediaPlayer — plays the media URL a Cast sender asks for.
 *
 * WHY a separate player from the AirPlay one: the Cast protocol reports playback state
 * (BUFFERING → PLAYING → IDLE) and a duration back to the sender, so this class keeps the
 * small amount of state `MEDIA_STATUS` needs. It otherwise delegates to Android's
 * [MediaPlayer], which already handles the containers Cast senders use (MP4/MKV over HTTP,
 * HLS, and progressive downloads).
 *
 * All methods are `@Synchronized` because Cast commands arrive on a socket thread while
 * MediaPlayer callbacks fire on their own thread.
 */
/**
 * CastPlayback — the small slice of a media player the Cast protocol needs.
 *
 * WHY an interface: [CastReceiverApp] is pure protocol logic and is unit tested on the
 * plain JVM, where `android.media.MediaPlayer` cannot be constructed (its constructor
 * needs native code). Depending on this interface lets the tests drive the protocol with a
 * fake player.
 *
 * The states are the Cast protocol's own (`IDLE`, `BUFFERING`, `PLAYING`, `PAUSED`).
 */
internal interface CastPlayback {
    fun load(url: String, startPositionSec: Double, autoplay: Boolean)
    fun play()
    fun pause()
    fun stop()
    fun seek(positionSec: Double)
    fun positionSec(): Double
    fun durationSec(): Double
    fun currentState(): String
}

internal class CastMediaPlayer(
    private val surfaceProvider: () -> Surface?,
    private val onStateChanged: (state: String, positionSec: Double, durationSec: Double) -> Unit
) : CastPlayback {

    /** Player states the Cast protocol understands. */
    object State {
        const val IDLE = "IDLE"
        const val BUFFERING = "BUFFERING"
        const val PLAYING = "PLAYING"
        const val PAUSED = "PAUSED"
    }

    private var player: MediaPlayer? = null
    @Volatile private var currentUrl: String? = null
    @Volatile private var state: String = State.IDLE
    @Volatile private var prepared = false
    @Volatile private var startAtMs = 0

    override fun currentState(): String = state
    fun currentUrl(): String? = currentUrl

    /** Loads [url] and starts (or buffers) it. [startPositionSec] may be 0 to start at the top. */
    @Synchronized
    override fun load(url: String, startPositionSec: Double, autoplay: Boolean) {
        release()
        currentUrl = url
        startAtMs = (startPositionSec * 1000.0).toLong().coerceAtLeast(0)
        state = State.BUFFERING
        Logger.i("Cast: loading $url (start=${startAtMs}ms autoplay=$autoplay)")
        val player = MediaPlayer()
        this.player = player
        prepared = false
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            surfaceProvider()?.let { player.setSurface(it) }
            player.setOnPreparedListener { onPrepared(it, autoplay) }
            player.setOnCompletionListener {
                Logger.i("Cast: playback completed")
                state = State.IDLE
                emit()
            }
            player.setOnErrorListener { _, what, extra ->
                Logger.e("Cast: MediaPlayer error what=$what extra=$extra")
                state = State.IDLE
                emit()
                true
            }
            player.setDataSource(url)
            player.prepareAsync()
        }.onFailure {
            Logger.e("Cast: failed to load $url", it)
            state = State.IDLE
            emit()
        }
        emit()
    }

    /** Resumes playback. No-op when nothing is loaded. */
    @Synchronized
    override fun play() {
        val p = player ?: run { Logger.w("Cast: PLAY with nothing loaded"); return }
        if (!prepared) return
        runCatching {
            if (!p.isPlaying) p.start()
            state = State.PLAYING
        }.onFailure { Logger.w("Cast: PLAY failed: ${it.message}") }
        emit()
    }

    /** Pauses playback. No-op when nothing is loaded. */
    @Synchronized
    override fun pause() {
        val p = player ?: return
        if (!prepared) return
        runCatching {
            if (p.isPlaying) p.pause()
            state = State.PAUSED
        }.onFailure { Logger.w("Cast: PAUSE failed: ${it.message}") }
        emit()
    }

    /** Stops playback and drops the session back to IDLE. */
    @Synchronized
    override fun stop() {
        release()
        currentUrl = null
        state = State.IDLE
        emit()
    }

    /** Seeks to [positionSec] seconds. */
    @Synchronized
    override fun seek(positionSec: Double) {
        val p = player ?: return
        if (!prepared) {
            startAtMs = (positionSec * 1000.0).toLong().coerceAtLeast(0)
            return
        }
        runCatching { p.seekTo((positionSec * 1000.0).toLong().toInt()) }
            .onFailure { Logger.w("Cast: SEEK failed: ${it.message}") }
        emit()
    }

    /** Current position in seconds. */
    @Synchronized
    override fun positionSec(): Double {
        val p = player ?: return 0.0
        if (!prepared) return startAtMs / 1000.0
        return runCatching { p.currentPosition / 1000.0 }.getOrDefault(0.0)
    }

    /** Duration in seconds, 0.0 when unknown or a live stream. */
    @Synchronized
    override fun durationSec(): Double {
        val p = player ?: return 0.0
        if (!prepared) return 0.0
        return runCatching { p.duration / 1000.0 }.getOrDefault(0.0)
    }

    /** Re-attaches the streaming surface after the Activity recreates it. */
    @Synchronized
    fun attachSurface() {
        val p = player ?: return
        surfaceProvider()?.let { surface -> runCatching { p.setSurface(surface) } }
    }

    @Synchronized
    fun release() {
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        prepared = false
        startAtMs = 0
    }

    private fun onPrepared(player: MediaPlayer, autoplay: Boolean) {
        synchronized(this) {
            if (this.player !== player) return     // released or replaced while preparing
            prepared = true
            runCatching { surfaceProvider()?.let { player.setSurface(it) } }
            if (startAtMs > 0) runCatching { player.seekTo(startAtMs) }
            if (autoplay) {
                runCatching { player.start() }
                state = State.PLAYING
            } else {
                state = State.PAUSED
            }
            Logger.i("Cast: prepared, duration=${durationSec()}s, autoplay=$autoplay")
        }
        emit()
    }

    private fun emit() {
        onStateChanged(state, positionSec(), durationSec())
    }
}
