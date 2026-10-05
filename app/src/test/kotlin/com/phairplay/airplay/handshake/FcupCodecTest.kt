package com.phairplay.airplay.handshake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FCUP wire contract: what the receiver asks the sender to fetch, and how it interprets the
 * answer. The field names and the plist shape are the ones UxPlay's `fcup_request.h` and
 * `http_handler_action` implement against real YouTube senders — no value here was invented, and
 * the tests pin them so a future edit cannot quietly change the contract.
 */
class FcupCodecTest {

    private val sessionId = "ABC123-SESSION"

    @Test
    fun `the reverse request is an unhandledURLRequest for the requested url`() {
        val body = FcupCodec.buildEventRequest(
            sessionId = sessionId,
            mediaUrl = "https://cdn.example/master.m3u8?signature=abc",
            requestId = 42L,
        )
        val root = PlistCodec.decode(body)

        assertEquals(FcupCodec.REQUEST_TYPE, root["type"])
        assertEquals(FcupCodec.SESSION_ID_VALUE, (root["sessionID"] as Number).toLong())

        @Suppress("UNCHECKED_CAST")
        val request = root["request"] as Map<String, Any?>
        assertEquals(42L, (request["FCUP_Response_RequestID"] as Number).toLong())
        assertEquals("https://cdn.example/master.m3u8?signature=abc", request["FCUP_Response_URL"])
        assertEquals(FcupCodec.CLIENT_INFO, (request["FCUP_Response_ClientInfo"] as Number).toLong())
        assertEquals(FcupCodec.CLIENT_REF, (request["FCUP_Response_ClientRef"] as Number).toLong())
        assertEquals(FcupCodec.SESSION_ID_VALUE, (request["sessionID"] as Number).toLong())

        @Suppress("UNCHECKED_CAST")
        val headers = request["FCUP_Response_Headers"] as Map<String, Any?>
        assertEquals(sessionId, headers["X-Playback-Session-Id"])
        assertEquals(FcupCodec.CLIENT_USER_AGENT, headers["User-Agent"])
    }

    @Test
    fun `the reverse request is an xml plist, which is what the sender parses`() {
        val body = FcupCodec.buildEventRequest(sessionId, "https://cdn.example/a.m3u8", 1L)
        val text = body.toString(Charsets.UTF_8)

        assertTrue(text.startsWith("<?xml"))
        assertTrue(text.contains("<plist"))
        assertTrue(text.contains(FcupCodec.REQUEST_TYPE))
    }

    @Test
    fun `an answer is decoded with its request id, url, status and body`() {
        val action = actionBody(
            requestId = 7L,
            url = "https://cdn.example/media.m3u8",
            status = 0,
            data = "#EXTM3U\n#EXTINF:6,\nseg1.ts\n",
        )

        val parsed = FcupCodec.parseAction(action)

        val response = (parsed as FcupCodec.ActionParse.Response).response
        assertEquals(7L, response.requestId)
        assertEquals("https://cdn.example/media.m3u8", response.url)
        assertEquals(0, response.statusCode)
        assertEquals("#EXTM3U\n#EXTINF:6,\nseg1.ts\n", response.data.toString(Charsets.UTF_8))
    }

    @Test
    fun `a sender error status is kept so the failure can be stated`() {
        val action = actionBody(requestId = 9L, url = "https://cdn.example/x.m3u8", status = 404, data = "nope")

        val response = (FcupCodec.parseAction(action) as FcupCodec.ActionParse.Response).response

        assertEquals(404, response.statusCode)
    }

    @Test
    fun `playlist bookkeeping is recognized rather than treated as a reply`() {
        val insert = PlistCodec.encode(
            mapOf(
                "type" to FcupCodec.TYPE_PLAYLIST_INSERT,
                "params" to mapOf("uuid" to "u-1"),
            )
        )

        val parsed = FcupCodec.parseAction(insert) as FcupCodec.ActionParse.Playlist
        assertEquals(FcupCodec.TYPE_PLAYLIST_INSERT, parsed.type)
        assertTrue(parsed.uuidPresent)
    }

