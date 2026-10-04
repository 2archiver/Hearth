package com.phairplay.airplay.handshake

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MirrorCrypto — key derivation and helpers for the AirPlay mirroring video stream.
 *
 * The stream is AES-128-CTR encrypted. RPiPlay's per-packet `og`/`nextDecryptCount`
 * bookkeeping (lib/mirror_buffer.c) reduces to a single continuous CTR keystream over the
 * concatenated video payloads (every full-block chunk leaves the cipher block-aligned, so
 * `aes_ctr_start_fresh_block` is always a no-op) — so one [Cipher] with sequential
 * `update()` per payload is exactly equivalent.
 *
 * Reference: RPiPlay lib/mirror_buffer.c (mirror_buffer_init_aes / mirror_buffer_decrypt).
 */
object MirrorCrypto {

    /**
     * Builds the AES-128-CTR cipher that decrypts the mirror video stream.
     *
     * key = SHA512("AirPlayStreamKey"+id ‖ eaeskey)[:16],
     * iv  = SHA512("AirPlayStreamIV"+id ‖ eaeskey)[:16],
     * where eaeskey = SHA512(aesKey ‖ ecdhSecret)[:16] and id is the unsigned decimal
     * streamConnectionID.
     */
    fun streamCipher(aesKey: ByteArray, ecdhSecret: ByteArray, streamConnectionId: Long): Cipher {
        val eaeskey = sha512(aesKey + ecdhSecret).copyOf(16)
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val key = sha512("AirPlayStreamKey$id".toByteArray(Charsets.US_ASCII) + eaeskey).copyOf(16)
        val iv = sha512("AirPlayStreamIV$id".toByteArray(Charsets.US_ASCII) + eaeskey).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    /**
     * Converts AVCC (4-byte big-endian length-prefixed) NAL units — the format of a decrypted
     * mirror video payload — into Annex-B (00 00 00 01 start codes) that MediaCodec expects.
     *
     * Bounds `len` against `data.size - i` (avoiding 32-bit signed `Int` overflow when a corrupt
     * packet carries a large length prefix, per UxPlay commit `e405fe1`) and rejects truncated
     * payloads where the length-prefixed NAL units do not consume the full packet (`i != data.size`).
     */
    fun avccToAnnexB(data: ByteArray): ByteArray {
        if (data.size < 4) return ByteArray(0)
        val out = ByteArrayOutputStream(data.size + 16)
        var i = 0
        while (i <= data.size - 4) {
            val len = ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
            i += 4
            if (len <= 0 || len > data.size - i) return ByteArray(0)
            out.write(START_CODE)
            out.write(data, i, len)
            i += len
        }
        if (i != data.size) return ByteArray(0)
        return out.toByteArray()
    }

    val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * Audio stream AES key: `SHA-512(aesKey ‖ ecdhSecret)[:16]` (the IV is the raw SETUP `eiv`),
     * or raw `aesKey[:16]` for legacy 3rd-party senders (`hashKey = false`, matching UxPlay's
     * `hash_aeskey` flag in `raop_handlers.h`).
     */
    fun audioKey(aesKey: ByteArray, ecdhSecret: ByteArray, hashKey: Boolean = true): ByteArray =
        if (hashKey && ecdhSecret.isNotEmpty()) {
            sha512(aesKey + ecdhSecret).copyOf(16)
        } else {
            aesKey.copyOf(16)
        }

    private fun sha512(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(b)
}
