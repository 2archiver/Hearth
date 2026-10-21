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

/**
 * Receives and decodes the AirPlay 2 mirroring video stream.
 *
 * The sender writes a 128-byte packet header followed by its payload. Type 0 payloads are decrypted
 * in arrival order (AES-CTR state must advance for every encoded packet); type 1 payloads carry clear
 * avcC SPS/PPS. A codec config and its matching IDR are kept together so queue pressure cannot
 * silently evict config ordering. Encoded loss always resumes at a configured IDR.
 *
 * The network reader and decoder consumer remain separate. The handoff is bounded independently by
 * frame count, total bytes and queued age; Stable FPS adds bounded timestamp-based presentation and
 * stale decoded-frame dropping, without a fixed frame-rate target or sleeps.
 */
class MirrorStreamServer(
    private val aesKey: ByteArray,
    private val ecdhSecret: ByteArray,
    @Volatile private var streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val stableFpsEnabled: Boolean = false,
) {
    private data class PendingConfig(
        val config: MirrorCodecConfig,
        val rawTimestamp: Long,
        val receivedAtNanos: Long,
    ) {
        fun matches(frameTimestamp: Long): Boolean = rawTimestamp == frameTimestamp
    }

    @Volatile private var cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    private val serverSocket = ServerSocket(0) // OS-assigned free port
    private val timestampMapper = MirrorTimestampMapper()
    private val frameQueue = BoundedMirrorFrameQueue(
        maxAgeNanos = if (stableFpsEnabled) {
            BoundedMirrorFrameQueue.STABLE_FPS_MAX_AGE_NANOS
        } else {
            BoundedMirrorFrameQueue.DEFAULT_MAX_AGE_NANOS
        },
    )

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null // owned by the decoder thread
    @Volatile private var pendingConfig: PendingConfig? = null
    @Volatile private var activeCodecConfig: MirrorCodecConfig? = null // reader-thread snapshot
    private var lastDecoderConfig: MirrorCodecConfig? = null

    // The SurfaceView may be recreated when the app backgrounds and returns; the decoder is rebuilt
    // against the new identity and encoded input waits for a configured IDR before resuming.
    @Volatile private var configuredSurface: Surface? = null
    private var receivedFrames = 0L

    /** OS-assigned TCP port macOS connects to (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    /** Re-key AES-CTR for a new stream and require a fresh codec-configured IDR. */
    fun rekey(newStreamConnectionId: Long) {
        streamConnectionId = newStreamConnectionId
        cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, newStreamConnectionId)
        pendingConfig = null
        requireRecoveryPoint()
        publishQueueStats()
    }

    fun start(scope: CoroutineScope) {
        running = true
        StreamStats.configureMirrorPacing(stableFpsEnabled, width, height)
        publishQueueStats()
        scope.launch(Dispatchers.IO) { runReader() }
        scope.launch(Dispatchers.IO) { runDecoder() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        requireRecoveryPoint()
        publishQueueStats()
        Logger.i("MirrorStreamServer stopped")
    }

    // ─── Network reader: read + decrypt in order, convert, timestamp and enqueue ───────────────
    private fun runReader() {
        try {
            Logger.i(
                "MirrorStreamServer listening on data port $dataPort " +
                    "(stableFps=$stableFpsEnabled, advertised=${width}x$height)",
            )
            var connections = 0
            while (running && !serverSocket.isClosed) {
                val socket = serverSocket.accept().also { client = it }
                connections++
                Logger.i("Mirror data connection #$connections from ${socket.inetAddress.hostAddress}")
                AirPlayTrace.record("Mirroring: sender connected the video stream (${socket.inetAddress.hostAddress})")
                // Reconnects begin a new cipher run and a new decode dependency chain.
                requireRecoveryPoint()
                pendingConfig = null
                publishQueueStats()
                readConnection(socket)
                runCatching { socket.close() }
                client = null
                if (running) Logger.i("Mirror data connection closed — waiting for a reconnect")
            }
        } catch (error: Exception) {
            if (running) Logger.e("Mirror reader error (${error.javaClass.simpleName})")
        } finally {
            running = false
            Logger.i("Mirror data connection ended")
        }
    }

    /** Reads one sender data connection until it closes. */
    private fun readConnection(socket: Socket) {
        try {
            runCatching { socket.keepAlive = true }
            // A new TCP data connection restarts AES-CTR at the negotiated stream connection id.
            cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
            val input = socket.getInputStream()
            val header = ByteArray(128)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, header.size)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                val rawTimestamp = leLong(header, 8)
                // payloadSize == 0 is valid for heartbeat/report packets.
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
                    0 -> handleVideoPayload(payload, rawTimestamp, payloadSize)
                    1 -> handleCodecConfig(header, payload, rawTimestamp)
                    2 -> Logger.v("Mirror: received type 2 heartbeat")
                    5 -> {
                        // Streaming report packets do not carry a presentation timestamp.
                        val plistSize = if (payload.size > 25_000) payload.size - 25_000 else payload.size
                        Logger.v("Mirror: received type 5 streaming info packet ($plistSize B plist)")
                    }
                    else -> Logger.v("Mirror: ignoring payload type $payloadType ($payloadSize B)")
                }
            }
        } catch (error: Exception) {
            if (running) Logger.w("Mirror data connection ended (${error.javaClass.simpleName})")
        }
    }

    private fun handleVideoPayload(
        payload: ByteArray,
        rawTimestamp: Long,
        payloadSize: Int,
    ) {
        if (payload.isEmpty()) return
        // ALWAYS consume the AES-CTR keystream in packet order, even if this access unit is later
        // rejected, too stale, or dropped while waiting for an IDR.
        val annexB = MirrorCrypto.avccToAnnexB(cipher.update(payload))
        if (annexB.isEmpty()) return

        val arrivalNanos = System.nanoTime()
        val sample = timestampMapper.map(rawTimestamp, arrivalNanos)
        StreamStats.noteVideoPayload(payloadSize, System.currentTimeMillis())
        StreamStats.noteMirrorTimestamp(sample.source.name.lowercase(), sample.discontinuity)
        if (sample.discontinuity) {
            AirPlayTrace.record("Mirroring timestamp discontinuity; presentation re-anchored to monotonic arrival")
        }

        val keyframe = isKeyframe(annexB)
        val frameConfig = if (keyframe) configForKeyframe(rawTimestamp, arrivalNanos) else null
        val prefix = frameConfig?.let { config ->
            val startCode = MirrorCrypto.START_CODE
            startCode + config.sps + startCode + config.pps
        }
        val fullFrame = if (prefix != null) prefix + annexB else annexB
        val queuedFrame = MirrorQueuedFrame(
            annexB = fullFrame,
            arrivalNanos = arrivalNanos,
            presentationTimeUs = sample.presentationTimeUs,
            codecConfig = frameConfig,
            isKeyframe = keyframe,
        )

        val before = frameQueue.snapshot(arrivalNanos)
        frameQueue.offer(queuedFrame, arrivalNanos)
        val after = frameQueue.snapshot(arrivalNanos)
        repeat((after.droppedFrames - before.droppedFrames).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) {
            StreamStats.noteVideoPayloadDropped()
        }
        publishQueueStats(arrivalNanos)
        receivedFrames++
        if (receivedFrames % LOG_EVERY_FRAMES == 0L) {
            Logger.i(
                "Mirror stats: rx=$receivedFrames encodedDrop=${after.droppedFrames} " +
                    "queue=${after.frames}/${after.bytes}B/${after.oldestAgeMillis}ms " +
                    "stableFps=$stableFpsEnabled pts=${sample.source.name.lowercase()}",
            )
        }
    }

    private fun parseConfig(payload: ByteArray): MirrorCodecConfig? = Companion.parseConfig(payload)

    private fun handleCodecConfig(header: ByteArray, payload: ByteArray, rawTimestamp: Long) {
        val stateByte = header[6].toInt() and 0xFF
        if (stateByte == 0x56 || stateByte == 0x5E) {
            Logger.i("Mirror: sender screen suspended (header[6]=0x${stateByte.toString(16)})")
        } else if (stateByte == 0x16 || stateByte == 0x1E) {
            Logger.d("Mirror: sender video active/resumed (header[6]=0x${stateByte.toString(16)})")
        }
        if (payload.isEmpty()) return
        parseConfig(payload)?.let { config ->
            pendingConfig = PendingConfig(config, rawTimestamp, System.nanoTime())
        }
    }

    /** Uses pending SPS/PPS only with its matching IDR; otherwise retain the known active config. */
    private fun configForKeyframe(rawTimestamp: Long, arrivalNanos: Long): MirrorCodecConfig? {
        val pending = pendingConfig
        if (pending != null) {
            val age = (arrivalNanos - pending.receivedAtNanos).coerceAtLeast(0L)
            if (age > MAX_PENDING_CONFIG_AGE_NANOS) {
                pendingConfig = null
            } else if (pending.matches(rawTimestamp)) {
                activeCodecConfig = pending.config
                pendingConfig = null
            }
        }
        return activeCodecConfig
    }

    private fun requireRecoveryPoint() {
        val before = frameQueue.snapshot().droppedFrames
        frameQueue.requireRecoveryPoint()
        val dropped = (frameQueue.snapshot().droppedFrames - before)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        repeat(dropped) { StreamStats.noteVideoPayloadDropped() }
    }

    private fun publishQueueStats(nowNanos: Long = System.nanoTime()) {
        val snapshot = frameQueue.snapshot(nowNanos)
        StreamStats.updateMirrorQueue(
            frames = snapshot.frames,
            bytes = snapshot.bytes,
            ageMillis = snapshot.oldestAgeMillis,
            awaitingIdr = snapshot.awaitingRecoveryPoint,
            overflows = snapshot.overflows,
        )
    }

    /** Decoder thread: this is the only thread that touches MediaCodec. */
    private fun runDecoder() {
        try {
            while (running) {
                val frame = frameQueue.take(QUEUE_WAIT_MILLIS)
                publishQueueStats()
                if (frame == null) continue
                val config = frame.codecConfig
                if (config != null) configureDecoder(config)
                decodeFrame(frame)
                publishResolution()
                publishQueueStats()
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (running) Logger.e("Mirror decoder thread error (${error.javaClass.simpleName})")
        } finally {
            decoder?.release()
            decoder = null
            StreamStats.videoDecoderReady = false
            publishQueueStats()
        }
    }

    private fun configureDecoder(config: MirrorCodecConfig) {
        val currentSurface = surfaceProvider()
        if (decoder != null && decoder?.isHealthy == true && lastDecoderConfig?.sameAs(config) == true &&
            currentSurface === configuredSurface
        ) return

        lastDecoderConfig = MirrorCodecConfig(config.sps.copyOf(), config.pps.copyOf())
        val surface = if (currentSurface != null) currentSurface else awaitSurface()
        if (surface == null) {
            StreamStats.videoDecoderReady = false
            requireRecoveryPoint()
            publishQueueStats()
            return
        }
        rebuildDecoder(surface)
    }

    /** Recreates MediaCodec from the latest SPS/PPS; presentation remains tied to the session clock. */
    private fun rebuildDecoder(surface: Surface?) {
        decoder?.release()
        decoder = null
        configuredSurface = surface
        val config = lastDecoderConfig ?: activeCodecConfig
        if (config == null || surface == null) {
            StreamStats.videoDecoderReady = false
            return
        }
        lastDecoderConfig = MirrorCodecConfig(config.sps.copyOf(), config.pps.copyOf())
        val startCode = MirrorCrypto.START_CODE
        val origin = timestampMapper.presentationOriginNanos
        decoder = VideoDecoder(
            outputSurface = surface,
            stableMirrorPacing = stableFpsEnabled,
            mirrorPresentationOriginNanos = origin,
        ).also {
            it.initialize(startCode + config.sps, startCode + config.pps, width, height)
        }
        StreamStats.configureMirrorPacing(stableFpsEnabled, width, height)
        StreamStats.videoDecoderReady = true
        Logger.i("Mirror decoder rebuilt for surface (SPS=${config.sps.size}B PPS=${config.pps.size}B)")
    }

    private fun decodeFrame(frame: MirrorQueuedFrame) {
        val liveSurface = surfaceProvider()
        if (liveSurface !== configuredSurface) {
            Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching decoder")
            requireRecoveryPoint()
            rebuildDecoder(liveSurface)
            if (!frame.isRecoveryPoint) {
                publishQueueStats()
                return
            }
        }

        val currentDecoder = decoder
        if (currentDecoder == null) {
            requireRecoveryPoint()
            publishQueueStats()
            return
        }
        if (!currentDecoder.isHealthy) {
            Logger.w("Mirror: decoder unhealthy — waiting for a new codec-configured IDR")
            currentDecoder.release()
            decoder = null
            configuredSurface = null
            lastDecoderConfig = null
            StreamStats.videoDecoderReady = false
            requireRecoveryPoint()
            publishQueueStats()
            return
        }
        if (frameQueue.snapshot().awaitingRecoveryPoint && !frame.isRecoveryPoint) {
            StreamStats.noteVideoPayloadDropped()
            return
        }

        if (receivedFrames == 1L) {
            Logger.i("Mirror: first video frame queued for decode (${frame.annexB.size}B)")
        }
        val accepted = currentDecoder.decodeNalUnit(frame.annexB, frame.presentationTimeUs)
        if (!accepted) {
            StreamStats.noteVideoPayloadDropped()
            requireRecoveryPoint()
            publishQueueStats()
            return
        }
        if (frame.isRecoveryPoint) frameQueue.markRecoveryPointAccepted()
    }

    /** Publishes the actual decoded dimensions, separate from the advertised capability size. */
    private fun publishResolution() {
        val decodedWidth = StreamStats.videoWidth
        val decodedHeight = StreamStats.videoHeight
        if (decodedWidth > 0 && decodedHeight > 0) {
            StreamStats.videoRes = "${decodedWidth}x$decodedHeight"
        }
    }

    /** Waits briefly for the display Surface, not to pace or delay frames. */
    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try {
                Thread.sleep(SURFACE_WAIT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buffer: ByteArray, length: Int): Boolean {
        var offset = 0
        while (offset < length) {
            val count = input.read(buffer, offset, length - offset)
            if (count < 0) return false
            offset += count
        }
        return true
    }

    private fun isKeyframe(annexB: ByteArray): Boolean {
        var index = 0
        while (index + 3 < annexB.size) {
            if (annexB[index].toInt() == 0 && annexB[index + 1].toInt() == 0 &&
                annexB[index + 2].toInt() == 1
            ) {
                if ((annexB[index + 3].toInt() and 0x1F) == 5) return true
                index += 3
            } else {
                index++
            }
        }
        return false
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun leLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) { index ->
            value = value or ((bytes[offset + index].toLong() and 0xFFL) shl (index * 8))
        }
        return value
    }

    companion object {
        private const val MAX_PAYLOAD = 8 * 1024 * 1024 // 8 MB per-packet sanity cap
        private const val MAX_PENDING_CONFIG_AGE_NANOS = 1_000_000_000L
        private const val LOG_EVERY_FRAMES = 300L
        private const val QUEUE_WAIT_MILLIS = 200L
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L

        /** Parses clear avcC SPS/PPS and rejects unsupported HEVC headers. */
        internal fun parseConfig(payload: ByteArray): MirrorCodecConfig? = try {
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
                val ppsLengthOffset = 8 + spsSize + 1 // SPS + one-byte PPS count
                if (spsSize <= 0 || ppsLengthOffset + 2 > payload.size) {
                    Logger.w("Mirror: invalid SPS size ($spsSize) in ${payload.size}B config packet")
                    null
                } else {
                    val ppsSize = ((payload[ppsLengthOffset].toInt() and 0xFF) shl 8) or
                        (payload[ppsLengthOffset + 1].toInt() and 0xFF)
                    val ppsEnd = ppsLengthOffset + 2 + ppsSize
                    if (ppsSize <= 0 || ppsEnd > payload.size) {
                        Logger.w("Mirror: invalid PPS size ($ppsSize) in ${payload.size}B config packet")
                        null
                    } else {
                        val sps = payload.copyOfRange(8, 8 + spsSize)
                        val pps = payload.copyOfRange(ppsLengthOffset + 2, ppsEnd)
                        MirrorCodecConfig(sps, pps)
                    }
                }
            }
        } catch (error: Exception) {
            Logger.e("Mirror: failed to parse SPS/PPS (${error.javaClass.simpleName})")
            null
        }
    }
}
