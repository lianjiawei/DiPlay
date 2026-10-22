package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class ExistingWifiSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var activity: DiPlayActivity

    @Before fun setup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    @Test fun modeAndCredentialsSurviveSwitchesWithoutOverwritingCarHotspot() {
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "car-password")
        AirPlayPersistence.saveExistingWifiCredentials(app, "Pocket", "pocket-password")
        for (mode in listOf(WirelessHotspotMode.EXISTING_WIFI, WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.MANUAL)) {
            AirPlayPersistence.saveWirelessHotspotMode(app, mode)
            assertEquals(mode, AirPlayPersistence.loadWirelessHotspotMode(app))
            assertEquals("Pocket", AirPlayPersistence.loadExistingWifiSsid(app))
            assertEquals("pocket-password", AirPlayPersistence.loadExistingWifiPassphrase(app))
            assertEquals("Car hotspot", AirPlayPersistence.loadManualHotspotSsid(app))
            assertEquals("car-password", AirPlayPersistence.loadManualHotspotPassphrase(app))
        }
        CarHotspotSettings.setEnabled(app, true)
        assertFalse(CarHotspotSettings.shouldEnable(app, true, WirelessHotspotMode.EXISTING_WIFI))
    }

    @Test fun selectingLanNeedsNoCredentialsAndDetailsHaveNoPasswordInput() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        controls().filterIsInstance<Button>().first { it.text.contains("Same LAN") }.performClick()
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertEquals("", AirPlayPersistence.loadExistingWifiSsid(app))
        controls().filterIsInstance<Button>().first { it.text.startsWith("Choose system Wi-Fi") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val fields = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().toList()
        assertEquals(1, fields.size) // Optional SSID fallback only; no password is requested.
        fields.single().setText(" Pocket ")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(" Pocket ", AirPlayPersistence.loadExistingWifiSsid(app))
        assertEquals("", AirPlayPersistence.loadExistingWifiPassphrase(app))
    }

    @Test fun hostRuntimeReceivesLanCredentialsAndValidationIsLimitedToWirelessLan() {
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(app, "Pocket", "pocket-password")
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        CarPlayHostActivity::class.java.getDeclaredField("airPlayIdentity").apply { isAccessible = true }
            .set(host, AirPlayPersistence.loadIdentity(app))
        CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        fun runtime() = CarPlayHostActivity::class.java.getDeclaredMethod("createRuntimeConfig")
            .apply { isAccessible = true }.invoke(host) as CarPlayRuntimeConfig
        assertEquals("Pocket", runtime().existingWifiSsid)
        assertEquals("", runtime().existingWifiPassphrase) // Historical credentials must not enter the runtime.
        assertEquals(CarPlayTransport.WIRELESS, runtime().transport)
        val permissions = CarPlayHostActivity::class.java.getDeclaredMethod("requiredWirelessPermissions")
            .apply { isAccessible = true }.invoke(host) as List<*>
        assertTrue(permissions.isEmpty()) // Manual SSID input must work without fine location on API 29.
        AirPlayPersistence.saveExistingWifiCredentials(app, "", "")
        AirPlayPersistence.saveWirelessEnabled(app, false)
        CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        assertEquals(CarPlayTransport.WIRED, runtime().transport)
    }

    private fun controls(): List<View> {
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("wirelessLinkControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        activity.setContentView(parent)
        return descendants(parent).toList()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
