package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The sender-mediated transport end to end, with a fake sender on the other side of the reverse
 * channel: the receiver asks (FCUP `/event`), the fake answers (`POST /action`), and the bridge has
 * to serve the player a playlist whose references it can actually resolve.
 *
 * What is asserted here is what must not regress:
 *  - playlists are fetched **through the sender** and rewritten, never handed to the player raw;
 *  - signed absolute URLs are handed over untouched (no query propagation, no re-signing);
 *  - relative references resolve against the sender's own URI, not the player's;
 *  - a reply is matched by id **and URL** — a mismatch is ignored, not served;
 *  - silence from the sender becomes a stated failure, never a hang;
 *  - a session cannot read another session's media, and a closed session serves nothing;
 *  - when a reference cannot be transported, the *playlist* fails: no silent downgrade.
 */
class SenderMediatedHlsBridgeTest {

    private val session = SessionToken(sessionId = "S1", generation = 1)

    @Test
    fun `the master playlist is fetched through the sender and rewritten for the player`() {
        val sender = FakeSender(session.sessionId)
        sender.answers[MASTER_URI] = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,CODECS="avc1.4d401f,mp4a.40.2"
            v800/prog.m3u8
        """.trimIndent() + "\n"
        val bridge = bridge(sender)

        val master = bridge.open(bridge.playerUri).toString(Charsets.UTF_8)

        assertEquals(listOf(MASTER_URI), sender.requestedUrls())
        assertTrue(master.contains("#EXTM3U"))
        // The player must never see a location it cannot fetch: the variant became a bridge URI.
        assertFalse(master.contains("v800/prog.m3u8"))
        assertTrue(master.contains("hearth-hls://${session.sessionId}/playlist/0.m3u8"))
        assertEquals(1, sender.requestedUrls().size)
    }

    @Test
    fun `a media playlist's relative segments become bridge items and are fetched from the sender`() {
        val sender = FakeSender(session.sessionId)
        sender.answers[MASTER_URI] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv800/prog.m3u8\n"
        sender.answers[VARIANT_URI] = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg-1.ts
            #EXTINF:6.0,
            seg-2.ts
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"
        sender.answers[SEGMENT_ONE_URI] = "ONE".toByteArray()
        sender.answers[SEGMENT_TWO_URI] = "TWO".toByteArray()
        val bridge = bridge(sender)

        bridge.open(bridge.playerUri)
        val mediaUri = "hearth-hls://${session.sessionId}/playlist/0.m3u8"
        val media = bridge.open(mediaUri).toString(Charsets.UTF_8)

        // Relative references resolved against the *sender's* playlist URL, and bridged.
        assertTrue(media.contains("hearth-hls://${session.sessionId}/item/0.ts"))
        assertTrue(media.contains("hearth-hls://${session.sessionId}/item/1.ts"))
        assertTrue(media.contains("#EXT-X-ENDLIST"))
        assertEquals(listOf(MASTER_URI, VARIANT_URI), sender.requestedUrls())

        assertEquals("ONE", bridge.open("hearth-hls://${session.sessionId}/item/0.ts").toString(Charsets.UTF_8))
        assertEquals("TWO", bridge.open("hearth-hls://${session.sessionId}/item/1.ts").toString(Charsets.UTF_8))
        assertEquals(
            listOf(MASTER_URI, VARIANT_URI, SEGMENT_ONE_URI, SEGMENT_TWO_URI),
            sender.requestedUrls(),
        )
    }

    @Test
    fun `an absolute segment url is not routed through the sender and keeps its signed query`() {
        val sender = FakeSender(session.sessionId)
        sender.answers[MASTER_URI] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv800/prog.m3u8\n"
        sender.answers[VARIANT_URI] = """
            #EXTM3U
            #EXTINF:6.0,
            https://cdn.example/seg.ts?sig=AbC%2Fd%3D&exp=1700
        """.trimIndent() + "\n"
        val bridge = bridge(sender)

        bridge.open(bridge.playerUri)
        val media = bridge.open("hearth-hls://${session.sessionId}/playlist/0.m3u8").toString(Charsets.UTF_8)

        assertTrue(media.contains("https://cdn.example/seg.ts?sig=AbC%2Fd%3D&exp=1700"))
        assertFalse("a directly fetchable segment must not be pulled through the sender", media.contains("item/"))
        assertEquals(listOf(MASTER_URI, VARIANT_URI), sender.requestedUrls())
    }

    @Test
    fun `a location only the sender can reach keeps everything on the sender's transport`() {
        // What the YouTube app actually sends: the HLS location names the *sender's* machine, so the
        // TV can never fetch it. Both the media playlist and its relative segments must come back
        // through the sender — handing the player `http://localhost:<port>/…` is a black screen.
        val sender = FakeSender(session.sessionId)
        val location = "http://localhost:64321/hls/master.m3u8"
        val variant = "http://localhost:64321/hls/v800/prog.m3u8"
        val segment = "http://localhost:64321/hls/v800/seg-1.ts"
        sender.answers[location] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv800/prog.m3u8\n"
        sender.answers[variant] = "#EXTM3U\n#EXTINF:6.0,\nseg-1.ts\n"
        sender.answers[segment] = "ONE".toByteArray()
        val bridge = SenderMediatedHlsBridge(
            sessionToken = session,
            contentLocation = location,
            channel = sender.install(ReverseHttpChannel.DEFAULT_REQUEST_TIMEOUT_MS),
        )

        bridge.open(bridge.playerUri)
        val media = bridge.open("hearth-hls://${session.sessionId}/playlist/0.m3u8").toString(Charsets.UTF_8)

        assertTrue(media.contains("hearth-hls://${session.sessionId}/item/0.ts"))
        assertFalse("the player must never be handed the sender's own loopback", media.contains("localhost"))
        assertEquals("ONE", bridge.open("hearth-hls://${session.sessionId}/item/0.ts").toString(Charsets.UTF_8))
        assertEquals(listOf(location, variant, segment), sender.requestedUrls())
    }

    @Test
    fun `live playlists are re-read from the sender on every refresh`() {
        val sender = FakeSender(session.sessionId)
        sender.answers[MASTER_URI] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv800/prog.m3u8\n"
        sender.answers[VARIANT_URI] = "#EXTM3U\n#EXTINF:6.0,\nseg-1.ts\n"
        val bridge = bridge(sender)
        bridge.open(bridge.playerUri)

        bridge.open("hearth-hls://${session.sessionId}/playlist/0.m3u8")
        sender.answers[VARIANT_URI] = "#EXTM3U\n#EXTINF:6.0,\nseg-2.ts\n"
        val refreshed = bridge.open("hearth-hls://${session.sessionId}/playlist/0.m3u8").toString(Charsets.UTF_8)

        assertTrue("a stale window would freeze the picture", refreshed.contains("item/1.ts"))
        assertEquals(2, sender.requestedUrls().count { it == VARIANT_URI })
    }

    @Test
    fun `the master playlist is fetched once and cached for the session`() {
        val sender = FakeSender(session.sessionId)
        sender.answers[MASTER_URI] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv/prog.m3u8\n"
        val bridge = bridge(sender)

        bridge.open(bridge.playerUri)
        bridge.open(bridge.playerUri)

        assertEquals(1, sender.requestedUrls().count { it == MASTER_URI })
    }

    @Test
    fun `a reply whose url does not match the request is ignored`() {
        val sender = FakeSender(session.sessionId)
        sender.answers[MASTER_URI] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv/prog.m3u8\n"
        val bridge = bridge(sender, requestTimeoutMs = 250L)

        // Pre-queue a wrong answer for the master request, then let the real one arrive.
        sender.answerOverride = { request ->
            sender.channel?.deliver(request.id, "https://elsewhere.example/master.m3u8", 0, "WRONG".toByteArray(), session.sessionId)
            sender.channel?.deliver(request.id, request.url, 0, sender.answers[request.url]!!, session.sessionId)
        }

        val master = bridge.open(bridge.playerUri).toString(Charsets.UTF_8)

        assertFalse("the mismatched bytes must never be served", master.contains("WRONG"))
        assertTrue(master.contains("#EXTM3U"))
    }

    @Test
    fun `replies may arrive out of order and each waiter gets its own bytes`() {
        val sender = FakeSender(session.sessionId)
        sender.answers[MASTER_URI] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv/prog.m3u8\n"
        sender.answers[VARIANT_URI] = "#EXTM3U\n#EXTINF:6.0,\nseg-1.ts\n#EXTINF:6.0,\nseg-2.ts\n"
        sender.answers[SEGMENT_ONE_URI] = "ONE".toByteArray()
        sender.answers[SEGMENT_TWO_URI] = "NINE".toByteArray()
        val bridge = bridge(sender)
        bridge.open(bridge.playerUri)
        bridge.open("hearth-hls://${session.sessionId}/playlist/0.m3u8")

        // Two loaders race; the sender answers the *second* request first.
        sender.autoAnswer = false
        val firstBytes = arrayOfNulls<ByteArray>(1)
        val secondBytes = arrayOfNulls<ByteArray>(1)
        val done = CountDownLatch(2)
        val alreadyAsked = sender.requests.size

        Thread {
            runCatching { firstBytes[0] = bridge.open("hearth-hls://${session.sessionId}/item/0.ts") }
            done.countDown()
        }.start()
        Thread {
            runCatching { secondBytes[0] = bridge.open("hearth-hls://${session.sessionId}/item/1.ts") }
            done.countDown()
        }.start()
        waitFor { sender.requests.size >= alreadyAsked + 2 }
        val pending = sender.requests.drop(alreadyAsked).take(2)
        sender.channel!!.deliver(pending[1].id, pending[1].url, 0, "NINE".toByteArray(), session.sessionId)
        sender.channel!!.deliver(pending[0].id, pending[0].url, 0, "ONE".toByteArray(), session.sessionId)
        assertTrue(done.await(2, TimeUnit.SECONDS))

        assertEquals("ONE", firstBytes[0]?.toString(Charsets.UTF_8))
        assertEquals("NINE", secondBytes[0]?.toString(Charsets.UTF_8))
    }

    @Test
    fun `silence from the sender becomes a stated failure, not a hang`() {
        val sender = FakeSender(session.sessionId)
        sender.autoAnswer = false
        val bridge = bridge(sender, requestTimeoutMs = 150L)

        val error = expectBridgeFailure { bridge.open(bridge.playerUri) }

        assertTrue(error.message.orEmpty().contains("did not answer"))
        assertNotNull(bridge.failureReason())
    }

    @Test
    fun `a late answer after the timeout is acknowledged but never applied`() {
        val sender = FakeSender(session.sessionId)
        sender.autoAnswer = false
        val bridge = bridge(sender, requestTimeoutMs = 120L)

        runCatching { bridge.open(bridge.playerUri) }
        waitFor { sender.requests.isNotEmpty() }
        val request = sender.requests.first()
        val delivery = sender.channel!!.deliver(
            request.id, request.url, 0, "#EXTM3U\n".toByteArray(), session.sessionId,
        )

        // A late answer is legitimate traffic: the sender is told it was received (HTTP 200), but it
        // is not *applied* — the waiter is gone, and its bytes must not be handed to a later read.
        assertEquals(200, delivery.httpStatus)
        assertFalse("a late answer is not applied to anything", delivery.accepted)
        assertTrue(delivery.summary.contains("ignored"))

        // A fresh fetch is served: the stale answer did not poison the channel for the URL.
        sender.answers[MASTER_URI] = "#EXTM3U\n"
        sender.autoAnswer = true
        assertTrue(bridge.open(bridge.playerUri).isNotEmpty())
    }

    @Test
    fun `a sender error status fails the read with the status in the reason`() {
        val sender = FakeSender(session.sessionId)
        // The body itself does not matter (it is never used): the sender answers with a status.
        sender.answers[MASTER_URI] = "#EXTM3U\n"
        sender.statusOverride = 404
        val bridge = bridge(sender)

        val error = expectBridgeFailure { bridge.open(bridge.playerUri) }

        assertTrue(error.message.orEmpty().contains("404"))
    }

    @Test
    fun `another session's uri is refused and a closed session serves nothing`() {
        val sender = FakeSender(session.sessionId)
        sender.answers[MASTER_URI] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv/prog.m3u8\n"
        val bridge = bridge(sender)
        bridge.open(bridge.playerUri)

        val foreign = expectBridgeFailure {
            bridge.open("hearth-hls://S2/master.m3u8")
        }
        assertTrue(foreign.message.orEmpty().contains("another session"))

        bridge.close()
        val afterClose = expectBridgeFailure { bridge.open(bridge.playerUri) }
        assertTrue(afterClose.message.orEmpty().contains("closed"))
        assertNotNull(bridge.failureReason())
    }

    @Test
    fun `closing the session releases a fetch that is still waiting`() {
        val sender = FakeSender(session.sessionId)
        sender.autoAnswer = false
        val bridge = bridge(sender, requestTimeoutMs = 10_000L)
        var failure: Throwable? = null
        val done = CountDownLatch(1)

        Thread {
            failure = runCatching { bridge.open(bridge.playerUri) }.exceptionOrNull()
            done.countDown()
        }.start()
        waitFor { sender.requests.isNotEmpty() }
        bridge.close()

        assertTrue("a player thread must never be left blocked by a dead session", done.await(2, TimeUnit.SECONDS))
        assertTrue(failure is HlsBridgeException)
    }

    @Test
    fun `a reference the bridge cannot address fails the playlist instead of downgrading it`() {
        val sender = FakeSender(session.sessionId)
        sender.answers[MASTER_URI] = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1
            v800/prog.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2
            v400/prog.m3u8
        """.trimIndent() + "\n"
        // One playlist reference is one too many for this session's table.
        val bridge = bridge(sender, maxPlaylistReferences = 1)

        val error = expectBridgeFailure { bridge.open(bridge.playerUri) }

        assertTrue(error.message.orEmpty().contains("too many media playlists"))
        assertNotNull(bridge.failureReason())
    }

