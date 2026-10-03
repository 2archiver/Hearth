package com.phairplay.util

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import java.net.NetworkInterface
import java.util.UUID

/**
 * NetworkUtils — Helper functions for reading network interface information.
 *
 * WHY: The AirPlay protocol requires the receiver to advertise its MAC address
 * and device name via mDNS TXT records. This class provides clean, safe methods
 * to read this information from the Android system.
 *
 * HOW: All methods are static (on the companion object) — no instance needed.
 * Read the device name and MAC address once at startup and pass them to MdnsService.
 *
 * Example:
 *   val name = NetworkUtils.getDeviceName(context)   // "My Android TV"
 *   val mac  = NetworkUtils.getMacAddress()           // "aa:bb:cc:dd:ee:ff"
 *   val uuid = NetworkUtils.getPersistentUuid(context) // stable UUID per device
 */
object NetworkUtils {

    /**
     * Returns the user-visible device name as configured in Android settings.
     *
     * This is used as the last-resort AirPlay name when the user has not set one in
     * Settings, so it is important that it matches what the user set in their TV's settings.
     *
     * Sources tried in order:
     * 1. Settings.Global.DEVICE_NAME (Android 5+, most TVs)
     * 2. Settings.Secure.BLUETOOTH_NAME (Bluetooth device name, often same as device name)
     * 3. Fallback: [MdnsNames.DEFAULT_DISPLAY_NAME] ("Apple TV") if neither source is available
     *
     * SECURITY: The returned value is sanitized by [MdnsNames.sanitize] — mDNS service
     * names must not contain certain special characters, and a name over 63 UTF-8 bytes
     * is silently mangled by the mDNS responder.
     *
     * @param context Android context (needed to read system settings)
     * @return The sanitized device name, never null or empty.
     */
    fun getDeviceName(context: Context): String {
        val rawName = Settings.Global.getString(context.contentResolver, "device_name")
            ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            ?: MdnsNames.DEFAULT_DISPLAY_NAME

        return MdnsNames.sanitize(rawName)
    }

