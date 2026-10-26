package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ScrollView
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.mockito.Mockito.mock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HomeCompactLayoutTest {
    @Config(qualifiers = "en-w900dp-h400dp-land")
    @Test fun compactLandscapeKeepsTheFooterBelowWirelessControls() {
        verifyHomeArrangement()
    }

    @Config(qualifiers = "en-w400dp-h900dp-port")
    @Test fun compactPortraitStacksWirelessBeforeUsb() {
        verifyHomeArrangement()
    }

    private fun verifyHomeArrangement() {
        CarPlayBackgroundSession.clear()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible()
        val activity = controller.get()
        val root = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val config = activity.resources.configuration
        val width = (config.screenWidthDp * metrics.density).toInt()
        val height = (config.screenHeightDp * metrics.density).toInt()
        repeat(3) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
        }
        fun text(id: Int) = descendants(root).filterIsInstance<TextView>()
            .single { it.visibility == View.VISIBLE && it.text.toString() == activity.getString(id) }
        val connect = text(R.string.ui_glass_connect_carplay)
        val connectionMode = text(R.string.home_connection_mode_usb)
        val connectPosition = IntArray(2).also(connect::getLocationOnScreen)
        val connectionModePosition = IntArray(2).also(connectionMode::getLocationOnScreen)
        assertTrue(connectionModePosition[1] >= connectPosition[1] + connect.height)
        val settings = text(R.string.settings)
        val settingsPosition = IntArray(2).also(settings::getLocationOnScreen)
        assertEquals("Connection mode and Settings share the footer row", connectionModePosition[1], settingsPosition[1])
        assertEquals("Footer actions have equal height", connectionMode.height, settings.height)
        assertTrue(settingsPosition[0] >= connectionModePosition[0] + connectionMode.width)
        assertTrue(text(R.string.car_home).visibility == View.VISIBLE)
        assertFalse(descendants(root).filterIsInstance<TextView>().any {
            it.text == activity.getString(R.string.a_familiar_drive) ||
                it.text == activity.getString(R.string.plug_your_iphone_into_a_usb_data_port_allow_carplay_when_y)
        })
        val scroll = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll")
        fun verifyNoExtraCard() {
            repeat(3) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            assertTrue("The home composition fits its viewport", scroll.getChildAt(0).height <= scroll.height)
            assertFalse("No auxiliary connection/version card", descendants(root).filterIsInstance<TextView>().any {
                it.text == activity.getString(R.string.wireless_carplay) ||
                    it.text == activity.getString(R.string.home_usb_carplay)
            })
            val footerPosition = IntArray(2).also(settings::getLocationOnScreen)
            assertTrue("Session changes keep the footer anchored within pixel rounding",
                kotlin.math.abs(settingsPosition[1] - footerPosition[1]) <= 1)
        }
        try {
            verifyNoExtraCard()
            CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
                width, height, Any(), CarPlaySessionDisplay(width, height, Surface.ROTATION_0, false, false, width, height)) {}
            for (active in listOf(false, true)) {
                CarPlayBackgroundSession.active = active
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
                verifyNoExtraCard()
                assertEquals(View.GONE, connectionMode.visibility)
                assertTrue(descendants(root).filterIsInstance<TextView>().any {
                    it.isShown && it.text in listOf(activity.getString(R.string.home_cancel_connection),
                        activity.getString(R.string.home_disconnect_carplay))
                })
            }
        } finally {
            CarPlayBackgroundSession.clear()
            controller.pause().stop().destroy()
        }
    }

    @Config(qualifiers = "en-w2667dp-h1333dp")
    @Test fun fullSizeHomeKeepsTheSameCleanComposition() = verifyHomeArrangement()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
