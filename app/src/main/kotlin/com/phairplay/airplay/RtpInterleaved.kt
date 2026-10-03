package com.phairplay.airplay

import com.phairplay.util.Logger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * RtpInterleaved — Reads binary RTP/RTCP frames from the RTSP TCP connection.
 *
 * WHY: After the RTSP RECORD response, the AirPlay sender switches the same
 * TCP connection from text-based RTSP to binary RTP interleaved framing
 * (RFC 2326 §10.12). Each frame looks like:
 *
 * ```
 * ┌──────┬────────────┬───────────────┬──────────────────────────────┐
 * │ '$'  │ channel(1) │ length(2, BE)  │ RTP/RTCP payload (N bytes)  │
 * │  1B  │     1B     │      2B        │            N bytes            │
 * └──────┴────────────┴───────────────┴──────────────────────────────┘
 * ```
 *
 * Channel assignments (negotiated in SETUP responses):
 * - 0 = video RTP
 * - 1 = video RTCP (control, we ignore)
 * - 2 = audio RTP  (if audio is also interleaved over TCP — rarely the case in AirPlay)
 *
 * In AirPlay screen mirroring, video RTP is always interleaved on the RTSP TCP
 * connection (channel 0). Audio typically goes over a separate UDP socket.
 *
 * HOW: Call [readLoop] from a background coroutine after RECORD is acknowledged.
 * It reads frames indefinitely until the stream ends or an error occurs.
 *
 * Example:
 *   // After RECORD response sent:
 *   RtpInterleaved.readLoop(
 *       inputStream = socket.getInputStream(),
 *       session = currentSession,
 *       onVideoNalUnit = { nalUnit, pts -> videoDecoder.decodeNalUnit(nalUnit, pts) },
 *       onStreamEnded = { /* handle disconnect */ }
 *   )
 */
object RtpInterleaved {

    /** The `$` byte that marks the start of an interleaved RTP frame. */
    private const val INTERLEAVED_MARKER = 0x24  // '$'

    /** RTP channel 0 = video RTP data. */
    private const val CHANNEL_VIDEO_RTP = 0

    /** Fixed RTP header size (no CSRC, no extension). */
    private const val RTP_FIXED_HEADER_BYTES = 12

    /**
     * H.264 NAL unit type 28 = FU-A (Fragmentation Unit type A).
     * Used to split a large NAL unit across multiple RTP packets (RFC 6184 §5.8).
     */
    private const val NAL_TYPE_FU_A = 28

    /**
     * H.264 NAL unit type 24 = STAP-A (Single-Time Aggregation Packet).
     * Carries several complete NAL units in one RTP packet — Apple senders use it to
     * ship SPS+PPS alongside the first packet of every keyframe (RFC 6184 §5.7.1).
     */
    private const val NAL_TYPE_STAP_A = 24

    /**
     * H.264 video uses a 90 kHz RTP clock (standard for video, per RFC 6184).
     * Used to convert RTP timestamps to microsecond presentation timestamps.
     */
    private const val RTP_VIDEO_CLOCK_HZ = 90_000L

    /**
     * Maximum acceptable RTP frame size.
     * Real AirPlay video frames are typically 1–200 KB.
     * Cap at 2 MB to reject clearly malformed/malicious frames.
     */
    private const val MAX_RTP_FRAME_BYTES = 2 * 1024 * 1024  // 2 MB

    /** Room to push back exactly one byte: the '$' a re-sync found belongs to the outer loop. */
    private const val PUSHBACK_BYTES = 1

