package com.phairplay.airplay

import org.junit.Assert.*
import org.junit.Test

class DiscoveryNetworkStateTest {
    @Test fun `initial and repeated properties do not rebuild registration`() {
        val state = DiscoveryNetworkState(setOf("192.168.1.2"))
        repeat(100) { assertFalse(state.changed(setOf("192.168.1.2"))) }
    }

    @Test fun `boot before DHCP republishes when a usable address arrives`() {
        val state = DiscoveryNetworkState(emptySet())
        assertFalse(state.changed(emptySet()))
        assertTrue(state.changed(setOf("192.168.1.2")))
        assertFalse(state.changed(setOf("192.168.1.2")))
    }

    @Test fun `quick reconnect to same address is not swallowed`() {
        val state = DiscoveryNetworkState(setOf("192.168.1.2"))
        state.lost()
        assertFalse(state.changed(emptySet()))
        assertTrue(state.changed(setOf("192.168.1.2")))
    }

    @Test fun `DHCP change republishes without requiring total network loss`() {
        val state = DiscoveryNetworkState(setOf("192.168.1.2"))
        assertTrue(state.changed(setOf("192.168.1.3")))
        assertFalse(state.changed(setOf("192.168.1.3")))
    }

    @Test fun `empty link followed by same IP counts as restoration`() {
        val state = DiscoveryNetworkState(setOf("192.168.1.2"))
        assertFalse(state.changed(emptySet()))
        assertTrue(state.changed(setOf("192.168.1.2")))
    }
}
