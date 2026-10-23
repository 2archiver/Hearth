package com.phairplay.airplay

import com.phairplay.airplay.handshake.PlistCodec

/** Standard reverse-HTTP status events; distinct from FCUP playlist requests. */
internal object VideoPlaybackEvent {
    fun stateName(state: AirPlayPlaybackState): String? = when (state) {
        AirPlayPlaybackState.LOADING -> "loading"
        AirPlayPlaybackState.PLAYING, AirPlayPlaybackState.AUDIO_ONLY -> "playing"
        AirPlayPlaybackState.PAUSED -> "paused"
        AirPlayPlaybackState.FAILED, AirPlayPlaybackState.STOPPED, AirPlayPlaybackState.DISCONNECTED -> "stopped"
        else -> null
    }

    fun frame(senderSessionId: String, state: String): ByteArray {
        require(state in setOf("loading", "playing", "paused", "stopped"))
        require(senderSessionId.isNotBlank() && senderSessionId.length <= 128 &&
            senderSessionId.all { it.code in 0x20..0x7e })
        val body = PlistCodec.encodeXml(mapOf("category" to "video", "sessionID" to 1L, "state" to state))
        return ("POST /event HTTP/1.1\r\n" +
            "X-Apple-Session-ID: $senderSessionId\r\n" +
            "Content-Type: application/x-apple-plist\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII) + body
    }
}
