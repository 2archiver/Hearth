package com.phairplay.airplay

/** Classifies protocol/player errors into a URL-free stage for service state and the Home card. */
internal object AirPlayPlaybackFailures {
    fun classify(reason: String): AirPlayPlaybackFailure {
        val prefix = reason.substringBefore(':', missingDelimiterValue = "")
        val detail = reason.substringAfter(':', missingDelimiterValue = reason).trim()
        val explicitStage = when (prefix) {
            "audio-setup" -> AirPlayPlaybackFailureStage.AUDIO_SETUP
            "play-negotiation" -> AirPlayPlaybackFailureStage.PLAY_NEGOTIATION
            "manifest" -> AirPlayPlaybackFailureStage.MANIFEST
            "player-setup" -> AirPlayPlaybackFailureStage.PLAYER_SETUP
            "player-preparation" -> AirPlayPlaybackFailureStage.PLAYER_PREPARATION
            "first-frame" -> AirPlayPlaybackFailureStage.FIRST_FRAME
            "seek" -> AirPlayPlaybackFailureStage.SEEK
            "network" -> AirPlayPlaybackFailureStage.NETWORK
            "decoder" -> AirPlayPlaybackFailureStage.DECODER
            "drm" -> AirPlayPlaybackFailureStage.DRM
            "playback" -> AirPlayPlaybackFailureStage.PLAYBACK
            else -> null
        }
        val (stage, safeDetail) = if (explicitStage != null) {
            explicitStage to when (explicitStage) {
                AirPlayPlaybackFailureStage.PLAY_NEGOTIATION -> safeNegotiationFailure(detail)
                AirPlayPlaybackFailureStage.MANIFEST -> safeManifestFailure(detail)
                AirPlayPlaybackFailureStage.PLAYER_SETUP -> "video player could not be started"
                AirPlayPlaybackFailureStage.AUDIO_SETUP -> "AirPlay audio setup failed"
                AirPlayPlaybackFailureStage.PLAYER_PREPARATION -> "player did not become ready"
                AirPlayPlaybackFailureStage.FIRST_FRAME -> "no video frame reached the display"
                AirPlayPlaybackFailureStage.SEEK -> "seek failed"
                AirPlayPlaybackFailureStage.NETWORK -> "media network request failed"
                AirPlayPlaybackFailureStage.DECODER -> "media decoder could not render the stream"
                AirPlayPlaybackFailureStage.DRM -> "protected media is not supported"
                AirPlayPlaybackFailureStage.PLAYBACK -> "media playback failed"
                AirPlayPlaybackFailureStage.UNKNOWN -> "media playback failed"
            }
        } else {
            when (reason) {
                "prepare/surface timeout" -> AirPlayPlaybackFailureStage.PLAYER_PREPARATION to
                    "player did not become ready or acquire a valid surface"
                "first frame timeout" -> AirPlayPlaybackFailureStage.FIRST_FRAME to
                    "player was prepared, but no video frame reached the display"
                "setup" -> AirPlayPlaybackFailureStage.PLAYER_SETUP to "video player could not be started"
                "playback state" -> AirPlayPlaybackFailureStage.PLAYBACK to "media playback failed"
                "seek", "initial seek" -> AirPlayPlaybackFailureStage.SEEK to "seek failed"
                else -> AirPlayPlaybackFailureStage.PLAYBACK to
                    if (reason.startsWith("decoder/network error")) "media decoder or network error"
                    else "media playback failed"
            }
        }
        return AirPlayPlaybackFailure(stage, safeDetail.take(MAX_DETAIL_CHARS))
    }

    private fun safeNegotiationFailure(reason: String): String = when {
        reason.contains("reverse channel", ignoreCase = true) || reason.contains("PTTH", ignoreCase = true) ->
            "sender reverse channel unavailable"
        reason.contains("session", ignoreCase = true) -> "AirPlay session was not accepted"
        else -> "sender-mediated /play was rejected"
    }

    private fun safeManifestFailure(reason: String): String = when {
        reason.contains("status", ignoreCase = true) -> "sender refused the playlist request"
        reason.contains("timeout", ignoreCase = true) || reason.contains("answer", ignoreCase = true) ->
            "sender did not return the playlist"
        reason.contains("closed", ignoreCase = true) -> "sender channel closed during playlist loading"
        else -> "master playlist could not be loaded through the sender"
    }

    private const val MAX_DETAIL_CHARS = 120
}
