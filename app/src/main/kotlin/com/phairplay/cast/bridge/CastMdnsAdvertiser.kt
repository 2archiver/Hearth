package com.phairplay.cast.bridge

import android.content.Context
import android.content.SharedPreferences
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.phairplay.util.Logger
import java.util.UUID

/**
 * CastMdnsAdvertiser — publishes `_googlecast._tcp`, the service type every Cast sender
 * (iPhone, Android, Chrome) browses for when you tap the Cast button.
 *
 * Without this record the sender never learns we exist, which is why PhairPlay's Cast card
 * could only ever report an error before: Google's Cast Connect SDK only advertises once a
 * Google-issued Application ID is configured. This advertises directly, so the built-in
 * bridge is discoverable with no registration at all.
 *
 * TXT records mirror what a real Chromecast publishes — senders read `ve` (protocol
 * version), `md` (model), `ca` (capabilities: 1 = video out, 4 = audio out) and `fn`
 * (friendly name) before deciding to show the device.
 */
internal class CastMdnsAdvertiser(
    private val context: Context,
    private val onRegistered: (String) -> Unit = {},
    private val onError: (Int) -> Unit = {}
) {

    private val nsdManager: NsdManager? =
        runCatching { context.getSystemService(Context.NSD_SERVICE) as NsdManager }.getOrNull()

    private var listener: NsdManager.RegistrationListener? = null

    /** True while the service is registered. */
    @Volatile var isRegistered = false
        private set

    /** Stable 16-byte device id, as the 32 hex characters `id` expects. */
    private val deviceId: String by lazy { loadOrCreateDeviceId() }

    fun start(serviceName: String, port: Int = CastV2Server.DEFAULT_PORT): Boolean {
        val manager = nsdManager
        if (manager == null) {
            Logger.e("Cast: NsdManager unavailable — cannot advertise Cast")
            return false
        }
        stop()

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            this.port = port
            setAttributeSafe("id", deviceId)
            setAttributeSafe("cd", certificateDigest(deviceId))
            setAttributeSafe("rm", MODEL_REVISION)
            setAttributeSafe("ve", PROTOCOL_VERSION)
            setAttributeSafe("md", MODEL)
            setAttributeSafe("ic", ICON_PATH)
            setAttributeSafe("fn", serviceName)
            setAttributeSafe("ca", CAPABILITIES.toString())
            setAttributeSafe("st", "0")
            setAttributeSafe("bs", buildId(deviceId))
            setAttributeSafe("nf", "1")
            setAttributeSafe("rs", "")
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                isRegistered = true
                Logger.i("Cast: advertised '${serviceInfo.serviceName}' over $SERVICE_TYPE")
                onRegistered(serviceInfo.serviceName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    isRegistered = true
                    onRegistered(serviceInfo.serviceName)
                    return
                }
                isRegistered = false
                Logger.e("Cast: mDNS advertisement failed, errorCode=$errorCode")
                onError(errorCode)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                isRegistered = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Logger.w("Cast: mDNS unregistration failed (non-fatal), errorCode=$errorCode")
                isRegistered = false
            }
        }

        listener = registration
        return try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            true
        } catch (e: Exception) {
            Logger.e("Cast: could not register $SERVICE_TYPE", e)
            listener = null
            false
        }
    }

    fun stop() {
        listener?.let { l ->
            try {
                nsdManager?.unregisterService(l)
            } catch (e: Exception) {
                Logger.w("Cast: mDNS unregistration error (non-fatal): ${e.message}")
            }
        }
        listener = null
        isRegistered = false
    }

    /**
     * NsdServiceInfo rejects some values (over-long, or empty on some Android builds) with
     * an IllegalArgumentException, and one bad record must not stop the whole advertisement.
     */
    private fun NsdServiceInfo.setAttributeSafe(key: String, value: String) {
        try {
            setAttribute(key, value)
        } catch (e: Exception) {
            Logger.d("Cast: skipping mDNS TXT record $key (${e.message})")
        }
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadOrCreateDeviceId(): String {
        val prefs = prefs()
        val stored = prefs.getString(KEY_DEVICE_ID, null)
        if (!stored.isNullOrBlank() && stored.length == 32) return stored
        val fresh = UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_DEVICE_ID, fresh).apply()
        return fresh
    }

    /** `cd` — senders do not verify it, but it must be 32 hex characters. */
    private fun certificateDigest(seed: String): String =
        seed.reversed().padEnd(32, '0').take(32)

    /** `bs` — 12 hex characters, e.g. FA8FCAF5B5A1. */
    private fun buildId(seed: String): String =
        seed.uppercase().take(12)

    companion object {
        const val SERVICE_TYPE = "_googlecast._tcp"

        private const val PREFS_NAME = "phairplay_cast"
        private const val KEY_DEVICE_ID = "cast_device_id"

        private const val MODEL = "Chromecast"
        private const val MODEL_REVISION = "Chromecast"
        private const val PROTOCOL_VERSION = "05"
        private const val ICON_PATH = "/setup/icon.png"

        /** 1 = video out, 4 = audio out. */
        private const val CAPABILITIES = 5
    }
}
