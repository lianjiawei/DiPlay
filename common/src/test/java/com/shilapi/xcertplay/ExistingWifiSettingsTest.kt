package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import com.shilapi.xcertplay.host.R
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
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
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

    @Test fun selectingLanDefaultsToReuseAndHidesTheOptionalLegacyPassword() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        controls().filterIsInstance<Button>().first { it.text.contains("Same LAN") }.performClick()
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertEquals("", AirPlayPersistence.loadExistingWifiSsid(app))
        controls().filterIsInstance<Button>().first { it.text.startsWith("Choose system Wi-Fi") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val fields = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().filter { it.visibility == View.VISIBLE }.toList()
        assertEquals(1, fields.size) // Optional SSID fallback only; no password is requested.
        fields.single().setText(" Pocket ")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(" Pocket ", AirPlayPersistence.loadExistingWifiSsid(app))
        assertEquals("", AirPlayPersistence.loadExistingWifiPassphrase(app))
        assertFalse(AirPlayPersistence.loadExistingWifiLegacyHandshake(app))
    }

    @Test fun legacyDialogValidatesPasswordAndSavesOnlyOnExplicitSave() {
        AirPlayPersistence.saveExistingWifiCredentials(app, "Router", "")
        controls().filterIsInstance<Button>().first { it.text.contains("Choose system Wi-Fi") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val details = ShadowAlertDialog.getLatestAlertDialog()
        val fields = descendants(details.window!!.decorView).filterIsInstance<EditText>().toList()
        val name = fields.single { it.hint == app.getString(R.string.existing_wifi_ssid) }
        val password = fields.single { it.hint == app.getString(R.string.existing_wifi_password) }
        assertTrue("Passwords stay masked after setting single-line input", password.transformationMethod is android.text.method.PasswordTransformationMethod)
        descendants(details.window!!.decorView).filterIsInstance<Button>().single {
            it.text.startsWith(app.getString(R.string.settings_existing_wifi_handshake) + " · ")
        }.performClick()
        val choices = ShadowAlertDialog.getLatestAlertDialog()
        choices.listView.performItemClick(null, 1, 1)
        choices.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, password.visibility)
        assertFalse("Choosing an option stages the form only", AirPlayPersistence.loadExistingWifiLegacyHandshake(app))
        details.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(details.isShowing)
        assertFalse(AirPlayPersistence.loadExistingWifiLegacyHandshake(app))
        password.setText("router-password")
        name.setText("Different router")
        assertEquals("", password.text.toString())
        password.setText("different-password")
        details.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(AirPlayPersistence.loadExistingWifiLegacyHandshake(app))
        assertEquals("Different router", AirPlayPersistence.loadExistingWifiSsid(app))
        assertEquals("different-password", AirPlayPersistence.loadExistingWifiPassphrase(app))
    }

    @Test fun cancellingTheLegacyFormPreservesThePreviousChoiceAndPassword() {
        AirPlayPersistence.saveExistingWifiCredentials(app, "Router", "router-password", legacyHandshake = true)
        controls().filterIsInstance<Button>().first { it.text.contains("Choose system Wi-Fi") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single {
            it.hint == app.getString(R.string.existing_wifi_ssid)
        }.setText("Router")
        assertEquals("Re-selecting the same network keeps its staged password", "router-password",
            descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single {
                it.hint == app.getString(R.string.existing_wifi_password)
            }.text.toString())
        descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single {
            it.hint == app.getString(R.string.existing_wifi_password)
        }.setText("another-password")
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertTrue(AirPlayPersistence.loadExistingWifiLegacyHandshake(app))
        assertEquals("router-password", AirPlayPersistence.loadExistingWifiPassphrase(app))
    }

    @Test fun quickMenuStagesBothMethodsAndPersistsOnlyTheSelectedCredentialPolicy() {
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(app, "Router", "")
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        fun invoke(name: String): Any? = CarPlayHostActivity::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(host)
        invoke("loadPersistedSettings")
        val baseline = invoke("menuSettingsSignature")
        val section = invoke("buildHotspotModeSection") as ViewGroup
        assertEquals("Opening the form alone must not stage an edit", baseline, invoke("menuSettingsSignature"))
        val choices = descendants(section).filterIsInstance<RadioButton>()
        choices.single { it.text == app.getString(R.string.settings_existing_wifi_compatibility) }.performClick()
        assertFalse(AirPlayPersistence.loadExistingWifiLegacyHandshake(app))
        assertEquals(false, invoke("validateManualHotspotSettings"))
        val passwordLabel = descendants(section).filterIsInstance<TextView>().single {
            it.text == app.getString(R.string.existing_wifi_password)
        }
        val password = descendants(passwordLabel.parent as ViewGroup).filterIsInstance<EditText>().single()
        assertTrue(password.isEnabled)
        password.setText("router-password")
        assertEquals(true, invoke("validateManualHotspotSettings"))
        invoke("persistMenuSettings")
        assertTrue(AirPlayPersistence.loadExistingWifiLegacyHandshake(app))
        assertEquals("router-password", AirPlayPersistence.loadExistingWifiPassphrase(app))
        choices.single { it.text == app.getString(R.string.settings_existing_wifi_reuse) }.performClick()
        invoke("persistMenuSettings")
        assertFalse(AirPlayPersistence.loadExistingWifiLegacyHandshake(app))
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
        assertFalse(runtime().existingWifiLegacyHandshake)
        assertEquals(CarPlayTransport.WIRELESS, runtime().transport)
        val permissions = CarPlayHostActivity::class.java.getDeclaredMethod("requiredWirelessPermissions")
            .apply { isAccessible = true }.invoke(host) as List<*>
        assertTrue(permissions.isEmpty()) // Manual SSID input must work without fine location on API 29.
        AirPlayPersistence.saveExistingWifiCredentials(app, "Pocket", "pocket-password", legacyHandshake = true)
        CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        assertEquals("pocket-password", runtime().existingWifiPassphrase)
        assertTrue(runtime().existingWifiLegacyHandshake)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        assertEquals("", runtime().existingWifiPassphrase)
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
