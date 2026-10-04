package com.phairplay.airplay.handshake

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the AAC-ELD AudioSpecificConfig builder, which replaced a hardcoded 44.1 kHz/stereo
 * config. The 44.1 kHz/stereo case must reproduce the previously-hardcoded canonical bytes so the
 * working macOS mirroring path is unchanged.
 */
class AudioStreamServerTest {

    @Test
    fun `AAC-ELD ASC for 44_100 Hz stereo equals the canonical F8 E8 50 00`() {
        assertArrayEquals(
            byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50.toByte(), 0x00.toByte()),
            AudioStreamServer.buildAacEldAsc(44100, 2)
        )
    }

    @Test
    fun `AAC-LC ASC for 44_100 Hz stereo is 12 10`() {
        // AOT=2(00010) freqIdx=4(0100) chanCfg=2(0010) GASC=000 → 0001 0010 0001 0000 = 12 10
        assertArrayEquals(
            byteArrayOf(0x12.toByte(), 0x10.toByte()),
            AudioStreamServer.buildAacLcAsc(44100, 2)
        )
    }

    @Test
    fun `AAC-ELD ASC encodes 48 kHz mono (freq index 3, channel config 1)`() {
        // AOT-escape(11111 000111) freqIdx=0011 chanCfg=0001 frameLenFlag=1 tail=0... → F8 E6 30 00
        assertArrayEquals(
            byteArrayOf(0xF8.toByte(), 0xE6.toByte(), 0x30.toByte(), 0x00.toByte()),
            AudioStreamServer.buildAacEldAsc(48000, 1)
        )
    }

    @Test
    fun `isNoDataRtpPayload detects empty, AAC-ELD no_data_marker, and 44-byte ALAC format packets`() {
        val emptyRtp = ByteArray(12)
        assertTrue(AudioStreamServer.isNoDataRtpPayload(emptyRtp, 12, 0, AudioStreamServer.CT_AAC_ELD))

        // 16-byte RTP packet with 4-byte AAC-ELD no_data_marker (0x00, 0x68, 0x34, 0x00)
        val noDataMarker = ByteArray(16).apply {
            this[12] = 0x00
            this[13] = 0x68
            this[14] = 0x34
            this[15] = 0x00
        }
        assertTrue(AudioStreamServer.isNoDataRtpPayload(noDataMarker, 12, 4, AudioStreamServer.CT_AAC_ELD))

        // 44-byte ALAC RTP packet (12-byte header + 32-byte format-only payload)
        val alacFormatPkt = ByteArray(44)
        assertTrue(AudioStreamServer.isNoDataRtpPayload(alacFormatPkt, 12, 32, AudioStreamServer.CT_ALAC))
        assertFalse(AudioStreamServer.isNoDataRtpPayload(alacFormatPkt, 12, 32, AudioStreamServer.CT_AAC_ELD))

        // Normal audio payload is not filtered
        val validAudio = ByteArray(64) { 0x55 }
        assertFalse(AudioStreamServer.isNoDataRtpPayload(validAudio, 12, 52, AudioStreamServer.CT_AAC_ELD))
    }

    @Test
    fun `MirrorCrypto avccToAnnexB converts valid NAL units and rejects overflow or truncated payloads`() {
        // Two valid NAL units: [len=3: 65 01 02] [len=2: 06 03]
        val avcc = byteArrayOf(
            0, 0, 0, 3, 0x65, 0x01, 0x02,
            0, 0, 0, 2, 0x06, 0x03
        )
        val expected = byteArrayOf(
            0, 0, 0, 1, 0x65, 0x01, 0x02,
            0, 0, 0, 1, 0x06, 0x03
        )
        assertArrayEquals(expected, MirrorCrypto.avccToAnnexB(avcc))

        // Large length prefix (0x7FFFFFFF) that would overflow signed 32-bit `i + len` (UxPlay commit e405fe1)
        val overflowAvcc = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x65, 0x01)
        assertEquals(0, MirrorCrypto.avccToAnnexB(overflowAvcc).size)

        // Truncated second NAL unit: must reject the whole frame rather than returning partial data
        val truncatedAvcc = byteArrayOf(
            0, 0, 0, 2, 0x65, 0x01,
            0, 0, 0, 10, 0x06
        )
        assertEquals(0, MirrorCrypto.avccToAnnexB(truncatedAvcc).size)
    }

    @Test
    fun `MirrorCrypto audioKey supports hashed and unhashed legacy keys`() {
        val rawKey = ByteArray(16) { (it + 1).toByte() }
        val secret = ByteArray(32) { (it + 10).toByte() }
        assertArrayEquals(rawKey, MirrorCrypto.audioKey(rawKey, secret, hashKey = false))
        assertArrayEquals(rawKey, MirrorCrypto.audioKey(rawKey, ByteArray(0), hashKey = true))
        assertFalse(rawKey.contentEquals(MirrorCrypto.audioKey(rawKey, secret, hashKey = true)))
    }

    @Test
    fun `MirrorStreamServer parseConfig parses avcC and rejects hvc1 or truncated payloads`() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x20)
        val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
        val avcC = byteArrayOf(
            0x01, 0x64, 0x00, 0x20, 0xFF.toByte(), 0xE1.toByte(),
            0x00, sps.size.toByte(), *sps,
            0x01,
            0x00, pps.size.toByte(), *pps
        )
        val cfg = MirrorStreamServer.parseConfig(avcC)
        assertNotNull(cfg)
        assertArrayEquals(sps, cfg!!.sps)
        assertArrayEquals(pps, cfg.pps)

        // hvc1 (HEVC) payload must be rejected cleanly
        val hvc1 = ByteArray(32).apply {
            this[4] = 'h'.code.toByte()
            this[5] = 'v'.code.toByte()
            this[6] = 'c'.code.toByte()
            this[7] = '1'.code.toByte()
        }
        assertNull(MirrorStreamServer.parseConfig(hvc1))

        // Truncated avcC payload must be rejected cleanly
        assertNull(MirrorStreamServer.parseConfig(avcC.copyOf(10)))
    }
}
