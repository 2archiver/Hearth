package com.phairplay.cast.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * CastReceiverAppTest — the Cast V2 handshake PhairPlay's built-in receiver answers.
 *
 * This is the logic an iPhone's Cast SDK talks to before it will show a video: browse →
 * CONNECT → GET_STATUS → LAUNCH → LOAD. If any reply is malformed the sender silently
 * drops the device, so each step is asserted here rather than on a TV.
 *
 * Runs on the plain JVM: the app drives a fake [CastPlayback], never a MediaPlayer.
 */
class CastReceiverAppTest {

    private class FakePlayback : CastPlayback {
        var loadedUrl: String? = null
        var startPosition = -1.0
        var autoplay: Boolean? = null
        var paused = false
        var stopped = false
        var seekTo = -1.0
        var state = CastMediaPlayer.State.IDLE

        override fun load(url: String, startPositionSec: Double, autoplay: Boolean) {
            loadedUrl = url
            startPosition = startPositionSec
            this.autoplay = autoplay
            state = CastMediaPlayer.State.BUFFERING
        }

        override fun play() { state = CastMediaPlayer.State.PLAYING }
        override fun pause() { paused = true; state = CastMediaPlayer.State.PAUSED }
        override fun stop() { stopped = true; state = CastMediaPlayer.State.IDLE }
        override fun seek(positionSec: Double) { seekTo = positionSec }
        override fun positionSec(): Double = 12.5
        override fun durationSec(): Double = 600.0
        override fun currentState(): String = state
    }

    private class Recorder : CastSink {
        val sent = mutableListOf<CastMessage>()
        override fun send(message: CastMessage) { sent += message }
        fun on(namespace: String): List<JSONObject> =
            sent.filter { it.namespace == namespace }
                .mapNotNull { it.payloadUtf8 }
                .map { JSONObject(it) }
        fun last(namespace: String): JSONObject? = on(namespace).lastOrNull()
    }

    private fun fixture(): Triple<CastReceiverApp, FakePlayback, Recorder> {
        val playback = FakePlayback()
        val sink = Recorder()
        val app = CastReceiverApp(
            displayName = "Living Room TV",
            player = playback,
            onVolumeChanged = { _, _ -> }
        )
        app.addSink(sink)
        return Triple(app, playback, sink)
    }

    private fun CastReceiverApp.receive(from: String, to: String, namespace: String, payload: String) {
        handle(CastMessage(from, to, namespace, payloadUtf8 = payload))
    }

    // ─── Handshake ───────────────────────────────────────────────────────────

