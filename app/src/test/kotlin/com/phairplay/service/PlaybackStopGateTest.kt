package com.phairplay.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Verifies the synchronous Stop acknowledgment wins over stale callbacks while teardown is pending. */
class PlaybackStopGateTest {

    @Test
    fun `stop ack blocks stale playback callbacks then reopens for reconnect`() {
        val gate = PlaybackStopGate()
        val events = mutableListOf<String>()

        assertFalse(gate.beginStopIf(isActive = { false }) { events += "unexpected ack" })
        assertTrue(gate.beginStopIf(isActive = { true }) { events += "stopping" })
        assertFalse(gate.runCallback { events += "stale connected" })
        assertTrue(gate.runCallback(publishDuringStop = true) { events += "discovery detail" })

        gate.finishStop()
        assertTrue(gate.runCallback { events += "reconnected" })
        assertEquals(listOf("stopping", "discovery detail", "reconnected"), events)
    }

    @Test
    fun `stop ack waits for a callback already publishing before it clears the session`() {
        val gate = PlaybackStopGate()
        val entered = CountDownLatch(1)
        val allowCallbackToFinish = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val events = mutableListOf<String>()

        val callback = thread {
            gate.runCallback {
                entered.countDown()
                allowCallbackToFinish.await()
                events += "old callback completed"
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val stop = thread {
            gate.beginStopIf(isActive = { true }) { events += "Stop acknowledged" }
            stopFinished.countDown()
        }
        assertFalse(stopFinished.await(25, TimeUnit.MILLISECONDS))

        allowCallbackToFinish.countDown()
        callback.join(1_000)
        stop.join(1_000)
        assertTrue(stopFinished.await(1, TimeUnit.SECONDS))
        assertFalse(gate.runCallback { events += "stale after ack" })
        assertEquals(listOf("old callback completed", "Stop acknowledged"), events)
    }
}
