package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayPlaybackFailuresTest {

    @Test
    fun `manifest rejection is separate from sender play negotiation`() {
        val missingReverse = AirPlayPlaybackFailures.classify("play-negotiation:no reverse channel")
        val failedMaster = AirPlayPlaybackFailures.classify("manifest:sender reported fetch status 404")

        assertEquals(AirPlayPlaybackFailureStage.PLAY_NEGOTIATION, missingReverse.stage)
        assertEquals(AirPlayPlaybackFailureStage.MANIFEST, failedMaster.stage)
        assertTrue(failedMaster.detail.contains("playlist"))
    }

    @Test
    fun `prepared player is not reported as rendered video`() {
        val preparation = AirPlayPlaybackFailures.classify(
            "player-preparation:player did not become ready or acquire a valid surface"
        )
        val firstFrame = AirPlayPlaybackFailures.classify(
            "first-frame:player was prepared, but no video frame reached the display"
        )

        assertEquals(AirPlayPlaybackFailureStage.PLAYER_PREPARATION, preparation.stage)
        assertEquals(AirPlayPlaybackFailureStage.FIRST_FRAME, firstFrame.stage)
        assertTrue(firstFrame.detail.contains("prepared"))
        assertFalse(firstFrame.detail.contains("playing"))
    }

    @Test
    fun `Media3 network decoder and protection errors remain separate categories`() {
        assertEquals(
            AirPlayPlaybackFailureStage.NETWORK,
            AirPlayPlaybackFailures.classify("network:media network request failed").stage,
        )
        assertEquals(
            AirPlayPlaybackFailureStage.DECODER,
            AirPlayPlaybackFailures.classify("decoder:media decoder could not render the stream").stage,
        )
        assertEquals(
            AirPlayPlaybackFailureStage.DRM,
            AirPlayPlaybackFailures.classify("drm:protected media is not supported").stage,
        )
    }

    @Test
    fun `failure details never echo a signed URL or arbitrary player error text`() {
        val failure = AirPlayPlaybackFailures.classify(
            "manifest:https://cdn.example/master.m3u8?signature=secret"
        )

        assertEquals(AirPlayPlaybackFailureStage.MANIFEST, failure.stage)
        assertFalse(failure.detail.contains("cdn.example"))
        assertFalse(failure.detail.contains("secret"))
    }
}
