package com.phairplay.airplay

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.phairplay.util.Logger
import java.io.IOException

/**
 * The Android player for AirPlay URL video: Media3/ExoPlayer.
 *
 * WHY EXOPLAYER AND NOT `android.media.MediaPlayer`:
 *  1. **Sender-mediated HLS cannot be a plain URL.** Its playlists exist only behind the sender's
 *     authenticated session, so the player must read them through a private URI scheme
 *     (`hearth-hls://…`) that no ordinary URL player understands. Media3 accepts a custom
 *     `DataSource`; MediaPlayer accepts only `http(s)`/file/content locations.
 *  2. **An HLS master playlist is a set of renditions.** Alternate audio (`#EXT-X-MEDIA`), init maps
 *     (`#EXT-X-MAP`), byte ranges and a sliding live window all have to be handled *inside one
 *     player* so the timeline has exactly one owner. Media3's HLS implementation does that;
 *     MediaPlayer's does not (a separate audio rendition would need a second player, which is
 *     exactly the unsynchronized double playback this path must not create).
 *
 * WHAT THIS FILE DOES: maps the identity/control surface the controller needs ([UrlVideoBackend])
 * onto ExoPlayer, including the facts that must be *observed* rather than assumed — first frame
 * rendered, whether the media has a video track at all, and the decoded video size (published to
 * [StreamStats] so the existing `StreamingScreen` aspect-fit applies to URL video as well).
 *
 * This file is excluded from the offline JVM test runner (the only place that imports
 * `androidx.media3.*`); the factory is installed by [com.phairplay.PhairPlayApp].
 */
@UnstableApi
internal class ExoUrlVideoBackendFactory(private val context: Context) : UrlVideoBackendFactory {
    override fun create(source: UrlVideoSessionSource?): UrlVideoBackend =
        ExoUrlVideoBackend(context.applicationContext, source)
}

@UnstableApi
internal class ExoUrlVideoBackend(
    context: Context,
    private val sessionSource: UrlVideoSessionSource?,
) : UrlVideoBackend {

    private val hlsFactory = HlsMediaSource.Factory(
        HearthUrlVideoDataSourceFactory(context, sessionSource)
    ).setAllowChunklessPreparation(true)

    private val progressiveFactory = ProgressiveMediaSource.Factory(
        HearthUrlVideoDataSourceFactory(context, sessionSource)
    )

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .build()
        .also { exo ->
            try {
                exo.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
            } catch (error: Exception) {
                runCatching { exo.release() }
                throw UrlVideoBackendSetupException(
                    AirPlayPlaybackFailureStage.AUDIO_SETUP,
                    "media audio output could not be configured",
                    error,
                )
            }
            exo.setHandleAudioBecomingNoisy(true)
            // Letterbox/pillarbox inside the surface instead of cropping: the surface is sized to the
            // video's aspect ratio by StreamingScreen, so this only affects the frames rendered
            // between a size change and the next layout pass.
            exo.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        }

    private var preparedListener: (() -> Unit)? = null
    private var completionListener: (() -> Unit)? = null
    private var errorListener: ((failure: UrlVideoBackendFailure) -> Boolean)? = null
    private var firstFrameListener: (() -> Unit)? = null

    /** `prepare` is reported once per data source; ExoPlayer may become READY again after rebuffering. */
    private var prepareReported = false

    /** Optimistic until [Tracks] arrive: an unloaded player must not be called "audio only". */
    private var videoTrackPresent = true

    /** Null until [Tracks] arrive — the controller must not guess who owns the soundtrack. */
    private var audioTrackPresent: Boolean? = null

    /** True once this backend published a video size to [StreamStats], so release can clear it. */
    private var publishedVideoSize = false

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            Logger.i("URL video: first frame rendered (ExoPlayer)")
            firstFrameListener?.invoke()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            // The UI reads these to letterbox correctly; without them a portrait clip is stretched.
            StreamStats.videoWidth = videoSize.width
            StreamStats.videoHeight = videoSize.height
            StreamStats.videoRes = "${videoSize.width}x${videoSize.height}"
            publishedVideoSize = true
            AirPlayTrace.record(
                "URL video: video size ${videoSize.width}x${videoSize.height}",
                role = "URL_VIDEO",
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            videoTrackPresent = tracks.groups.any { group ->
                group.type == C.TRACK_TYPE_VIDEO && group.length > 0
            }
            audioTrackPresent = tracks.groups.any { group ->
                group.type == C.TRACK_TYPE_AUDIO && group.length > 0
            }
            Logger.i(
                "URL video: tracks ready (videoTrack=$videoTrackPresent, " +
                    "audioTrack=$audioTrackPresent, groups=${tracks.groups.size})"
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // PlaybackException messages/causes can embed the URI, so classify only its stable code
            // name and send a URL-free failure stage to the controller.
            Logger.w("URL video: ExoPlayer error code=${error.errorCode} name=${error.errorCodeName}")
            val failure = classifyPlaybackError(error.errorCodeName)
            val handled = errorListener?.invoke(failure) ?: false
            if (!handled) Logger.d("URL video: player error was not handled by the session")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (!prepareReported) {
                        prepareReported = true
                        preparedListener?.invoke()
                    }
                }
                Player.STATE_ENDED -> completionListener?.invoke()
                else -> Unit
            }
        }
    }

    private fun classifyPlaybackError(codeName: String): UrlVideoBackendFailure {
        val normalized = codeName.uppercase()
        return when {
            "DRM" in normalized -> UrlVideoBackendFailure(
                AirPlayPlaybackFailureStage.DRM,
                "protected media is not supported",
            )
            "PARSING_MANIFEST" in normalized || "MANIFEST" in normalized -> UrlVideoBackendFailure(
                AirPlayPlaybackFailureStage.MANIFEST,
                "media manifest could not be parsed",
            )
            normalized.startsWith("ERROR_CODE_IO") || "NETWORK" in normalized -> UrlVideoBackendFailure(
                AirPlayPlaybackFailureStage.NETWORK,
                "media network request failed",
            )
            "DECODING" in normalized -> UrlVideoBackendFailure(
                AirPlayPlaybackFailureStage.DECODER,
                "media decoder could not render the stream",
            )
            else -> UrlVideoBackendFailure(
                AirPlayPlaybackFailureStage.PLAYBACK,
                "media playback failed",
            )
        }
    }

    init {
        player.addListener(listener)
    }

    override fun setOnPreparedListener(listener: (() -> Unit)?) {
        preparedListener = listener
    }

    override fun setOnCompletionListener(listener: (() -> Unit)?) {
        completionListener = listener
    }

    override fun setOnErrorListener(listener: ((failure: UrlVideoBackendFailure) -> Boolean)?) {
        errorListener = listener
    }

    override fun setOnFirstFrameListener(listener: (() -> Unit)?) {
        firstFrameListener = listener
    }

    override fun setDataSource(url: String) {
        prepareReported = false
        videoTrackPresent = true
        audioTrackPresent = null
        val uri = Uri.parse(url)
        player.setMediaSource(mediaSourceFor(uri, MediaItem.fromUri(uri)))
    }

    override fun prepareAsync() = player.prepare()

    override fun setSurface(surface: UrlVideoSurface?) {
        val platform = surface?.platformSurface
        if (platform == null) player.clearVideoSurface() else player.setVideoSurface(platform)
    }

    override fun seekTo(positionMs: Int) = player.seekTo(positionMs.coerceAtLeast(0).toLong())

    override fun start() = player.play()

    override fun pause() = player.pause()

    override val isPlaying: Boolean get() = player.isPlaying

    override val durationMs: Int
        get() {
            val duration = player.duration
            if (duration == C.TIME_UNSET || duration <= 0L) return 0
            return duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

    override val positionMs: Int
        get() = player.currentPosition.coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override val hasVideoTrack: Boolean get() = videoTrackPresent

    override val hasAudioTrack: Boolean? get() = audioTrackPresent

    /**
     * Mutes only this player's own output. The AirPlay audio path has its own volume, so this can
     * never silence the stream the user is still listening to while a video is loading.
     */
    override fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    override fun release() {
        runCatching { player.removeListener(listener) }
        runCatching { player.stop() }
        runCatching { player.release() }
        if (publishedVideoSize) {
            // Only clear what this backend published: the mirroring decoder owns these fields while
            // it is running, and URL video and mirroring never run at the same time.
            publishedVideoSize = false
            StreamStats.videoWidth = 0
            StreamStats.videoHeight = 0
            StreamStats.videoRes = ""
        }
    }

    /** Explicit source choice: `.m3u8` is HLS, everything else is progressive. No content sniffing. */
    private fun mediaSourceFor(uri: Uri, mediaItem: MediaItem): MediaSource =
        if (isHls(uri)) hlsFactory.createMediaSource(mediaItem)
        else progressiveFactory.createMediaSource(mediaItem)

    private fun isHls(uri: Uri): Boolean =
        uri.lastPathSegment?.lowercase()?.endsWith(".m3u8") == true
}

