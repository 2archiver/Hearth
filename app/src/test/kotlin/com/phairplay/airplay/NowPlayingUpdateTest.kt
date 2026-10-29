package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec
import org.junit.Assert.*
import org.junit.Test

class NowPlayingUpdateTest {
    private fun command(policy: String, fields: Map<String, Any?>) = PlistCodec.encode(mapOf(
        "type" to "updateMRNowPlayingInfo", "params" to mapOf(
            "type" to "npi-text", "mergePolicy" to policy,
            "params" to fields.mapKeys { "kMRMediaRemoteNowPlayingInfo" + it.key },
        ),
    ))

    @Test fun textAndCoverArriveInTheIosCommandEnvelope() {
        val cover = byteArrayOf(1, 2, 3)
        val update = NowPlayingUpdate.decode(command("replace", mapOf(
            "Title" to "Track", "Artist" to "Artist", "Album" to "Album", "ArtworkData" to cover,
        )))!!
        val result = update.applyTo(NowPlayingInfo("ben"))
        assertEquals("Track", result.title)
        assertEquals("Artist", result.artist)
        assertEquals("Album", result.album)
        assertArrayEquals(cover, result.artwork)
        assertEquals("ben", result.senderName)
    }

    @Test fun progressOnlyUpdateRetainsTrackAndArtwork() {
        val previous = NowPlayingInfo("ben", "Track", "Artist", "Album", byteArrayOf(1, 2))
        val result = NowPlayingUpdate.decode(command("update", mapOf("ElapsedTime" to 10.0)))!!.applyTo(previous)
        assertEquals(previous, result)
        assertSame(previous.artwork, result.artwork)
    }

    @Test fun replacementClearsOldTrackAndCover() {
        val previous = NowPlayingInfo("ben", "Old", "Old artist", "Old album", byteArrayOf(1))
        val result = NowPlayingUpdate.decode(command("replace", mapOf("Title" to "New")))!!.applyTo(previous)
        assertEquals("New", result.title)
        assertNull(result.artist)
        assertNull(result.album)
        assertNull(result.artwork)
    }

    @Test fun explicitEmptyArtworkClearsCoverOnUpdate() {
        val previous = NowPlayingInfo("ben", "Track", artwork = byteArrayOf(1))
        val result = NowPlayingUpdate.decode(command("update", mapOf("ArtworkData" to ByteArray(0))))!!.applyTo(previous)
        assertNull(result.artwork)
        assertEquals("Track", result.title)
    }

    @Test fun unknownCommandDoesNotReplaceNowPlaying() {
        assertNull(NowPlayingUpdate.decode(PlistCodec.encode(mapOf("type" to "other"))))
    }

    @Test fun malformedAndOversizedCommandsAreRejected() {
        for (body in listOf(byteArrayOf(1), ByteArray(NowPlayingUpdate.MAX_BODY_BYTES + 1),
            command("unknown", emptyMap()), command("replace", mapOf("ArtworkData" to "https://example.com")))) {
            try { NowPlayingUpdate.decode(body); fail("Invalid command accepted") }
            catch (_: Exception) { }
        }
    }

    @Test fun commandBodyAboveOld64KiBLimitStaysBinaryAndKeepsNextRequest() {
        val body = command("replace", mapOf("Title" to "Track", "ArtworkData" to ByteArray(100_000) { 1 }))
        val wire = "POST /command HTTP/1.1\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body +
            "OPTIONS * RTSP/1.0\r\nCSeq: 2\r\n\r\n".toByteArray()
        val input = java.io.ByteArrayInputStream(wire)
        val reader = RtspRequestReader(65536, 1024 * 1024)
        val request = reader.read(input)!!
        assertEquals("", request.body)
        assertArrayEquals(body, request.bodyBytes)
        assertEquals("Track", NowPlayingUpdate.decode(request.bodyBytes)!!.applyTo(NowPlayingInfo("ben")).title)
        assertEquals("OPTIONS", reader.read(input)!!.method)
    }

    @Test fun commandRouteDeliversMetadataAndAcknowledgesHttp() {
        var received: NowPlayingUpdate? = null
        val handler = RtspHandler(
            context = io.mockk.mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {}, onNowPlayingUpdate = { received = it },
        )
        val response = handler.routeRequest(RtspRequest(
            "POST", "/command", emptyMap(), "", command("replace", mapOf("Title" to "Track")), "HTTP/1.1",
        ))
        assertEquals(200, response.statusCode)
        assertEquals("HTTP/1.1", response.protocol)
        assertEquals("Track", received!!.applyTo(NowPlayingInfo("ben")).title)
        val malformed = handler.routeRequest(RtspRequest("POST", "/command", emptyMap(), "bad", protocol = "HTTP/1.1"))
        assertEquals(400, malformed.statusCode)
    }

    @Test fun dacpHeadersAreForwardedEvenWhenSentSeparately() {
        val headers = mutableListOf<Pair<String?, String?>>()
        val handler = RtspHandler(
            context = io.mockk.mockk(relaxed = true), videoSurfaceProvider = { null },
            onStreamingStarted = {}, onStreamingStopped = {}, onRemoteControlInfo = { id, token -> headers += id to token },
        )
        handler.routeRequest(RtspRequest("OPTIONS", "*", mapOf("DACP-ID" to "ABC"), ""))
        handler.routeRequest(RtspRequest("OPTIONS", "*", mapOf("Active-Remote" to "123"), ""))
        assertEquals(listOf("ABC" to null, null to "123"), headers)
    }

    @Test fun oversizedCommandIsRejectedBeforeBodyRead() {
        val wire = "POST /command HTTP/1.1\r\nContent-Length: ${NowPlayingUpdate.MAX_BODY_BYTES + 1}\r\n\r\n"
        assertNull(RtspRequestReader(65536, 1024 * 1024).read(java.io.ByteArrayInputStream(wire.toByteArray())))
    }
}
