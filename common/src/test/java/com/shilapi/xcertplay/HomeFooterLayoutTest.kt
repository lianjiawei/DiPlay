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
                    ReflectionHelpers.setField(activity, "setupError", null)
                    ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
                    var resolvedInsets = false
                    fun layout() {
                        repeat(3) {
                            shadowOf(Looper.getMainLooper()).idle()
                            if (resolvedInsets) root.setPadding(0, (8*density).toInt(), 0, (24*density).toInt())
                            root.measure(exact(width), exact(height)); root.layout(0, 0, width, height)
                        }
                    }
                    layout()
                    // Inject already-resolved insets: Robolectric's platform dispatch reports no system bars.
                    resolvedInsets = true
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
                    comparePreparation(activity, root, width, height, name, headline, ring, status, settings, brand)
                } finally { CarPlayBackgroundSession.clear(); controller.pause().stop().destroy() }
            }
        } finally { RuntimeEnvironment.setFontScale(1f); AppAppearanceRuntime.resetForTest() }
    }

    private fun comparePreparation(home: DiPlayActivity, homeRoot: ViewGroup, width: Int, height: Int,
        name: String, headline: View, ring: View, status: View, settings: TextView, brand: View) {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            ReflectionHelpers.setField(host, "appNight", true)
            ReflectionHelpers.setField(host, "wirelessEnabled", true)
            val root = ReflectionHelpers.callInstanceMethod<ViewGroup>(host, "buildContentView")
            root.layoutDirection = homeRoot.layoutDirection
            val viewport = ReflectionHelpers.getField<ConnectionPreparationLayout>(host, "connectionPanel")
            viewport.setPadding(homeRoot.paddingLeft, homeRoot.paddingTop, homeRoot.paddingRight, homeRoot.paddingBottom)
            repeat(3) {
                descendants(root).forEach { it.forceLayout() }
                root.measure(exact(width), exact(height)); root.layout(0, 0, width, height)
            }
            // Single-line text resolves its scroll offset in pre-draw, as in an attached window.
            root.viewTreeObserver.dispatchOnPreDraw()
            fun rect(root: ViewGroup, view: View) = Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
            val content = viewport.contentArea.getChildAt(0) as ViewGroup
            for ((homeView, waitingView) in listOf(headline to content.getChildAt(0), ring to content.getChildAt(1), status to content.getChildAt(3))) {
                assertEquals("$name same slot when switching pages", rect(homeRoot, homeView), rect(root, waitingView))
            }
            val actions = descendants(viewport).filterIsInstance<GlassActionRow>().single()
            assertEquals("$name same bottom action baseline", rect(homeRoot, settings).top, rect(root, actions).top)
            for (index in 0 until actions.childCount) {
                assertEquals("$name same action width", settings.width, actions.getChildAt(index).width)
                assertEquals("$name same action height", settings.height, actions.getChildAt(index).height)
                assertEquals(settings.textSize, (actions.getChildAt(index) as TextView).textSize, .01f)
            }
            val waitingBrand = descendants(viewport).filterIsInstance<HomeBrandView>().single()
            assertEquals("$name same wordmark position", rect(homeRoot, brand), rect(root, waitingBrand))
            val word = waitingBrand.getChildAt(1) as TextView
            assertTrue("$name wordmark visible: ${word.text}, measured=${word.measuredWidth}x${word.measuredHeight}, bounds=${rect(root, word)}, textHeight=${word.layout?.height}",
                word.height > 0 && word.layout != null && word.height >= word.layout.height)
            capture(root, "$name-preparation")
        } finally {
            ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicBoolean>(host, "shuttingDown").set(true)
            ReflectionHelpers.getField<android.os.Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            for (field in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, field).shutdownNow()
        }
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
