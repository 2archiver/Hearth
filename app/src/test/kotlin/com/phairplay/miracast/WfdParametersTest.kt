package com.phairplay.miracast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WfdParametersTest — the WFD capability negotiation (RTSP M3–M5).
 *
 * WHY: the old receiver answered every `GET_PARAMETER` with one fixed blob and ignored what the
 * source asked for. A source asks for a specific list and validates the reply against it, so a
 * missing `wfd_uibc_capability` (which Windows always asks for) is enough for it to drop the
 * whole session before a single frame is sent. These tests pin the reply shape.
 */
class WfdParametersTest {

    /** The parameter list Windows 10/11 sends in its M3 request (abridged). */
    private val windowsRequest = listOf(
        "wfd_client_rtp_ports",
        "wfd_audio_codecs",
        "wfd_video_formats",
        "wfd_3d_video_formats",
        "wfd_coupled_sink",
        "wfd_display_edid",
        "wfd_connector_type",
        "wfd_uibc_capability",
        "wfd_standby_resume_capability",
        "wfd_content_protection",
        "wfd_idr_request_capability"
    ).joinToString("\r\n", postfix = "\r\n")

    @Test
    fun `a bare parameter name is a request for that parameter, not an empty value`() {
        val parsed = WfdParameters.parse("wfd_video_formats\r\nwfd_audio_codecs\r\n")
        assertEquals(listOf("wfd_video_formats" to "", "wfd_audio_codecs" to ""), parsed)
    }

    @Test
    fun `a name-value pair parses into name and value`() {
        val parsed = WfdParameters.parse("wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0\r\n")
        assertEquals(listOf("wfd_presentation_URL" to "rtsp://192.168.49.1/wfd1.0"), parsed)
    }

    @Test
    fun `an empty request gets every parameter we publish`() {
        val body = WfdParameters.renderResponse("")
        assertTrue(body.contains("wfd_video_formats"))
        assertTrue(body.contains("wfd_audio_codecs"))
        assertTrue(body.contains("wfd_client_rtp_ports"))
        assertTrue(body.contains("wfd_uibc_capability"))
    }

    @Test
    fun `the reply answers exactly what Windows asks for, and nothing it did not`() {
        val body = WfdParameters.renderResponse(windowsRequest)
        val answered = body.lineSequence().filter { it.isNotBlank() }
            .map { it.substringBefore(':').trim() }
            .toList()

        for (name in listOf("wfd_uibc_capability", "wfd_standby_resume_capability",
                "wfd_3d_video_formats", "wfd_idr_request_capability", "wfd_content_protection")) {
            assertTrue("M3 reply is missing $name — Windows drops the session", name in answered)
        }
        // Nothing extra: the reply must match the request list, not our full table.
        assertFalse("wfd_route" in answered)
        assertEquals(windowsRequest.lineSequence().filter { it.isNotBlank() }.count(), answered.size)
    }

    @Test
    fun `unknown parameters answer none rather than being omitted`() {
        val body = WfdParameters.renderResponse("wfd_something_vendor_specific\r\n")
        assertEquals("wfd_something_vendor_specific: none\r\n", body)
    }

    @Test
    fun `video formats report a defined H264 profile and level`() {
        val formats = WfdParameters.SUPPORTED["wfd_video_formats"]!!
        val fields = formats.split(" ")
        assertEquals(13, fields.size)                 // the WFD video-formats field layout
        val profile = fields[2]
        val level = fields[3]
        // 0 = Constrained High, 1 = Constrained Baseline are the only defined profiles;
        // levels are 0..4. The old value was "02"/"10", neither of which is defined.
        assertTrue("profile '$profile' is not defined by WFD", profile in setOf("0", "00", "1", "01"))
        assertTrue("level '$level' is not defined by WFD", level.toInt(16) in 0..4)
    }

    @Test
    fun `we advertise the mandatory LPCM format`() {
        assertTrue(WfdParameters.SUPPORTED["wfd_audio_codecs"]!!.startsWith("LPCM 00000003"))
    }

    @Test
    fun `SET_PARAMETER only accepts the parameters the source drives`() {
        val values = LinkedHashMap<String, String>()
        val applied = WfdParameters.applySetParameter(
            "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0\r\n" +
                "wfd_trigger_method: SETUP\r\n" +
                "wfd_audio_codecs: AAC 00000007 00\r\n" +
                "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n",
            values
        )

        // Source-driven values are remembered…
        assertEquals(2, applied.size)
        assertEquals("rtsp://192.168.49.1/wfd1.0", values["wfd_presentation_URL"])
        assertEquals("SETUP", values["wfd_trigger_method"])

        // …but we never echo back a codec or transport we do not implement. Promising one
        // would have the source stream in a format this receiver then drops.
        assertEquals(null, values["wfd_audio_codecs"])
        assertEquals(null, values["wfd_client_rtp_ports"])
    }

    @Test
    fun `a remembered source value is echoed in the next GET_PARAMETER reply`() {
        val values = LinkedHashMap<String, String>()
        WfdParameters.applySetParameter(
            "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0\r\n", values
        )
        val body = WfdParameters.renderResponse("wfd_presentation_URL\r\n", values)
        assertEquals("wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0\r\n", body)
    }

    @Test
    fun `OPTIONS lists every method the receiver implements`() {
        val publicMethods = WfdParameters.PUBLIC_METHODS
        for (method in listOf("SETUP", "PLAY", "PAUSE", "TEARDOWN", "GET_PARAMETER", "SET_PARAMETER")) {
            assertTrue("Public header is missing $method", method in publicMethods)
        }
        assertTrue(publicMethods.startsWith("org.wfa.wfd1.0"))
    }
}
