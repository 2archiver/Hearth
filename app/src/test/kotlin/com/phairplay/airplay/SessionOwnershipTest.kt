package com.phairplay.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionOwnershipTest {

    @Test
    fun `stale connection cannot stop the session a newer connection owns`() {
        val ownership = SessionOwnership()
        val oldControl = Any()
        val newControl = Any()
        ownership.claim(oldControl)
        assertTrue("takeover is reported", ownership.claim(newControl))
        assertFalse(ownership.release(oldControl))
        assertTrue(ownership.isOwner(newControl))
        assertTrue(ownership.release(newControl))
        assertFalse(ownership.isClaimed())
    }

    @Test
    fun `probe without a session cannot stop anything`() {
        val ownership = SessionOwnership()
        assertFalse(ownership.release(Any()))
    }

    @Test
    fun `re-claiming by the same owner is not a takeover`() {
        val ownership = SessionOwnership()
        val c = Any()
        assertFalse(ownership.claim(c))
        assertFalse(ownership.claim(c))
        ownership.reset()
        assertFalse(ownership.isClaimed())
    }
}
