package com.phairplay.airplay

/**
 * Decides when a video session may take over the sound, and remembers that it did.
 *
 * WHY A CLASS: the rules here are the difference between "video with sound" and the two failure
 * modes users actually notice — *echo* (the media's audio plus a still-running AirPlay audio stream)
 * and *silence* (the AirPlay audio was silenced for a video that never played anything).
 *
 * The rules, in order:
 *  1. The AirPlay audio output is suspended **only** when the player reports that the media carries
 *     its own audio track. "Prepared", "connected" or "the transport is up" are not evidence that
 *     anything will be heard, and silencing a working audio stream for a video that then fails
 *     leaves the user with neither.
 *  2. Every decision belongs to a **generation** (one `/play`): a late callback from an old player
 *     must never silence the newer session's audio.
 *  3. A new video item **resets** the takeover: its soundtrack may be the AirPlay stream again, so
 *     the suspended output is restored (the caller unmutes it) until the new item proves otherwise.
 *  4. A takeover is **not undone** by a later video failure: audio that was suspended for a playing
 *     video is not the session's timeline any more, and restarting it from a stale position is how
 *     ghost playback happens. Only a new video item, or a new session, restores the AirPlay audio.
 *
 * Pure state, no media access: [AirPlayReceiver] acts on the answers, and this class is unit-tested
 * on its own.
 */
internal class VideoAudioHandover {

    private val lock = Any()

    /** Generation of the video session that is loading or playing; [NO_GENERATION] when idle. */
    private var videoGeneration: Long = NO_GENERATION

    /** Generation whose media audio owns the output (the AirPlay audio output is suspended). */
    private var takenOverGeneration: Long = NO_GENERATION

    /** True while the AirPlay audio output is suspended for [takenOverGeneration]. */
    var isAudioSuspended: Boolean = false
        private set

    /** The generation that owns the soundtrack, if any. */
    val owner: Long? get() = synchronized(lock) { takenOverGeneration.takeIf { it != NO_GENERATION } }

    /**
     * A video session is loading (a `/play` was accepted).
     *
     * @return true when a *previous* video's takeover must be undone: the caller restores the AirPlay
     *   audio output, because this new item has not yet proven that it carries its own audio
     */
    fun onVideoLoading(generation: Long): Boolean = synchronized(lock) {
        val wasSuspended = isAudioSuspended && generation != takenOverGeneration
        if (generation != videoGeneration) {
            videoGeneration = generation
            takenOverGeneration = NO_GENERATION
            isAudioSuspended = false
        }
        wasSuspended
    }

    /** True when [generation] is the video session this handover is currently tracking. */
    fun isCurrent(generation: Long): Boolean = synchronized(lock) { generation == videoGeneration }

    /** The first frame of [generation] was rendered. False for a stale or repeated callback. */
    fun onFirstFrame(generation: Long): Boolean = synchronized(lock) {
        generation == videoGeneration
    }

    /**
     * The player reported whether the media carries its own audio track.
     *
     * @return true when the caller must suspend the AirPlay audio output **now** — the media owns the
     *   soundtrack, for the current generation, and it has not already been suspended
     */
    fun onMediaAudioOwns(generation: Long, mediaOwnsAudio: Boolean): Boolean = synchronized(lock) {
        if (generation != videoGeneration) return false
        if (!mediaOwnsAudio) return false
        if (takenOverGeneration == generation) return false
        takenOverGeneration = generation
        isAudioSuspended = true
        true
    }

    /**
     * The video session of [generation] ended or failed.
     *
     * @return true when that generation had taken the soundtrack over, in which case the caller must
     *   **not** restart the AirPlay audio: the suspended output is deliberate and stale
     */
    fun onVideoEnded(generation: Long): Boolean = synchronized(lock) {
        if (generation == videoGeneration) videoGeneration = NO_GENERATION
        takenOverGeneration == generation
    }

    /** A new AirPlay session replaced the old one: nothing is owned by the previous generation. */
    fun onSessionReplaced() = synchronized(lock) {
        videoGeneration = NO_GENERATION
        takenOverGeneration = NO_GENERATION
        isAudioSuspended = false
    }

    private companion object {
        const val NO_GENERATION = -1L
    }
}