    @Test
    fun `a media item larger than the bound is refused rather than buffered`() {
        val sender = FakeSender(session.sessionId)
        sender.answers[MASTER_URI] = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv800/prog.m3u8\n"
        sender.answers[VARIANT_URI] = "#EXTM3U\n#EXTINF:6.0,\nseg-1.ts\n"
        sender.answers[SEGMENT_ONE_URI] = ByteArray(64)
        val bridge = bridge(sender, maxSegmentBytes = 16)
        bridge.open(bridge.playerUri)
        bridge.open("hearth-hls://${session.sessionId}/playlist/0.m3u8")

        val error = expectBridgeFailure { bridge.open("hearth-hls://${session.sessionId}/item/0.ts") }

        assertTrue(error.message.orEmpty().contains("size limit"))
    }

    @Test
    fun `a start on a closed session fails before any player exists`() {
        val sender = FakeSender(session.sessionId)
        val bridge = bridge(sender)
        bridge.close()

        assertFalse(bridge.start())
        assertNotNull(bridge.failureReason())
    }

    // ─── harness ────────────────────────────────────────────────────────────────────────────────

    private fun bridge(
        sender: FakeSender,
        requestTimeoutMs: Long = ReverseHttpChannel.DEFAULT_REQUEST_TIMEOUT_MS,
        maxSegmentBytes: Int = SenderMediatedHlsBridge.DEFAULT_MAX_SEGMENT_BYTES,
        maxPlaylistReferences: Int = SenderMediatedHlsBridge.MAX_PLAYLIST_REFERENCES,
    ): SenderMediatedHlsBridge {
        val channel = sender.install(requestTimeoutMs)
        return SenderMediatedHlsBridge(
            sessionToken = session,
            contentLocation = MASTER_URI,
            channel = channel,
            maxSegmentBytes = maxSegmentBytes,
            maxPlaylistReferences = maxPlaylistReferences,
        )
    }

