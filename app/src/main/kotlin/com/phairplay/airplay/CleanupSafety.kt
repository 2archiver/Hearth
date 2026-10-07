package com.phairplay.airplay

/** Runs independent teardown steps even when one component's cleanup throws. */
internal object CleanupSafety {
    fun runAll(
        steps: List<Pair<String, () -> Unit>>,
        onFailure: (label: String, error: Exception) -> Unit = { _, _ -> },
    ) {
        steps.forEach { (label, action) ->
            try {
                action()
            } catch (error: Exception) {
                onFailure(label, error)
            }
        }
    }
}
