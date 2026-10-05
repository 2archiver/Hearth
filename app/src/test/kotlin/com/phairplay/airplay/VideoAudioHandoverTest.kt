package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio-ownership rules, as invariants rather than as anecdotes: silence must never be the
 * result of guessing, and a stale generation must never touch a newer player's soundtrack.
 */
class VideoAudioHandoverTest {

    @Test
    fun `a first frame alone does not take the soundtrack over`() {
        val handover = VideoAudioHandover()
        handover.onVideoLoading(1)

        assertTrue(handover.onFirstFrame(1))
        assertFalse("a frame is not proof that the media carries audio", handover.onMediaAudioOwns(1, false))
        assertFalse(handover.isAudioSuspended)
        assertNull(handover.owner)
    }

    @Test
    fun `media audio takes over exactly once per generation`() {
        val handover = VideoAudioHandover()
        handover.onVideoLoading(4)
        handover.onFirstFrame(4)

        assertTrue(handover.onMediaAudioOwns(4, true))
        assertTrue(handover.isAudioSuspended)
        assertEquals(4L, handover.owner)
        assertFalse("a repeated decision must not act twice", handover.onMediaAudioOwns(4, true))
    }

    @Test
    fun `a stale generation can neither take over nor be taken over`() {
        val handover = VideoAudioHandover()
        handover.onVideoLoading(7)

        assertFalse("an old player's first frame is not this generation's", handover.onFirstFrame(6))
        assertFalse(handover.onMediaAudioOwns(6, true))
        assertFalse(handover.isAudioSuspended)
    }

    @Test
    fun `a new video item restores the output until it proves it carries audio`() {
        val handover = VideoAudioHandover()
        handover.onVideoLoading(1)
        handover.onFirstFrame(1)
        assertTrue(handover.onMediaAudioOwns(1, true))

        assertTrue(
            "the next item may have no audio of its own, so the AirPlay output is restored",
            handover.onVideoLoading(2)
        )
        assertFalse(handover.isAudioSuspended)
        assertNull(handover.owner)

        assertFalse(handover.onMediaAudioOwns(2, false))
        assertTrue(handover.onMediaAudioOwns(2, true))
    }

    @Test
    fun `a video that ends after taking over is reported so audio is not restarted`() {
        val handover = VideoAudioHandover()
        handover.onVideoLoading(3)
        handover.onFirstFrame(3)
        handover.onMediaAudioOwns(3, true)

        assertTrue(handover.onVideoEnded(3))
        assertTrue("the suspension is deliberate and must survive the session", handover.isAudioSuspended)
        assertFalse("a stale end must not claim a takeover it never had", handover.onVideoEnded(2))
    }

    @Test
    fun `a video that failed before any takeover leaves the AirPlay audio alone`() {
        val handover = VideoAudioHandover()
        handover.onVideoLoading(5)
        handover.onFirstFrame(5)
        handover.onMediaAudioOwns(5, false)

        assertFalse("no takeover happened, so nothing must be un-suspended", handover.onVideoEnded(5))
        assertFalse(handover.isAudioSuspended)
    }

    @Test
    fun `a replaced session owns nothing`() {
        val handover = VideoAudioHandover()
        handover.onVideoLoading(9)
        handover.onFirstFrame(9)
        handover.onMediaAudioOwns(9, true)

        handover.onSessionReplaced()

        assertFalse(handover.isAudioSuspended)
        assertNull(handover.owner)
        assertFalse(handover.isCurrent(9))
    }

    @Test
    fun `the same generation replayed does not restore the output`() {
        val handover = VideoAudioHandover()
        handover.onVideoLoading(1)
        handover.onFirstFrame(1)
        handover.onMediaAudioOwns(1, true)

        assertFalse(
            "a second /play of the same generation must not unmute the stream it replaced",
            handover.onVideoLoading(1)
        )
        assertTrue(handover.isAudioSuspended)
    }
}
