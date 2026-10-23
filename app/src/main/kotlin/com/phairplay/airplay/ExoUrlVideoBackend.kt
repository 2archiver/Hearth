package com.phairplay.airplay

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.phairplay.util.Logger
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

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

    @Volatile private var traceContext: UrlVideoTraceContext? = null
    @Volatile private var activeRequestUri: Uri? = null
    @Volatile private var activeRequestHeaders: Map<String, String> = emptyMap()
    @Volatile private var requestGeneration = 0L
    @Volatile private var released = false
    private var pendingUri: Uri? = null
    private var pendingMediaItem: MediaItem? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prepareExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Hearth-media-probe").apply { isDaemon = true }
    }
    private val requestSequence = AtomicLong(0L)
    private val dataSourceFactory = HearthUrlVideoDataSourceFactory(
        sessionSource = sessionSource,
        traceContext = { traceContext },
        requestSequence = requestSequence,
        requestHeaders = { uri -> requestHeadersFor(uri) },
    )

    private val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)
        .setAllowChunklessPreparation(true)

    private val progressiveFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
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
    private var unknownTracksLogged = false

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
            trace(
                "URL video: decoded video size ${videoSize.width}x${videoSize.height}",
                AirPlayTrace.Kind.LIFECYCLE,
            )
        }

        override fun onTracksChanged(tracks: Tracks) {
            val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
            if (videoGroups.isEmpty() && audioGroups.isEmpty()) {
                // Media3 can report an empty/metadata-only Tracks snapshot while preparation is
                // still running. Preserve UNKNOWN rather than turning that transient into AUDIO_ONLY.
                if (!unknownTracksLogged) {
                    unknownTracksLogged = true
                    trace(
                        "Track discovery still unknown during preparation (groups=${tracks.groups.size})",
                        AirPlayTrace.Kind.INFO,
                    )
                }
                return
            }

            videoTrackPresent = videoGroups.isNotEmpty()
            audioTrackPresent = audioGroups.isNotEmpty()
            val trackDetail = (videoGroups + audioGroups).joinToString("; ") { group ->
                val label = if (group.type == C.TRACK_TYPE_VIDEO) "video" else "audio"
                val supported = (0 until group.length).count(group::isTrackSupported)
                val selected = (0 until group.length).count(group::isTrackSelected)
                val mimeTypes = (0 until group.length)
                    .mapNotNull { index -> group.mediaTrackGroup.getFormat(index).sampleMimeType }
                    .distinct()
                    .map(::safeMimeType)
                    .filter(String::isNotBlank)
                    .joinToString(",")
                    .ifBlank { "mime-unknown" }
                "$label=$mimeTypes supported=$supported/${group.length} selected=$selected"
            }
            Logger.i(
                "URL video: tracks confirmed (video=$videoTrackPresent, audio=$audioTrackPresent; $trackDetail)"
            )
            trace(
                "Track discovery confirmed (video=${if (videoTrackPresent) "present" else "absent"}, " +
                    "audio=${if (audioTrackPresent == true) "present" else "absent"}; $trackDetail)",
                AirPlayTrace.Kind.LIFECYCLE,
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            // PlaybackException messages/causes can embed the URI, so classify only its stable code
            // name and send a URL-free failure stage to the controller.
            Logger.w("URL video: ExoPlayer error code=${error.errorCode} name=${error.errorCodeName}")
            trace(
                "Media3 player error code=${error.errorCode} name=${error.errorCodeName}",
                AirPlayTrace.Kind.FAILURE,
            )
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

    override fun setTraceContext(context: UrlVideoTraceContext?) {
        traceContext = context
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

    override fun setDataSource(url: String) = setDataSource(url, emptyMap())

    override fun setDataSource(url: String, requestHeaders: Map<String, String>) {
        prepareReported = false
        videoTrackPresent = true
        audioTrackPresent = null
        unknownTracksLogged = false
        val uri = Uri.parse(url)
        requestGeneration++
        released = false
        activeRequestUri = uri
        activeRequestHeaders = requestHeaders.toMap()
        pendingUri = uri
        pendingMediaItem = MediaItem.fromUri(uri)
    }

    override fun prepareAsync() {
        val uri = pendingUri ?: return
        val mediaItem = pendingMediaItem ?: return
        val generation = requestGeneration
        if (isBridgeUri(uri) || isHls(uri)) {
            preparePlayer(uri, mediaItem, hls = true, generation = generation, evidence = "URL path")
            return
        }
        if (hasKnownProgressiveExtension(uri)) {
            preparePlayer(uri, mediaItem, hls = false, generation = generation, evidence = "URL path")
            return
        }

        // Source type cannot be selected from a signed/extensionless URL. Probe one bounded byte
        // range off the UI thread through the same redirect/TLS guard used for playback. MIME type
        // is authoritative when available; a small body prefix handles generic/octet-stream hosts.
        prepareExecutor.execute {
            val result = probeHls(uri, requestHeadersFor(uri))
            mainHandler.post {
                if (released || requestGeneration != generation) return@post
                preparePlayer(
                    uri,
                    mediaItem,
                    hls = result.isHls,
                    generation = generation,
                    evidence = result.evidence,
                )
            }
        }
    }

    private data class HlsProbeResult(val isHls: Boolean, val evidence: String)

    private fun preparePlayer(
        uri: Uri,
        mediaItem: MediaItem,
        hls: Boolean,
        generation: Long,
        evidence: String,
    ) {
        if (released || requestGeneration != generation) return
        trace(
            "Media source selected: ${if (hls) "HLS" else "progressive"} " +
                "(evidence=$evidence); route=" +
                if (isBridgeUri(uri)) "sender reverse channel" else "receiver HTTP(S)",
            AirPlayTrace.Kind.LIFECYCLE,
        )
        try {
            player.setMediaSource(mediaSourceFor(mediaItem, hls))
            player.prepare()
        } catch (error: Exception) {
            Logger.e("URL video: media source setup failed (${error.javaClass.simpleName})")
            errorListener?.invoke(
                UrlVideoBackendFailure(
                    AirPlayPlaybackFailureStage.PLAYER_SETUP,
                    "media source could not be prepared",
                ),
            )
        }
    }

    private fun probeHls(uri: Uri, headers: Map<String, String>): HlsProbeResult {
        val source = HearthRedirectGuardedHttpDataSource()
        return try {
            val spec = DataSpec.Builder()
                .setUri(uri)
                .setHttpMethod(DataSpec.HTTP_METHOD_GET)
                .setPosition(0L)
                .setLength(HLS_PROBE_BYTES.toLong())
                .setHttpRequestHeaders(headers)
                .build()
            source.open(spec, headers)
            val mimeType = responseContentType(source.getResponseHeaders())
            val mimeResult = UrlVideoSourceClassifier.classify(mimeType)
            if (mimeResult.isHls) {
                HlsProbeResult(true, mimeResult.evidence)
            } else {
                val prefix = ByteArray(HLS_PROBE_BYTES)
                var read = 0
                while (read < prefix.size) {
                    val count = source.read(prefix, read, prefix.size - read)
                    if (count <= 0) break
                    read += count
                }
                val result = UrlVideoSourceClassifier.classify(mimeType, prefix.copyOf(read))
                HlsProbeResult(result.isHls, result.evidence)
            }
        } catch (error: Exception) {
            Logger.d("URL video: guarded HLS probe could not classify response (${error.javaClass.simpleName})")
            HlsProbeResult(false, "probe fallback")
        } finally {
            runCatching { source.close() }
        }
    }

    private fun responseContentType(headers: Map<String, List<String>>): String =
        headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()

    private fun requestHeadersFor(uri: Uri): Map<String, String> {
        val root = activeRequestUri ?: return emptyMap()
        val headers = activeRequestHeaders
        return if (sameOrigin(root, uri)) headers else {
            headers.filterKeys { it.lowercase(Locale.ROOT) !in SENSITIVE_REQUEST_HEADERS }
        }
    }

    private fun sameOrigin(first: Uri, second: Uri): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) && effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: Uri): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

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
        released = true
        requestGeneration++
        activeRequestHeaders = emptyMap()
        activeRequestUri = null
        pendingUri = null
        pendingMediaItem = null
        mainHandler.removeCallbacksAndMessages(null)
        prepareExecutor.shutdownNow()
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

    /** Explicit source choice after URL-path classification or the guarded response probe. */
    private fun mediaSourceFor(mediaItem: MediaItem, hls: Boolean): MediaSource =
        if (hls) hlsFactory.createMediaSource(mediaItem)
        else progressiveFactory.createMediaSource(mediaItem)

    private fun isHls(uri: Uri): Boolean =
        uri.lastPathSegment?.lowercase(Locale.ROOT)?.endsWith(".m3u8") == true

    private fun hasKnownProgressiveExtension(uri: Uri): Boolean {
        val path = uri.lastPathSegment?.lowercase(Locale.ROOT).orEmpty()
        return KNOWN_PROGRESSIVE_EXTENSIONS.any(path::endsWith)
    }

    private fun isBridgeUri(uri: Uri): Boolean =
        uri.scheme?.equals(SenderMediatedHlsBridge.SCHEME, ignoreCase = true) == true

    private fun safeMimeType(value: String?): String =
        value?.lowercase(Locale.ROOT)?.take(80)
            ?.takeIf { MIME_TYPE_PATTERN.matches(it) }
            ?: "mime-unknown"

    private fun trace(message: String, kind: AirPlayTrace.Kind = AirPlayTrace.Kind.INFO) {
        val context = traceContext
        AirPlayTrace.record(
            message = message,
            sessionId = context?.sessionId,
            connectionId = context?.connectionId,
            role = context?.role,
            kind = kind,
        )
    }

    private companion object {
        const val HLS_PROBE_BYTES = 1024
        val MIME_TYPE_PATTERN = Regex("[a-z0-9.+_-]+/[a-z0-9.+_-]+")
        val KNOWN_PROGRESSIVE_EXTENSIONS = setOf(
            ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".mpg", ".mpeg", ".ts",
            ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg",
        )
        val SENSITIVE_REQUEST_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "cookie",
            "cookie2",
            "x-api-key",
            "x-auth-token",
            "x-access-token",
        )
    }
}
