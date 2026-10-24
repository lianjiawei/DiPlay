package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test

class ExistingWifiConfigTest {
    private fun config(transport: CarPlayTransport, mode: WirelessHotspotMode,
        ssid: String = "", password: String = "") = CarPlayRuntimeConfig(
        mfiTarget = MfiTarget.LOCAL,
        identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test",
            manufacturer = "test", serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        transport = transport, wirelessHotspotMode = mode,
        existingWifiSsid = ssid, existingWifiPassphrase = password)

    @Test fun lanRequiresNameAndPasswordButUsbAndDirectIgnoreUnusedCredentials() {
        for ((name, password) in listOf("" to "", "Lian" to "", "Lian" to "short", "" to "12345678", "x".repeat(33) to "12345678")) {
            assertThrows(IllegalArgumentException::class.java) {
                config(CarPlayTransport.WIRELESS, WirelessHotspotMode.EXISTING_WIFI, name, password)
            }
            assertEquals(CarPlayTransport.WIRED, config(CarPlayTransport.WIRED, WirelessHotspotMode.EXISTING_WIFI, name, password).transport)
            assertEquals(WirelessHotspotMode.WIFI_P2P, config(CarPlayTransport.WIRELESS, WirelessHotspotMode.WIFI_P2P, name, password).wirelessHotspotMode)
        }
        assertEquals("router-password", config(CarPlayTransport.WIRELESS, WirelessHotspotMode.EXISTING_WIFI, "Lian", "router-password").existingWifiPassphrase)
    }
}
