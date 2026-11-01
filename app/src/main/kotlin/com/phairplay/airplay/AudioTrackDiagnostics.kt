package com.phairplay.airplay

import android.media.AudioTimestamp
import android.media.AudioTrack

/** Playback-thread-only, once-per-second observation. Never changes the audio route or samples. */
internal class AudioTrackDiagnostics(private val sampleRate: Int, private val channels: Int) {
    private val clock = AudioPlaybackClock(sampleRate)
    private val timestamp = AudioTimestamp()
    private var nextSampleNanos = 0L
    private var writtenBytes = 0L

    fun noteWrite(track: AudioTrack, bytesWritten: Int) {
        if (bytesWritten > 0) writtenBytes += bytesWritten
        val now = System.nanoTime()
        if (now < nextSampleNanos) return
        nextSampleNanos = now + 1_000_000_000L
        // Telemetry must never turn a vendor AudioTrack failure into a playback failure.
        runCatching {
            StreamStats.audioUnderruns = track.underrunCount
            StreamStats.audioOutputBufferFrames = track.bufferSizeInFrames
            StreamStats.audioOutputSampleRate = sampleRate
            val hasTimestamp = track.getTimestamp(timestamp)
            val stable = hasTimestamp && clock.observe(timestamp.framePosition, timestamp.nanoTime, System.nanoTime())
            if (!hasTimestamp) clock.reset()
            StreamStats.audioClockState = if (stable) "hardware timestamp" else "unavailable/settling"
            val played = if (stable) timestamp.framePosition else track.playbackHeadPosition.toLong() and 0xffff_ffffL
            val writtenFrames = writtenBytes / (channels * 2L)
            StreamStats.audioQueuedOutputFrames = if (played <= writtenFrames) writtenFrames - played else null
            // No RTP/video anchor is known. Do not turn a hardware timestamp into a fake sync claim.
        }.onFailure {
            StreamStats.audioClockState = "unavailable"
            StreamStats.audioQueuedOutputFrames = null
        }
    }
}
