package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** 1.8.2 regressions: header case, connection retirement and failed-SETUP cleanup. */
class RtspRobustnessTest {

    private fun reader() = RtspRequestReader(maxMessageBytes = 64 * 1024, maxPhotoBytes = 1024 * 1024)

    @Test
    fun `lower-case content-length still reads the body and keeps the stream in sync`() {
        val wire = "POST /play RTSP/1.0\r\ncseq: 3\r\ncontent-length: 5\r\n\r\nhello" +
            "OPTIONS * RTSP/1.0\r\nCSeq: 4\r\n\r\n"
        val input = ByteArrayInputStream(wire.toByteArray())
        val first = reader().read(input)
        assertNotNull(first)
        assertEquals("hello", first!!.body)
        assertEquals("3", first.header("CSeq"))
        val second = reader().read(input)
        assertEquals("OPTIONS", second!!.method)
        assertEquals("4", second.header("cseq"))
    }

    @Test
    fun `header lookup is case-insensitive for maps built elsewhere`() {
        val request = RtspRequest("GET", "/info", mapOf("user-agent" to "AirPlay/800"), "")
        assertEquals("AirPlay/800", request.header("User-Agent"))
    }

    private class FakeConnection(override val holdsSession: Boolean) : RtspConnection {
        override fun serve() = Unit
        override fun close() = Unit
    }

    @Test
    fun `connection cap retires the oldest idle connection, not the session`() {
        val control = FakeConnection(holdsSession = true)
        val probe1 = FakeConnection(holdsSession = false)
        val probe2 = FakeConnection(holdsSession = false)
        assertSame(probe1, retireCandidate(listOf(control, probe1, probe2)))
        assertSame(control, retireCandidate(listOf(control)))
    }

    private fun setup(body: Map<String, Any?>) = RtspRequest(
        method = "SETUP", uri = "rtsp://tv/1", headers = mapOf("CSeq" to "5"), body = "",
        bodyBytes = PlistCodec.encode(body)
    )

    @Test
    fun `failed mirror SETUP does not let this connection end the session on close`() {
        var stopped = false
        val handler = TestableRtspHandler(onStreamingStarted = {}, onStreamingStopped = { stopped = true })
        // No fp-setup ran, so decrypting ekey throws → 400.
        val response = handler.routeRequest(setup(mapOf("ekey" to ByteArray(72))))
        assertEquals(400, response.statusCode)
        assertFalse(handler.holdsSession)
        handler.close()
        assertFalse("a failed SETUP must not tear down another connection's session", stopped)
    }

    @Test
    fun `successful mirror SETUP still ends its session when the connection closes`() {
        var stopped = false
        val handler = TestableRtspHandler(onStreamingStarted = {}, onStreamingStopped = { stopped = true })
        val response = handler.routeRequest(setup(mapOf("name" to "iPhone")))
        assertEquals(200, response.statusCode)
        assertTrue(handler.holdsSession)
        handler.close()
        assertTrue(stopped)
    }
}
