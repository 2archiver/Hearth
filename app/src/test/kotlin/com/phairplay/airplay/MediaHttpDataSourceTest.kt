package com.phairplay.airplay

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Real socket/Media3 regressions: the production HTTP wrapper runs under Android framework stubs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@androidx.media3.common.util.UnstableApi
class MediaHttpDataSourceTest {
    @Before fun permitOnlyTheTestLoopbackServer() {
        // Production deliberately blocks loopback sender URLs. Replace only that policy here,
        // so a deterministic local fixture can exercise the actual HTTP implementation.
        mockkObject(LocalMediaAddressPolicy)
        every { LocalMediaAddressPolicy.isLoopbackHost(any()) } returns false
        every { LocalMediaAddressPolicy.allowsCleartextHost(any()) } returns true
    }
    @After fun restoreAddressPolicy() = unmockkObject(LocalMediaAddressPolicy)

    @Test fun shortFinalRangeReturnsAvailableBytes() = serve(
        "206 Partial Content", "Content-Range: bytes 900-902/903\r\nContent-Length: 3\r\n", "abc",
    ) { url ->
        val source = HearthRedirectGuardedHttpDataSource()
        try {
            assertEquals(3L, source.open(spec(url, 900L, 1024L)))
            val bytes = ByteArray(10)
            assertEquals(3, source.read(bytes, 0, bytes.size))
            assertEquals("abc", bytes.decodeToString(0, 3))
            assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, bytes.size))
        } finally { source.close() }
    }

    @Test fun incorrectSeekResponseFailsBeforeDecoding() = serve(
        "206 Partial Content", "Content-Range: bytes 0-2/903\r\nContent-Length: 3\r\n", "abc",
    ) { url ->
        val source = HearthRedirectGuardedHttpDataSource()
        try {
            try { source.open(spec(url, 900L, 3L)); fail("Wrong starting byte was accepted") }
            catch (_: IOException) { /* Expected: no incorrect bytes reach the decoder. */ }
        } finally { source.close() }
    }

    @Test fun truncatedKnownLengthFailsInsteadOfCompletingVideo() = serve(
        "200 OK", "Content-Length: 10\r\n", "abc",
    ) { url ->
        val source = HearthRedirectGuardedHttpDataSource()
        try {
            source.open(spec(url))
            val bytes = ByteArray(32)
            assertEquals(3, source.read(bytes, 0, bytes.size))
            try { source.read(bytes, 0, bytes.size); fail("Truncated video was silently completed") }
            catch (_: EOFException) { /* Media3 can retry a failed network read. */ }
        } finally { source.close() }
    }

    @Test fun boundedProbeAcceptsShortUnknownLengthResponse() = serve(
        "200 OK", "Content-Type: application/octet-stream\r\n", "#EXTM3U\n",
    ) { url ->
        val source = HearthRedirectGuardedHttpDataSource()
        try {
            source.open(spec(url, length = 1024L))
            val bytes = ByteArray(1024)
            val count = source.read(bytes, 0, bytes.size)
            assertTrue(UrlVideoSourceClassifier.classify(null, bytes.copyOf(count)).isHls)
            assertEquals(C.RESULT_END_OF_INPUT, source.read(bytes, 0, bytes.size))
        } finally { source.close() }
    }

    @Test fun wrapperPreservesResponseHeadersForMedia3() = serve(
        "200 OK", "Content-Type: application/vnd.apple.mpegurl\r\nContent-Length: 8\r\n", "#EXTM3U\n",
    ) { url ->
        val source = HearthUrlVideoDataSource(null, { null }, AtomicLong())
        try {
            source.open(spec(url))
            val type = source.getResponseHeaders().entries.first { it.key.equals("Content-Type", true) }.value.first()
            assertEquals("application/vnd.apple.mpegurl", type)
        } finally { source.close() }
    }

    @Test fun bridgeByteRangesCapLargeLengthsWithoutOverflow() {
        val source = bridgeSource()
        try {
            assertEquals(2L, source.open(spec("hearth-hls://fixture/item/0", 1L, Long.MAX_VALUE)))
            val bytes = ByteArray(8)
            assertEquals(2, source.read(bytes, 0, bytes.size))
            assertEquals("bc", bytes.decodeToString(0, 2))
            assertEquals(0, source.read(bytes, 0, 0))
        } finally { source.close() }
    }

    @Test fun bridgeRejectsASeekPastTheResourceEnd() {
        val source = bridgeSource()
        try {
            try { source.open(spec("hearth-hls://fixture/item/0", 4L)); fail("seek past EOF accepted") }
            catch (_: IOException) { /* Preserve a real seek failure instead of clamping silently. */ }
        } finally { source.close() }
    }

    @Test fun gzipPlaylistIsDecodedBeforeHlsClassification() {
        val body = java.io.ByteArrayOutputStream().apply {
            java.util.zip.GZIPOutputStream(this).use { it.write("#EXTM3U\n".toByteArray()) }
        }.toByteArray()
        serve("200 OK", "Content-Encoding: gzip\r\nContent-Length: ${body.size}\r\n", body) { url ->
            val source = HearthRedirectGuardedHttpDataSource()
            try {
                source.open(spec(url, length = 1024L))
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32)
                while (true) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                }
                assertTrue(UrlVideoSourceClassifier.classify(null, bytes.toByteArray()).isHls)
            } finally { source.close() }
        }
    }

    @Test fun compressedPartialResponseRetriesWithoutRangeAndDecodesAtRequestedOffset() {
        val original = "0123456789".toByteArray()
        val compressed = java.io.ByteArrayOutputStream().apply {
            java.util.zip.GZIPOutputStream(this).use { it.write(original) }
        }.toByteArray()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
            val worker = thread(isDaemon = true) {
                repeat(2) { index ->
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val headers = StringBuilder()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            headers.append(line).append("\n")
                        }
                        requests.add(headers.toString())
                        val status = if (index == 0) "206 Partial Content" else "200 OK"
                        socket.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Encoding: gzip\r\n" +
                            "Content-Length: ${compressed.size}\r\nConnection: close\r\n\r\n").toByteArray() + compressed)
                    }
                }
            }
            val source = HearthRedirectGuardedHttpDataSource()
            try {
                assertEquals(3L, source.open(spec("http://127.0.0.1:${server.localPort}/media", 4L, 3L)))
                val out = ByteArray(16)
                assertEquals(3, source.read(out, 0, out.size))
                assertEquals("456", out.decodeToString(0, 3))
                assertEquals(C.RESULT_END_OF_INPUT, source.read(out, 0, out.size))
                worker.join(6000)
                assertEquals(2, requests.size)
                assertTrue(requests[0].contains("Range: bytes=4-6", true))
                assertFalse(requests[1].contains("Range:", true))
            } finally { source.close() }
        }
    }

    @Test fun webPageResponseFailsBeforeDecoderWaitsForMedia() = serve(
        "200 OK", "Content-Type: text/html\r\nContent-Length: 4\r\n", "oops",
    ) { url ->
        val source = HearthRedirectGuardedHttpDataSource()
        try {
            try { source.open(spec(url)); fail("HTML accepted as media") }
            catch (error: IOException) { assertTrue(error.message!!.contains("instead of playable media")) }
        } finally { source.close() }
    }

    private fun bridgeSource() = HearthUrlVideoDataSource(object : UrlVideoSessionSource {
        override val sessionId = "fixture"
        override fun open(uri: String) = "abc".toByteArray()
    }, { null }, AtomicLong())

    private fun spec(url: String, position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(url).setPosition(position).setLength(length).build()

    /** Serves one finite HTTP response; no external website, DNS, account or TV is required. */
    private fun serve(status: String, headers: String, body: String, test: (String) -> Unit) =
        serve(status, headers, body.toByteArray(), test)

    private fun serve(status: String, headers: String, body: ByteArray, test: (String) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val worker = thread(isDaemon = true, name = "Hearth-media-fixture") {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* Read request headers. */ }
                    socket.getOutputStream().write(
                        ("HTTP/1.1 $status\r\n${headers}Connection: close\r\n\r\n").toByteArray() + body,
                    )
                    socket.getOutputStream().flush()
                }
            }
            try { test("http://127.0.0.1:${server.localPort}/media") }
            finally { worker.join(6000) }
        }
    }
}
