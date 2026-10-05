package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ─── Generation model (1.8.3) ─────────────────────────────────────────────

    @Test
    fun `a secondary connection joins only with the same protocol fingerprint`() {
        val ownership = SessionOwnership()
        val control = Any()
        val event = Any()
        val token = ownership.claimSession(
            control, "aabbccddeeff", AirPlayConnectionRole.CONTROL, AirPlaySessionMode.MIRRORING,
        ).token

        assertEquals(
            token,
            ownership.associateConnection(event, "aabbccddeeff", AirPlayConnectionRole.REVERSE_EVENT),
        )
        assertNull(
            "a different sender must not join this session",
            ownership.associateConnection(Any(), "001122334455", AirPlayConnectionRole.REVERSE_EVENT),
        )
    }

    @Test
    fun `a stale generation can neither close nor update the current session`() {
        val ownership = SessionOwnership()
        val first = Any()
        val second = Any()
        val old = ownership.claimSession(
            first, "aabbccddeeff", AirPlayConnectionRole.CONTROL, AirPlaySessionMode.MIRRORING,
        ).token
        val current = ownership.claimSession(
            second, "001122334455", AirPlayConnectionRole.CONTROL, AirPlaySessionMode.MIRRORING,
        ).token

        assertEquals(SessionCloseResult.STALE, ownership.closeConnection(old, first, explicitTeardown = true))
        assertFalse(ownership.updatePlaybackState(old, AirPlayPlaybackState.PLAYING))
        assertTrue(ownership.isCurrent(current))
        assertTrue(ownership.updatePlaybackState(current, AirPlayPlaybackState.PLAYING))
        assertEquals(AirPlayPlaybackState.PLAYING, ownership.snapshot()?.playbackState)
    }

    @Test
    fun `EOF keeps the session while a confirmed media role is still live`() {
        val ownership = SessionOwnership()
        val control = Any()
        val token = ownership.claimSession(
            control, "aabbccddeeff", AirPlayConnectionRole.CONTROL, AirPlaySessionMode.MIRRORING,
        ).token

        assertEquals(
            SessionCloseResult.KEEP_ACTIVE,
            ownership.closeConnection(
                token, control, explicitTeardown = false,
                externallyLiveMediaRoles = setOf(AirPlayMediaRole.MIRROR_VIDEO),
            ),
        )
        assertTrue(ownership.isCurrent(token))

        // The stream stopping on its own is what finally ends the orphaned session.
        assertEquals(SessionCloseResult.CLEANUP, ownership.updateMediaRole(token, AirPlayMediaRole.MIRROR_VIDEO, false))
        assertFalse(ownership.isCurrent(token))
    }

    @Test
    fun `a protocol TEARDOWN is terminal even while a media role is live`() {
        val ownership = SessionOwnership()
        val control = Any()
        val token = ownership.claimSession(
            control, "aabbccddeeff", AirPlayConnectionRole.CONTROL, AirPlaySessionMode.MIRRORING,
        ).token
        ownership.updateMediaRole(token, AirPlayMediaRole.MIRROR_VIDEO, active = true)

        assertEquals(SessionCloseResult.CLEANUP, ownership.closeConnection(token, control, explicitTeardown = true))
        assertFalse(ownership.isCurrent(token))
    }

    @Test
    fun `a connection that never claimed the session cannot report playback state`() {
        val ownership = SessionOwnership()

        assertFalse(ownership.updatePlaybackState(null, AirPlayPlaybackState.PLAYING))
        assertNull(ownership.snapshot())
    }
}
