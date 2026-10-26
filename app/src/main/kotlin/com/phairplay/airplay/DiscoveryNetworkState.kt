package com.phairplay.airplay

/**
 * Tracks usable default-network addresses so duplicate callbacks do not churn mDNS.
 * Seed with the address used at receiver startup; after a loss the same address must
 * count as a restoration. Call only on the main thread, alongside mDNS registration.
 */
internal class DiscoveryNetworkState(initialAddresses: Set<String>) {
    private var addresses = initialAddresses

    fun lost() { addresses = emptySet() }

    /** Empty links wait for DHCP; repeated properties for the same link do no work. */
    fun changed(current: Set<String>): Boolean {
        if (current.isEmpty()) {
            lost()
            return false
        }
        if (current == addresses) return false
        addresses = current.toSet()
        return true
    }
}
