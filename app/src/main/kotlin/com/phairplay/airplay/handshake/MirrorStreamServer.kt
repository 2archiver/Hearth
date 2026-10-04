package com.phairplay.airplay.handshake

import android.view.Surface
import com.phairplay.airplay.AirPlayTrace
import com.phairplay.airplay.StreamStats
import com.phairplay.airplay.VideoDecoder
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MirrorStreamServer — receives and decodes the AirPlay 2 mirroring video stream.
 *
 * macOS connects to [dataPort] and sends [128-byte header][payload] packets:
 *  - payload_size = little-endian int at header offset 0
 *  - payload_type = little-endian short at header offset 4, low byte
 *      • type 1: unencrypted avcC (SPS/PPS) → (re)configure the decoder
 *      • type 0: AES-CTR-encrypted H.264 (AVCC) → decrypt, convert to Annex-B, decode
 *
 * Architecture: a network thread reads + decrypts packets (keeping the AES-CTR keystream strictly
 * ordered) and pushes work onto a bounded queue; a separate decoder thread consumes it. This way
 * the socket is always drained fast — if the decoder can't keep up (this SoC is modest, and the
 * audio codec competes for resources), frames are dropped from the queue instead of stalling the
 * socket, which previously caused macOS to drop the whole session.
 *
 * Reference: RPiPlay lib/raop_rtp_mirror.c (raop_rtp_mirror_thread).
 */
