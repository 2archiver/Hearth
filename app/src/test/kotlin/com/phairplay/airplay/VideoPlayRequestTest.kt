package com.phairplay.airplay

import org.junit.Assert.*
import org.junit.Test

class VideoPlayRequestTest {
    private val url = "https://cdn.example.test/video.m3u8?token=a%2Bb&quality=1080"

    @Test fun `absolute seconds take precedence over a fractional offset`() {
        val play = VideoPlayRequest.parse(mapOf("Content-Location" to url,
            "Start-Position-Seconds" to 42.5, "Start-Position" to 0.25))!!
        assertTrue(play.seconds)
        assertEquals(42.5, play.start, 0.0)
        assertEquals(url, play.url) // Preserve signed query strings exactly.
    }

    @Test fun `legacy text parameters retain fractional semantics`() {
        val play = VideoPlayRequest.parse(mapOf("content-location" to url, "start-position" to "0.25"))!!
        assertFalse(play.seconds)
        assertEquals(0.25, play.start, 0.0)
    }

    @Test fun `seconds in text body remain absolute even below one second`() {
        val play = VideoPlayRequest.parse(mapOf("Content-Location" to url, "Start-Position-Seconds" to "0.5"))!!
        assertTrue(play.seconds)
        assertEquals(0.5, play.start, 0.0)
    }

    @Test fun `direct URL without resume starts at zero`() {
        assertEquals(0.0, VideoPlayRequest.parse(mapOf("Content-Location" to url))!!.start, 0.0)
    }

    @Test fun `malformed offsets and unsupported protocols are rejected`() {
        for (offset in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, "bad")) {
            assertNull(VideoPlayRequest.parse(mapOf("Content-Location" to url, "Start-Position-Seconds" to offset)))
        }
        assertNull(VideoPlayRequest.parse(mapOf("Content-Location" to url, "Start-Position" to 42.5)))
        for (location in listOf("", "file:///etc/passwd", "mlhls://localhost/master.m3u8", "https://", "https://bad host/a")) {
            assertNull(VideoPlayRequest.parse(mapOf("Content-Location" to location)))
        }
    }
}
