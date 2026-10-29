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
    @Config(sdk = [29, 31])
    @Test fun footerUsesCurrentWindowOnFirstLayoutAndEveryResize() {
        // Configuration can exclude bars while the edge-to-edge window includes them.
        // Repeated manual layouts hid this regression in the original screenshot test.
        RuntimeEnvironment.setQualifiers("zh-rCN-w792dp-h310dp-land-533dpi")
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        DiPlayPreferences.saveAutoConnect(app, false)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "GarveyPhone")
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        AirPlayPersistence.saveAppAppearance(app, AppAppearance.DARK)
        CarPlayBackgroundSession.clear()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible()
        try {
            val screen = controller.get()
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            ReflectionHelpers.setField(screen, "setupError", null)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "refreshStatus")
            val root = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
            val density = screen.resources.displayMetrics.density
            val settings = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == screen.getString(R.string.settings) }
            val brand = descendants(root).filterIsInstance<HomeBrandView>().single()
            fun bottom(view: View) = Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }.bottom
            for ((name, dims) in listOf(
                "first-window" to intArrayOf(792, 360, 0),
                "resized-portrait" to intArrayOf(360, 792, 0),
                "resized-landscape" to intArrayOf(792, 360, 24),
                "bars-hidden" to intArrayOf(792, 360, 0))) {
                val width = (dims[0]*density).toInt()
                val height = (dims[1]*density).toInt()
                root.setPadding(if (dims[0] > dims[1]) (46*density).toInt() else 0, 0, 0, (dims[2]*density).toInt())
                root.measure(exact(width), exact(height))
                root.layout(0, 0, width, height)
                root.viewTreeObserver.dispatchOnPreDraw()
                capture(root, "api${android.os.Build.VERSION.SDK_INT}-$name")
                assertEquals("$name footer decoration follows the current window", dims[0] > dims[1] * 1.3f, brand.visibility == View.VISIBLE)
                assertEquals("$name footer must use this traversal's window height", 12*density,
                    (height-root.paddingBottom-maxOf(bottom(settings), bottom(brand))).toFloat(), 1f)
                assertTrue("$name no extra scrolling", root.getChildAt(0).height <= height-root.paddingBottom)
                val hero = descendants(root).filterIsInstance<ConnectionContentLayout>().single()
                for (index in 0 until hero.childCount) {
                    val child = hero.getChildAt(index)
                    assertTrue("$name content clears the reserved footer gap: child=$index bottom=${child.bottom}, height=${hero.height}",
                        child.bottom <= hero.height)
                }
                assertTrue("$name normal window keeps the phone below the ring",
                    hero.getChildAt(2).top >= hero.getChildAt(1).bottom)
                val primary = ReflectionHelpers.getField<TextView>(screen, "homeActionHint")
                val footerTop = bottom(settings)-settings.height
                assertTrue("$name primary clears the footer", bottom(primary) <= footerTop)
                if (name == "bars-hidden") {
                    CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
                        width, height, Any(), CarPlaySessionDisplay(width, height, Surface.ROTATION_0, false, false, width, height)) {}
                    CarPlayBackgroundSession.active = true; CarPlayBackgroundSession.mainPictureReady = true
                    ReflectionHelpers.callInstanceMethod<Unit>(screen, "refreshStatus")
                    root.measure(exact(width), exact(height)); root.layout(0, 0, width, height)
                    root.viewTreeObserver.dispatchOnPreDraw()
                    assertEquals("Connected state keeps footer position", footerTop, bottom(settings)-settings.height)
                    capture(root, "api${android.os.Build.VERSION.SDK_INT}-connected")
                    CarPlayBackgroundSession.clear()
                }
            }
            // Unknown external destinations already fall back to home; keep that route safe.
            ReflectionHelpers.setField(screen, "page", "unknown")
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
        } finally {
            controller.pause().stop().destroy()
            CarPlayBackgroundSession.clear()
        }
    }

    @Test fun actionsStayAtSafeBottomAndShareSizeAcrossSessionStates() {
        val app = RuntimeEnvironment.getApplication()
        try {
            for ((name, qualifiers) in listOf(
                "compact" to "zh-rCN-w800dp-h480dp-land-mdpi",
                "phone-landscape" to "zh-rCN-w880dp-h400dp-land-xxhdpi",
                "lincoln-head-unit-large" to "zh-rCN-w960dp-h540dp-land-xhdpi",
                "full-large" to "zh-rCN-w1280dp-h720dp-land-mdpi",
                "portrait-large" to "zh-rCN-w400dp-h840dp-port-xhdpi",
                "portrait-en-large" to "en-w400dp-h840dp-port-xhdpi")) {
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
                    val primary = ReflectionHelpers.getField<TextView>(activity, "homeActionHint")
                    val connectionMode = ReflectionHelpers.getField<TextView>(activity, "homeConnectionFooter")
                    val disconnect = ReflectionHelpers.getField<TextView>(activity, "disconnectButton")
                    val settings = descendants(root).filterIsInstance<TextView>().single { it.text.toString() == activity.getString(R.string.settings) }
                    val brand = descendants(root).filterIsInstance<HomeBrandView>().single()
                    assertEquals("$name footer decoration follows the current window", width > height * 1.3f, brand.visibility == View.VISIBLE)
                    val ring = descendants(root).filterIsInstance<ConnectionRingView>().single()
                    val headline = ReflectionHelpers.getField<TextView>(activity, "homeHeadline")
                    val status = ReflectionHelpers.getField<TextView>(activity, "status")
                    fun rect(view: View) = Rect(0, 0, view.width, view.height).also { root.offsetDescendantRectToMyCoords(view, it) }
                    fun geometry() = listOf(headline, ring, status, primary, settings, brand).map(::rect)
                    val original = geometry()
                    for (state in listOf("ready", "connecting", "failure", "connected", "disconnected")) {
                        when (state) {
                            "connecting" -> CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
                                width, height, Any(), CarPlaySessionDisplay(width, height, Surface.ROTATION_0, false, false, width, height)) {}
                            "failure" -> {
                                ReflectionHelpers.setField(activity, "connectionFailureSummary", activity.getString(R.string.ui_connection_usb_transition_timeout))
                                ReflectionHelpers.setField(activity, "connectionHelpDetail", activity.getString(R.string.connection_help_usb))
                                ReflectionHelpers.setField(activity, "startupRetryStopped", true)
                            }
                            "connected" -> {
                                ReflectionHelpers.setField(activity, "connectionFailureSummary", null)
                                ReflectionHelpers.setField(activity, "connectionHelpDetail", null)
                                ReflectionHelpers.setField(activity, "startupRetryStopped", false)
                                CarPlayBackgroundSession.active = true; CarPlayBackgroundSession.mainPictureReady = true
                            }
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
                        assertTrue("$name/$state no home scrolling", root.getChildAt(0).height <= height-root.paddingTop-root.paddingBottom)
                        assertTrue(rect(primary).bottom <= rect(settings).top)
                        assertTrue("$name/$state ring remains an action", ring.isClickable && ring.isFocusable)
                        for (label in listOf(primary, settings, connectionMode, disconnect).filter { it.isShown }) {
                            assertTrue("$name/$state label fits: ${label.text}; layout=${label.layout.height}, height=${label.height}, " +
                                "padding=${label.compoundPaddingTop}+${label.compoundPaddingBottom}, lines=${label.layout.lineCount}",
                                label.layout.height <= label.height-label.compoundPaddingTop-label.compoundPaddingBottom)
                        }
                        if (connectionMode.isShown) {
                            assertEquals("$name/$state connection mode stays on one line", 1, connectionMode.layout.lineCount)
                            assertEquals("$name/$state connection mode must not retain horizontal text scrolling", 0, connectionMode.scrollX)
                        }
                        when (state) {
                            "connecting" -> assertEquals(activity.getString(R.string.home_cancel_connection), disconnect.text.toString())
                            "connected" -> {
                                assertEquals(activity.getString(R.string.home_disconnect_carplay), disconnect.text.toString())
                                assertEquals("$name connected action stays on one line", 1, disconnect.layout.lineCount)
                                assertEquals("$name connected action is not truncated", 0, disconnect.layout.getEllipsisCount(0))
                                for (button in listOf(disconnect, settings)) {
                                    assertTrue("$name footer icon and text are drawn as a centered group",
                                        ReflectionHelpers.getField<Boolean>(button, "centeredIconGroup"))
                                    val icon = button.compoundDrawablesRelative[0]
                                    assertNotNull(icon)
                                    val groupWidth = icon.bounds.width() + button.compoundDrawablePadding +
                                        kotlin.math.ceil(button.paint.measureText(button.text.toString()).toDouble()).toInt() + 1
                                    assertTrue("$name centered group fits inside button", groupWidth <= button.width - button.paddingLeft - button.paddingRight)
                                }
                            }
                        }
                        if (state == "ready" || state == "connected" || state == "failure") capture(root, "$name-$state")
                    }
                    comparePreparation(activity, root, width, height, name, headline, ring, status, settings, brand)
                } finally { CarPlayBackgroundSession.clear(); controller.pause().stop().destroy() }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
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
            assertEquals("$name same footer decoration visibility", brand.visibility, waitingBrand.visibility)
            if (waitingBrand.visibility == View.VISIBLE) {
                assertEquals("$name same wordmark position", rect(homeRoot, brand), rect(root, waitingBrand))
                val word = waitingBrand.getChildAt(1) as TextView
                assertEquals("$name wordmark must not scroll", 0, word.scrollX)
                assertEquals(host.getString(R.string.app_name), word.text.toString())
                assertTrue("$name wordmark visible: ${word.text}, measured=${word.measuredWidth}x${word.measuredHeight}, bounds=${rect(root, word)}, textHeight=${word.layout?.height}",
                    word.height > 0 && word.layout != null && word.height >= word.layout.height)
            }
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
