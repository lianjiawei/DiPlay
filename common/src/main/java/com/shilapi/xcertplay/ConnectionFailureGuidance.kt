package com.shilapi.xcertplay

import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.BluetoothRadioCheck
import java.util.Locale

/** Suggested user action, never a diagnosis inferred from an arbitrary exception message. */
internal object ConnectionFailureGuidance {
    /** A single next step on the waiting screen; the full explanation stays in Connection help. */
    @StringRes
    fun summary(failure: CarPlayStatus.Failed, wireless: Boolean, mode: WirelessHotspotMode): Int {
        if (failure.wifiResetRequired) return R.string.ui_connection_hint_reset_wifi
        val error = failure.message.lowercase(Locale.ROOT)
        if (wireless && failure.startupFailure != WirelessStartupFailure.BLUETOOTH_UNUSABLE &&
            "turn on wi-fi in the head unit" in error) return R.string.ui_connection_wifi_off
        if (wireless && failure.startupFailure == null &&
            BluetoothRadioCheck.RADIO_OFF_MARK.lowercase(Locale.ROOT) in error) return R.string.ui_connection_bluetooth_off
        if (!wireless && isIphoneUsbPermission(error)) return R.string.ui_connection_usb_permission
        if (!wireless && "usb re-enumeration timed out" in error) return R.string.ui_connection_usb_transition_timeout
        if (failure.startupFailure == WirelessStartupFailure.FIRST_TCP_TIMEOUT) return when (mode) {
            WirelessHotspotMode.EXISTING_WIFI -> R.string.ui_connection_timeout_same_wifi
            WirelessHotspotMode.MANUAL -> R.string.ui_connection_timeout_hotspot
            else -> R.string.ui_connection_timeout_wireless
        }
        return when (message(failure, wireless, mode)) {
            R.string.connection_help_reset_wifi -> R.string.ui_connection_hint_reset_wifi
            R.string.connection_help_permissions -> R.string.ui_connection_hint_permissions
            R.string.connection_help_usb -> R.string.ui_connection_hint_usb
            R.string.connection_help_existing_wifi -> R.string.ui_connection_hint_same_wifi
            R.string.connection_help_bluetooth -> R.string.ui_connection_hint_wireless
            R.string.connection_help_bluetooth_unusable -> R.string.ui_connection_hint_usb_fallback
            R.string.connection_help_hotspot, R.string.connection_help_wifi_direct,
            R.string.connection_help_iphone_wifi -> R.string.ui_connection_hint_network
            else -> R.string.ui_connection_hint_retry
        }
    }

    private fun isIphoneUsbPermission(error: String): Boolean =
        "iphone usb permission was denied" in error || "iphone usb permission was not granted" in error

    /** Clear a specific old failure only after the connection reaches evidence of recovery. */
    fun recovered(failure: CarPlayStatus.Failed, wireless: Boolean, status: CarPlayStatus): Boolean {
        val error = failure.message.lowercase(Locale.ROOT)
        return when {
            failure.wifiResetRequired -> false
            wireless && "turn on wi-fi in the head unit" in error -> status is CarPlayStatus.HotspotReady
            wireless && failure.startupFailure == null && BluetoothRadioCheck.RADIO_OFF_MARK.lowercase(Locale.ROOT) in error ->
                status == CarPlayStatus.RunningWireless || status == CarPlayStatus.WirelessActive
            !wireless && isIphoneUsbPermission(error) -> status == CarPlayStatus.SelectingConfiguration ||
                status == CarPlayStatus.OpeningDataPaths || status == CarPlayStatus.Pairing || status == CarPlayStatus.RunningControl
            else -> false
        }
    }

    @StringRes
    fun stage(status: CarPlayStatus, wireless: Boolean): Int? = if (wireless) when (status) {
        CarPlayStatus.ConnectingBluetooth -> R.string.ui_connection_stage_bluetooth
        else -> null
    } else when (status) {
        CarPlayStatus.RequestingIphonePermission -> R.string.ui_connection_stage_usb_permission
        CarPlayStatus.WaitingForReenumeration -> R.string.ui_connection_stage_usb_transition
        CarPlayStatus.OpeningDataPaths -> R.string.ui_connection_stage_usb_data
        CarPlayStatus.Pairing -> R.string.ui_connection_stage_pairing
        else -> null
    }

    @StringRes
    fun message(
        failure: CarPlayStatus.Failed,
        wireless: Boolean,
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
    ): Int {
        if (failure.wifiResetRequired) return R.string.connection_help_reset_wifi
        val error = failure.message.lowercase(Locale.ROOT)
        if (wireless && failure.startupFailure != WirelessStartupFailure.BLUETOOTH_UNUSABLE &&
            "turn on wi-fi in the head unit" in error) return R.string.ui_connection_wifi_off
        if (wireless && failure.startupFailure == null &&
            BluetoothRadioCheck.RADIO_OFF_MARK.lowercase(Locale.ROOT) in error) return R.string.ui_connection_bluetooth_off
        if (!wireless && isIphoneUsbPermission(error)) return R.string.ui_connection_usb_permission
        if (!wireless && "usb re-enumeration timed out" in error) return R.string.ui_connection_usb_transition_timeout
        when (failure.startupFailure) {
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> return R.string.connection_help_bluetooth_unusable
            WirelessStartupFailure.HOTSPOT_NOT_READY,
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> return network(mode)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> return iphoneWifi(mode)
            null -> Unit
        }

        // A permission Android itself refuses (as on MIUI 13, #671) is not one the driver can grant.
        if ("securityexception" in error || "lacks permission" in error) return R.string.connection_help_general
        if ("permission" in error || "denied" in error) return R.string.connection_help_permissions
        if (!wireless) {
            return if (listOf("usb", "ncm", "configuration", "iphone", "cable").any { it in error }) {
                R.string.connection_help_usb
            } else {
                R.string.connection_help_general
            }
        }
        if ("bluetooth" in error || "rfcomm" in error || "paired" in error) {
            return R.string.connection_help_bluetooth
        }
        if ("hotspot" in error || "access point" in error || "creategroup" in error) {
            return network(mode)
        }
        if ("wifi" in error || "wi-fi" in error || "tcp" in error || "airplay" in error) {
            return iphoneWifi(mode)
        }
        return R.string.connection_help_general
    }

    /** The network the iPhone joins: the car hotspot, a Wi-Fi Direct group, or a shared Wi-Fi. */
    @StringRes
    private fun network(mode: WirelessHotspotMode): Int = when (mode) {
        WirelessHotspotMode.MANUAL -> R.string.connection_help_hotspot
        WirelessHotspotMode.EXISTING_WIFI -> R.string.connection_help_existing_wifi
        WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> R.string.connection_help_wifi_direct
    }

    /** Only the car hotspot is a network the driver joins on the iPhone. */
    @StringRes
    private fun iphoneWifi(mode: WirelessHotspotMode): Int =
        if (mode == WirelessHotspotMode.MANUAL) R.string.connection_help_iphone_wifi else network(mode)
}
