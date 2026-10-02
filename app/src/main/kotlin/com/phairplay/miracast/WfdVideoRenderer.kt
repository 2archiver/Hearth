package com.phairplay.miracast

import android.view.Surface
import com.phairplay.airplay.VideoDecoder
import com.phairplay.airplay.VideoRtpProcessor
import com.phairplay.util.Logger

/**
 * WfdVideoRenderer — H.264 decode pipeline for one Miracast (WFD) session.
 *
 * WHY: Before this class existed, a Miracast sender could complete the whole WFD
 * RTSP handshake (PLAY) and still see a blank TV screen: the receiver negotiated
 * the session but never decoded a single frame.
 *
 * HOW: The WFD media loop feeds complete RTP packets from the interleaved video
 * channel into a [VideoRtpProcessor] (FU-A / STAP-A / single-NAL depacketization).
 * Reassembled NAL units arrive here, where we:
 *   1. Cache SPS (type 7) / PPS (type 8) — WFD has no SDP, so the decoder
 *      configuration must be learned from the bitstream itself.
 *   2. Lazily create the hardware [VideoDecoder] once SPS+PPS are known AND the
 *      activity's streaming [Surface] is ready (the overlay appears asynchronously
 *      when the session reports CONNECTED, so early frames may be dropped).
 *   3. Feed every NAL unit to the decoder, which renders directly to the Surface.
 *
 * Frames that arrive before (1) and (2) complete are intentionally dropped — a
 * Miracast sender repeats SPS/PPS with every keyframe, so the pipeline self-heals
 * within a few hundred milliseconds.
 *
 * Threading: [onRtpVideoFrame] runs on the WFD RTSP socket thread; [release] may
 * be called from the service thread when the receiver stops — both entry points
 * into decoder state are synchronized.
 */
internal class WfdVideoRenderer(
    private val surfaceProvider: () -> Surface?
) {

    // Last seen SPS/PPS NAL units — required before the decoder can be configured
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    // Hardware decoder — null until SPS + PPS + Surface are all available
    private var decoder: VideoDecoder? = null

    // RTP depacketizer state (FU-A reassembly) for this session
    private val rtpProcessor = VideoRtpProcessor { nalUnit, ptsUs -> onNalUnit(nalUnit, ptsUs) }

    /** Feeds one complete RTP packet from the interleaved WFD video channel. */
    fun onRtpVideoFrame(rtpFrame: ByteArray) {
        rtpProcessor.processRtpFrame(rtpFrame)
    }

    /** Handles one reassembled H.264 NAL unit. Safe to call before the decoder exists. */
    @Synchronized
    fun onNalUnit(nalUnit: ByteArray, presentationTimeUs: Long) {
        if (nalUnit.isEmpty()) return

        // Learn the bitstream configuration from the stream itself (WFD has no SDP).
        when (nalUnit[0].toInt() and 0x1F) {
            NAL_TYPE_SPS -> sps = nalUnit
            NAL_TYPE_PPS -> pps = nalUnit
        }

        var active = decoder
        if (active != null && !active.isHealthy) {
            Logger.w("WFD: H.264 decoder entered error state — recreating on next SPS/PPS")
            active.release()
            decoder = null
            active = null
        }
        if (active == null) {
            active = createDecoder() ?: return  // still waiting for SPS/PPS/Surface — drop frame
        }
        active.decodeNalUnit(nalUnit, presentationTimeUs)
    }

    /**
     * Creates the hardware decoder once SPS, PPS, and an output Surface are all
     * available. Returns null (and drops the frame) while any of them is missing.
     */
    private fun createDecoder(): VideoDecoder? {
        val spsBytes = sps ?: return null
        val ppsBytes = pps ?: return null
        // Surface appears asynchronously when MainActivity shows the streaming
        // overlay — retry cheaply on subsequent NAL units until it exists.
        val surface = surfaceProvider() ?: return null

        val candidate = VideoDecoder(surface)
        return try {
            // Width/height are hints only — VideoDecoder parses the true size from
            // the SPS NAL unit itself and reports it for aspect-fit rendering.
            candidate.initialize(spsBytes, ppsBytes, FALLBACK_WIDTH, FALLBACK_HEIGHT)
            decoder = candidate
            Logger.i("WFD: H.264 decoder initialized (SPS + PPS received)")
            candidate
        } catch (e: Exception) {
            Logger.e("WFD: failed to initialize H.264 decoder", e)
            candidate.release()
            null
        }
    }

    /** Releases the decoder and cached configuration. Idempotent. */
    @Synchronized
    fun release() {
        decoder?.release()
        decoder = null
        sps = null
        pps = null
    }

    companion object {
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8

        // Provisional hints; the real resolution is parsed from the SPS NAL unit.
        private const val FALLBACK_WIDTH = 1920
        private const val FALLBACK_HEIGHT = 1080
    }
}
