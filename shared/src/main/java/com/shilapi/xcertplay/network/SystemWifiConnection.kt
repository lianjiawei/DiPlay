package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build

/** Explicit user-requested station switching on Android 7.1–9; newer systems own that UI. */
object SystemWifiConnection {
    @Suppress("DEPRECATION")
    fun connect(context: Context, network: ExistingWifiNetwork, password: String): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return false
        if (com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(network.ssid, "") != null) return false
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return false
        return runCatching {
            if (!wifi.isWifiEnabled) return false
            val networkId = if (network.networkId != null && password.isEmpty()) network.networkId else {
                val configuration = WifiConfiguration().apply {
                    SSID = quote(network.ssid)
                    when (network.security) {
                        ExistingWifiNetwork.Security.OPEN -> allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        ExistingWifiNetwork.Security.WPA2_PERSONAL, ExistingWifiNetwork.Security.UNKNOWN -> {
                            if (password.length !in 8..63) return false
                            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                            preSharedKey = quote(password)
                        }
                        ExistingWifiNetwork.Security.UNSUPPORTED -> return false
                    }
                }
                wifi.addNetwork(configuration).takeIf { it >= 0 } ?: return false
            }
            // This switches the station connection only after the user presses Connect.
            wifi.enableNetwork(networkId, true) && wifi.reconnect()
        }.getOrDefault(false)
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