    private fun expectBridgeFailure(block: () -> Unit): HlsBridgeException {
        try {
            block()
        } catch (e: HlsBridgeException) {
            return e
        }
        fail("expected the bridge to refuse, but it served media")
        error("unreachable")
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("condition was not met in time")
    }

    /**
     * A stand-in for the sender: it parses each FCUP request the receiver writes and answers it on
     * the channel, exactly as `POST /action` does on the wire.
     */
    private class FakeSender(val sessionId: String) {
        val requests = CopyOnWriteArrayList<Request>()
        val answers = Answers()
        var autoAnswer = true
        var statusOverride: Int? = null
        /** Optional hook that replaces the standard answer for one request. */
        var answerOverride: ((Request) -> Unit)? = null
        var channel: ReverseHttpChannel? = null

        data class Request(val id: Long, val url: String)

        fun install(requestTimeoutMs: Long): ReverseHttpChannel {
            val created = ReverseHttpChannel(
                connectionId = "C1",
                senderSessionId = sessionId,
                writeFrame = { frame -> onFrame(frame) },
                requestTimeoutMs = requestTimeoutMs,
            )
            channel = created
            return created
        }

        fun requestedUrls(): List<String> = requests.map { it.url }

        private fun onFrame(frame: ByteArray): Boolean {
            val request = decode(frame) ?: return false
            requests += request
            answerOverride?.invoke(request)?.let { return true }
            if (autoAnswer) {
                val data = answers[request.url] ?: return true
                channel?.deliver(request.id, request.url, statusOverride ?: 0, data, sessionId)
            }
            return true
        }

