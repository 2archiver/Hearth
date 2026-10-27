package com.phairplay.airplay

import org.junit.Assert.*
import org.junit.Test

/** A busy decoder must be drained before an encoded frame is discarded for IDR recovery. */
class DecoderInputPumpTest {
    @Test fun drainsBetweenWaitsAndPreservesInputWhenOutputFreesIt() {
        var drained = false
        val waits = mutableListOf<Long>()
        val index = DecoderInputPump.acquire(
            dequeue = { waits += it; if (drained) 3 else -1 },
            drain = { drained = true },
        )
        assertEquals(3, index)
        assertEquals(listOf(10_000L, 10_000L), waits)
    }
    @Test fun persistentBackpressureHasABoundedWait() {
        var waits = 0; var drains = 0
        assertEquals(-1, DecoderInputPump.acquire({ waits++; -1 }, { drains++ }))
        assertEquals(10, waits)
        assertEquals(10, drains)
    }
    @Test fun interruptionDoesNotFeedAnotherFrame() {
        assertEquals(-1, DecoderInputPump.acquire({ fail("dequeue after stop"); 0 }, {}, { true }))
    }
}
