package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager

/** A system network name, never a source of saved Wi-Fi passwords. */
data class ExistingWifiNetwork(
    val ssid: String,
    val connected: Boolean = false,
    val saved: Boolean = false,
    val networkId: Int? = null,
    val security: Security = Security.UNKNOWN,
) {
    enum class Security { UNKNOWN, OPEN, WPA2_PERSONAL, UNSUPPORTED }
}

/** Reads system snapshots without scanning, joining a network or changing the active CarPlay link. */
object ExistingWifiNetworks {
    @Suppress("DEPRECATION")
    fun read(context: Context): List<ExistingWifiNetwork> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
        val station = runCatching { wifi.connectionInfo }.getOrNull()
        val current = station?.takeIf { it.networkId >= 0 }?.ssid?.let(::stationSsid)
        // Android 10+ can hide saved networks from ordinary apps. Current/visible names still help.
        val saved = runCatching { wifi.configuredNetworks.orEmpty() }.getOrDefault(emptyList())
        val scanned = runCatching { wifi.scanResults.orEmpty() }.getOrDefault(emptyList())
        val names = linkedSetOf<String>()
        current?.let(names::add)
        saved.mapNotNull { stationSsid(it.SSID) }.forEach(names::add)
        scanned.mapNotNull { visibleSsid(it.SSID) }.forEach(names::add)
        return names.map { name ->
            val configuration = saved.firstOrNull { stationSsid(it.SSID) == name }
            val scan = scanned.filter { visibleSsid(it.SSID) == name }.maxByOrNull { it.level }
            val capabilities = scan?.capabilities.orEmpty().uppercase(java.util.Locale.ROOT)
            val security = when {
                "EAP" in capabilities || "WEP" in capabilities -> ExistingWifiNetwork.Security.UNSUPPORTED
                "PSK" in capabilities -> ExistingWifiNetwork.Security.WPA2_PERSONAL
                "SAE" in capabilities || "OWE" in capabilities || "WAPI" in capabilities -> ExistingWifiNetwork.Security.UNSUPPORTED
                scan != null && capabilities.isNotEmpty() -> ExistingWifiNetwork.Security.OPEN
                configuration?.allowedKeyManagement?.get(WifiConfiguration.KeyMgmt.WPA_PSK) == true -> ExistingWifiNetwork.Security.WPA2_PERSONAL
                configuration?.allowedKeyManagement?.get(WifiConfiguration.KeyMgmt.NONE) == true &&
                    configuration.wepKeys?.any { !it.isNullOrEmpty() } != true -> ExistingWifiNetwork.Security.OPEN
                else -> ExistingWifiNetwork.Security.UNKNOWN
            }
            ExistingWifiNetwork(name, name == current, configuration != null,
                configuration?.networkId?.takeIf { it >= 0 }, security)
        }.sortedWith(compareByDescending<ExistingWifiNetwork> { it.connected }
            .thenByDescending { it.saved }.thenBy { it.ssid })
    }

    private fun stationSsid(value: String?): String? = visibleSsid(value?.removeSurrounding("\""))

    // Spaces and literal quotes in a scanned SSID belong to the name.
    private fun visibleSsid(value: String?): String? = value?.takeUnless {
        it.isEmpty() || it == WifiManager.UNKNOWN_SSID || '\u0000' in it
    }

}
