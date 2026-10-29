package com.phairplay.airplay.handshake

/** Keep sender volume independent of soundtrack ownership. */
internal class AudioOutputGate(initialVolumeDb: Float? = null) {
    @Volatile var suspended: Boolean = false
    @Volatile private var requestedGain = gain(initialVolumeDb ?: 0f)
    val effectiveGain: Float get() = if (suspended) 0f else requestedGain

    fun setVolume(db: Float) {
        if (db.isFinite()) requestedGain = gain(db)
    }

    private fun gain(db: Float): Float = when {
        !db.isFinite() -> 1f
        db <= -144f -> 0f
        else -> ((db + 30f) / 30f).coerceIn(0f, 1f)
    }
}