class MirrorStreamServer(
    private val aesKey: ByteArray,
    private val ecdhSecret: ByteArray,
    @Volatile private var streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    private sealed class Item
    internal data class Config(val sps: ByteArray, val pps: ByteArray) : Item()
    private class Frame(val annexB: ByteArray) : Item()

    @Volatile private var cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    private val serverSocket = ServerSocket(0)            // OS-assigned free port
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null   // owned by the decoder thread
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    private var pendingSpsPps: ByteArray? = null          // Annex-B SPS+PPS prepended to next VCL frame (UxPlay raop_rtp_mirror.c)
    // The Surface the current decoder was built against. The SurfaceView destroys its Surface when
    // the app backgrounds and creates a NEW one on return, so we watch for the identity changing
    // and rebuild the decoder — otherwise video stays black after foregrounding.
    @Volatile private var configuredSurface: Surface? = null
    private var framePtsUs = 0L
    private var framesIn = 0
    private var framesDropped = 0
    // Set by the reader thread when a frame is dropped under load; the decoder thread then skips
    // frames until the next keyframe (IDR) so it never decodes a reference-broken, corrupt stream.
    @Volatile private var awaitingKeyframe = false

    /** The OS-assigned TCP port macOS should connect to (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    /**
     * Re-keys the AES-CTR stream cipher with a fresh [newStreamConnectionId] and resets any
     * residual keystream state (UxPlay commit `f0b042b`).
     */
    fun rekey(newStreamConnectionId: Long) {
        streamConnectionId = newStreamConnectionId
        cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, newStreamConnectionId)
        awaitingKeyframe = true
    }

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReader() }
        scope.launch(Dispatchers.IO) { runDecoder() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        queue.clear()
        Logger.i("MirrorStreamServer stopped")
    }

    // ─── Network reader thread: read + decrypt (ordered), enqueue, never block on decode ──────
    private fun runReader() {
        try {
            Logger.i("MirrorStreamServer listening on data port $dataPort")
            // Accept repeatedly, not just once.
            //
            // WHY: the sender's data connection is a plain TCP socket that it re-opens after any
            // interruption — a Wi-Fi blip, the phone sleeping and waking, or iOS handing the
            // stream to a new process. With a single `accept()` the *listener* was still bound
            // (so the SETUP reply stayed valid) but nothing was reading it any more, and the
            // reconnect sat in the kernel's backlog until the sender timed out and dropped the
            // whole mirror. Accepting in a loop costs one coroutine and turns that dead end into
            // a one-second gap.
            var connections = 0
            while (running && !serverSocket.isClosed) {
                val socket = serverSocket.accept().also { client = it }
                connections++
                Logger.i("Mirror data connection #$connections from ${socket.inetAddress.hostAddress}")
                AirPlayTrace.record("Mirroring: sender connected the video stream (${socket.inetAddress.hostAddress})")
                // A reconnect arrives with a fresh H.264 config packet; resync the decoder at the
                // next keyframe instead of feeding it frames from a broken reference chain.
                awaitingKeyframe = true
                readConnection(socket)
                runCatching { socket.close() }
                client = null
                if (running) Logger.i("Mirror data connection closed — waiting for a reconnect")
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror reader error", e)
        } finally {
            running = false
            Logger.i("Mirror data connection ended")
        }
    }

    /** Reads one sender data connection until it closes; returns when the socket ends. */
    private fun readConnection(socket: Socket) {
        try {
            runCatching { socket.keepAlive = true }
            // Reset the AES-CTR stream cipher at the start of each TCP data connection so no
            // residual keystream state carries over across reconnects (UxPlay commit f0b042b).
            cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
            val input = socket.getInputStream()
            val header = ByteArray(128)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, 128)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                // payloadSize == 0 is valid (e.g. type 2 1s heartbeat in UxPlay raop_rtp_mirror.c).
                if (payloadSize < 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: bad payloadSize=$payloadSize type=$payloadType — stopping")
                    break
                }
                val payload = if (payloadSize > 0) {
                    ByteArray(payloadSize).also { if (!readFully(input, it, payloadSize)) return }
                } else {
                    ByteArray(0)
                }
                when (payloadType) {
                    0 -> {
                        if (payload.isEmpty()) continue
                        // ALWAYS advance the AES-CTR keystream, in order, for every video payload —
                        // skipping any packet desyncs the keystream and corrupts all later frames.
                        val annexB = MirrorCrypto.avccToAnnexB(cipher.update(payload))
                        if (annexB.isNotEmpty()) {
                            val prefix = pendingSpsPps
                            val fullFrame = if (prefix != null) {
                                pendingSpsPps = null
                                prefix + annexB
                            } else {
                                annexB
                            }
                            enqueue(Frame(fullFrame), payloadSize)
                        }
                    }
                    1 -> {
                        val stateByte = header[6].toInt() and 0xFF
                        if (stateByte == 0x56 || stateByte == 0x5E) {
                            Logger.i("Mirror: sender screen suspended (header[6]=0x${stateByte.toString(16)})")
                        } else if (stateByte == 0x16 || stateByte == 0x1E) {
                            Logger.d("Mirror: sender video active/resumed (header[6]=0x${stateByte.toString(16)})")
                        }
                        if (payload.isNotEmpty()) {
                            parseConfig(payload)?.let { cfg ->
                                val sc = MirrorCrypto.START_CODE
                                pendingSpsPps = sc + cfg.sps + sc + cfg.pps
                                enqueue(cfg, payloadSize)
                            }
                        }
                    }
                    2 -> {
                        // Old-protocol 1-second heartbeat packet (payloadSize == 0); keep connection alive.
                        Logger.v("Mirror: received type 2 heartbeat")
                    }
                    5 -> {
                        // Video streaming performance/info binary plist from sender
                        // (with optional 25,000-byte lock-screen trailer, UxPlay raop_rtp_mirror.c).
                        val plistSize = if (payload.size > 25000) payload.size - 25000 else payload.size
                        Logger.v("Mirror: received type 5 streaming info packet ($plistSize B plist)")
                    }
                    else -> Logger.v("Mirror: ignoring payload type $payloadType ($payloadSize B)")
                }
            }
        } catch (e: Exception) {
            if (running) Logger.w("Mirror data connection ended: ${e.message}")
        }
    }

    /**
     * Bounded enqueue — if the decoder is behind, drop the oldest item to keep latency bounded.
     *
     * [payloadBytes] is the on-the-wire size of the payload, which is what the debug overlay's
     * bitrate counter averages. Sampling moved into [StreamStats.noteVideoPayload] so the fps
     * figure refreshes every second instead of every 300 payloads.
     */
    private fun enqueue(item: Item, payloadBytes: Int) {
        framesIn++
        StreamStats.noteVideoPayload(payloadBytes)
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            framesDropped++
            StreamStats.noteVideoPayloadDropped()
            awaitingKeyframe = true        // a frame was lost — resync the decoder at the next IDR
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            Logger.i("Video stats: in=$framesIn dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) queue=${queue.size}/$QUEUE_CAPACITY ${StreamStats.videoFps}fps")
        }
    }

    /**
     * Publishes the *decoded* size (rather than the advertised one) to the HUD once MediaCodec
     * has reported it, so the overlay shows what is really being rendered.
     */
    private fun publishResolution() {
        val width = StreamStats.videoWidth
        val height = StreamStats.videoHeight
        if (width <= 0 || height <= 0) return
        val resolved = "${width}x${height}"
        if (resolved != StreamStats.videoRes) StreamStats.videoRes = resolved
    }

    private fun parseConfig(payload: ByteArray): Config? = Companion.parseConfig(payload)

    // ─── Decoder thread: consume the queue; the only thread that touches the decoder ──────────
    private fun runDecoder() {
        try {
            while (running) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS)
                if (item == null) {
                    // Queue drained — the HUD's queue depth must fall back to 0, otherwise it
                    // freezes at whatever the backlog was and looks like a stalled decoder.
                    StreamStats.videoQueue = 0
                    continue
                }
                when (item) {
                    is Config -> configureDecoder(item.sps, item.pps)
                    is Frame -> decodeFrame(item.annexB)
                }
                StreamStats.videoQueue = queue.size
                publishResolution()
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror decoder thread error", e)
        } finally {
            decoder?.release()
            decoder = null
            StreamStats.videoDecoderReady = false
            StreamStats.videoQueue = 0
        }
    }

    private fun configureDecoder(sps: ByteArray, pps: ByteArray) {
        // New SPS/PPS (or first config) — cache it and (re)build against the current surface.
        val d = decoder
        val surface = awaitSurface()
        if (d != null && d.isHealthy && sps.contentEquals(lastSps) && pps.contentEquals(lastPps) &&
            surface === configuredSurface) return
        lastSps = sps
        lastPps = pps
        rebuildDecoder(surface)
    }

    /**
     * (Re)creates the decoder for [surface] from the cached SPS/PPS. Safe to call on a surface
     * change (app background→foreground): releases the old decoder and resyncs at the next keyframe.
     * If the surface or config isn't available yet, leaves the decoder null and retries on a later
     * frame (frames are dropped until then).
     */
    private fun rebuildDecoder(surface: Surface?) {
        decoder?.release()
        decoder = null
        configuredSurface = surface
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (surface == null) return                            // backgrounded — wait for the surface to return
        val sc = MirrorCrypto.START_CODE
        decoder = VideoDecoder(surface).also { it.initialize(sc + sps, sc + pps, width, height) }
        awaitingKeyframe = true                                // a fresh decoder must start at an IDR
        // The advertised size first; VideoDecoder refines it from the SPS and reports the real
        // decoded size back through StreamStats.videoWidth/videoHeight.
        StreamStats.videoRes = "${width}x${height}"
        StreamStats.videoDecoderReady = true
        Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B)")
    }

    private fun decodeFrame(annexB: ByteArray) {
        // Re-attach to the live Surface if it changed (the app was backgrounded and returned, so the
        // SurfaceView made a new Surface). Without this, video stays black after foregrounding.
        val liveSurface = surfaceProvider()
        if (liveSurface !== configuredSurface) {
            Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching decoder")
            rebuildDecoder(liveSurface)
        }
        val d = decoder ?: return                              // need surface + SPS/PPS first
        if (!d.isHealthy) {                                    // error state — drop, await next config
            Logger.w("Mirror: decoder unhealthy — dropping, awaiting new SPS/PPS")
            d.release(); decoder = null; configuredSurface = null; lastSps = null; lastPps = null
            StreamStats.videoDecoderReady = false
            return
        }
        if (awaitingKeyframe) {
            // After a dropped frame the stream is reference-broken; skip until the next IDR so we
            // don't feed the decoder predicted frames with missing references (which smear/blocky).
            if (!isKeyframe(annexB)) return
            awaitingKeyframe = false
            Logger.i("Mirror: resynced on keyframe after a dropped frame")
        }
        if (framePtsUs == 0L) Logger.i("Mirror: first video frame fed to decoder (${annexB.size}B)")
        d.decodeNalUnit(annexB, framePtsUs)
        framePtsUs += FRAME_INTERVAL_US
    }

    /** True if the Annex-B frame contains an IDR NAL unit (type 5) — a decodable resync point. */
    private fun isKeyframe(annexB: ByteArray): Boolean {
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                if ((annexB[i + 3].toInt() and 0x1F) == 5) return true   // IDR slice
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** The streaming Surface appears shortly after CONNECTED is emitted; poll briefly. */
    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val MAX_PAYLOAD = 8 * 1024 * 1024        // 8 MB sanity cap per frame
        private const val FRAME_INTERVAL_US = 1_000_000L / 60  // monotonic PTS hint (~60fps)
        private const val QUEUE_CAPACITY = 90                  // ~1.5s @60fps before dropping
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L

        /**
         * Parses an H.264 `avcC` configuration packet (type 1 payload) into SPS and PPS byte
         * arrays, validating bounds and rejecting unsupported `hvc1` (HEVC) headers (matching
         * UxPlay's `raop_rtp_mirror.c`).
         */
        internal fun parseConfig(payload: ByteArray): Config? = try {
            if (payload.size >= 8 &&
                payload[4] == 'h'.code.toByte() &&
                payload[5] == 'v'.code.toByte() &&
                payload[6] == 'c'.code.toByte() &&
                payload[7] == '1'.code.toByte()
            ) {
                Logger.w("Mirror: received hvc1 (HEVC) config packet — only H.264 avcC is advertised")
                null
            } else if (payload.size < 11) {
                Logger.w("Mirror: avcC config payload too short (${payload.size}B)")
                null
            } else {
                val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
                val ppsLenOffset = 8 + spsSize + 1                   // skip the 1-byte PPS count
                if (spsSize <= 0 || ppsLenOffset + 2 > payload.size) {
                    Logger.w("Mirror: invalid SPS size ($spsSize) in ${payload.size}B config packet")
                    null
                } else {
                    val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
                        (payload[ppsLenOffset + 1].toInt() and 0xFF)
                    val ppsEnd = ppsLenOffset + 2 + ppsSize
                    if (ppsSize <= 0 || ppsEnd > payload.size) {
                        Logger.w("Mirror: invalid PPS size ($ppsSize) in ${payload.size}B config packet")
                        null
                    } else {
                        val sps = payload.copyOfRange(8, 8 + spsSize)
                        val pps = payload.copyOfRange(ppsLenOffset + 2, ppsEnd)
                        Config(sps, pps)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("Mirror: failed to parse SPS/PPS", e); null
        }
    }
}