    /**
     * Reads RTP interleaved frames from [inputStream] until the connection ends.
     *
     * SECURITY: Frame length is capped at [MAX_RTP_FRAME_BYTES]. Oversized frames
     * are rejected to prevent heap exhaustion via malicious AirPlay senders.
     *
     * @param inputStream   The raw TCP InputStream from the RTSP socket, positioned
     *                      immediately after the 200 OK response to RECORD was sent.
     * @param onVideoNalUnit Called with each H.264 NAL unit extracted from a video RTP frame.
     *                      @param nalUnit  Raw NAL unit bytes (RTP header stripped).
     *                      @param ptsUs    Presentation timestamp in microseconds.
     * @param onStreamEnded Called when the stream ends cleanly (EOF) or with an error.
     */
    fun readLoop(
        inputStream: InputStream,
        onVideoNalUnit: (nalUnit: ByteArray, ptsUs: Long) -> Unit,
        onStreamEnded: () -> Unit
    ) {
        // FU-A (Fragmentation Unit A) reassembly state — local so concurrent streams
        // don't share state. Non-null means we are accumulating fragments for one NAL unit.
        var fuaAccumulator: ByteArrayOutputStream? = null

        // Wrapped so a re-sync can push the '$' it found back into the stream (see
        // [resyncPastEmbeddedRtsp]). PushbackInputStream delegates every other call through.
        val pb = if (inputStream is PushbackInputStream) inputStream
                 else PushbackInputStream(inputStream, PUSHBACK_BYTES)

        try {
            while (true) {
                // Read the 4-byte interleaved frame header: $ channel(1) length(2)
                val marker = pb.read()
                if (marker == -1) break  // clean EOF — sender disconnected

                // A mid-stream RTSP request — the keep-alive OPTIONS every ~30 s, a
                // GET_PARAMETER, a TEARDOWN — starts with ASCII text, not '$'. The old code
                // skipped a single byte and carried on, so it read the next letters of
                // "OPTIONS" as channel+length, decoded a garbage frame size, and tore the
                // mirror down on a perfectly normal keep-alive ("video dies after a few
                // seconds"). Scan forward to the next '$' instead and keep streaming.
                if (marker != INTERLEAVED_MARKER) {
                    if (!resyncPastEmbeddedRtsp(pb, marker)) break
                    continue
                }

                val channel = pb.read()
                if (channel == -1) break

                val lenHigh = pb.read()
                val lenLow = pb.read()
                if (lenHigh == -1 || lenLow == -1) break

                val frameLength = (lenHigh shl 8) or lenLow

                // SECURITY: Reject unreasonably large frames
                if (frameLength <= 0 || frameLength > MAX_RTP_FRAME_BYTES) {
                    Logger.w("RtpInterleaved: invalid frame length $frameLength — stopping")
                    break
                }

                val frameData = ByteArray(frameLength)
                var bytesRead = 0
                while (bytesRead < frameLength) {
                    val n = pb.read(frameData, bytesRead, frameLength - bytesRead)
                    if (n == -1) {
                        Logger.w("RtpInterleaved: EOF mid-frame")
                        onStreamEnded()
                        return
                    }
                    bytesRead += n
                }

                // Only process video RTP frames (channel 0); ignore RTCP (channel 1)
                if (channel == CHANNEL_VIDEO_RTP) {
                    fuaAccumulator = processVideoRtpFrame(frameData, fuaAccumulator, onVideoNalUnit)
                }
                // Note: audio over interleaved TCP is rare in AirPlay; UDP is used instead.
            }
        } catch (e: Exception) {
            Logger.e("RtpInterleaved: read error — stream ended", e)
        }

        onStreamEnded()
    }

    /**
     * Re-synchronises the interleaved reader after hitting a byte that is not `$`.
     *
     * Scans forward to the next `$` and pushes it back so the outer loop reads it as the start
     * of a frame. RTSP request lines and headers are 7-bit ASCII and cannot contain `$`, so
     * the marker is unambiguous and this cannot cut a frame in half. Whatever RTP was in
     * flight when the request arrived is simply lost — senders retransmit, and the session
     * survives, which is the entire point.
     *
     * @return false when no marker turns up within [MAX_RTP_FRAME_BYTES], i.e. the stream is
     *   not recoverable and the caller should end the session.
     */
    private fun resyncPastEmbeddedRtsp(pb: PushbackInputStream, firstByte: Int): Boolean {
        Logger.d(
            "RtpInterleaved: embedded RTSP data between frames (first byte 0x" +
                firstByte.toString(16) + ") — re-syncing to the next marker"
        )
        var scanned = 0
        while (scanned < MAX_RTP_FRAME_BYTES) {
            val b = pb.read()
            if (b == -1) return false
            scanned++
            if (b == INTERLEAVED_MARKER) {
                pb.unread(b)   // push '$' back for the outer loop to consume as a frame marker
                Logger.d("RtpInterleaved: re-synced after $scanned byte(s)")
                return true
            }
        }
        Logger.w("RtpInterleaved: no marker within $MAX_RTP_FRAME_BYTES bytes — ending stream")
        return false
    }