    /**
     * Returns the device's Wi-Fi or Ethernet MAC address.
     *
     * The MAC address is the AirPlay `deviceid` (mDNS TXT, `GET /info`) and the prefix of
     * the `_raop._tcp` service name. Senders — iOS especially — remember a receiver by it,
     * so it has to be *stable* across reboots, not merely valid.
     *
     * That is why the interfaces are ranked instead of taking the first one Java hands back:
     * `NetworkInterface.getNetworkInterfaces()` order is not guaranteed, and on a Google TV
     * with **Ethernet** plugged in it commonly lists `wlan0` first even when Wi-Fi is
     * disconnected. Picking that made the `deviceid` change between reboots, which shows up
     * as an iPhone that refuses to reconnect, offers to re-pair, or lists the same TV twice.
     *
     * Ranking: Ethernet (`eth*`) → Wi-Fi (`wlan*`) → anything else that is up. An interface
     * with no IPv4 address is skipped in the first pass, because a down interface still
     * reports a hardware address.
     *
     * NOTE: On Android 10+, direct MAC access is restricted. We use NetworkInterface
     * instead of WifiManager.getConnectionInfo() which is deprecated.
     *
     * @return MAC address in "aa:bb:cc:dd:ee:ff" format (lowercase, colon-separated).
     */
    fun getMacAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val candidates = interfaces.filter {
                !it.isLoopback && it.isUp && it.hardwareAddress != null && it.hardwareAddress.size == 6
            }
            val ranked = candidates.sortedByDescending { iface ->
                when {
                    iface.name.startsWith("eth", ignoreCase = true) -> 3
                    iface.name.startsWith("wlan", ignoreCase = true) -> 2
                    else -> 1
                }
            }
            val withAddress = ranked.firstOrNull { iface -> iface.hasIpv4Address() }
                ?: ranked.firstOrNull()
            withAddress?.hardwareAddress
                ?.joinToString(":") { byte -> "%02x".format(byte) }
                ?: FALLBACK_MAC_ADDRESS
        } catch (e: Exception) {
            FALLBACK_MAC_ADDRESS
        }
    }

    /**
     * Returns this device's IPv4 address, or null when it has none.
     *
     * Used for the address shown under the AirPlay card — "Advertising on Ethernet ·
     * 192.168.1.42" is what tells a wired TV's owner that the advertisement really is on the
     * network the iPhone is joined to — and for the Home screen's network summary. Ethernet is
     * preferred over Wi-Fi for the same stability reason as [getMacAddress].
     */
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val ranked = interfaces
                .filter { !it.isLoopback && it.isUp }
                .sortedByDescending { iface ->
                    when {
                        iface.name.startsWith("eth", ignoreCase = true) -> 3
                        iface.name.startsWith("wlan", ignoreCase = true) -> 2
                        else -> 1
                    }
                }
            for (iface in ranked) {
                val address = iface.inetAddresses?.toList().orEmpty()
                    .firstOrNull { addr ->
                        !addr.isLoopbackAddress &&
                            !addr.isLinkLocalAddress &&
                            addr is java.net.Inet4Address
                    }
                if (address != null) return address.hostAddress
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A description of the network Hearth is currently advertising on.
     *
     * WHY: "my iPhone cannot see the TV" is almost always a network question — the phone is
     * on Wi-Fi while the TV is wired, or the two are on different subnets, or multicast does
     * not cross the router. Naming the interface and address here turns that into something
     * the user can check instead of a mystery.
     */
    data class NetworkSummary(
        /** Interface name, e.g. `eth0`, `wlan0`. */
        val interfaceName: String?,
        /** IPv4 address, e.g. `192.168.1.42`. */
        val ipAddress: String?,
        /** Wi-Fi SSID when the active interface is Wi-Fi and the SSID is readable. */
        val ssid: String?,
        /** True when the interface looks like wired Ethernet. */
        val isEthernet: Boolean,
        /** True when the interface looks like Wi-Fi. */
        val isWifi: Boolean
    ) {
        /** Short human label, e.g. `Ethernet · 192.168.1.42` or `Wi-Fi "Home" · 192.168.1.7`. */
        fun label(): String {
            val medium = when {
                isEthernet -> "Ethernet"
                isWifi -> if (!ssid.isNullOrBlank()) "Wi-Fi \"$ssid\"" else "Wi-Fi"
                else -> interfaceName ?: "Unknown network"
            }
            return if (ipAddress.isNullOrBlank()) medium else "$medium · $ipAddress"
        }
    }

    /**
     * Summarises the network Hearth is on, for the Home screen and for diagnosing
     * discovery problems on wired (Ethernet) Google TVs.
     *
     * Never throws — if the platform refuses to answer, the summary is simply empty and the
     * UI shows nothing rather than crashing the Home screen.
     */
    @Suppress("DEPRECATION")   // WifiManager.getConnectionInfo() — no non-deprecated equivalent
    fun getNetworkSummary(context: Context): NetworkSummary {
        val ip = getLocalIpAddress()
        var interfaceName: String? = null
        var ssid: String? = null

        // Which interface owns the address we advertise?
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val match = interfaces.firstOrNull { iface ->
                !iface.isLoopback && iface.isUp &&
                    iface.inetAddresses?.toList().orEmpty().any { it.hostAddress == ip }
            }
            interfaceName = match?.name
        } catch (e: Exception) {
            // leave interfaceName null
        }

        // Wi-Fi SSID needs WifiManager; it throws SecurityException on some builds and is
        // unavailable without location on Android 9+, so never let it break the summary.
        val wifiManager = try {
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        } catch (e: Exception) {
            null
        }
        try {
            val raw = wifiManager?.connectionInfo?.ssid?.trim('"')
            if (!raw.isNullOrBlank() && raw != UNKNOWN_SSID) {
                ssid = raw
            }
        } catch (e: Exception) {
            // SSID simply unavailable (missing location permission, or Wi-Fi off)
        }

        val isEthernet = interfaceName?.startsWith("eth", ignoreCase = true) == true
        val isWifi = interfaceName?.startsWith("wlan", ignoreCase = true) == true ||
            (!isEthernet && ssid != null)
        return NetworkSummary(
            interfaceName = interfaceName,
            ipAddress = ip,
            ssid = if (isWifi) ssid else null,
            isEthernet = isEthernet,
            isWifi = isWifi
        )
    }

    private fun NetworkInterface.hasIpv4Address(): Boolean =
        inetAddresses?.toList().orEmpty().any { addr ->
            !addr.isLoopbackAddress && !addr.isLinkLocalAddress && addr is java.net.Inet4Address
        }

    /**
     * Returns a stable, device-specific UUID for use in AirPlay's `pi` TXT record.
     *
     * WHY: macOS uses the `pi` (persistent identifier) to recognize a receiver
     * across app restarts. If we generate a new UUID every time, macOS may show
     * duplicate entries in the AirPlay menu.
     *
     * This UUID is generated once and stored in Android's secure settings,
     * so it persists across app restarts and even reinstalls (as long as the
     * app's data is not cleared).
     *
     * SECURITY: This UUID is not a secret — it's transmitted in plaintext via mDNS.
     * It does not contain any sensitive device information.
     *
     * @param context Android context (needed to read/write secure settings)
     * @return A stable UUID string in standard "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" format.
     */
    fun getPersistentUuid(context: Context): String {
        // Persist the UUID in the app's own private SharedPreferences.
        // (Originally this used Settings.Secure, which requires the privileged
        // WRITE_SECURE_SETTINGS permission and threw a SecurityException on normal
        // installs, aborting AirPlay receiver startup. App-private storage needs no
        // permission and still persists across restarts.)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedUuid = prefs.getString(PREF_KEY_DEVICE_UUID, null)
        if (!storedUuid.isNullOrBlank()) {
            return storedUuid
        }

        // Generate a new UUID and store it for future use
        val newUuid = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_KEY_DEVICE_UUID, newUuid).apply()
        return newUuid
    }

    // Constants
    // NOTE: the device-name fallback lives in MdnsNames.DEFAULT_DISPLAY_NAME so that every
    // place that needs "the name we show when nothing is configured" agrees on one value.
    private const val FALLBACK_MAC_ADDRESS = "aa:bb:cc:dd:ee:ff"
    /** What Android reports instead of an SSID when it is not allowed to know it. */
    private const val UNKNOWN_SSID = "<unknown ssid>"
    private const val PREFS_NAME = "phairplay_prefs"
    private const val PREF_KEY_DEVICE_UUID = "phairplay_device_uuid"
}
