package com.phairplay.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoHandlerTest {

    @Test
    fun `valid JPEG payload is accepted`() {
        val result = PhotoHandler.validatePhoto(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            "image/jpeg"
        )

        assertTrue(result is PhotoValidation.Valid)
    }

    @Test
    fun `valid PNG payload is accepted`() {
        val result = PhotoHandler.validatePhoto(
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A
            ),
            "image/png"
        )

        assertTrue(result is PhotoValidation.Valid)
    }

    @Test
    fun `unsupported payload is rejected`() {
        val result = PhotoHandler.validatePhoto("not an image".toByteArray(), "image/jpeg")

        assertTrue(result is PhotoValidation.Invalid)
    }

    @Test
    fun `content type mismatch is rejected`() {
        val result = PhotoHandler.validatePhoto(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            "image/png"
        )

        assertTrue(result is PhotoValidation.Invalid)
    }

    @Test
    fun `cacheOnly and displayCached actions are parsed without treating cached display as a new upload`() {
        assertTrue(PhotoHandler.parseAssetAction("cacheOnly") == PhotoAssetAction.CACHE_ONLY)
        assertTrue(PhotoHandler.parseAssetAction("displayCached") == PhotoAssetAction.DISPLAY_CACHED)
        assertTrue(PhotoHandler.parseAssetAction(null) == PhotoAssetAction.DISPLAY)
        assertTrue(PhotoHandler.parseAssetAction("unexpected") == null)
    }

    @Test
    fun `opaque asset keys allow visible tokens but reject CRLF and unbounded values`() {
        assertTrue(PhotoHandler.isValidAssetKey("a53e50c4-photos-1"))
        assertTrue(PhotoHandler.isValidAssetKey("asset+/=="))
        assertFalse(PhotoHandler.isValidAssetKey("asset\r\nX-Injected: yes"))
        assertFalse(PhotoHandler.isValidAssetKey("x".repeat(PhotoHandler.MAX_ASSET_KEY_CHARS + 1)))
    }
}
