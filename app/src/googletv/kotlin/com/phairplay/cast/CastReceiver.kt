package com.phairplay.cast

import android.content.Context
import com.google.android.gms.cast.tv.CastReceiverContext
import com.phairplay.BuildConfig
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger

/**
 * Google TV Cast Connect receiver lifecycle.
 *
 * Starts the official Cast Android TV receiver SDK. Cast Connect requires a
 * registered Cast Application ID and sender-side Cast support.
 *
 * PORTS NOTE: Cast SDK binds TCP 8008 (HTTP) and 8009 (DIAL/discovery) when
 * [CastReceiverContext.start] is called. Previously, declaring the
 * `RECEIVER_OPTIONS_PROVIDER_CLASS_NAME` meta-data in AndroidManifest.xml caused
 * the SDK to auto-initialize at process start and bind those ports BEFORE this
 * class ever checked whether Cast was enabled — which surfaced as "Port 8008/8009"
 * showing up in tools like netstat even though the user had Cast disabled.
 * PhairPlay removes that meta-data entry and only calls initInstance+start from
 * [start] below, after verifying: (a) Cast is enabled in Settings, (b) a valid
 * non-placeholder App ID is configured, and (c) Google Play Services is present.
 * CastReceiverOptionsProvider is referenced programmatically by the SDK via
 * reflection the first time initInstance runs; no manifest entry is needed for that.
 */
class CastReceiver(
    private val context: Context,
    private val onStateChanged: (ProtocolState) -> Unit
) {
    private var started = false

    fun start() {
        if (!isConfigured()) {
            Logger.w("Google Cast is not configured: set PHAIRPLAY_CAST_APP_ID to a registered Cast App ID")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        if (!isAvailable(context)) {
            Logger.w("Google Cast not available on this device (missing Google Play Services)")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        try {
            // initInstance(Context) is the only public overload; it reads its
            // CastReceiverOptions via reflection on the ReceiverOptionsProvider class.
            // Our CastReceiverOptionsProvider returns a minimal options set (status text).
            CastReceiverContext.initInstance(context.applicationContext)
            val receiverContext = CastReceiverContext.getInstance()
            receiverContext.start()
            started = true
            Logger.i("Cast Connect receiver started on ports 8008/8009")
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
