package com.phairplay.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Tests the generation guard used by the service to reject delayed receiver callbacks.
 *
 * WHY: a Stop acknowledgment is published before socket and codec teardown completes. Callbacks
 * queued by the old receiver must not restore CONNECTED/PLAYING after that acknowledgment.
 */
class ReceiverLifecycleGateTest {

    @Test
    fun `new stop generation invalidates older queued callback`() {
        val gate = ReceiverLifecycleGate()
        val receiverTicket = gate.next()
        val published = mutableListOf<String>()

        assertTrue(gate.runIfCurrent(receiverTicket) { published += "connected" })
        val stopTicket = gate.next()

        assertFalse(gate.runIfCurrent(receiverTicket) { published += "stale playing" })
        assertTrue(gate.runIfCurrent(stopTicket) { published += "stopping" })
        assertEquals(listOf("connected", "stopping"), published)
    }

    @Test
    fun `stop generation waits for an in-flight state publication then invalidates it`() {
        val gate = ReceiverLifecycleGate()
        val receiverTicket = gate.next()
        val publicationEntered = CountDownLatch(1)
        val allowPublicationToFinish = CountDownLatch(1)
        val commandAdvanced = CountDownLatch(1)
        val published = mutableListOf<String>()
        var stopTicket = 0L

        val callback = thread {
            gate.runIfCurrent(receiverTicket) {
                publicationEntered.countDown()
                allowPublicationToFinish.await()
                published += "old callback completed before Stop"
            }
        }
        assertTrue(publicationEntered.await(1, TimeUnit.SECONDS))
        val stop = thread {
            stopTicket = gate.next()
            commandAdvanced.countDown()
        }

        // The same lock protects freshness and the short publication block, so Stop cannot pass
        // the check while the previous callback is actively publishing state.
        assertFalse(commandAdvanced.await(25, TimeUnit.MILLISECONDS))
        allowPublicationToFinish.countDown()
        callback.join(1_000)
        stop.join(1_000)

        assertTrue(commandAdvanced.await(1, TimeUnit.SECONDS))
        assertFalse(gate.runIfCurrent(receiverTicket) { published += "stale callback" })
        assertTrue(gate.runIfCurrent(stopTicket) { published += "Stop acknowledged" })
        assertEquals(listOf("old callback completed before Stop", "Stop acknowledged"), published)
    }
}
