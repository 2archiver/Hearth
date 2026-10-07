package com.phairplay.airplay

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Narrow exception for AirPlay sender-hosted cleartext media.
 *
 * Android 9+ blocks cleartext HTTP by default. AirPlay senders can announce a local HTTP media URL,
 * so the app enables platform cleartext support but this policy permits an `http://` media request
 * only when every resolved address is private, link-local or IPv6 ULA. Loopback, unspecified,
 * multicast and public addresses are not accepted. HTTPS media does not use this exception.
 */
internal object LocalMediaAddressPolicy {

    fun allowsCleartextHost(host: String?): Boolean {
        val candidate = host?.trim()?.removePrefix("[")?.removeSuffix("]")
            ?.takeIf { it.isNotEmpty() } ?: return false
        val addresses = runCatching { InetAddress.getAllByName(candidate) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return false
        // Reject mixed public/private DNS answers: the HTTP stack could otherwise choose the public
        // address even though one answer happened to look like a LAN sender.
        return addresses.all(::isLocalUnicast)
    }

    private fun isLocalUnicast(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isMulticastAddress) return false
        if (address.isLinkLocalAddress || address.isSiteLocalAddress) return true

        val bytes = address.address
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0].toInt() and 0xff
                val second = bytes[1].toInt() and 0xff
                // Shared address space (100.64.0.0/10) is used by some home/mobile networks.
                first == 100 && second in 64..127
            }
            is Inet6Address -> {
                // Unique-local IPv6 (fc00::/7); link-local was accepted above.
                (bytes[0].toInt() and 0xfe) == 0xfc
            }
            else -> false
        }
    }
}
