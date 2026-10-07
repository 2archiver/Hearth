package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * Tests that cleartext video requests are limited to local, unicast sender addresses.
 *
 * WHY: the Android manifest must permit HTTP for some LAN-hosted AirPlay URLs. This policy is the
 * application-level guard that prevents that exception from allowing public or loopback cleartext.
 */
class LocalMediaAddressPolicyTest {

    @Test
    fun `private link local and shared address space are allowed`() {
        assertTrue(LocalMediaAddressPolicy.allowsCleartextHost("192.168.1.24"))
        assertTrue(LocalMediaAddressPolicy.allowsCleartextHost("10.20.30.40"))
        assertTrue(LocalMediaAddressPolicy.allowsCleartextHost("172.20.0.8"))
        assertTrue(LocalMediaAddressPolicy.allowsCleartextHost("169.254.8.9"))
        assertTrue(LocalMediaAddressPolicy.allowsCleartextHost("100.96.1.2"))
        assertTrue(LocalMediaAddressPolicy.allowsCleartextHost("fc00::42"))
        assertTrue(LocalMediaAddressPolicy.allowsCleartextHost("fe80::42"))
    }

    @Test
    fun `public loopback unspecified and multicast addresses are blocked`() {
        assertFalse(LocalMediaAddressPolicy.allowsCleartextHost("8.8.8.8"))
        assertFalse(LocalMediaAddressPolicy.allowsCleartextHost("127.0.0.1"))
        assertFalse(LocalMediaAddressPolicy.allowsCleartextHost("0.0.0.0"))
        assertFalse(LocalMediaAddressPolicy.allowsCleartextHost("224.0.0.1"))
        assertFalse(LocalMediaAddressPolicy.allowsCleartextHost("::1"))
        assertFalse(LocalMediaAddressPolicy.allowsCleartextHost("ff02::1"))
    }

    @Test
    fun `initial direct URLs allow local cleartext and public TLS only`() {
        assertNull(MediaRedirectPolicy.rejectionReason(URI("http://192.168.1.24/movie.m3u8")))
        assertNull(MediaRedirectPolicy.rejectionReason(URI("https://media.example/movie.m3u8")))
        assertEquals(
            "cleartext media host is not a local sender",
            MediaRedirectPolicy.rejectionReason(URI("http://8.8.8.8/movie.m3u8"))
        )
    }

    @Test
    fun `every redirect hop is checked for cleartext destination`() {
        assertNull(
            MediaRedirectPolicy.rejectionReason(
                URI("http://10.0.0.2/movie.m3u8"),
                originalScheme = "http"
            )
        )
        assertEquals(
            "cleartext media host is not a local sender",
            MediaRedirectPolicy.rejectionReason(
                URI("http://8.8.8.8/movie.m3u8"),
                originalScheme = "http"
            )
        )
    }

    @Test
    fun `cross protocol and credential redirects are rejected`() {
        assertEquals(
            "cross-protocol media redirect is blocked",
            MediaRedirectPolicy.rejectionReason(
                URI("http://192.168.1.24/movie.m3u8"),
                originalScheme = "https"
            )
        )
        assertEquals(
            "media URL credentials are blocked",
            MediaRedirectPolicy.rejectionReason(URI("https://user:secret@media.example/movie.m3u8"))
        )
    }

    @Test
    fun `empty and unresolvable hosts are blocked`() {
        assertFalse(LocalMediaAddressPolicy.allowsCleartextHost(null))
        assertFalse(LocalMediaAddressPolicy.allowsCleartextHost(" "))
        assertFalse(LocalMediaAddressPolicy.allowsCleartextHost("invalid host name"))
    }
}