    @Test
    fun `an unknown type is unsupported, never decoded as a reply`() {
        val body = PlistCodec.encode(mapOf("type" to "somethingElse", "params" to mapOf("a" to 1L)))

        val parsed = FcupCodec.parseAction(body)

        assertTrue(parsed is FcupCodec.ActionParse.Unsupported)
        assertEquals("somethingElse", (parsed as FcupCodec.ActionParse.Unsupported).type)
    }

    @Test
    fun `malformed actions are rejected with a url-free reason`() {
        assertTrue(FcupCodec.parseAction(ByteArray(0)) is FcupCodec.ActionParse.Invalid)
        assertTrue(FcupCodec.parseAction("not a plist".toByteArray()) is FcupCodec.ActionParse.Invalid)

        // A reply without its data cannot be served, and must not become an empty playlist.
        val noData = PlistCodec.encode(
            mapOf(
                "type" to FcupCodec.RESPONSE_TYPE,
                "params" to mapOf("FCUP_Response_URL" to "https://cdn.example/a.m3u8"),
            )
        )
        val parsed = FcupCodec.parseAction(noData)
        assertTrue(parsed is FcupCodec.ActionParse.Invalid)
        val reason = (parsed as FcupCodec.ActionParse.Invalid).reason
        assertFalse("the reason must not contain the URL", reason.contains("cdn.example"))
    }

    @Test
    fun `an oversized answer is refused instead of buffered`() {
        val action = PlistCodec.encode(
            mapOf(
                "type" to FcupCodec.RESPONSE_TYPE,
                "params" to mapOf(
                    "FCUP_Response_RequestID" to 1L,
                    "FCUP_Response_URL" to "https://cdn.example/a.m3u8",
                    "FCUP_Response_Data" to ByteArray(FcupCodec.MAX_DATA_BYTES + 1),
                ),
            )
        )

        assertTrue(FcupCodec.parseAction(action) is FcupCodec.ActionParse.Invalid)
    }

    @Test
    fun `a blank or overlong url is refused`() {
        val blank = PlistCodec.encode(
            mapOf(
                "type" to FcupCodec.RESPONSE_TYPE,
                "params" to mapOf(
                    "FCUP_Response_URL" to " ",
                    "FCUP_Response_Data" to "x".toByteArray(),
                ),
            )
        )
        assertTrue(FcupCodec.parseAction(blank) is FcupCodec.ActionParse.Invalid)

        val long = PlistCodec.encode(
            mapOf(
                "type" to FcupCodec.RESPONSE_TYPE,
                "params" to mapOf(
                    "FCUP_Response_URL" to ("https://cdn.example/" + "a".repeat(FcupCodec.MAX_URL_CHARS)),
                    "FCUP_Response_Data" to "x".toByteArray(),
                ),
            )
        )
        assertTrue(FcupCodec.parseAction(long) is FcupCodec.ActionParse.Invalid)
    }

    @Test
    fun `a request id may be missing, and that is not the same as request zero`() {
        val action = PlistCodec.encode(
            mapOf(
                "type" to FcupCodec.RESPONSE_TYPE,
                "params" to mapOf(
                    "FCUP_Response_URL" to "https://cdn.example/a.m3u8",
                    "FCUP_Response_Data" to "#EXTM3U\n".toByteArray(),
                ),
            )
        )

        val response = (FcupCodec.parseAction(action) as FcupCodec.ActionParse.Response).response
        assertNull(response.requestId)
    }

    /** One `POST /action` body exactly as the sender builds it (binary plist root + `params`). */
    private fun actionBody(requestId: Long, url: String, status: Int, data: String): ByteArray =
        PlistCodec.encode(
            mapOf(
                "type" to FcupCodec.RESPONSE_TYPE,
                "params" to mapOf(
                    "FCUP_Response_RequestID" to requestId,
                    "FCUP_Response_URL" to url,
                    "FCUP_Response_StatusCode" to status.toLong(),
                    "FCUP_Response_Data" to data.toByteArray(Charsets.UTF_8),
                ),
            )
        )
}
