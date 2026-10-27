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

    private fun spec(url: String, position: Long = 0L, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(url).setPosition(position).setLength(length).build()

    /** Serves one finite HTTP response; no external website, DNS, account or TV is required. */
    private fun serve(status: String, headers: String, body: String, test: (String) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val worker = thread(isDaemon = true, name = "Hearth-media-fixture") {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* Read request headers. */ }
                    socket.getOutputStream().write(
                        ("HTTP/1.1 $status\r\n${headers}Connection: close\r\n\r\n$body").toByteArray(),
                    )
                    socket.getOutputStream().flush()
                }
            }
            try { test("http://127.0.0.1:${server.localPort}/media") }
            finally { worker.join(6000) }
        }
    }
}