    /**
     * Parses a video RTP frame and delivers H.264 NAL unit(s) via [callback].
     *
     * Handles three H.264 RTP packetization modes (RFC 6184):
     * - **Single NAL unit** (type ≤ 23): the payload IS the NAL unit.
     * - **FU-A** (type = 28): a large NAL unit fragmented across multiple RTP packets.
     *   Fragments are accumulated in [fuaAccumulator] until the E (end) bit is set,
     *   then the reconstructed NAL unit is delivered and the accumulator is cleared.
     * - **STAP-A** (type = 24): several complete NAL units aggregated in one packet;
     *   each contained NAL unit is delivered individually (in packet order).
     *
     * @param rtpFrame       Full RTP frame bytes (header + payload).
     * @param fuaAccumulator Ongoing FU-A reassembly buffer from the previous call, or null.
     * @param callback       Called with (nalUnit, presentationTimeUs) for each complete NAL unit.
     * @return Updated FU-A accumulator (null = no FU-A in progress after this call).
     */
    internal fun processVideoRtpFrame(
        rtpFrame: ByteArray,
        fuaAccumulator: ByteArrayOutputStream?,
        callback: (ByteArray, Long) -> Unit
    ): ByteArrayOutputStream? {
        if (rtpFrame.size < RTP_FIXED_HEADER_BYTES) {
            Logger.w("RtpInterleaved: RTP frame too small (${rtpFrame.size} bytes)")
            return null
        }

        // CSRC count (CC) is in the lower 4 bits of byte 0
        val csrcCount = rtpFrame[0].toInt() and 0x0F
        val headerSize = RTP_FIXED_HEADER_BYTES + csrcCount * 4

        // Check for RTP header extension (X bit = bit 4 of byte 0)
        val hasExtension = (rtpFrame[0].toInt() and 0x10) != 0
        var payloadOffset = headerSize
        if (hasExtension && rtpFrame.size >= payloadOffset + 4) {
            val extLengthWords = ((rtpFrame[payloadOffset + 2].toInt() and 0xFF) shl 8) or
                                  (rtpFrame[payloadOffset + 3].toInt() and 0xFF)
            payloadOffset += 4 + extLengthWords * 4
        }

        if (payloadOffset >= rtpFrame.size) {
            Logger.w("RtpInterleaved: no payload after RTP header")
            return null
        }

        // Extract the 32-bit RTP timestamp from bytes 4–7 (big-endian) and convert to µs
        val rtpTimestamp = ((rtpFrame[4].toLong() and 0xFF) shl 24) or
                           ((rtpFrame[5].toLong() and 0xFF) shl 16) or
                           ((rtpFrame[6].toLong() and 0xFF) shl 8) or
                            (rtpFrame[7].toLong() and 0xFF)
        val ptsUs = rtpTimestamp * 1_000_000L / RTP_VIDEO_CLOCK_HZ

        // Dispatch based on NAL unit type (low 5 bits of first payload byte)
        val nalType = rtpFrame[payloadOffset].toInt() and 0x1F
        return when {
            nalType == NAL_TYPE_FU_A ->
                handleFuaFragment(rtpFrame, payloadOffset, ptsUs, fuaAccumulator, callback)
            nalType == NAL_TYPE_STAP_A -> {
                if (fuaAccumulator != null) {
                    Logger.w("RtpInterleaved: STAP-A discards incomplete FU-A (${fuaAccumulator.size()} B)")
                }
                handleStapA(rtpFrame, payloadOffset, ptsUs, callback)
                null
            }
            else -> {
                // Single NAL unit mode — deliver directly
                if (fuaAccumulator != null) {
                    Logger.w("RtpInterleaved: dropping incomplete FU-A (${fuaAccumulator.size()} B)")
                }
                callback(rtpFrame.copyOfRange(payloadOffset, rtpFrame.size), ptsUs)
                null
            }
        }
    }

    /**
     * Unpacks an STAP-A aggregation packet and delivers each contained NAL unit.
     *
     * STAP-A packet layout (RFC 6184 §5.7.1):
     * ```
     * ┌ STAP indicator (1B) ─┐ ┌ size (2B) ┌ NAL bytes ┐ ┌ size ┌ NAL ┐ …
     * │ F(1) NRI(2) type=24  │ │           │           │
     * ```
     *
     * @param rtpFrame       Full RTP frame bytes.
     * @param payloadOffset  Offset of the STAP-A indicator byte inside [rtpFrame].
     * @param ptsUs          Presentation timestamp shared by all contained NAL units.
     * @param callback       Called once per contained NAL unit.
     */
    private fun handleStapA(
        rtpFrame: ByteArray,
        payloadOffset: Int,
        ptsUs: Long,
        onVideoNalUnit: (ByteArray, Long) -> Unit
    ) {
        var offset = payloadOffset + 1  // skip the STAP-A indicator byte
        while (offset + 2 <= rtpFrame.size) {
            val nalLength = ((rtpFrame[offset].toInt() and 0xFF) shl 8) or
                            (rtpFrame[offset + 1].toInt() and 0xFF)
            offset += 2
            if (nalLength <= 0 || offset + nalLength > rtpFrame.size) {
                Logger.w("RtpInterleaved: malformed STAP-A length $nalLength — dropping packet")
                return
            }
            onVideoNalUnit(rtpFrame.copyOfRange(offset, offset + nalLength), ptsUs)
            offset += nalLength
        }
    }

