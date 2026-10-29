package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.graphics.Bitmap
import android.graphics.Canvas
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.update.UpdateAvailability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w900dp-h600dp-land")
class HomeConnectionModeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        DiPlayPreferences.saveAutoConnect(app, false)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "GarveyPhone")
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        CarPlayBackgroundSession.clear()
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
    }

    @Test fun homeUsesTheConfiguredModeAndTheFooterOnlyOpensConnectionSettings() {
        for ((mode, expectedWireless, label) in listOf(
            Triple(DefaultConnectionMode.USB, false, R.string.home_connection_mode_usb_short),
            Triple(DefaultConnectionMode.WIRELESS, true, R.string.home_connection_mode_wireless_short),
        )) {
            DiPlayPreferences.saveDefaultConnectionMode(app, mode)
            AirPlayPersistence.saveWirelessEnabled(app, !expectedWireless)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
            val activity = controller.get()
            try {
                ReflectionHelpers.setField(activity, "setupError", null)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                var starts = 0
                activity.projectionStarter = { starts++; true }

                val connectionMode = ReflectionHelpers.getField<Button>(activity, "homeConnectionFooter")
                val phone = ReflectionHelpers.getField<TextView>(activity, "homePhoneName")
                assertEquals(if (expectedWireless) activity.getString(R.string.home_iphone_selected, "GarveyPhone")
                    else activity.getString(R.string.home_iphone_generic), phone.text.toString())
                assertFalse(phone.isClickable)
                assertFalse(phone.isFocusable)
                assertTrue(phone.compoundDrawablesRelative.all { it == null })
                assertFalse(phone.performClick())
                assertEquals(activity.getString(label), connectionMode.text.toString())
                assertEquals(activity.getString(if (expectedWireless) R.string.home_connection_mode_wireless
                    else R.string.home_connection_mode_usb), connectionMode.contentDescription.toString())
                assertEquals(1, connectionMode.maxLines)
                connectionMode.performClick()

                assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
                assertEquals(SettingsCategory.CONNECTION,
                    ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val phoneSetting = descendants(root).filterIsInstance<Button>().single {
                    it.text.toString() == activity.getString(R.string.choose_iphone_prefix) + "GarveyPhone"
                }
                assertTrue("The selected iPhone remains changeable in Connection settings", phoneSetting.isClickable)
                assertEquals(0, starts)
                assertEquals(!expectedWireless, AirPlayPersistence.loadWirelessEnabled(app))

                ReflectionHelpers.setField(activity, "page", "home")
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                val primary = ReflectionHelpers.getField<View>(activity, "homeConnectionRing")
                assertEquals(View.VISIBLE, primary.visibility)
                primary.performClick()

                assertEquals(1, starts)
                assertEquals(expectedWireless, AirPlayPersistence.loadWirelessEnabled(app))
                assertEquals("The home action must not replace the saved wireless sub-mode",
                    WirelessHotspotMode.WIFI_P2P, AirPlayPersistence.loadWirelessHotspotMode(app))
            } finally {
                controller.pause().stop().destroy()
                CarPlayBackgroundSession.clear()
            }
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w960dp-h540dp-land-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wirelessNameOnLincolnScreen() = captureWirelessName("1920x1080")

    @Test @Config(qualifiers = "zh-rCN-w400dp-h840dp-port-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun wirelessNameOnPortraitScreen() = captureWirelessName("portrait")

    private fun captureWirelessName(name: String) {
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        RuntimeEnvironment.setFontScale(1.3f)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
        try {
            val activity = controller.get()
            ReflectionHelpers.setField(activity, "setupError", null)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
            val label = ReflectionHelpers.getField<TextView>(activity, "homePhoneName")
            assertEquals(activity.getString(R.string.home_iphone_selected, "GarveyPhone"), label.text.toString())
            assertFalse(label.isClickable)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val metrics = activity.resources.displayMetrics
            val width = (activity.resources.configuration.screenWidthDp * metrics.density).toInt()
            val height = (activity.resources.configuration.screenHeightDp * metrics.density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            assertEquals("Device label should stay on one line", 1, label.layout.lineCount)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val file = File("build/reports/home-device-name/$name.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    @Test fun liveUsbSessionDoesNotShowTheSavedWirelessPhone() {
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
        try {
            val activity = controller.get()
            ReflectionHelpers.setField(activity, "setupError", null)
            ReflectionHelpers.setField(activity, "wirelessEnabled", false)
            ReflectionHelpers.setField(activity, "unifiedConnectionRequested", true)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
            assertEquals(activity.getString(R.string.home_iphone_generic),
                ReflectionHelpers.getField<TextView>(activity, "homePhoneName").text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun lastUsedModeDisplaysTheEffectiveTransportInsteadOfAnAmbiguousLabel() {
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.LAST_USED)
        for ((wireless, label) in listOf(
            false to R.string.home_connection_mode_usb_short,
            true to R.string.home_connection_mode_wireless_short,
        )) {
            AirPlayPersistence.saveWirelessEnabled(app, wireless)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
            try {
                val activity = controller.get()
                ReflectionHelpers.setField(activity, "setupError", null)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                val footer = ReflectionHelpers.getField<Button>(activity, "homeConnectionFooter")
                assertEquals(activity.getString(label), footer.text.toString())
                assertFalse(CarPlayBackgroundSession.hasSession())
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }
}
