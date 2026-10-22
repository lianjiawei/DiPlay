package com.shilapi.xcertplay

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.shilapi.xcertplay.network.ExistingWifiNetwork
import com.shilapi.xcertplay.network.ExistingWifiNetworks
import com.shilapi.xcertplay.network.SystemWifiConnection
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 27, 33], manifest = Config.NONE)
class SystemWifiConnectionTest {
    private val context = mock(Context::class.java)
    private val wifi = mock(WifiManager::class.java)

    @Before fun setup() {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(WifiManager::class.java)).thenReturn(wifi)
    }

    @Test fun currentAndSavedAndVisibleNamesAreMergedWithoutChangingTheNetwork() {
        val info = mock(WifiInfo::class.java)
        `when`(info.ssid).thenReturn("\" Current Wi-Fi \"")
        `when`(info.networkId).thenReturn(7)
        `when`(wifi.connectionInfo).thenReturn(info)
        `when`(wifi.configuredNetworks).thenReturn(listOf(WifiConfiguration().apply {
            SSID = "\"Saved\""; networkId = 3; allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }))
        `when`(wifi.scanResults).thenReturn(listOf(
            scan("Saved", "[WPA2-PSK-CCMP][ESS]"), scan("New", "[ESS]"), scan("New", "[ESS]"),
            scan("\"Literal quotes\"", "[ESS]"), scan("", "")))
        val choices = ExistingWifiNetworks.read(context)
        assertEquals(" Current Wi-Fi ", choices.first().ssid)
        assertTrue(choices.first().connected)
        assertEquals("Saved", choices[1].ssid)
        assertEquals(1, choices.count { it.ssid == "New" })
        assertTrue(choices.any { it.ssid == "\"Literal quotes\"" })
        assertEquals(ExistingWifiNetwork.Security.WPA2_PERSONAL, choices.single { it.ssid == "Saved" }.security)
        verify(wifi, never()).enableNetwork(anyInt(), anyBoolean())
        verify(wifi, never()).addNetwork(any())
    }

    @Test fun permissionRestrictionsLeaveManualSetupAvailable() {
        `when`(wifi.connectionInfo).thenThrow(SecurityException())
        `when`(wifi.configuredNetworks).thenThrow(SecurityException())
        `when`(wifi.scanResults).thenThrow(SecurityException())
        assertTrue(ExistingWifiNetworks.read(context).isEmpty())
    }

    @Test fun explicitPasswordConnectsTheHeadUnitOnLegacyAndroidOnly() {
        val network = ExistingWifiNetwork("Office \"Wi-Fi\"", security = ExistingWifiNetwork.Security.WPA2_PERSONAL)
        if (Build.VERSION.SDK_INT >= 29) {
            assertFalse(SystemWifiConnection.connect(context, network, "secret123"))
            verifyNoInteractions(wifi)
            return
        }
        `when`(wifi.isWifiEnabled).thenReturn(true)
        `when`(wifi.addNetwork(any())).thenReturn(8)
        `when`(wifi.enableNetwork(8, true)).thenReturn(true)
        `when`(wifi.reconnect()).thenReturn(true)
        assertTrue(SystemWifiConnection.connect(context, network, "secret123"))
        val argument = ArgumentCaptor.forClass(WifiConfiguration::class.java)
        verify(wifi).addNetwork(argument.capture())
        assertEquals("\"Office \\\"Wi-Fi\\\"\"", argument.value.SSID)
        assertEquals("\"secret123\"", argument.value.preSharedKey)
        assertTrue(argument.value.allowedKeyManagement[WifiConfiguration.KeyMgmt.WPA_PSK])
    }

    @Test fun savedNetworksUseSystemCredentialsAndFailedSwitchesDoNotReconnect() {
        if (Build.VERSION.SDK_INT >= 29) return
        val network = ExistingWifiNetwork("Saved", saved = true, networkId = 4)
        `when`(wifi.isWifiEnabled).thenReturn(true)
        assertFalse(SystemWifiConnection.connect(context, network, ""))
        verify(wifi).enableNetwork(4, true)
        verify(wifi, never()).addNetwork(any())
        verify(wifi, never()).reconnect()
    }

    private fun scan(name: String, flags: String) = ScanResult().apply { SSID = name; capabilities = flags }
}
