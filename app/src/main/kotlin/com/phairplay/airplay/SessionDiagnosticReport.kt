package com.phairplay.airplay

/** User-facing local snapshot; no media URLs, credentials, or inferred failure causes. */
internal object SessionDiagnosticReport {
    fun create(build: String, media3: String, api: Int): String = buildString {
        appendLine("Hearth $build · Android $api · Media3 $media3")
        if (StreamStats.source.isBlank() && !StreamStats.audioActive && !StreamStats.videoDecoderReady) {
            append("No media session is active. Start a cast, then run this check again.")
            return@buildString
        }
        appendLine()
        appendLine("Video: ${StreamStats.videoRes.ifBlank { "not measured" }}")
        appendLine("Decoder: ${StreamStats.decoderName.ifBlank { "not measured" }}")
        appendLine(StreamStats.urlVideoSummary)
        appendLine("Mirror queue: ${StreamStats.videoQueue} frames · ${StreamStats.videoQueueAgeMs} ms oldest")
        appendLine("Dropped frames: ${StreamStats.videoFramesDropped} before decode · ${StreamStats.videoPresentationDropped} after decode")
        appendLine("Audio: ${if (StreamStats.audioActive) StreamStats.audioCodec.ifBlank { "active" } else "inactive"}")
        appendLine("Audio underruns: ${StreamStats.audioUnderruns ?: "not measured"}")
        appendLine("Audio clock: ${StreamStats.audioClockState}")
        appendLine("Audio/video sync: ${StreamStats.avSyncOffsetMs?.let { "${it} ms" } ?: "not measured"}")
        if (StreamStats.mirrorStableFpsEnabled && StreamStats.videoPresentationDropped > 0L) {
            appendLine()
            appendLine("Experimental pacing dropped late frames. Try Playback profile → Original for the next session and compare.")
        }
        if ((StreamStats.audioUnderruns ?: 0) > 0) {
            appendLine()
            appendLine("Audio ran out of samples during this session. Repeat the same clip with a steady network connection and compare the underrun count.")
        }
        appendLine()
        append("For a reported playback error, open its Activity log entry for the recorded stage. Copy includes redacted technical details.")
    }.trimEnd()
}
