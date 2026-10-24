package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.update.UpdateAvailability
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HomeFooterLayoutTest {
    @Test fun actionsStayAtSafeBottomAndShareSizeAcrossSessionStates() {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        try {
            for ((name, qualifiers) in listOf(
                "compact" to "zh-rCN-w800dp-h480dp-land-mdpi",
                "phone-landscape" to "zh-rCN-w880dp-h400dp-land-xxhdpi",
                "full-large" to "zh-rCN-w1280dp-h720dp-land-mdpi",
                "portrait-large" to "zh-rCN-w400dp-h840dp-port-xhdpi",
                "rtl-large" to "ar-ldrtl-w960dp-h540dp-land-mdpi")) {
                RuntimeEnvironment.setQualifiers(qualifiers)
                RuntimeEnvironment.setFontScale(if (name.endsWith("large")) 1.3f else 1f)
                app.getSharedPreferences("diplay", 0).edit().clear().commit()
                app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
                DiPlayPreferences.saveAutoConnect(app, false)
                DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "GarveyPhone")
                UpdateAvailability.saveBackgroundChecksEnabled(app, false)
                AirPlayPersistence.saveAppAppearance(app, AppAppearance.DARK)
                CarPlayBackgroundSession.clear()
                val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                    Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
                try {
                    val activity = controller.get()
                    val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ScrollView
                    val density = activity.resources.displayMetrics.density
                    val width = (activity.resources.configuration.screenWidthDp * density).toInt()
                    val height = (activity.resources.configuration.screenHeightDp * density).toInt()
                    // Keep the camera/system bars outside the footer in either orientation.
                    ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, (8*density).toInt(), 0, (24*density).toInt()))
                        .build())
                    fun layout() {
                        repeat(3) {
                            root.measure(exact(width), exact(height)); root.layout(0, 0, width, height)
                            shadowOf(Looper.getMainLooper()).idle()
                        }
                    }
                    layout()
                    val primary = ReflectionHelpers.getField<TextView>(activity, "connectButton")
                    val usb = ReflectionHelpers.getField<TextView>(activity, "homeUsbFooter")
                    val settings = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.settings) }
                    val brand = descendants(root).filterIsInstance<HomeBrandView>().single()
                    val ring = descendants(root).filterIsInstance<ConnectionRingView>().single()
                    val headline = ReflectionHelpers.getField<TextView>(activity, "homeHeadline")
                    val status = ReflectionHelpers.getField<TextView>(activity, "status")
                    fun rect(view: View) = Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
                    fun geometry() = listOf(headline, ring, status, primary, settings, brand).map(::rect)
                    val original = geometry()
                    for (state in listOf("ready", "connecting", "connected", "disconnected")) {
                        when (state) {
                            "connecting" -> CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
                                width, height, Any(), CarPlaySessionDisplay(width, height, Surface.ROTATION_0, false, false, width, height)) {}
                            "connected" -> CarPlayBackgroundSession.active = true
                            "disconnected" -> CarPlayBackgroundSession.clear()
                        }
                        ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
                        layout()
                        val positions = geometry()
                        original.zip(positions).forEach { (before, after) ->
                            assertTrue("$name/$state position changed: $before -> $after",
                                kotlin.math.abs(before.top-after.top) <= 1 && kotlin.math.abs(before.bottom-after.bottom) <= 1 &&
                                    before.left == after.left && before.right == after.right)
                        }
                        val footerBottom = maxOf(rect(settings).bottom, rect(brand).bottom)
                        assertEquals("$name/$state footer hugs safe bottom", 12*density,
                            (height-root.paddingBottom-footerBottom).toFloat(), 1f)
                        assertEquals("$name/$state primary matches footer width", settings.width, primary.width)
                        assertEquals("$name/$state primary matches footer height", settings.height, primary.height)
                        assertEquals(settings.textSize, primary.textSize, .01f)
                        assertTrue("$name/$state no home scrolling", root.getChildAt(0).height <= height-root.paddingTop-root.paddingBottom)
                        assertTrue(rect(primary).bottom <= rect(settings).top)
                        assertTrue(primary.height >= 48*density)
                        for (label in listOf(primary, settings, usb).filter { it.isShown }) {
                            assertTrue("$name/$state label fits: ${label.text}", label.layout.height <= label.height-label.compoundPaddingTop-label.compoundPaddingBottom)
                        }
                        if (state == "ready" || state == "connected") capture(root, "$name-$state")
                    }
                } finally { CarPlayBackgroundSession.clear(); controller.pause().stop().destroy() }
            }
        } finally { RuntimeEnvironment.setFontScale(1f); AppAppearanceRuntime.resetForTest() }
    }

    private fun capture(view: View, name: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val file = File("build/reports/home-footer/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
