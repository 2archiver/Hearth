package com.phairplay.airplay.handshake

import org.junit.Assert.*
import org.junit.Test

class AudioOutputGateTest {
    @Test fun `sender volume cannot unmute an output owned by another soundtrack`() {
        val gate = AudioOutputGate(-15f)
        assertEquals(0.5f, gate.effectiveGain, 0.001f)
        gate.suspended = true
        gate.setVolume(0f)
        assertEquals(0f, gate.effectiveGain, 0f)
        gate.setVolume(-6f)
        assertEquals(0f, gate.effectiveGain, 0f)
        gate.suspended = false
        assertEquals(0.8f, gate.effectiveGain, 0.001f)
    }

    @Test fun `mute and invalid volume remain safe across suspend resume`() {
        val gate = AudioOutputGate()
        gate.setVolume(-144f)
        gate.suspended = true
        gate.setVolume(Float.NaN)
        gate.setVolume(Float.POSITIVE_INFINITY)
        gate.suspended = false
        assertEquals(0f, gate.effectiveGain, 0f)
    }
}