    /**
     * Accumulates one H.264 FU-A fragment and delivers the reassembled NAL unit
     * when the end fragment arrives.
     *
     * FU-A packet layout (RFC 6184 §5.8):
     * ```
     * ┌─ FU indicator (1B) ──┐  ┌─ FU header (1B) ─────────────────┐
     * │ F(1) NRI(2) type=28  │  │ S(1) E(1) R(1) original NAL type │
     * └──────────────────────┘  └──────────────────────────────────┘
     * ```
     * The original NAL unit header is reconstructed from NRI + original NAL type.
     *
     * @return Updated accumulator, or null when the NAL unit is complete or on error.
     */
    private fun handleFuaFragment(
        rtpFrame: ByteArray,
        payloadOffset: Int,
        ptsUs: Long,
        fuaAccumulator: ByteArrayOutputStream?,
        onVideoNalUnit: (ByteArray, Long) -> Unit
    ): ByteArrayOutputStream? {
        if (payloadOffset + 1 >= rtpFrame.size) {
            Logger.w("RtpInterleaved: FU-A packet has no FU header — discarding")
            return null
        }

        val fuIndicator = rtpFrame[payloadOffset    ].toInt() and 0xFF
        val fuHeader    = rtpFrame[payloadOffset + 1].toInt() and 0xFF
        val isStart = (fuHeader and 0x80) != 0
        val isEnd   = (fuHeader and 0x40) != 0

        val accumulator: ByteArrayOutputStream
        if (isStart) {
            if (fuaAccumulator != null) {
                Logger.w("RtpInterleaved: new FU-A start discards incomplete previous fragment")
            }
            // Reconstruct the original NAL unit header: NRI from FU indicator + type from FU header
            val reconstitutedHeader = ((fuIndicator and 0x60) or (fuHeader and 0x1F)).toByte()
            accumulator = ByteArrayOutputStream()
            accumulator.write(reconstitutedHeader.toInt())
        } else {
            if (fuaAccumulator == null) {
                Logger.w("RtpInterleaved: FU-A fragment without start bit — discarding")
                return null
            }
            accumulator = fuaAccumulator
        }

        // Append the fragment data (skip FU indicator byte + FU header byte)
        val fragmentStart = payloadOffset + 2
        if (fragmentStart < rtpFrame.size) {
            accumulator.write(rtpFrame, fragmentStart, rtpFrame.size - fragmentStart)
        }

        return if (isEnd) {
            onVideoNalUnit(accumulator.toByteArray(), ptsUs)
            null  // reassembly complete — clear accumulator
        } else {
            accumulator  // still accumulating
        }
    }

}

/**
 * VideoRtpProcessor — stateful RTP → H.264 depacketizer for one media session.
 *
 * WHY: [RtpInterleaved.readLoop] owns its entire input stream, which fits AirPlay
 * (the connection switches completely to RTP after RECORD). A caller that has to keep
 * answering RTSP keep-alives on the same socket while RTP frames flow reads the
 * `$`-framing itself and feeds complete RTP payloads here instead.
 *
 * Holds the per-session FU-A reassembly state, exactly like [RtpInterleaved.readLoop].
 *
 * Example:
 *   val processor = VideoRtpProcessor { nal, pts -> decoder.decodeNalUnit(nal, pts) }
 *   processor.processRtpFrame(rtpPayload)   // one interleaved video frame at a time
 */
internal class VideoRtpProcessor(
    private val onVideoNalUnit: (nalUnit: ByteArray, ptsUs: Long) -> Unit
) {
    private var fuaAccumulator: ByteArrayOutputStream? = null

    /** Feeds one complete RTP packet (12-byte header + payload) from the video channel. */
    fun processRtpFrame(rtpFrame: ByteArray) {
        fuaAccumulator = RtpInterleaved.processVideoRtpFrame(rtpFrame, fuaAccumulator, onVideoNalUnit)
    }
}