        /**
         * Answer bodies: the setters take either text (a playlist, which is what most tests write)
         * or raw bytes (a segment), so a test reads like the sender's own traffic.
         */
        class Answers {
            private val bodies = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

            operator fun set(url: String, body: String) {
                bodies[url] = body.toByteArray(Charsets.UTF_8)
            }

            operator fun set(url: String, body: ByteArray) {
                bodies[url] = body
            }

            operator fun get(url: String): ByteArray? = bodies[url]
        }

        /** Reads the FCUP plist out of one already-framed reverse request. */
        private fun decode(frame: ByteArray): Request? {
            val separator = indexOfHeaderEnd(frame) ?: return null
            val body = frame.copyOfRange(separator + 4, frame.size)
            val root = runCatching { PlistCodec.decode(body) }.getOrNull() ?: return null
            @Suppress("UNCHECKED_CAST")
            val request = root["request"] as? Map<String, Any?> ?: return null
            val id = (request["FCUP_Response_RequestID"] as? Number)?.toLong() ?: return null
            val url = request["FCUP_Response_URL"] as? String ?: return null
            return Request(id, url)
        }

        private fun indexOfHeaderEnd(bytes: ByteArray): Int? {
            for (i in 0..bytes.size - 4) {
                if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                    bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
                ) {
                    return i
                }
            }
            return null
        }
    }

    private companion object {
        const val MASTER_URI = "https://cdn.example/video/master.m3u8?token=abc"
        const val VARIANT_URI = "https://cdn.example/video/v800/prog.m3u8"
        const val SEGMENT_ONE_URI = "https://cdn.example/video/v800/seg-1.ts"
        const val SEGMENT_TWO_URI = "https://cdn.example/video/v800/seg-2.ts"
    }
}
