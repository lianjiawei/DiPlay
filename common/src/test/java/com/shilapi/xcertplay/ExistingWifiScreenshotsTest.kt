package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExistingWifiScreenshotsTest {
    @Test fun networkSetupAndPasswordConnectionRemainReachableWithLargeFonts() {
        val app = RuntimeEnvironment.getApplication()
        try {
            for ((name, qualifiers, width, height) in listOf(
                Scenario("compact", "zh-rCN-w600dp-h360dp-land-mdpi", 600, 360),
                Scenario("full", "zh-rCN-w1280dp-h720dp-land-mdpi", 1280, 720),
                Scenario("arabic", "ar-ldrtl-w640dp-h360dp-land-mdpi", 640, 360))) {
                RuntimeEnvironment.setQualifiers(qualifiers)
                RuntimeEnvironment.setFontScale(1.3f)
                shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
                shadowOf(app.getSystemService(WifiManager::class.java)).setScanResults(listOf(ScanResult().apply {
                    SSID = "Office Wi-Fi"; capabilities = "[WPA2-PSK-CCMP][ESS]"
                }))
                val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
                activity.setTheme(android.R.style.Theme_Material_NoActionBar)
                val parent = LinearLayout(activity)
                DiPlayActivity::class.java.getDeclaredMethod("wirelessLinkControls", LinearLayout::class.java)
                    .apply { isAccessible = true }.invoke(activity, parent)
                activity.setContentView(parent)
                val choose = descendants(parent).filterIsInstance<Button>().single { it.text.contains(" · ") }
                choose.performClick()
                val details = ShadowAlertDialog.getLatestAlertDialog()
                assertEquals(2, descendants(details.window!!.decorView).filterIsInstance<EditText>().count { it.visibility == View.VISIBLE })
                capture(details, "$name-details", width, height)
                descendants(details.window!!.decorView).filterIsInstance<ImageButton>().single {
                    it.contentDescription.toString().startsWith(activity.getString(R.string.settings_existing_wifi_select) + " · ")
                }.performClick()
                val list = ShadowAlertDialog.getLatestAlertDialog()
                capture(list, "$name-networks", width, height)
                assertTrue("Wi-Fi choices remain visible at $name", list.listView.height > 0)
                list.listView.performItemClick(null, 0, 0)
                val password = ShadowAlertDialog.getLatestAlertDialog()
                assertTrue(descendants(password.window!!.decorView).filterIsInstance<EditText>().any {
                    it.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
                })
                assertTrue(descendants(password.window!!.decorView).filterIsInstance<EditText>().all {
                    it.transformationMethod is android.text.method.PasswordTransformationMethod
                })
                capture(password, "$name-password", width, height)
                password.dismiss(); details.dismiss(); activity.finish()
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en")
        }
    }

    @Test @Config(sdk = [29]) fun credentialFormKeepsSaveReachableAcrossOrientationsAndLargeText() {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        try {
            for ((name, qualifiers, width, height) in listOf(
                Scenario("compact", "zh-rCN-w600dp-h360dp-land-mdpi", 600, 360),
                Scenario("portrait", "zh-rCN-w400dp-h840dp-port-mdpi", 400, 840),
                Scenario("full", "zh-rCN-w1280dp-h720dp-land-mdpi", 1280, 720),
                Scenario("arabic", "ar-ldrtl-w640dp-h360dp-land-mdpi", 640, 360))) {
                RuntimeEnvironment.setQualifiers(qualifiers)
                RuntimeEnvironment.setFontScale(1.3f)
                DiPlayPreferences.saveAutoConnect(app, false)
                UpdateAvailability.saveBackgroundChecksEnabled(app, false)
                run {
                    AirPlayPersistence.saveAppAppearance(app, AppAppearance.DARK)
                    AirPlayPersistence.saveExistingWifiCredentials(app, "Lian", "test-password")
                    AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
                    val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                        Intent(app, DiPlayActivity::class.java).putExtra("page", "connection")).setup().visible()
                    try {
                        val screen = controller.get()
                        ReflectionHelpers.callInstanceMethod<Unit>(screen, "askExistingWifiNetwork")
                        val dialog = ShadowAlertDialog.getLatestAlertDialog()
                        val inputs = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().filter { it.visibility == View.VISIBLE }.toList()
                        assertEquals(2, inputs.size)
                        capture(dialog, "$name-credentials", width, height)
                        dialog.dismiss()
                    } finally { controller.pause().stop().destroy() }
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en")
            app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            app.getSharedPreferences("diplay", 0).edit().clear().commit()
        }
    }

    private data class Scenario(val name: String, val qualifiers: String, val width: Int, val height: Int)

    private fun capture(dialog: AlertDialog, name: String, width: Int, height: Int) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val decor = dialog.window!!.decorView
        val dialogWidth = dialog.window!!.attributes.width.takeIf { it > 0 } ?: (width - 32)
        decor.measure(View.MeasureSpec.makeMeasureSpec(dialogWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height - 16, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE).takeIf { it.visibility == View.VISIBLE }
            ?: dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        assertTrue("Positive action is reachable: $name", save.width > 0 && save.height > 0 && save.isShown)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            decor.draw(Canvas(bitmap))
            val file = File("build/reports/existing-wifi-ui/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
