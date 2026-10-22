package com.shilapi.xcertplay

import android.Manifest
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
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
                assertEquals(1, descendants(details.window!!.decorView).filterIsInstance<EditText>().count())
                capture(details, "$name-details", width, height)
                descendants(details.window!!.decorView).filterIsInstance<Button>().single { it.text.contains(" · ") }.performClick()
                val list = ShadowAlertDialog.getLatestAlertDialog()
                capture(list, "$name-networks", width, height)
                assertTrue("Wi-Fi choices remain visible at $name", list.listView.height > 0)
                list.listView.performItemClick(null, 0, 0)
                val password = ShadowAlertDialog.getLatestAlertDialog()
                assertTrue(descendants(password.window!!.decorView).filterIsInstance<EditText>().any {
                    it.inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
                })
                capture(password, "$name-password", width, height)
                password.dismiss(); details.dismiss(); activity.finish()
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en")
        }
    }

    private data class Scenario(val name: String, val qualifiers: String, val width: Int, val height: Int)

    private fun capture(dialog: AlertDialog, name: String, width: Int, height: Int) {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val decor = dialog.window!!.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(width - 32, View.MeasureSpec.EXACTLY),
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