/**
 * Data source for AirPlay video: `http(s)`/file/content locations go to a plain [DefaultDataSource],
 * while `hearth-hls://` URIs are read from the session bridge.
 *
 * A bridge body is fetched whole (the reverse channel is a request/response protocol with no range
 * support) and the requested window is sliced locally, so a player that reads a byte range — a seek,
 * or `#EXT-X-BYTERANGE` segments — still gets exactly the bytes it asked for.
 */
@UnstableApi
internal class HearthUrlVideoDataSource(
    context: Context,
    private val sessionSource: UrlVideoSessionSource?,
) : DataSource {

    private val delegate: DataSource = DefaultDataSource.Factory(context).createDataSource()
    private var uri: Uri? = null
    private var body: ByteArray? = null
    private var readPosition = 0
    private var readEnd = 0

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        body = null
        if (isBridgeUri(dataSpec.uri)) {
            val source = sessionSource
                ?: throw IOException("sender-mediated media was requested without a session bridge")
            val bytes = source.open(dataSpec.uri.toString())
            val from = dataSpec.position.coerceIn(0L, bytes.size.toLong()).toInt()
            val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                (bytes.size - from).toLong()
            } else {
                dataSpec.length
            }
            readPosition = from
            readEnd = (from + requested).coerceAtMost(bytes.size.toLong()).toInt()
            body = bytes
            return (readEnd - from).toLong()
        }
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytes = body ?: return delegate.read(buffer, offset, length)
        if (readPosition >= readEnd) return C.RESULT_END_OF_INPUT
        val count = minOf(length, readEnd - readPosition)
        System.arraycopy(bytes, readPosition, buffer, offset, count)
        readPosition += count
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        body = null
        runCatching { delegate.close() }
    }

    private fun isBridgeUri(value: Uri): Boolean =
        value.scheme?.equals(SenderMediatedHlsBridge.SCHEME, ignoreCase = true) == true
}

/** Factory of [HearthUrlVideoDataSource]; one data source per open, as Media3 expects. */
@UnstableApi
internal class HearthUrlVideoDataSourceFactory(
    private val context: Context,
    private val sessionSource: UrlVideoSessionSource?,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = HearthUrlVideoDataSource(context, sessionSource)
}
