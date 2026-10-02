package com.phairplay.cast

import android.content.Context
import com.phairplay.BuildConfig
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger

/**
 * JVM test-runner stand-in for the Google TV CastReceiver (app/src/googletv/).
 *
 * The real implementation needs the Google Cast TV SDK (an AAR), which the
 * AGP-free test-runner cannot load. This stub keeps the same public surface
 * (notably isConfigured) so CastReceiverTest runs on the plain JVM.
 */
class CastReceiver(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val onStateChanged: (ProtocolState) -> Unit
) {
    fun start() {
        Logger.w("Google Cast is not available in the JVM test-runner stub")
        onStateChanged(ProtocolState.ERROR)
    }

    fun stop() {
        onStateChanged(ProtocolState.DISABLED)
    }

    companion object {
        fun isAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

        fun isConfigured(appId: String = CAST_APP_ID): Boolean {
            val normalized = appId.trim()
            return normalized.isNotEmpty() &&
                normalized != "TODO_REGISTER_YOUR_CAST_APP_ID" &&
                normalized != "00000000"
        }

        const val CAST_APP_ID = BuildConfig.CAST_APP_ID
    }
}
