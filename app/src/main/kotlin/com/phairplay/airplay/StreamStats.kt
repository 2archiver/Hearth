package com.phairplay.airplay

/**
 * StreamStats — live counters for the on-screen debug overlay (Settings → "Debug overlay").
 *
 * The three receivers write into this object as they run:
 *  - AirPlay mirroring: [com.phairplay.airplay.handshake.MirrorStreamServer] (video) and
 *    [com.phairplay.airplay.handshake.AudioStreamServer] (audio)
 *  - Miracast: [com.phairplay.miracast.WfdVideoRenderer] / [com.phairplay.miracast.WfdRtspServer]
 *  - Google Cast: [com.phairplay.cast.bridge.CastMediaPlayer]
 *
 * [com.phairplay.ui.StreamingScreen] polls [summary] a few times a second to render the HUD.
 * Plain volatile fields keep the hot path allocation-free — no flows or locks on the per-frame
 * path. The only lock in here guards the one-second sampling window, which is entered once per
 * payload, not once per byte.
 *
 * WHY THIS OBJECT IS SHAPED THE WAY IT IS
 * ---------------------------------------
 * Before 1.5.0 the overlay could sit on screen showing nothing but zeroes, which looked
 * identical to "the overlay is broken". Three separate causes, all fixed here:
 *
 *  1. `overlayEnabled` was only copied from settings inside `startAirPlay()`. Toggling
 *     Settings → "Debug overlay" does not restart the service (it should not have to), so the
 *     toggle did nothing until the user happened to press Restart. The flag is now mirrored
 *     live from the settings flow — see [com.phairplay.service.PhairPlayService.onCreate].
 *  2. Only the AirPlay servers ever wrote counters, so a Cast or Miracast session showed an
 *     empty HUD. Every receiver now feeds this object, and [source] says which one.
 *  3. `videoFps` was recomputed only every 300 payloads — five seconds of "0 fps" at 60 fps,
 *     and forever if the sender sent fewer than 300 payloads. Sampling is now a rolling
 *     one-second window ([noteVideoPayload]) that also yields a bitrate.
 */
object StreamStats {

    /** Which receiver owns the current session. */
    const val SOURCE_AIRPLAY = "AirPlay"
    const val SOURCE_CAST = "Cast"
    const val SOURCE_MIRACAST = "Miracast"

    /** How long the fps/bitrate counters are averaged over. */
    private const val SAMPLE_WINDOW_MS = 1_000L

    /** How much of a URL the HUD shows before truncating it. */
    private const val MAX_URL_COLUMNS = 44

    /**
     * Master switch, mirrored from the user setting as it changes (not just at receiver start).
     * The overlay only draws when this is true.
     */
    @Volatile var overlayEnabled = false

    // ─── Session ────────────────────────────────────────────────────────────
    /** [SOURCE_AIRPLAY] / [SOURCE_CAST] / [SOURCE_MIRACAST], or "" when idle. */
    @Volatile var source: String = ""

    /** Wall-clock start of the current session, or 0 when idle. */
    @Volatile var sessionStartedAtMillis: Long = 0L

    // ─── Video (AirPlay mirror + Miracast) ──────────────────────────────────
    @Volatile var videoRes = ""        // e.g. "1920x1080"
    @Volatile var videoFps = 0         // payloads/sec over the last sample window
    @Volatile var videoQueue = 0       // current decode-queue depth
    @Volatile var videoDropPct = 0     // cumulative % of payloads dropped under load
    @Volatile var videoBitrateKbps = 0 // payload bytes/sec over the last window, in kbit/s
    @Volatile var videoDecoderReady = false
    @Volatile var videoFramesIn = 0
    @Volatile var videoFramesDropped = 0

    // Actual decoded video dimensions (from the SPS, so portrait phone streams are portrait here).
    // StreamingScreen reads these to aspect-fit the Surface instead of stretching to 16:9.
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0

    // ─── Audio (AirPlay audio + WFD audio + Cast playback) ──────────────────
    @Volatile var audioActive = false  // true while an audio stream is running
    @Volatile var audioQueue = 0       // current playback-queue depth
    @Volatile var audioDupPct = 0      // % of RTP packets that were redundant duplicates
    @Volatile var audioCodec = ""      // "AAC-ELD", "ALAC", "LPCM", "AAC" …

    // ─── Google Cast playback ───────────────────────────────────────────────
    @Volatile var castState = ""       // IDLE / BUFFERING / PLAYING / PAUSED
    @Volatile var castPositionSec = 0.0
    @Volatile var castDurationSec = 0.0
    @Volatile var castContentId = ""

    // ─── Rolling sample window ──────────────────────────────────────────────
    private val sampleLock = Any()
    private var windowStartMs = 0L
    private var windowBytes = 0L
    private var windowPayloads = 0L

    /** Called once when a receiver reports CONNECTED, so the HUD starts from a clean slate. */
    fun beginSession(source: String) {
        resetStreams()
        this.source = source
        sessionStartedAtMillis = System.currentTimeMillis()
    }

    /** Called once when a session ends. */
    fun endSession() {
        resetStreams()
    }

