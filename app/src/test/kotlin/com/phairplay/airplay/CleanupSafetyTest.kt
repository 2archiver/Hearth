package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies one broken native/network close does not skip later receiver cleanup actions. */
class CleanupSafetyTest {

    @Test
    fun `cleanup continues after each step throws and reports failures`() {
        val attempted = mutableListOf<String>()
        val failures = mutableListOf<String>()

        CleanupSafety.runAll(
            listOf(
                "socket" to { attempted += "socket"; error("socket already closed") },
                "player" to { attempted += "player"; error("codec release failed") },
                "keys" to { attempted += "keys" },
            )
        ) { label, error -> failures += "$label:${error.message}" }

        assertEquals(listOf("socket", "player", "keys"), attempted)
        assertEquals(listOf("socket:socket already closed", "player:codec release failed"), failures)
    }
}
