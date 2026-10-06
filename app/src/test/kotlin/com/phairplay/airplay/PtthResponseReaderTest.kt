package com.phairplay.airplay

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PtthResponseReaderTest {

    @Test
    fun `fragmented HTTP and EVENT replies are framed and consumed without their bodies`() {
        val wire = (
            "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello" +
                "EVENT/1.0 204 No Content\r\nContent-Length: 0\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val input = FragmentingInputStream(wire, maxChunk = 2)
        val reader = PtthResponseReader()

        val first = reader.read(input) as PtthResponseReader.ReadOutcome.Response
        val second = reader.read(input) as PtthResponseReader.ReadOutcome.Response

        assertEquals("HTTP/1.1", first.protocol)
        assertEquals(200, first.statusCode)
        assertEquals(5, first.bodyBytes)
        assertEquals("EVENT/1.0", second.protocol)
        assertEquals(204, second.statusCode)
        assertEquals(0, second.bodyBytes)
    }

    @Test
    fun `chunked PTTH body and trailers do not consume the following response`() {
        val wire = (
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "3;part=one\r\nabc\r\n2\r\nde\r\n0\r\nX-Test: ignored\r\n\r\n" +
                "EVENT/1.0 200 OK\r\nContent-Length: 0\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val input = FragmentingInputStream(wire, maxChunk = 1)
        val reader = PtthResponseReader()

        val first = reader.read(input) as PtthResponseReader.ReadOutcome.Response
        val second = reader.read(input) as PtthResponseReader.ReadOutcome.Response

        assertEquals(5, first.bodyBytes)
        assertEquals("EVENT/1.0", second.protocol)
        assertEquals(200, second.statusCode)
    }

    @Test
    fun `oversized or ambiguous response framing is rejected before reading a body`() {
        val oversized = PtthResponseReader(maxBodyBytes = 4).read(
            ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n".toByteArray())
        ) as PtthResponseReader.ReadOutcome.End
        val ambiguous = PtthResponseReader().read(
            ByteArrayInputStream(
                "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray()
            )
        ) as PtthResponseReader.ReadOutcome.End

        assertTrue(oversized.reason.contains("exceeds limit"))
        assertTrue(ambiguous.reason.contains("ambiguous"))
    }

    @Test
    fun `serve keeps the upgraded PTTH socket alive and never parses responses as RTSP`() {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val peer = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        val accepted = listener.accept()
        listener.close()
        peer.soTimeout = 3_000

        val firstFrame = "POST /event HTTP/1.1\r\nContent-Length: 0\r\n\r\n".toByteArray()
        val secondFrame = "POST /event HTTP/1.1\r\nX-Test: second\r\nContent-Length: 0\r\n\r\n".toByteArray()
        val firstWritten = CountDownLatch(1)
        val released = CountDownLatch(1)
        var reverseWriter: ((ByteArray) -> Boolean)? = null
        val host = object : SenderMediatedHlsHost {
            override fun registerReverseChannel(
                connectionId: String,
                senderSessionId: String?,
                writer: (ByteArray) -> Boolean,
                revoke: (String) -> Unit,
            ): Boolean {
                reverseWriter = writer
                // Attempt a write immediately; the writer must wait for the 101 to be fully sent.
                thread(name = "ptth-test-early-write") {
                    assertTrue(writer(firstFrame))
                    firstWritten.countDown()
                }
                return true
            }

            override fun releaseReverseChannel(connectionId: String) {
                released.countDown()
            }

            override fun startSenderMediatedPlay(request: SenderMediatedPlayRequest) = SenderMediatedPlayResult.ACCEPTED
            override fun deliverAction(senderSessionId: String?, body: ByteArray) = SenderMediatedActionResult(200, "test")
        }
        val handler = RtspHandler(
            context = mockk<Context>(relaxed = true),
            videoSurfaceProvider = { null },
            onStreamingStarted = {},
            onStreamingStopped = {},
            socket = accepted,
            traceConnectionId = "C-PTTH-TEST",
            hlsHost = host,
        )
        val serving = thread(name = "ptth-test-server") { handler.serve() }

        try {
            peer.getOutputStream().write(
                "POST /reverse HTTP/1.1\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
            )
            peer.getOutputStream().flush()
            val upgradeResponse = readHeaderBlock(peer)
            assertTrue(upgradeResponse.startsWith("HTTP/1.1 101 Switching Protocols"))
            assertTrue(firstWritten.await(2, TimeUnit.SECONDS))
            assertEquals(firstFrame.toList(), readExact(peer, firstFrame.size).toList())

            // The response body is deliberately split; a response parser must consume it and return
            // to the PTTH loop rather than route `HTTP/1.1` through RtspHandler.routeRequest.
            val response = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello" +
                "EVENT/1.0 204 No Content\r\nContent-Length: 0\r\n\r\n"
            val bytes = response.toByteArray(Charsets.US_ASCII)
            peer.getOutputStream().write(bytes, 0, 9)
            peer.getOutputStream().flush()
            peer.getOutputStream().write(bytes, 9, bytes.size - 9)
            peer.getOutputStream().flush()

            assertTrue(reverseWriter!!.invoke(secondFrame))
            assertEquals(secondFrame.toList(), readExact(peer, secondFrame.size).toList())
        } finally {
            peer.close()
            serving.join(2_000)
            handler.close()
        }
        assertFalse("the RTSP server thread must exit after peer EOF", serving.isAlive)
        assertTrue("the upgraded offer is released with its socket", released.await(2, TimeUnit.SECONDS))
    }

    private fun readHeaderBlock(socket: Socket): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < terminator.size) {
            val value = socket.getInputStream().read()
            check(value >= 0) { "peer closed before response headers completed" }
            out.write(value)
            matched = if (value.toByte() == terminator[matched]) matched + 1
            else if (value.toByte() == terminator[0]) 1 else 0
        }
        return out.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun readExact(socket: Socket, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = socket.getInputStream().read(result, offset, count - offset)
            check(read >= 0) { "peer closed before the complete PTTH frame arrived" }
            offset += read
        }
        return result
    }

    private class FragmentingInputStream(bytes: ByteArray, private val maxChunk: Int) : ByteArrayInputStream(bytes) {
        override fun read(target: ByteArray, offset: Int, length: Int): Int =
            super.read(target, offset, minOf(length, maxChunk))
    }
}
