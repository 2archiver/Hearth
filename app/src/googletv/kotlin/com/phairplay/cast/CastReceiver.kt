package com.phairplay.cast

import android.content.Context
import com.google.android.gms.cast.tv.CastReceiverContext
import com.phairplay.BuildConfig
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger

/**
 * Google TV Cast Connect receiver lifecycle.
 *
 * This class starts the official Cast Android TV receiver SDK. Cast Connect
 * still requires a registered Cast Application ID and sender-side Cast support,
 * and this is the real SDK entry point.
 */
class CastReceiver(
    private val context: Context,
    private val onStateChanged: (ProtocolState) -> Unit
) {
    private var started = false

    fun start() {
        if (!isConfigured()) {
            Logger.w("Google Cast is not configured: missing Cast application ID")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        if (!isAvailable(context)) {
            Logger.w("Google Cast not available on this device (missing Google Play Services)")
            // ERROR, not DISABLED: settings say Cast is on — the honest detail
            // ("Cast App ID not set or Play Services unavailable") explains why it can't run.
            onStateChanged(ProtocolState.ERROR)
            return
        }

        try {
            CastReceiverContext.initInstance(context.applicationContext)
            val receiverContext = CastReceiverContext.getInstance()
            receiverContext.start()
            started = true
            Logger.i("Cast Connect receiver started")
            onStateChanged(ProtocolState.ADVERTISING)
        } catch (e: Exception) {
            Logger.e("Failed to start Cast Connect receiver", e)
            onStateChanged(ProtocolState.ERROR)
        }
    }

    fun stop() {
        try {
            if (started) {
                CastReceiverContext.getInstance().stop()
                Logger.i("Cast Connect receiver stopped")
            }
        } catch (e: Exception) {
            Logger.e("Failed to stop Cast Connect receiver", e)
        } finally {
            started = false
            onStateChanged(ProtocolState.DISABLED)
        }
    }

    companion object {
        fun isAvailable(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo("com.google.android.gms", 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun isConfigured(appId: String = CAST_APP_ID): Boolean {
            val normalized = appId.trim()
            return normalized.isNotEmpty() &&
                normalized != "TODO_REGISTER_YOUR_CAST_APP_ID" &&
                normalized != "00000000"
        }

        const val CAST_APP_ID = BuildConfig.CAST_APP_ID
    }
}
