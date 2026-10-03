package com.phairplay.miracast

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Looper
import com.phairplay.airplay.RtspRequest
import com.phairplay.service.ProtocolState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MiracastReceiverTest — verifies Wi-Fi Direct service advertisement behavior.
 */
class MiracastReceiverTest {

    @Test
    fun `start advertises WFD service and emits advertising state`() {
        val context = mockk<Context>()
        val manager = mockk<WifiP2pManager>(relaxed = true)
        val channel = mockk<WifiP2pManager.Channel>(relaxed = true)
        val actionListener = slot<WifiP2pManager.ActionListener>()
        val states = mutableListOf<ProtocolState>()

        every { context.getSystemService(Context.WIFI_P2P_SERVICE) } returns manager
        every { context.mainLooper } returns Looper.getMainLooper()
        every { context.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") } returns
            PackageManager.PERMISSION_GRANTED
        every { context.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") } returns
            PackageManager.PERMISSION_DENIED
        every { manager.initialize(eq(context), any(), any()) } returns channel
        every {
            manager.addLocalService(
                eq(channel),
                any<WifiP2pDnsSdServiceInfo>(),
                capture(actionListener)
            )
        } answers {
            actionListener.captured.onSuccess()
            Unit
        }

        MiracastReceiver(context) { states.add(it) }.start()

        verify(exactly = 1) {
            manager.addLocalService(eq(channel), any<WifiP2pDnsSdServiceInfo>(), any())
        }
        assertEquals(ProtocolState.ADVERTISING, states.last())
    }

    @Test
    fun `start emits error when WifiP2pManager is unavailable`() {
        val context = mockk<Context>()
        val states = mutableListOf<ProtocolState>()

        every { context.getSystemService(Context.WIFI_P2P_SERVICE) } returns null

        MiracastReceiver(context) { states.add(it) }.start()

        assertTrue(states.contains(ProtocolState.ERROR))
    }

    @Test
    fun `WFD RTSP port uses Miracast default`() {
        assertEquals(7236, MiracastReceiver.WFD_RTSP_PORT)
    }

    @Test
    fun `WFD RTSP server advertises sink capabilities`() {
        val server = WfdRtspServer(
            onSessionStarted = {},
            onSessionStopped = {}
        )

        val response = server.routeRequest(
            RtspRequest(
                method = "GET_PARAMETER",
                uri = "rtsp://192.168.49.1/wfd1.0",
                headers = mapOf("CSeq" to "2"),
                body = ""
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals("text/parameters", response.headers["Content-Type"])
        assertTrue(response.body.contains("wfd_audio_codecs"))
        assertTrue(response.body.contains("wfd_video_formats"))
        assertTrue(response.body.contains("wfd_client_rtp_ports"))
    }

    @Test
    fun `WFD RTSP PLAY emits connected state once`() {
        var started = 0
        val server = WfdRtspServer(
            onSessionStarted = { started++ },
            onSessionStopped = {}
        )
        val request = RtspRequest(
            method = "PLAY",
            uri = "rtsp://192.168.49.1/wfd1.0",
            headers = mapOf("CSeq" to "5"),
            body = ""
        )

        assertEquals(200, server.routeRequest(request).statusCode)
        assertEquals(200, server.routeRequest(request).statusCode)

        assertEquals(1, started)
    }

    @Test
    fun `OPTIONS lists every method the receiver implements`() {
        val server = WfdRtspServer(onSessionStarted = {}, onSessionStopped = {})
        val response = server.routeRequest(
            RtspRequest("OPTIONS", "*", mapOf("CSeq" to "1", "Require" to "org.wfa.wfd1.0"), "")
        )
        assertEquals(200, response.statusCode)
        // Sources read this before using SETUP/PLAY; the old reply omitted both.
        for (method in listOf("SETUP", "PLAY", "PAUSE", "GET_PARAMETER", "SET_PARAMETER")) {
            assertTrue("Public header is missing $method",
                response.headers["Public"]?.contains(method) == true)
        }
    }

    @Test
    fun `GET_PARAMETER answers exactly the parameters the source asked for`() {
        val server = WfdRtspServer(onSessionStarted = {}, onSessionStopped = {})
        val request = RtspRequest(
            method = "GET_PARAMETER",
            uri = "rtsp://192.168.49.1/wfd1.0",
            headers = mapOf("CSeq" to "3", "Content-Type" to "text/parameters"),
            body = "wfd_uibc_capability\r\nwfd_standby_resume_capability\r\n"
        )
        val body = server.routeRequest(request).body
        val answered = body.lineSequence().filter { it.isNotBlank() }
            .map { it.substringBefore(':').trim() }.toList()

        assertEquals(listOf("wfd_uibc_capability", "wfd_standby_resume_capability"), answered)
    }

    @Test
    fun `SET_PARAMETER keeps the source presentation URL and echoes it back`() {
        val server = WfdRtspServer(onSessionStarted = {}, onSessionStopped = {})
        server.routeRequest(
            RtspRequest(
                method = "SET_PARAMETER",
                uri = "rtsp://192.168.49.1/wfd1.0",
                headers = mapOf("CSeq" to "4", "Content-Type" to "text/parameters"),
                body = "wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0\r\n" +
                    "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n"
            )
        )

        val body = server.routeRequest(
            RtspRequest(
                method = "GET_PARAMETER",
                uri = "rtsp://192.168.49.1/wfd1.0",
                headers = mapOf("CSeq" to "5", "Content-Type" to "text/parameters"),
                body = "wfd_presentation_URL\r\nwfd_client_rtp_ports\r\n"
            )
        ).body

        // The source's own choice is echoed…
        assertTrue(body.contains("wfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0"))
        // …but a transport we do not implement is never promised back.
        assertTrue(body.contains("wfd_client_rtp_ports: RTP/AVP/TCP;unicast 0 0 mode=play"))
    }

    @Test
    fun `PLAY reports a range and RTP-Info so the source can timestamp the stream`() {
        val server = WfdRtspServer(onSessionStarted = {}, onSessionStopped = {})
        val response = server.routeRequest(
            RtspRequest("PLAY", "rtsp://192.168.49.1/wfd1.0", mapOf("CSeq" to "6"), "")
        )
        assertEquals(200, response.statusCode)
        assertTrue(response.headers["Range"] == "npt=now-")
        assertTrue(response.headers["RTP-Info"]?.startsWith("url=rtsp://") == true)
    }
}