    /** Clears every counter. Keeps [overlayEnabled] — that is a setting, not stream state. */
    fun resetStreams() {
        source = ""
        sessionStartedAtMillis = 0L

        videoRes = ""
        videoFps = 0
        videoQueue = 0
        videoDropPct = 0
        videoBitrateKbps = 0
        videoDecoderReady = false
        videoFramesIn = 0
        videoFramesDropped = 0
        videoWidth = 0
        videoHeight = 0

        audioActive = false
        audioQueue = 0
        audioDupPct = 0
        audioCodec = ""

        castState = ""
        castPositionSec = 0.0
        castDurationSec = 0.0
        castContentId = ""

        synchronized(sampleLock) {
            windowStartMs = 0L
            windowBytes = 0L
            windowPayloads = 0L
        }
    }

    /**
     * Records one video payload (an AirPlay mirror packet or a WFD RTP packet) and refreshes
     * [videoFps] / [videoBitrateKbps] once per [SAMPLE_WINDOW_MS].
     *
     * Called from the socket reader thread, so it must stay cheap — that is why the window is
     * recomputed at most once a second rather than per packet.
     */
    fun noteVideoPayload(bytes: Int) = noteVideoPayload(bytes, System.currentTimeMillis())

    /**
     * Same as [noteVideoPayload] with the clock injected, so the sampling window can be
     * exercised in a JVM unit test without sleeping a second.
     */
    fun noteVideoPayload(bytes: Int, nowMillis: Long) {
        val now = nowMillis
        synchronized(sampleLock) {
            if (windowStartMs == 0L) windowStartMs = now
            windowBytes += if (bytes > 0) bytes.toLong() else 0L
            windowPayloads++
            val elapsed = now - windowStartMs
            if (elapsed >= SAMPLE_WINDOW_MS) {
                videoFps = (windowPayloads * 1000L / elapsed).toInt()
                // bytes → kilobits: (bytes * 8) / 1000, spread over `elapsed` ms → per second.
                videoBitrateKbps = (windowBytes * 8L / elapsed).toInt()
                windowStartMs = now
                windowBytes = 0L
                windowPayloads = 0L
            }
        }
        videoFramesIn++
    }

    /** Records a payload that was dropped because the decoder queue was full. */
    fun noteVideoPayloadDropped() {
        videoFramesDropped++
        val total = videoFramesIn
        if (total > 0) videoDropPct = (videoFramesDropped * 100 / total).coerceIn(0, 100)
    }

    // ─── Rendering ──────────────────────────────────────────────────────────

    /** Human-readable multi-line HUD text. */
    fun summary(nowMillis: Long = System.currentTimeMillis()): String {
        val text = StringBuilder()
        text.append("PhairPlay · debug\n")

        val active = source
        text.append("SRC    ").append(if (active.isEmpty()) "idle — nothing streaming" else active)
        if (sessionStartedAtMillis != 0L) {
            text.append(" · ").append(formatClock((nowMillis - sessionStartedAtMillis) / 1000.0))
        }
        text.append('\n')

        if (active == SOURCE_CAST) {
            text.append("MEDIA  ").append(castState.ifEmpty { "—" })
                .append("  ").append(formatClock(castPositionSec))
            if (castDurationSec > 0.0) text.append(" / ").append(formatClock(castDurationSec))
            text.append('\n')
            if (castContentId.isNotBlank()) {
                text.append("URL    ").append(shorten(castContentId, MAX_URL_COLUMNS)).append('\n')
            }
            text.append("AUDIO  ").append(if (audioActive) "on" else "off").append('\n')
            return text.toString().trimEnd()
        }

        text.append("VIDEO  ").append(videoRes.ifEmpty { "—" })
            .append("  ").append(videoFps).append("fps")
            .append("  ").append(videoBitrateKbps).append("kbps")
            .append("  q").append(videoQueue)
            .append("  drop").append(videoDropPct).append("%\n")
        text.append("DECODE ")
            .append(if (videoDecoderReady) "ready" else "waiting for SPS/PPS + surface")
            .append("  in ").append(videoFramesIn)
            .append(" drop ").append(videoFramesDropped)
            .append('\n')
        text.append("AUDIO  ")
            .append(
                if (audioActive) {
                    buildString {
                        append("on")
                        if (audioCodec.isNotBlank()) append("  ").append(audioCodec)
                        append("  q").append(audioQueue)
                        append("  dup").append(audioDupPct).append("%")
                    }
                } else {
                    "off"
                }
            )
        return text.toString().trimEnd()
    }

    /** Seconds → `m:ss` (or `h:mm:ss` past an hour). Never negative. */
    private fun formatClock(seconds: Double): String {
        val total = if (seconds.isFinite() && seconds > 0.0) seconds.toLong() else 0L
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        // Hand-rolled rather than String.format(): that needs an explicit Locale to satisfy
        // Android Lint's DefaultLocale check, and lint is warnings-as-errors in this project.
        return if (hours > 0) {
            "$hours:${twoDigits(minutes)}:${twoDigits(secs)}"
        } else {
            "$minutes:${twoDigits(secs)}"
        }
    }

    private fun twoDigits(value: Long): String =
        if (value < 10) "0$value" else value.toString()

    /** Truncates the middle of a long string so the HUD stays one line wide. */
    private fun shorten(value: String, max: Int): String {
        if (value.length <= max) return value
        val head = max * 2 / 3
        val tail = max - head - 1
        return value.substring(0, head) + "…" + value.substring(value.length - tail)
    }
}
