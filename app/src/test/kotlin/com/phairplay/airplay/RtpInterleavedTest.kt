package com.phairplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * RtpInterleavedTest — Unit tests for [RtpInterleaved].
 *
 * WHY: [RtpInterleaved] reads binary data from the network. It is critical that:
 * - Valid `$`-framed RTP frames are parsed correctly
 * - Non-`$` bytes are skipped without crashing (keep-alive RTSP text)
 * - Oversized frames are rejected (DoS prevention)
 * - EOF is handled cleanly
 * - RTP header is parsed correctly and presentation timestamp is computed
 *
 * HOW: We use [ByteArrayInputStream] to simulate the TCP InputStream,
 * avoiding real network sockets.
 */
class RtpInterleavedTest {

    // ─── Frame parsing ────────────────────────────────────────────────────────

    @Test
    fun `valid video RTP frame triggers onVideoNalUnit callback`() {
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 90000L)
        val stream = buildInterleavedStream(channel = 0, payload = rtp)

        var nalUnitReceived: ByteArray? = null
        var ptsReceived: Long = -1

        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { nal, pts ->
                nalUnitReceived = nal
                ptsReceived = pts
            },
            onStreamEnded = {}
        )

        assertNotNull("NAL unit should be received", nalUnitReceived)
        // 90000 RTP ticks @ 90kHz = 1 second = 1_000_000 µs
        assertEquals(1_000_000L, ptsReceived)
    }

    @Test
    fun `non-video channel (channel 1 = RTCP) does not trigger callback`() {
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 0L)
        val stream = buildInterleavedStream(channel = 1, payload = rtp)  // RTCP

        var callbackInvoked = false
        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { _, _ -> callbackInvoked = true },
            onStreamEnded = {}
        )

        assertTrue("RTCP channel should not trigger video callback", !callbackInvoked)
    }

    @Test
    fun `empty stream calls onStreamEnded without crash`() {
        val stream = ByteArrayInputStream(byteArrayOf())
        var streamEnded = false

        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { _, _ -> },
            onStreamEnded = { streamEnded = true }
        )

        assertTrue("onStreamEnded should be called on empty stream", streamEnded)
    }

    @Test
    fun `non-dollar bytes are skipped`() {
        // Prepend some non-'$' garbage bytes before a valid frame
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 90000L)
        val frame = buildInterleavedFrame(channel = 0, payload = rtp)
        val garbage = byteArrayOf(0x41, 0x42, 0x43)  // "ABC" — not '$'
        val data = garbage + frame

        var nalReceived = false
        RtpInterleaved.readLoop(
            inputStream = ByteArrayInputStream(data),
            onVideoNalUnit = { _, _ -> nalReceived = true },
            onStreamEnded = {}
        )

        assertTrue("NAL unit should be received after garbage bytes", nalReceived)
    }

    /**
     * The keep-alive regression: macOS/iOS send an RTSP `OPTIONS` on the same socket roughly
     * every 30 s mid-stream. Skipping one byte used to make the reader interpret "PTIONS…" as
     * a frame header, hit an invalid length, and end a healthy mirror — "video stops after a
     * few seconds". Two frames must survive a request in between, in order.
     */
    @Test
    fun `an embedded RTSP keep-alive does not end the stream`() {
        val first = buildInterleavedFrame(0, buildMinimalVideoRtpFrame(timestampRtp90k = 90_000L))
        val second = buildInterleavedFrame(0, buildMinimalVideoRtpFrame(timestampRtp90k = 180_000L))
        val keepAlive = "OPTIONS rtsp://192.168.1.42:7000 RTSP/1.0\r\nCSeq: 7\r\n\r\n".toByteArray()

        val timestamps = mutableListOf<Long>()
        var ended = false
        RtpInterleaved.readLoop(
            inputStream = ByteArrayInputStream(first + keepAlive + second),
            onVideoNalUnit = { _, pts -> timestamps.add(pts) },
            onStreamEnded = { ended = true }
        )

        assertEquals("Both frames either side of the keep-alive must be delivered", 2, timestamps.size)
        assertEquals(1_000_000L, timestamps[0])
        assertEquals(2_000_000L, timestamps[1])
        assertTrue("onStreamEnded still runs at EOF", ended)
    }

    @Test
    fun `a stream with no further marker after RTSP text ends cleanly`() {
        val frame = buildInterleavedFrame(0, buildMinimalVideoRtpFrame(timestampRtp90k = 90_000L))
        var count = 0
        var ended = false
        RtpInterleaved.readLoop(
            inputStream = ByteArrayInputStream(frame + "GET_PARAMETER rtsp/ RTSP/1.0\r\n".toByteArray()),
            onVideoNalUnit = { _, _ -> count++ },
            onStreamEnded = { ended = true }
        )
        assertEquals(1, count)
        assertTrue("EOF while re-syncing must still report the stream as ended", ended)
    }

    @Test
    fun `RTP timestamp zero produces zero presentation time`() {
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 0L)
        val stream = buildInterleavedStream(channel = 0, payload = rtp)

        var ptsReceived = -1L
        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { _, pts -> ptsReceived = pts },
            onStreamEnded = {}
        )

        assertEquals(0L, ptsReceived)
    }

    // ─── STAP-A aggregation (senders that ship SPS+PPS in one RTP packet) ────

    @Test
    fun `STAP-A packet delivers each contained NAL unit`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f)
        // 0xce / 0xe2 are above Byte.MAX_VALUE, so Kotlin needs the explicit conversion.
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x06, 0xe2.toByte())
        // STAP-A layout: indicator(0x78 = type 24) + [len(2B) NAL]+
        val stapPayload = byteArrayOf(0x78) +
            byteArrayOf(0x00, sps.size.toByte()) + sps +
            byteArrayOf(0x00, pps.size.toByte()) + pps
        val rtp = buildRtpFrame(timestampRtp90k = 90_000L, payload = stapPayload)
        val stream = buildInterleavedStream(channel = 0, payload = rtp)

        val received = mutableListOf<ByteArray>()
        var ptsReceived = -1L
        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { nal, pts -> received.add(nal); ptsReceived = pts },
            onStreamEnded = {}
        )

        assertEquals("STAP-A must deliver both contained NAL units", 2, received.size)
        assertArrayEquals("First NAL should be the SPS", sps, received[0])
        assertArrayEquals("Second NAL should be the PPS", pps, received[1])
        assertEquals("Both NAL units share the packet timestamp", 1_000_000L, ptsReceived)
    }

    @Test
    fun `malformed STAP-A length is dropped without crashing`() {
        // Declares a 40-byte NAL but only 2 payload bytes follow
        val payload = byteArrayOf(0x78, 0x00, 0x40, 0x11, 0x22)
        val rtp = buildRtpFrame(timestampRtp90k = 0L, payload = payload)
        val stream = buildInterleavedStream(channel = 0, payload = rtp)

        var callbackCount = 0
        RtpInterleaved.readLoop(
            inputStream = stream,
            onVideoNalUnit = { _, _ -> callbackCount++ },
            onStreamEnded = {}
        )

        assertEquals("Malformed STAP-A must deliver nothing", 0, callbackCount)
    }

    // ─── VideoRtpProcessor (incremental API used by the RTP media loop) ──────

    @Test
    fun `VideoRtpProcessor depackets without owning the input stream`() {
        val rtp = buildMinimalVideoRtpFrame(timestampRtp90k = 45_000L)
        val received = mutableListOf<Pair<ByteArray, Long>>()

        val processor = VideoRtpProcessor { nal, pts -> received.add(nal to pts) }
        processor.processRtpFrame(rtp)

        assertEquals(1, received.size)
        assertEquals(0x65.toByte(), received[0].first[0])
        assertEquals(500_000L, received[0].second)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Builds a minimal valid RTP frame (12-byte header + 4-byte dummy NAL payload).
     *
     * @param timestampRtp90k RTP timestamp in 90 kHz clock ticks.
     */
    private fun buildMinimalVideoRtpFrame(timestampRtp90k: Long): ByteArray {
        val payload = byteArrayOf(0x65, 0x00, 0x00, 0x00)  // fake IDR NAL unit
        return buildRtpFrame(timestampRtp90k, payload)
    }

    /**
     * Builds an RTP frame with an explicit payload (12-byte fixed header).
     *
     * @param timestampRtp90k RTP timestamp in 90 kHz clock ticks.
     * @param payload         RTP payload bytes (H.264 NAL or aggregation packet).
     */
    private fun buildRtpFrame(timestampRtp90k: Long, payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()  // V=2, P=0, X=0, CC=0
        header[1] = 0x60.toByte()  // M=0, PT=96
        header[2] = 0x00           // Seq high
        header[3] = 0x01           // Seq low = 1
        // Timestamp (big-endian 32-bit)
        header[4] = ((timestampRtp90k shr 24) and 0xFF).toByte()
        header[5] = ((timestampRtp90k shr 16) and 0xFF).toByte()
        header[6] = ((timestampRtp90k shr  8) and 0xFF).toByte()
        header[7] = ( timestampRtp90k         and 0xFF).toByte()
        // SSRC = 0 (bytes 8–11 already zero)
        return header + payload
    }

    /** Wraps [payload] in an interleaved `$` frame with the given [channel]. */
    private fun buildInterleavedFrame(channel: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        return byteArrayOf(
            0x24,                    // '$'
            channel.toByte(),
            (len shr 8).toByte(),    // length high byte
            (len and 0xFF).toByte()  // length low byte
        ) + payload
    }

    /** Creates a [ByteArrayInputStream] containing a single interleaved frame. */
    private fun buildInterleavedStream(channel: Int, payload: ByteArray): ByteArrayInputStream =
        ByteArrayInputStream(buildInterleavedFrame(channel, payload))
}
