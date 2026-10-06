package com.phairplay.airplay

import com.phairplay.airplay.handshake.FcupCodec
import com.phairplay.airplay.handshake.PlistCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class SenderMediatedHlsCoordinatorTest {

    @Test
    fun `bridge is routable before start waits for the first master action`() {
        val ownership = SessionOwnership()
        val fingerprint = AirPlaySessionFingerprint.of("youtube-session")
        val claim = ownership.claimSession(
            connection = "play-control",
            protocolSessionFingerprint = fingerprint,
            role = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL,
            mode = AirPlaySessionMode.SENDER_MEDIATED_HLS,
        )
        val playbackBridge = AtomicReference<SenderMediatedHlsBridge?>()
        val actionResult = AtomicReference<SenderMediatedActionResult?>()
        lateinit var coordinator: SenderMediatedHlsCoordinator
        coordinator = SenderMediatedHlsCoordinator(
            isStopped = { false },
            isSessionCurrent = ownership::isCurrent,
            onPlaybackReady = { _, bridge -> playbackBridge.set(bridge) },
            requestTimeoutMs = 500,
        )
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nv720/prog.m3u8\n"
        assertTrue(
            coordinator.registerReverseChannel(
                connectionId = "reverse-channel",
                senderSessionId = "youtube-session",
                writer = { frame ->
                    val (requestId, url) = decodeEventRequest(frame)
                    val action = actionBody(requestId, url, 200, master.toByteArray())
                    // This synchronous reply arrives from the writer before /play has returned. If
                    // the bridge was not published before bridge.start(), no action target exists.
                    actionResult.set(coordinator.deliverAction("youtube-session", action))
                    true
                },
            )
        )

        val result = coordinator.startSenderMediatedPlay(
            SenderMediatedPlayRequest(
                connectionId = "play-control",
                senderSessionId = "youtube-session",
                location = MASTER_URI,
                startSeconds = 0.0,
                seconds = true,
                token = claim.token,
            )
        )

        assertTrue("/play should succeed only after its first FCUP response was applied", result.accepted)
        assertEquals(200, actionResult.get()?.httpStatus)
        assertNotNull(playbackBridge.get())
        assertTrue(playbackBridge.get()!!.describe().contains("master=loaded"))
        coordinator.reset("test complete")
    }

    @Test
    fun `replacement session keeps its matching reverse offer across old media cleanup`() {
        val ownership = SessionOwnership()
        val old = ownership.claimSession(
            connection = "old-control",
            protocolSessionFingerprint = AirPlaySessionFingerprint.of("old-sender-session"),
            role = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL,
            mode = AirPlaySessionMode.SENDER_MEDIATED_HLS,
        )
        val currentToken = AtomicReference(old.token)
        lateinit var coordinator: SenderMediatedHlsCoordinator
        coordinator = SenderMediatedHlsCoordinator(
            isStopped = { false },
            isSessionCurrent = { token -> ownership.isCurrent(token) },
            onPlaybackReady = { _, _ -> },
            requestTimeoutMs = 500,
        )
        val newSessionId = "new-youtube-session"
        var routedSession: String? = null
        coordinator.registerReverseChannel(
            connectionId = "new-reverse",
            senderSessionId = newSessionId,
            writer = { frame ->
                val (requestId, url) = decodeEventRequest(frame)
                routedSession = url
                coordinator.deliverAction(
                    newSessionId,
                    actionBody(requestId, url, 200, "#EXTM3U\n".toByteArray()),
                )
                true
            },
        )

        val replacement = ownership.claimSession(
            connection = "new-control",
            protocolSessionFingerprint = AirPlaySessionFingerprint.of(newSessionId),
            role = AirPlayConnectionRole.DIRECT_VIDEO_CONTROL,
            mode = AirPlaySessionMode.SENDER_MEDIATED_HLS,
        )
        assertSame(old.token, replacement.replaced)
        currentToken.set(replacement.token)
        coordinator.replaceSession(
            reason = "test replacement",
            connectionId = "new-control",
            protocolSessionFingerprint = AirPlaySessionFingerprint.of(newSessionId),
        )

        val result = coordinator.startSenderMediatedPlay(
            SenderMediatedPlayRequest(
                connectionId = "new-control",
                senderSessionId = newSessionId,
                location = MASTER_URI,
                startSeconds = 0.0,
                seconds = true,
                token = currentToken.get(),
            )
        )

        assertTrue(result.accepted)
        assertEquals(MASTER_URI, routedSession)
        coordinator.reset("test complete")
    }

    @Test
    fun `replacement and reset revoke only offers that are no longer eligible`() {
        val coordinator = SenderMediatedHlsCoordinator(
            isStopped = { false },
            isSessionCurrent = { true },
            onPlaybackReady = { _, _ -> },
        )
        val oldRevocation = AtomicReference<String?>()
        val currentRevocation = AtomicReference<String?>()
        val mismatchedControlRevocation = AtomicReference<String?>()
        coordinator.registerReverseChannel(
            connectionId = "old-reverse",
            senderSessionId = "old-sender-session",
            writer = { true },
            revoke = oldRevocation::set,
        )
        coordinator.registerReverseChannel(
            connectionId = "current-reverse",
            senderSessionId = "current-sender-session",
            writer = { true },
            revoke = currentRevocation::set,
        )
        coordinator.registerReverseChannel(
            connectionId = "new-control",
            senderSessionId = "old-sender-session",
            writer = { true },
            revoke = mismatchedControlRevocation::set,
        )

        coordinator.replaceSession(
            reason = "replacement test",
            connectionId = "new-control",
            protocolSessionFingerprint = AirPlaySessionFingerprint.of("current-sender-session"),
        )

        assertEquals("replacement test", oldRevocation.get())
        assertEquals(null, currentRevocation.get())
        assertEquals("replacement test", mismatchedControlRevocation.get())
        coordinator.reset("receiver cleanup")
        assertEquals("receiver cleanup", currentRevocation.get())
    }

    private fun decodeEventRequest(frame: ByteArray): Pair<Long, String> {
        val separator = frame.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
        check(separator >= 0) { "reverse request has no header terminator" }
        val headerBytes = frame.copyOfRange(0, separator).toString(Charsets.ISO_8859_1)
        assertTrue(headerBytes.startsWith("POST /event HTTP/1.1"))
        val body = frame.copyOfRange(separator + 4, frame.size)
        val root = PlistCodec.decode(body)
        @Suppress("UNCHECKED_CAST")
        val request = root["request"] as Map<String, Any?>
        return (request["FCUP_Response_RequestID"] as Number).toLong() to
            (request["FCUP_Response_URL"] as String)
    }

    private fun actionBody(requestId: Long, url: String, status: Int, data: ByteArray): ByteArray =
        PlistCodec.encode(
            mapOf(
                "type" to FcupCodec.RESPONSE_TYPE,
                "params" to mapOf(
                    "FCUP_Response_RequestID" to requestId,
                    "FCUP_Response_URL" to url,
                    "FCUP_Response_StatusCode" to status.toLong(),
                    "FCUP_Response_Data" to data,
                ),
            )
        )

    private companion object {
        const val MASTER_URI = "mlhls://localhost:6234/youtube/master.m3u8"
    }
}
