package com.phairplay.cast.bridge

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SsdpResponderTest — the DIAL half of Cast discovery.
 *
 * WHY THIS MATTERS: PhairPlay advertised `_googlecast._tcp` over mDNS and served DIAL over HTTP
 * on 8008, but never answered SSDP. Google Cast senders browse mDNS and found us; DIAL senders —
 * Netflix, YouTube, Windows — send `M-SEARCH` to 239.255.255.250:1900 and only talk to devices
 * that answer. We never answered, so to every DIAL sender the TV did not exist.
 *
 * The responder itself needs a socket, but its protocol decisions are pure string work and are
 * asserted here on the plain JVM.
 */
class SsdpResponderTest {

    private fun responder(): SsdpResponder = SsdpResponder(
        context = mockk<Context>(relaxed = true),
        locationUrl = { "http://192.168.1.42:8008/ssdp/device-desc.xml" },
        deviceUuid = { "0d1b2c3d-4e5f-6789-abcd-ef0123456789" }
    )

    private fun search(st: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: $st\r\n" +
            "\r\n"

    @Test
    fun `ssdpall is answered with every target we publish`() {
        val targets = responder().matchingSearchTargets(search("ssdp:all"))
        assertNotNull(targets)
        assertEquals(SsdpResponder.ALL_SEARCH_TARGETS, targets)
        assertTrue(targets!!.contains(SsdpResponder.ST_DIAL_SERVICE))
    }

    @Test
    fun `upnp rootdevice is answered too`() {
        assertEquals(
            SsdpResponder.ALL_SEARCH_TARGETS,
            responder().matchingSearchTargets(search("upnp:rootdevice"))
        )
    }

    @Test
    fun `the DIAL service search target is answered with itself`() {
        assertEquals(
            listOf(SsdpResponder.ST_DIAL_SERVICE),
            responder().matchingSearchTargets(search(SsdpResponder.ST_DIAL_SERVICE))
        )
    }

    @Test
    fun `unrelated search targets are ignored`() {
        assertNull(responder().matchingSearchTargets(search("urn:schemas-upnp-org:device:MediaRenderer:1")))
        assertNull(responder().matchingSearchTargets(search("urn:dial-multiscreen-org:service:dial:2")))
    }

    @Test
    fun `anything that is not an M-SEARCH is ignored`() {
        assertNull(responder().matchingSearchTargets("NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\n\r\n"))
    }

    @Test
    fun `the reply points the sender at the DIAL device description`() {
        val body = responder().searchResponse(
            SsdpResponder.ST_DIAL_SERVICE,
            "http://192.168.1.42:8008/ssdp/device-desc.xml",
            "0d1b2c3d-4e5f-6789-abcd-ef0123456789"
        )
        assertTrue(body.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(body.contains("LOCATION: http://192.168.1.42:8008/ssdp/device-desc.xml\r\n"))
        assertTrue(body.contains("ST: ${SsdpResponder.ST_DIAL_SERVICE}\r\n"))
        assertTrue(body.contains("USN: uuid:0d1b2c3d-4e5f-6789-abcd-ef0123456789::${SsdpResponder.ST_DIAL_SERVICE}\r\n"))
        assertTrue(body.endsWith("\r\n\r\n"))
    }

    @Test
    fun `headers are parsed case-insensitively and the ST header is what decides`() {
        // Windows sends "ST:", some stacks send "st:".
        val lower = "M-SEARCH * HTTP/1.1\r\nHost: 239.255.255.250:1900\r\nst: ssdp:all\r\n\r\n"
        assertEquals(SsdpResponder.ALL_SEARCH_TARGETS, responder().matchingSearchTargets(lower))
    }
}
