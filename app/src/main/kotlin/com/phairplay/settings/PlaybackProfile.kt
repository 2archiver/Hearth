package com.phairplay.settings

/** Fixed receiver-side playout targets, not measured end-to-end latency. */
enum class PlaybackProfile(val bufferMillis: Long?) {
    // Keep the 3.6 immediate presentation path until physical-device acceptance passes.
    LEGACY(null),
    RESPONSIVE(30),
    BALANCED(60),
    STABLE(120);

    companion object {
        fun fromStored(value: String?): PlaybackProfile =
            entries.firstOrNull { it.name == value } ?: LEGACY
    }
}
