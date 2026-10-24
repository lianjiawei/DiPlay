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
import org.robolectric.util.ReflectionHelpers

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

    @Test fun selectingLanRequiresBothCredentialsWithoutAModeSelector() {
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        controls().filterIsInstance<Button>().first { it.text.contains("Same LAN") }.performClick()
        assertEquals(WirelessHotspotMode.EXISTING_WIFI, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertEquals("", AirPlayPersistence.loadExistingWifiSsid(app))
        controls().filterIsInstance<Button>().first { it.text.startsWith("Choose system Wi-Fi") }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val fields = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().filter { it.visibility == View.VISIBLE }.toList()
        assertEquals(2, fields.size)
        fields.single { it.hint == app.getString(R.string.existing_wifi_ssid) }.setText(" Pocket ")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(dialog.isShowing)
        fields.single { it.hint == app.getString(R.string.existing_wifi_password) }.setText("router-password")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(" Pocket ", AirPlayPersistence.loadExistingWifiSsid(app))
        assertEquals("router-password", AirPlayPersistence.loadExistingWifiPassphrase(app))
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
        assertEquals(View.VISIBLE, password.visibility)
        details.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertTrue(details.isShowing)
        password.setText("router-password")
        name.setText("Different router")
        assertEquals("", password.text.toString())
        password.setText("different-password")
        details.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("Different router", AirPlayPersistence.loadExistingWifiSsid(app))
        assertEquals("different-password", AirPlayPersistence.loadExistingWifiPassphrase(app))
    }

    @Test fun cancellingTheLegacyFormPreservesThePreviousChoiceAndPassword() {
        AirPlayPersistence.saveExistingWifiCredentials(app, "Router", "router-password")
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
        assertEquals("router-password", AirPlayPersistence.loadExistingWifiPassphrase(app))
    }

    @Test fun quickMenuStagesCredentialsAndDoesNotMutateOnOpenOrCancel() {
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(app, "Router", "")
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        fun invoke(name: String): Any? = CarPlayHostActivity::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(host)
        invoke("loadPersistedSettings")
        val baseline = invoke("menuSettingsSignature")
        val section = invoke("buildHotspotModeSection") as ViewGroup
        assertEquals(baseline, invoke("menuSettingsSignature"))
        assertEquals(false, invoke("validateManualHotspotSettings"))
        val passwordLabel = descendants(section).filterIsInstance<TextView>().single {
            it.text == app.getString(R.string.existing_wifi_password)
        }
        val password = descendants(passwordLabel.parent as ViewGroup).filterIsInstance<EditText>().single()
        assertTrue(password.isEnabled)
        password.setText("router-password")
        assertEquals(true, invoke("validateManualHotspotSettings"))
        assertEquals("", AirPlayPersistence.loadExistingWifiPassphrase(app))
        invoke("persistMenuSettings")
        assertEquals("router-password", AirPlayPersistence.loadExistingWifiPassphrase(app))
        password.setText("another-password")
        invoke("loadPersistedSettings")
        assertEquals("router-password", ReflectionHelpers.getField<String>(host, "existingWifiPassphrase"))
    }

    @Test fun upgradeKeepsSavedCredentialsAndIgnoresTheRetiredModeFlag() {
        val prefs = app.getSharedPreferences("xcertplay_airplay", 0)
        for (oldMode in listOf(false, true)) {
            prefs.edit().putString("existing_wifi_ssid", "Lian")
                .putString("existing_wifi_passphrase", "router-password")
                .putBoolean("existing_wifi_legacy_handshake", oldMode).commit()
            assertEquals("Lian", AirPlayPersistence.loadExistingWifiSsid(app))
            assertEquals("router-password", AirPlayPersistence.loadExistingWifiPassphrase(app))
            AirPlayPersistence.saveExistingWifiCredentials(app, "Lian", "new-password")
            assertFalse(prefs.contains("existing_wifi_legacy_handshake"))
        }
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
        assertEquals("pocket-password", runtime().existingWifiPassphrase)
        assertEquals(CarPlayTransport.WIRELESS, runtime().transport)
        val permissions = CarPlayHostActivity::class.java.getDeclaredMethod("requiredWirelessPermissions")
            .apply { isAccessible = true }.invoke(host) as List<*>
        assertTrue(permissions.isEmpty()) // Manual SSID input must work without fine location on API 29.
        AirPlayPersistence.saveExistingWifiCredentials(app, "Pocket", "pocket-password")
        CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        assertEquals("pocket-password", runtime().existingWifiPassphrase)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        assertEquals("", runtime().existingWifiPassphrase)
        AirPlayPersistence.saveExistingWifiCredentials(app, "", "")
        AirPlayPersistence.saveWirelessEnabled(app, false)
        CarPlayHostActivity::class.java.getDeclaredMethod("loadPersistedSettings").apply { isAccessible = true }.invoke(host)
        assertEquals(CarPlayTransport.WIRED, runtime().transport)
    }

    @Test fun missingPasswordStopsAutomaticProjectionWithAReadableSetupPrompt() {
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveExistingWifiCredentials(app, "Lian", "")
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.callInstanceMethod<Unit>(host, "loadPersistedSettings")
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(800, 480)
        CarPlayHostActivity::class.java.getDeclaredMethod("startCarPlay", sizeClass)
            .apply { isAccessible = true }.invoke(host, size)
        assertTrue(ReflectionHelpers.getField<Boolean>(host, "startupRetryStopped"))
        assertNull(ReflectionHelpers.getField<Any?>(host, "controller"))
        assertEquals(app.getString(R.string.settings_existing_wifi_setup_required), ReflectionHelpers.getField<String>(host, "latestStage"))
        AirPlayPersistence.saveWirelessEnabled(app, false)
        ReflectionHelpers.callInstanceMethod<Unit>(host, "loadPersistedSettings")
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(host, "validateManualHotspotSettings"))
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
