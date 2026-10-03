package com.phairplay.airplay

/**
 * StreamStats — live counters for the on-screen debug overlay (Settings → "Debug overlay").
 *
 * The two receivers write into this object as they run:
 *  - AirPlay mirroring: [com.phairplay.airplay.handshake.MirrorStreamServer] (video) and
 *    [com.phairplay.airplay.handshake.AudioStreamServer] (audio)
 *  - Apple Casting (mirroring): [com.phairplay.airplay.handshake.MirrorStreamServer]
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
 *  2. Only the AirPlay servers ever wrote counters, so a mirroring session showed an empty HUD.
 *     Every receiver now feeds this object, and [source] says which one.
 *  3. `videoFps` was recomputed only every 300 payloads — five seconds of "0 fps" at 60 fps,
 *     and forever if the sender sent fewer than 300 payloads. Sampling is now a rolling
 *     one-second window ([noteVideoPayload]) that also yields a bitrate.
 */
object StreamStats {

    /** Which receiver owns the current session. */
    const val SOURCE_AIRPLAY = "AirPlay"

    /** How long the fps/bitrate counters are averaged over. */
    private const val SAMPLE_WINDOW_MS = 1_000L

    /**
     * Sentinel for "no sample window open".
     *
     * Deliberately not `0L`: using 0 as the marker meant a payload timestamped at 0 (a fresh
     * clock, a test) re-opened the window on every call and the counters never published.
     */
    private const val WINDOW_CLOSED = -1L

    /**
     * Master switch, mirrored from the user setting as it changes (not just at receiver start).
     * The overlay only draws when this is true.
     */
    @Volatile var overlayEnabled = false

    // ─── Session ────────────────────────────────────────────────────────────
    /** [SOURCE_AIRPLAY], or an empty string when idle. */
    @Volatile var source: String = ""

    /** Wall-clock start of the current session, or 0 when idle. */
    @Volatile var sessionStartedAtMillis: Long = 0L

    // ─── Video (AirPlay streaming + Apple Casting mirroring) ────────────────
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

    // ─── Audio (mirror audio + buffered/RAOP audio) ─────────────────────────
    @Volatile var audioActive = false  // true while an audio stream is running
    @Volatile var audioQueue = 0       // current playback-queue depth
    @Volatile var audioDupPct = 0      // % of RTP packets that were redundant duplicates
    @Volatile var audioCodec = ""      // "AAC-ELD", "ALAC", "LPCM", "AAC" …


    // ─── Rolling sample window ──────────────────────────────────────────────
    private val sampleLock = Any()
    /** Start of the open window, or [WINDOW_CLOSED] when no window is open. */
    private var windowStartMs = WINDOW_CLOSED
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

        synchronized(sampleLock) {
            windowStartMs = WINDOW_CLOSED
            windowBytes = 0L
            windowPayloads = 0L
        }
    }

    /**
     * Records one video payload (an AirPlay mirror RTP packet) and refreshes
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
            if (windowStartMs == WINDOW_CLOSED) windowStartMs = now
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
        text.append("Hearth · debug\n")

        val active = source
        text.append("SRC    ").append(if (active.isEmpty()) "idle — nothing streaming" else active)
        if (sessionStartedAtMillis != 0L) {
            text.append(" · ").append(formatClock((nowMillis - sessionStartedAtMillis) / 1000.0))
        }
        text.append('\n')

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
}
