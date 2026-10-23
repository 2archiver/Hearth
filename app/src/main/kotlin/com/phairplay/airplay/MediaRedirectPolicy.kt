package com.phairplay.airplay

import java.net.URI
import java.util.Locale

/**
 * Validates direct-media URLs and every redirect target before a cleartext connection is opened.
 *
 * AirPlay senders may host media over HTTP on the LAN. Android's manifest therefore enables
 * cleartext at the app level, so the player must enforce this policy itself for the initial URL
 * and each redirect hop. HTTPS stays subject to the ordinary platform TLS checks.
 */
internal object MediaRedirectPolicy {

    /** Return a URL-free reason when [candidate] is not allowed, otherwise null. */
    fun rejectionReason(candidate: URI, originalScheme: String? = null): String? {
        val scheme = candidate.scheme?.lowercase(Locale.ROOT)
            ?: return "media URL has no scheme"
        if (scheme != "http" && scheme != "https") return "unsupported media URL scheme"
        // A LAN HTTP media endpoint may hand off to a TLS CDN. Never permit the reverse:
        // credentials and protected transport must not be downgraded by a redirect.
        if (originalScheme.equals("https", ignoreCase = true) && scheme == "http") {
            return "cross-protocol media redirect is blocked"
        }
        if (candidate.rawUserInfo != null) return "media URL credentials are blocked"
        val host = candidate.host?.takeIf(String::isNotBlank)
            ?: return "media URL has no valid host"
        if (LocalMediaAddressPolicy.isLoopbackHost(host)) {
            return "loopback media host may refer to the sender, not this receiver"
        }
        if (scheme == "http" && !LocalMediaAddressPolicy.allowsCleartextHost(host)) {
            return "cleartext media host is not a local sender"
        }
        return null
    }
}
