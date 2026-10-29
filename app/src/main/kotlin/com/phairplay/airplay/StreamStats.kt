package com.phairplay.airplay

/**
 * StreamStats — live counters for the playback debug overlay (Settings → "Debug overlay") and for
 * the Settings → Activity log details.
 *
 * WHAT CHANGED IN 2.1.0
 * ---------------------
 * 2.0's overlay labelled its receive-payload rate as FPS (`rx=60/s`). That number counts RTP
 * payloads on the socket, which is packet throughput, not frames — it is not the receiver's
 * rendering rate and it is not evidence that 60 distinct frames reached the TV. 2.1.0 keeps the
 * two measurements separate and only shows them under names that say what they measure:
 *
 *  - [presentedFps] / [frameTimeP50Ms] / [frameTimeP95Ms] are measured at the one point where a
 *    decoded frame is handed to the display Surface ([noteFramePresented]). This is the app-side
 *    presentation rate. It is not a panel composition rate — Android exposes no reliable
 *    per-frame on-glass callback — and the overlay never claims otherwise.
 *  - [sourceFps] is derived from the sender's own presentation timestamps (see
 *    [noteSourceFramePts]). It reports the cadence the sender chose (24/25/30/50/60), so a 30 fps
 *    source is never presented as if it were 60.
 *  - [rxPayloadsPerSec] and [rxBitrateKbps] are the socket-side figures and are labelled RX.
 *
 * A value that has not been measured is reported as `N/A`, never as `0` (the 2.0 overlay could show
 * a convincing row of zeroes that looked like a broken measurement).
 *
 * THREADING / COST
 * ----------------
 * Writers are the mirror reader thread, the decoder thread, the audio thread and the service. The
 * hot path is plain volatile writes plus one short synchronized block per payload/frame that only
 * appends to a fixed-size ring and recomputes once a second. No allocations, no flows and no
 * per-frame logging — [summary] is called by the UI at 1 Hz, which is the only place that builds
 * strings.
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

    /** How many presented-frame intervals the p50/p95 window keeps (≈4 s at 60 fps). */
    private const val MAX_FRAME_INTERVALS = 240

    /** Master switch, mirrored from the user setting as it changes (not just at receiver start). */
    @Volatile var overlayEnabled = false

    // ─── Session ────────────────────────────────────────────────────────────
    /** [SOURCE_AIRPLAY], or an empty string when idle. */
    @Volatile var source: String = ""

    /** Wall-clock start of the current session, or 0 when idle. */
    @Volatile var sessionStartedAtMillis: Long = 0L

    /**
     * Receiver-wide state for the overlay's title line ("ready", "stopped", "error").
     * Unlike the stream counters this survives [resetStreams] — it describes the service, not a
     * session, so the overlay can say why nothing is on screen.
     */
    @Volatile var receiverState: String = ""

    /** Panel refresh rate in Hz when the UI knows it; 0 when unknown. Never used as an fps. */
    @Volatile var displayRefreshHz: Float = 0f

    // ─── Video: receive side (labelled RX in the overlay) ───────────────────
    @Volatile var videoRes = ""          // e.g. "1920x1080"
    @Volatile var rxPayloadsPerSec = 0   // RTP payloads/sec — NOT frames, NOT fps
    @Volatile var rxBitrateKbps = 0      // payload bytes/sec over the last window, in kbit/s
    @Volatile var videoQueue = 0         // current encoded-queue depth
    @Volatile var videoDropPct = 0       // cumulative % of payloads dropped under load
    @Volatile var videoDecoderReady = false
    @Volatile var videoFramesIn = 0
    /** Encoded frames lost before decode (queue pressure or missing codec input buffer). */
    @Volatile var videoFramesDropped = 0
    @Volatile var videoQueueBytes = 0L
    @Volatile var videoQueueAgeMs = 0L
    @Volatile var videoQueueOverflows = 0L
    @Volatile var videoAwaitingIdr = false

    // ─── Video: presentation side (measured, laballed RENDER) ───────────────
    /** Frames handed to the Surface over the last complete window; 0.0 = not measured yet. */
    @Volatile var presentedFps: Double = 0.0
    @Volatile var frameTimeP50Ms: Double = 0.0
    @Volatile var frameTimeP95Ms: Double = 0.0
    @Volatile var presentedFrames = 0L

    /** Encoded cadence taken from the sender's presentation timestamps; 0.0 = unknown. */
    @Volatile var sourceFps: Double = 0.0

    /** Scheduled (timestamp-paced) presentations, used by Stable FPS. */
    @Volatile var videoPresentationScheduled = 0L

    /** Decoded frames discarded as stale by Stable FPS pacing. */
    @Volatile var videoPresentationDropped = 0L
    @Volatile var mirrorStableFpsEnabled = false
    @Volatile var mirrorTimestampSource = "unknown"
    @Volatile var mirrorTimestampDiscontinuities = 0L

    /** Decoder implementation reported by MediaCodec after configure(), or "" when unknown. */
    @Volatile var decoderName = ""

    // Actual decoded video dimensions (from the SPS, so portrait phone streams are portrait here).
    // StreamingScreen reads these to aspect-fit the Surface instead of stretching to 16:9.
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0

    // ─── Audio ──────────────────────────────────────────────────────────────
    @Volatile var audioActive = false  // true while an audio stream is running
    @Volatile var audioQueue = 0       // current playback-queue depth
    @Volatile var audioDupPct = 0      // % of RTP packets that were redundant duplicates
    @Volatile var audioCodec = ""      // "AAC-ELD", "ALAC", "LPCM", "AAC" …

    /**
     * Measured audio-minus-video presentation offset in ms, or null when the pipeline cannot
     * measure it. Only a real anchor may set this: the overlay prints `N/A` rather than inventing
     * a zero, because "0 ms" would read as a verified perfect sync.
     */
    @Volatile var avSyncOffsetMs: Int? = null

    // ─── Rolling sample windows ─────────────────────────────────────────────
    private val sampleLock = Any()

    /** Start of the open RX window, or [WINDOW_CLOSED] when no window is open. */
    private var windowStartMs = WINDOW_CLOSED
    private var windowBytes = 0L
    private var windowPayloads = 0L

    /** Presentation window: frames handed to the Surface since [presentedWindowStartNanos]. */
    private var presentedWindowStartNanos = WINDOW_CLOSED
    private var presentedWindowCount = 0
    private var lastPresentedNanos = WINDOW_CLOSED

    /** Ring of presented-frame intervals (ns) for p50/p95. Fixed size: no growth, no churn. */
    private val intervalsNanos = LongArray(MAX_FRAME_INTERVALS)
    private val sortedScratch = LongArray(MAX_FRAME_INTERVALS)
    private var intervalCount = 0
    private var intervalNext = 0

    /** Sender-PTS window, so source cadence is measured rather than assumed. */
    private var sourceWindowStartUs = WINDOW_CLOSED
    private var sourceWindowFrames = 0

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

    /** Clears every stream counter. Keeps [overlayEnabled] and [receiverState] — they are not stream state. */
    fun resetStreams() {
        source = ""
        sessionStartedAtMillis = 0L

        videoRes = ""
        rxPayloadsPerSec = 0
        rxBitrateKbps = 0
        videoQueue = 0
        videoDropPct = 0
        videoDecoderReady = false
        videoFramesIn = 0
        videoFramesDropped = 0
        videoQueueBytes = 0L
        videoQueueAgeMs = 0L
        videoQueueOverflows = 0L
        videoAwaitingIdr = false

        presentedFps = 0.0
        frameTimeP50Ms = 0.0
        frameTimeP95Ms = 0.0
        presentedFrames = 0L
        sourceFps = 0.0
        decoderName = ""

        videoPresentationScheduled = 0L
        videoPresentationDropped = 0L
        mirrorStableFpsEnabled = false
        mirrorTimestampSource = "unknown"
        mirrorTimestampDiscontinuities = 0L

        videoWidth = 0
        videoHeight = 0

        audioActive = false
        audioQueue = 0
        audioDupPct = 0
        audioCodec = ""
        avSyncOffsetMs = null

        synchronized(sampleLock) {
            windowStartMs = WINDOW_CLOSED
            windowBytes = 0L
            windowPayloads = 0L

            presentedWindowStartNanos = WINDOW_CLOSED
            presentedWindowCount = 0
            lastPresentedNanos = WINDOW_CLOSED
            intervalCount = 0
            intervalNext = 0

            sourceWindowStartUs = WINDOW_CLOSED
            sourceWindowFrames = 0
        }
    }

    /**
     * Records one received video payload (an AirPlay mirror RTP packet) and refreshes
     * [rxPayloadsPerSec] / [rxBitrateKbps] once per second.
     *
     * This is socket throughput. It is **not** a frame rate and must never be labelled as one.
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
        synchronized(sampleLock) {
            if (windowStartMs == WINDOW_CLOSED) windowStartMs = nowMillis
            windowBytes += if (bytes > 0) bytes.toLong() else 0L
            windowPayloads++
            val elapsed = nowMillis - windowStartMs
            if (elapsed >= SAMPLE_WINDOW_MS) {
                rxPayloadsPerSec = (windowPayloads * 1000L / elapsed).toInt()
                // bytes → kilobits: (bytes * 8) / 1000, spread over `elapsed` ms → per second.
                rxBitrateKbps = (windowBytes * 8L / elapsed).toInt()
                windowStartMs = nowMillis
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

    /**
     * Records one decoded frame handed to the display Surface.
     *
     * This is the only honest source for "render fps" in this app: it is where a frame actually
     * leaves the decoder for the TV. Counting payloads (2.0's `rx=…/s`) measured network
     * throughput and could read 60 while nothing was being presented.
     *
     * @param nowNanos monotonic clock, injectable for tests.
     */
    @JvmOverloads
    fun noteFramePresented(nowNanos: Long = System.nanoTime()) {
        synchronized(sampleLock) {
            presentedFrames++
            if (presentedWindowStartNanos == WINDOW_CLOSED) presentedWindowStartNanos = nowNanos
            presentedWindowCount++

            val previous = lastPresentedNanos
            if (previous != WINDOW_CLOSED) {
                val delta = nowNanos - previous
                // Ignore sub-millisecond duplicates and multi-second gaps: neither is a frame
                // interval, and a stall would otherwise poison p50/p95.
                if (delta in MIN_FRAME_INTERVAL_NANOS..MAX_FRAME_INTERVAL_NANOS) {
                    intervalsNanos[intervalNext] = delta
                    intervalNext = (intervalNext + 1) % MAX_FRAME_INTERVALS
                    if (intervalCount < MAX_FRAME_INTERVALS) intervalCount++
                }
            }
            lastPresentedNanos = nowNanos

            val elapsedMs = (nowNanos - presentedWindowStartNanos) / 1_000_000L
            if (elapsedMs >= SAMPLE_WINDOW_MS) {
                presentedFps = presentedWindowCount * 1000.0 / elapsedMs
                publishFrameTimes()
                presentedWindowStartNanos = nowNanos
                presentedWindowCount = 0
            }
        }
    }

    /** p50/p95 of the retained presented-frame intervals. Caller holds [sampleLock]. */
    private fun publishFrameTimes() {
        val count = intervalCount
        if (count == 0) {
            frameTimeP50Ms = 0.0
            frameTimeP95Ms = 0.0
            return
        }
        System.arraycopy(intervalsNanos, 0, sortedScratch, 0, count)
        java.util.Arrays.sort(sortedScratch, 0, count)
        frameTimeP50Ms = sortedScratch[(count - 1) * 50 / 100] / 1_000_000.0
        frameTimeP95Ms = sortedScratch[(count - 1) * 95 / 100] / 1_000_000.0
    }

    /**
     * Records the sender's presentation timestamp of one decoded access unit and derives
     * [sourceFps] from consecutive PTS values.
     *
     * WHY from PTS: AirPlay does not tell the receiver "this stream is 30 fps". The sender's
     * timestamps do carry the real cadence, so a 30 fps mirror is reported as 30 fps instead of
     * being "smoothness-matched" up to the panel rate. Backwards jumps (a sender clock reset) open
     * a new window instead of producing a nonsense rate.
     */
    fun noteSourceFramePts(presentationTimeUs: Long) {
        synchronized(sampleLock) {
            if (sourceWindowStartUs == WINDOW_CLOSED) {
                sourceWindowStartUs = presentationTimeUs
                sourceWindowFrames = 0
                return
            }
            sourceWindowFrames++
            val elapsedUs = presentationTimeUs - sourceWindowStartUs
            if (elapsedUs <= 0L) {
                // Sender re-anchored (dropped/rewound timestamps): restart the measurement.
                sourceWindowStartUs = presentationTimeUs
                sourceWindowFrames = 0
                return
            }
            if (elapsedUs >= SAMPLE_WINDOW_MS * 1000L) {
                sourceFps = sourceWindowFrames * 1_000_000.0 / elapsedUs
                sourceWindowStartUs = presentationTimeUs
                sourceWindowFrames = 0
            }
        }
    }

    /** Publishes bounded mirror-queue diagnostics; byte values never include media contents. */
    fun updateMirrorQueue(
        frames: Int,
        bytes: Long,
        ageMillis: Long,
        awaitingIdr: Boolean,
        overflows: Long,
    ) {
        videoQueue = frames.coerceAtLeast(0)
        videoQueueBytes = bytes.coerceAtLeast(0L)
        videoQueueAgeMs = ageMillis.coerceAtLeast(0L)
        videoAwaitingIdr = awaitingIdr
        videoQueueOverflows = overflows.coerceAtLeast(0L)
    }

    fun noteMirrorTimestamp(source: String, discontinuity: Boolean) {
        mirrorTimestampSource = source
        if (discontinuity) mirrorTimestampDiscontinuities++
    }

    fun noteMirrorPresentationScheduled() {
        videoPresentationScheduled++
    }

    /** Stale decoded presentation drops do not corrupt the encoded H.264 reference chain. */
    fun noteMirrorPresentationDropped() {
        videoPresentationDropped++
    }

    fun configureMirrorPacing(enabled: Boolean, width: Int, height: Int) {
        mirrorStableFpsEnabled = enabled
        if (width > 0 && height > 0) videoRes = "${width}x${height}"
    }

    // ─── Rendering ──────────────────────────────────────────────────────────

    /**
     * Compact, multi-line overlay text for the top-left TV safe area.
     *
     * Rules this text follows, so the overlay stays honest:
     *  - every measured number carries the label of what it measures (RENDER / SOURCE / RX);
     *  - an unmeasured value prints `N/A`, never a zero that looks like a measurement;
     *  - the panel refresh rate is printed as `Hz`, never as an fps;
     *  - unavailable telemetry says so in words (`sync N/A`) instead of omitting the row, so a
     *    reader cannot mistake the absence for a zero.
     */
    fun summary(
        nowMillis: Long = System.currentTimeMillis(),
        nowNanos: Long = System.nanoTime(),
    ): String {
        val recentFps = synchronized(sampleLock) {
            if (lastPresentedNanos != WINDOW_CLOSED &&
                nowNanos - lastPresentedNanos in 0L..2_000_000_000L) presentedFps else 0.0
        }
        return buildString {
            append("Hearth ").append(com.phairplay.BuildConfig.VERSION_NAME.substringBefore("-main.").substringBefore("-googletv"))
            if (recentFps > 0.0) append(" · ").append(fps(recentFps))
            if (source.isEmpty()) {
                append("\nWaiting for a stream")
            } else {
                append("\n").append(videoRes.ifEmpty { "Connecting…" })
                if (sessionStartedAtMillis != 0L) {
                    append(" · ").append(formatClock((nowMillis - sessionStartedAtMillis) / 1000.0))
                }
                if (videoAwaitingIdr) append("\nRecovering video…")
            }
        }
    }

    fun diagnosticsSummary(nowMillis: Long = System.currentTimeMillis()): String {
        val text = StringBuilder()
        val active = source
        val state = receiverState
        text.append("HEARTH ").append(com.phairplay.BuildConfig.VERSION_NAME.substringBefore("-main.").substringBefore("-googletv"))
        if (active.isNotEmpty()) text.append(" · ").append(active)
        if (displayRefreshHz > 0f) text.append(" · panel ").append(oneDecimal(displayRefreshHz.toDouble())).append(" Hz")
        if (sessionStartedAtMillis != 0L) {
            text.append(" · ").append(formatClock((nowMillis - sessionStartedAtMillis) / 1000.0))
        }
        text.append('\n')

        if (active.isEmpty()) {
            text.append("idle — nothing streaming")
            if (state.isNotEmpty()) text.append(" (receiver ").append(state).append(')')
            text.append('\n').append("RENDER  N/A — no frames presented yet")
            return text.toString().trimEnd()
        }

        text.append("RENDER  ").append(fps(presentedFps))
        text.append("  frame ").append(ms(frameTimeP50Ms)).append(" / ").append(ms(frameTimeP95Ms))
            .append(" ms p50/p95\n")
        text.append("SOURCE  ").append(
            if (sourceFps > 0.0) "${fps(sourceFps)} (sender PTS)" else "N/A (sender PTS not read yet)"
        ).append('\n')
        text.append("DROPS   ").append(videoFramesDropped).append(" encoded · ")
            .append(videoPresentationDropped).append(" stale · ").append(videoDropPct).append("%\n")
        text.append("VIDEO   ").append(videoRes.ifEmpty { "N/A" })
        if (decoderName.isNotEmpty()) text.append("  ").append(decoderName)
        text.append('\n')
        text.append("RX      ").append(megabits(rxBitrateKbps)).append("  · ")
            .append(rxPayloadsPerSec).append(" pkt/s · q").append(videoQueue)
            .append('/').append(videoQueueBytes / 1024).append(" KiB\n")
        text.append("PACE    stable ").append(if (mirrorStableFpsEnabled) "on" else "off")
            .append(" · pts ").append(mirrorTimestampSource)
            .append(" · scheduled ").append(videoPresentationScheduled)
            .append(" · resets ").append(mirrorTimestampDiscontinuities)
            .append(" · idr ").append(if (videoAwaitingIdr) "wait" else "ready")
            .append('\n')
        text.append("AUDIO   ").append(
            if (audioActive) {
                buildString {
                    append(if (audioCodec.isNotBlank()) audioCodec else "active")
                    append(" · q").append(audioQueue)
                    append(" · dup ").append(audioDupPct).append('%')
                }
            } else {
                "off"
            }
        )
        val sync = avSyncOffsetMs
        text.append(" · sync ").append(if (sync != null) "${sync} ms" else "N/A (not measurable)")
        text.append('\n')
        text.append("KEYS    D-pad stays on the app; this overlay never takes focus")
        return text.toString().trimEnd()
    }

    /** fps or `N/A`; never a fabricated 0.0. */
    private fun fps(value: Double): String =
        if (value > 0.0 && value.isFinite()) "${oneDecimal(value)} fps" else "N/A"

    private fun ms(value: Double): String =
        if (value > 0.0 && value.isFinite()) oneDecimal(value) else "N/A"

    private fun megabits(kbps: Int): String =
        if (kbps <= 0) "N/A kbps"
        else if (kbps >= 1000) "${oneDecimal(kbps / 1000.0)} Mbps"
        else "$kbps kbps"

    /** One decimal, hand-rolled: String.format needs a Locale to satisfy lint's DefaultLocale. */
    private fun oneDecimal(value: Double): String {
        if (!value.isFinite()) return "N/A"
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        val whole = rounded.toLong()
        val tenth = kotlin.math.round((rounded - whole) * 10.0).toLong()
        return "$whole.$tenth"
    }

    /** Seconds → `m:ss` (or `h:mm:ss` past an hour). Never negative. */
    private fun formatClock(seconds: Double): String {
        val total = if (seconds.isFinite() && seconds > 0.0) seconds.toLong() else 0L
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            "$hours:${twoDigits(minutes)}:${twoDigits(secs)}"
        } else {
            "$minutes:${twoDigits(secs)}"
        }
    }

    private fun twoDigits(value: Long): String =
        if (value < 10) "0$value" else value.toString()

    /** A duplicate presentation is not an interval. */
    private const val MIN_FRAME_INTERVAL_NANOS = 1_000_000L

    /** A two-second gap is a stall, not the frame interval of a slow stream. */
    private const val MAX_FRAME_INTERVAL_NANOS = 2_000_000_000L
}
