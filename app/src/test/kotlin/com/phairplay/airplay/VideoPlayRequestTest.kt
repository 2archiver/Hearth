package com.phairplay.airplay

import org.junit.Assert.*
import org.junit.Test

import com.phairplay.airplay.handshake.PlistCodec

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

    // ─── /play body decoding (binary plist, XML plist, legacy text) ───────────

    @Test fun `binary plist bodies decode to a direct URL request`() {
        val body = PlistCodec.encode(mapOf("Content-Location" to url, "Start-Position-Seconds" to 12.5))

        val parsed = VideoPlayRequest.decodeBody(body, "application/x-apple-binary-plist")

        assertTrue("expected success, got $parsed", parsed is BodyParse.Success)
        val success = parsed as BodyParse.Success
        assertEquals(url, success.request.url)
        assertEquals(12.5, success.request.start, 0.0)
        assertTrue(success.request.seconds)
        assertEquals(BodyEncoding.BINARY_PLIST, success.encoding)
        assertTrue("field names may be reported", success.fieldNames.contains("Content-Location"))
    }

    @Test fun `XML plist bodies decode as well`() {
        val body = PlistCodec.encodeXml(mapOf("Content-Location" to url, "Start-Position-Seconds" to 7.25))

        val parsed = VideoPlayRequest.decodeBody(body, "text/x-apple-plist+xml")

        assertTrue("expected success, got $parsed", parsed is BodyParse.Success)
        val success = parsed as BodyParse.Success
        assertEquals(BodyEncoding.XML_PLIST, success.encoding)
        assertEquals(7.25, success.request.start, 0.0)
    }

    @Test fun `legacy text bodies keep fractional semantics`() {
        val body = "Content-Location: $url\r\nStart-Position: 0.5\r\n".toByteArray(Charsets.UTF_8)

        val parsed = VideoPlayRequest.decodeBody(body, "text/parameters")

        assertTrue("expected success, got $parsed", parsed is BodyParse.Success)
        val success = parsed as BodyParse.Success
        assertEquals(BodyEncoding.TEXT, success.encoding)
        assertFalse(success.request.seconds)
        assertEquals(0.5, success.request.start, 0.0)
    }

    @Test fun `internal HLS locations are classified as sender-mediated, never as a direct URL`() {
        val body = PlistCodec.encode(
            mapOf(
                "Content-Location" to "mlhls://localhost/abcd/master.m3u8",
                "Start-Position-Seconds" to 3.0,
            )
        )

        val parsed = VideoPlayRequest.decodeBody(body, "application/x-apple-binary-plist")

        // This location is only resolvable by the *sender*, so it must never reach the player as if
        // it were a URL, and it must not be reported as an unknown scheme either: the handler needs
        // to see it as the sender-mediated case it is (and answer honestly if no bridge can serve it).
        assertTrue("expected sender-mediated, got $parsed", parsed is BodyParse.SenderMediated)
        val senderMediated = parsed as BodyParse.SenderMediated
        assertEquals("mlhls", senderMediated.scheme)
        assertEquals("mlhls://localhost/abcd/master.m3u8", senderMediated.request.url)
        assertEquals(3.0, senderMediated.request.start, 0.0)
        assertTrue(senderMediated.request.seconds)
    }

    @Test fun `oversized and empty bodies are rejected without parsing`() {
        val oversized = VideoPlayRequest.decodeBody(ByteArray(VideoPlayRequest.MAX_BODY_BYTES + 1), null)
        assertTrue("expected invalid, got $oversized", oversized is BodyParse.Invalid)
        val tooBig = oversized as BodyParse.Invalid
        assertTrue(tooBig.reason.contains("exceeds"))

        val empty = VideoPlayRequest.decodeBody(ByteArray(0), null)
        assertTrue("expected invalid, got $empty", empty is BodyParse.Invalid)
        val missing = empty as BodyParse.Invalid
        assertEquals(BodyEncoding.EMPTY, missing.encoding)
    }
}
