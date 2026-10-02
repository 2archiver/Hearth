package com.phairplay.cast

import android.content.Context
import com.phairplay.BuildConfig
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger

/**
 * Fire TV Cast receiver implementation.
 *
 * Amazon Fire TV does not ship Google Play Services, so the official Google
 * Cast TV SDK cannot run on this flavor. The receiver reports ERROR (with the
 * honest "Cast App ID not set or Play Services unavailable" detail) instead of
 * pretending to advertise a protocol it cannot serve — or worse, showing the
 * old DISABLED/"Enable in Settings" hint when the toggle was already on.
 */
class CastReceiver(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val onStateChanged: (ProtocolState) -> Unit
) {
    fun start() {
        Logger.w("Google Cast is not available on Fire TV flavor (no Google Play Services)")
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