    @Test
    fun `CONNECT is acknowledged on the connection namespace`() {
        val (app, _, sink) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_CONNECTION, """{"type":"CONNECT"}""")
        val reply = sink.last(CastReceiverApp.NS_CONNECTION)
        assertNotNull(reply)
        assertEquals("CONNECT", reply?.optString("type"))
    }

    @Test
    fun `PING is answered with PONG`() {
        val (app, _, sink) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_HEARTBEAT, """{"type":"PING"}""")
        assertEquals("PONG", sink.last(CastReceiverApp.NS_HEARTBEAT)?.optString("type"))
    }

    @Test
    fun `GET_STATUS answers with a RECEIVER_STATUS carrying the request id`() {
        val (app, _, sink) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"GET_STATUS","requestId":7}""")
        val reply = sink.last(CastReceiverApp.NS_RECEIVER)
        assertNotNull(reply)
        assertEquals("RECEIVER_STATUS", reply?.optString("type"))
        assertEquals(7, reply?.optInt("requestId") ?: -1)
        val status = reply?.optJSONObject("status")
        assertNotNull(status)
        assertTrue(status?.optBoolean("isActiveInput") == true)
        assertEquals(1.0, status?.optJSONObject("volume")?.optDouble("level") ?: -1.0, 0.001)
        assertEquals(0, status?.optJSONArray("applications")?.length() ?: -1)
    }

    @Test
    fun `LAUNCH opens a session and echoes the requested app id`() {
        val (app, _, sink) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"LAUNCH","appId":"CC1AD845","requestId":2}""")
        assertTrue(app.hasSession())

        val apps = sink.last(CastReceiverApp.NS_RECEIVER)
            ?.optJSONObject("status")
            ?.optJSONArray("applications")
        assertEquals(1, apps?.length() ?: -1)
        val launched = apps?.optJSONObject(0)
        assertEquals("CC1AD845", launched?.optString("appId"))
        assertEquals("Living Room TV", launched?.optString("displayName"))
        assertNotNull(launched?.optString("transportId"))
        assertNotNull(launched?.optString("sessionId"))

        val namespaces = launched?.optJSONArray("namespaces")
        val names = (0 until (namespaces?.length() ?: 0))
            .mapNotNull { namespaces?.optJSONObject(it)?.optString("name") }
        assertTrue(names.contains(CastReceiverApp.NS_MEDIA))
        assertTrue(names.contains(CastReceiverApp.NS_CONNECTION))
    }

    @Test
    fun `an unregistered app id is accepted, so any sender app can cast`() {
        val (app, _, sink) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"LAUNCH","appId":"233637DE","requestId":3}""")
        assertEquals(
            "233637DE",
            sink.last(CastReceiverApp.NS_RECEIVER)
                ?.optJSONObject("status")
                ?.optJSONArray("applications")
                ?.optJSONObject(0)
                ?.optString("appId")
        )
    }

    // ─── Media ───────────────────────────────────────────────────────────────

    @Test
    fun `LOAD plays the content url and reports a MEDIA_STATUS`() {
        val (app, playback, sink) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}""")
        val transport = sink.last(CastReceiverApp.NS_RECEIVER)
            ?.optJSONObject("status")
            ?.optJSONArray("applications")
            ?.optJSONObject(0)
            ?.optString("transportId")
        assertNotNull(transport)

        app.receive("sender-0", transport!!, CastReceiverApp.NS_MEDIA,
            """{"type":"LOAD","requestId":9,"sessionId":"s","autoplay":true,"currentTime":30,
                "media":{"contentId":"http://192.168.1.9:8080/movie.mp4","contentType":"video/mp4","streamType":"BUFFERED"}}""")

        assertEquals("http://192.168.1.9:8080/movie.mp4", playback.loadedUrl)
        assertEquals(30.0, playback.startPosition, 0.001)
        assertTrue(playback.autoplay == true)

        val status = sink.last(CastReceiverApp.NS_MEDIA)
        assertEquals("MEDIA_STATUS", status?.optString("type"))
        assertEquals(9, status?.optInt("requestId") ?: -1)
        val item = status?.optJSONArray("status")?.optJSONObject(0)
        assertEquals("http://192.168.1.9:8080/movie.mp4", item?.optJSONObject("media")?.optString("contentId"))
        assertEquals(12.5, item?.optDouble("currentTime") ?: -1.0, 0.001)
        assertEquals(600.0, item?.optJSONObject("media")?.optDouble("duration") ?: -1.0, 0.001)
        assertTrue((item?.optInt("mediaSessionId", 0) ?: 0) > 0)
    }

    @Test
    fun `PAUSE and SEEK reach the player`() {
        val (app, playback, sink) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}""")
        app.receive("sender-0", "web-1", CastReceiverApp.NS_MEDIA,
            """{"type":"LOAD","requestId":1,"media":{"contentId":"http://x/y.mp4"},"autoplay":true}""")
        app.receive("sender-0", "web-1", CastReceiverApp.NS_MEDIA, """{"type":"PAUSE","requestId":2}""")
        app.receive("sender-0", "web-1", CastReceiverApp.NS_MEDIA, """{"type":"SEEK","requestId":3,"currentTime":42}""")

        assertTrue(playback.paused)
        assertEquals(42.0, playback.seekTo, 0.001)
        // Every command gets an answer, or the sender sits waiting.
        assertEquals(3, sink.on(CastReceiverApp.NS_MEDIA).size)
    }

    @Test
    fun `a media command with no session gets INVALID_REQUEST instead of silence`() {
        // Silence is the worst possible answer: the sender sits on "connecting…" forever
        // waiting for a reply that will never come. Before 1.5 this returned nothing.
        val (app, playback, sink) = fixture()
        app.receive("sender-0", "web-1", CastReceiverApp.NS_MEDIA,
            """{"type":"LOAD","requestId":1,"media":{"contentId":"http://x/y.mp4"}}""")
        assertNull(playback.loadedUrl)
        val reply = sink.last(CastReceiverApp.NS_MEDIA)
        assertEquals("INVALID_REQUEST", reply?.optString("type"))
        assertEquals(1, reply?.optInt("requestId") ?: -1)
    }

    @Test
    fun `LOAD without a contentId reports LOAD_FAILED rather than doing nothing`() {
        val (app, _, sink) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}""")
        app.receive("sender-0", "web-1", CastReceiverApp.NS_MEDIA,
            """{"type":"LOAD","requestId":9,"media":{"contentType":"video/mp4"}}""")
        val reply = sink.last(CastReceiverApp.NS_MEDIA)
        assertEquals("LOAD_FAILED", reply?.optString("type"))
        assertEquals(9, reply?.optInt("requestId") ?: -1)
        assertTrue((reply?.optInt("detailedErrorCode") ?: 0) > 0)
    }

    // ─── Queue ───────────────────────────────────────────────────────────────

    private fun launched(): Triple<CastReceiverApp, FakePlayback, Recorder> {
        val fixture = fixture()
        fixture.first.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}""")
        return fixture
    }

    @Test
    fun `QUEUE_LOAD plays the first item and reports the queue back`() {
        val (app, playback, sink) = launched()
        app.receive("sender-0", "web-1", CastReceiverApp.NS_MEDIA,
            """{"type":"QUEUE_LOAD","requestId":4,"startIndex":0,"repeatMode":"REPEAT_OFF",
                "items":[
                  {"media":{"contentId":"http://x/one.mp4"},"autoplay":true},
                  {"media":{"contentId":"http://x/two.mp4"},"autoplay":true}
                ]}""")

        assertEquals("http://x/one.mp4", playback.loadedUrl)

        val ids = sink.on(CastReceiverApp.NS_MEDIA).mapNotNull { it.optJSONArray("itemIds") }.lastOrNull()
        assertNotNull("QUEUE_LOAD must answer with QUEUE_ITEM_IDS", ids)
        assertEquals(2, ids?.length() ?: -1)

        // QUEUE_LOAD answers twice (MEDIA_STATUS then QUEUE_ITEM_IDS), so pick the status out.
        val status = sink.on(CastReceiverApp.NS_MEDIA)
            .lastOrNull { it.optString("type") == "MEDIA_STATUS" }
        assertNotNull(status)
        assertEquals(
            "http://x/one.mp4",
            status?.optJSONArray("status")?.optJSONObject(0)?.optJSONObject("media")?.optString("contentId")
        )
        assertTrue(app.hasSession())
    }

    @Test
    fun `QUEUE_NEXT advances to the following item`() {
        val (app, playback, _) = launched()
        app.receive("sender-0", "web-1", CastReceiverApp.NS_MEDIA,
            """{"type":"QUEUE_LOAD","requestId":1,"startIndex":0,
                "items":[
                  {"media":{"contentId":"http://x/one.mp4"}},
                  {"media":{"contentId":"http://x/two.mp4"}}
                ]}""")
        assertEquals("http://x/one.mp4", playback.loadedUrl)

        app.receive("sender-0", "web-1", CastReceiverApp.NS_MEDIA,
            """{"type":"QUEUE_NEXT","requestId":2}""")
        assertEquals("http://x/two.mp4", playback.loadedUrl)
        assertTrue(app.hasSession())
    }

    @Test
    fun `an empty QUEUE_LOAD is rejected instead of silently starting nothing`() {
        val (app, _, sink) = launched()
        app.receive("sender-0", "web-1", CastReceiverApp.NS_MEDIA,
            """{"type":"QUEUE_LOAD","requestId":3,"items":[]}""")
        assertEquals("INVALID_REQUEST", sink.last(CastReceiverApp.NS_MEDIA)?.optString("type"))
    }

    // ─── Per-sender replies ──────────────────────────────────────────────────

    @Test
    fun `a reply goes to the sender that asked, not to every connected phone`() {
        val playback = FakePlayback()
        val phoneA = Recorder()
        val phoneB = Recorder()
        val app = CastReceiverApp("TV", playback, onVolumeChanged = { _, _ -> })
        app.addSink(phoneA)
        app.addSink(phoneB)

        app.handle(
            CastMessage("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
                payloadUtf8 = """{"type":"GET_STATUS","requestId":1}"""),
            phoneA
        )

        assertEquals(0, phoneB.sent.size)
        assertEquals(1, phoneA.sent.size)
        assertEquals("RECEIVER_STATUS", phoneA.last(CastReceiverApp.NS_RECEIVER)?.optString("type"))
    }

    // ─── Private protocols ───────────────────────────────────────────────────

    @Test
    fun `an app using a private Cast channel is reported once, naming the app`() {
        val notices = mutableListOf<String>()
        val playback = FakePlayback()
        val sink = Recorder()
        val app = CastReceiverApp(
            displayName = "TV",
            player = playback,
            onVolumeChanged = { _, _ -> },
            onUnsupportedNamespace = { notices += it }
        )
        app.addSink(sink)
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"LAUNCH","appId":"233637DE","requestId":1}""")

        val mdx = "urn:x-cast:com.google.youtube.mdx"
        app.receive("sender-0", "web-1", mdx, """{"type":"mdxOpen"}""")
        app.receive("sender-0", "web-1", mdx, """{"type":"mdxNext"}""")

        assertEquals(1, notices.size)                 // once, not on every message
        assertTrue(notices[0].contains("233637DE"))
        assertTrue(notices[0].contains(mdx))
        assertTrue(notices[0].contains("Screen Mirroring"))
    }

    @Test
    fun `SET_VOLUME on the media namespace also updates the receiver volume`() {
        var notified: Double? = null
        val app = CastReceiverApp("TV", FakePlayback(), onVolumeChanged = { level, _ -> notified = level })
        val sink = Recorder()
        app.addSink(sink)
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}""")

        app.receive("sender-0", "web-1", CastReceiverApp.NS_MEDIA,
            """{"type":"SET_VOLUME","requestId":2,"volume":{"level":0.4,"muted":false}}""")

        assertEquals(0.4, notified ?: -1.0, 0.001)
        assertEquals(
            0.4,
            sink.last(CastReceiverApp.NS_RECEIVER)
                ?.optJSONObject("status")?.optJSONObject("volume")?.optDouble("level") ?: -1.0,
            0.001
        )
    }

    @Test
    fun `STOP ends the session and empties the applications list`() {
        val (app, playback, sink) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"LAUNCH","appId":"CC1AD845","requestId":1}""")
        assertTrue(app.hasSession())

        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"STOP","sessionId":"s","requestId":5}""")
        assertTrue(!app.hasSession())
        assertTrue(playback.stopped)
        assertEquals(
            0,
            sink.last(CastReceiverApp.NS_RECEIVER)
                ?.optJSONObject("status")
                ?.optJSONArray("applications")
                ?.length() ?: -1
        )
    }

    @Test
    fun `GET_APP_AVAILABILITY reports every app as available`() {
        val (app, _, sink) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"GET_APP_AVAILABILITY","appId":["CC1AD845","233637DE"],"requestId":4}""")
        val reply = sink.last(CastReceiverApp.NS_RECEIVER)
        assertEquals("GET_APP_AVAILABILITY", reply?.optString("type"))
        assertEquals(4, reply?.optInt("requestId") ?: -1)
        assertEquals("APP_AVAILABLE", reply?.optJSONObject("availability")?.optString("CC1AD845"))
        assertEquals("APP_AVAILABLE", reply?.optJSONObject("availability")?.optString("233637DE"))
    }

    @Test
    fun `SET_VOLUME is stored and reported back`() {
        var notified: Double? = null
        val playback = FakePlayback()
        val sink = Recorder()
        val app = CastReceiverApp("TV", playback, onVolumeChanged = { level, _ -> notified = level })
        app.addSink(sink)

        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"SET_VOLUME","requestId":6,"volume":{"level":0.25,"muted":false}}""")
        assertEquals(0.25, notified ?: -1.0, 0.001)
        assertEquals(
            0.25,
            sink.last(CastReceiverApp.NS_RECEIVER)
                ?.optJSONObject("status")
                ?.optJSONObject("volume")
                ?.optDouble("level") ?: -1.0,
            0.001
        )
    }

    @Test
    fun `an unparseable payload is dropped instead of killing the session`() {
        val (app, _, _) = fixture()
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER, "not json at all")
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER, """{"type":"NONSENSE"}""")
        app.receive("sender-0", "receiver-0", "urn:x-cast:com.google.cast.unknown", "{}")
        // Still alive: a well-formed message after the junk is answered normally.
        app.receive("sender-0", "receiver-0", CastReceiverApp.NS_RECEIVER,
            """{"type":"GET_STATUS","requestId":8}""")
        assertTrue(!app.hasSession())
    }
}
