package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.widget.Button
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.update.UpdateAvailability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

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
                assertEquals(activity.getString(label), connectionMode.text.toString())
                assertEquals(activity.getString(if (expectedWireless) R.string.home_connection_mode_wireless
                    else R.string.home_connection_mode_usb), connectionMode.contentDescription.toString())
                assertEquals(1, connectionMode.maxLines)
                connectionMode.performClick()

                assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
                assertEquals(SettingsCategory.CONNECTION,
                    ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
                assertEquals(0, starts)
                assertEquals(!expectedWireless, AirPlayPersistence.loadWirelessEnabled(app))

                ReflectionHelpers.setField(activity, "page", "home")
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
                val primary = ReflectionHelpers.getField<Button>(activity, "connectButton")
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
