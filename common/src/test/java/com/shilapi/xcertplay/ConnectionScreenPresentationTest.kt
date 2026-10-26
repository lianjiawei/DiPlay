package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionScreenPresentationTest {
    @After fun cleanup() { RuntimeEnvironment.setFontScale(1f) }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun fullWaitingScreenWithLargeText() = capture("full-zh")

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-mdpi")
    fun compactWaitingScreenWithLargeText() = capture("compact-zh")

    @Test @Config(qualifiers = "zh-rCN-w400dp-h840dp-port-mdpi")
    fun tallPortraitKeepsActionsNearTheBottom() = capture("portrait-zh")

    @Test @Config(qualifiers = "zh-rCN-w640dp-h240dp-land-mdpi")
    fun shortLandscapeKeepsAllTextVisible() = capture("short-zh")

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun landscapeReadingAreaKeepsSecondaryTextLegibleInBothThemes() {
        val app = RuntimeEnvironment.getApplication()
        for (night in listOf(true)) {
            val text = TextView(app)
            SceneText.apply(text, night, secondary = true)
            val bitmap = Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888)
            try {
                SceneBackdrop(app, night, quiet = false, readingShare = .6f).apply {
                    setBounds(0, 0, 800, 480); draw(Canvas(bitmap))
                }
                for (y in 0 until 480 step 20) for (x in 0 until 480 step 20) {
                    assertTrue("Text contrast at $x,$y, night=$night",
                        androidx.core.graphics.ColorUtils.calculateContrast(text.currentTextColor, bitmap.getPixel(x, y)) >= 4.5)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun onlyTheHaloMovesWhileTheTrackAndPhoneStayFixed() {
        val ring = ConnectionRingView(RuntimeEnvironment.getApplication())
        ring.layout(0, 0, 200, 200)
        ring.state(connecting = true)
        fun render(angle: Float): Bitmap {
            ReflectionHelpers.setField(ring, "angle", angle)
            return Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also { ring.draw(Canvas(it)) }
        }
        val a = render(0f)
        val b = render(120f)
        try {
            var changed = 0
            for (y in 0 until 200) for (x in 0 until 200) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) changed++
                val radius = kotlin.math.hypot((x - 100).toDouble(), (y - 100).toDouble())
                if (radius < 80 || (y < 75 && x > 125)) {
                    assertEquals("Fixed icon and unlit track at $x,$y", a.getPixel(x, y), b.getPixel(x, y))
                }
            }
            assertTrue("The halo must move", changed > 100)
            ring.state(connecting = false, completed = true)
            val completedA = render(0f)
            val completedB = render(120f)
            try { assertTrue("Completed ring is static", completedA.sameAs(completedB)) }
            finally { completedA.recycle(); completedB.recycle() }
        } finally { a.recycle(); b.recycle() }
    }

    @Test @Config(qualifiers = "zh-rCN-w640dp-h240dp-land-mdpi")
    fun retryAndWifiRecoveryStayDirectlyUsableInShortWindows() {
        RuntimeEnvironment.setFontScale(1.3f)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val root = ReflectionHelpers.callInstanceMethod<ViewGroup>(host, "buildContentView")
            val panel = ReflectionHelpers.getField<ConnectionPreparationLayout>(host, "connectionPanel")
            for (field in listOf("startupRetryButton", "wifiRecoveryButton")) {
                ReflectionHelpers.setField(host, "startupRetryStopped", field == "startupRetryButton")
                val recovery = ReflectionHelpers.getField<View>(host, "wifiRecoveryButton")
                recovery.visibility = if (field == "wifiRecoveryButton") View.VISIBLE else View.GONE
                ReflectionHelpers.callInstanceMethod<Unit>(host, "updateDebugOverlays")
                repeat(3) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 640, 240)
                }
                val action = ReflectionHelpers.getField<View>(host, field)
                assertEquals(View.VISIBLE, action.visibility)
                assertTrue(action.parent is ViewGroup)
                assertTrue(action.height >= GlassChrome.MIN_TARGET_DP)
                val bounds = android.graphics.Rect(0, 0, action.width, action.height)
                panel.offsetDescendantRectToMyCoords(action, bounds)
                val footer = descendants(panel).filterIsInstance<GlassActionRow>().single()
                assertTrue("Recovery action clears footer", bounds.bottom <= footer.top)
                assertTrue(bounds.top >= 0 && bounds.right <= panel.width)
            }
        } finally {
            ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
            ReflectionHelpers.getField<android.os.Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            for (field in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<ExecutorService>(host, field).shutdownNow()
        }
    }

    private fun capture(name: String, safeInsets: Boolean = false) {
        RuntimeEnvironment.setFontScale(1.3f)
        for (night in listOf(true)) {
            val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
            try {
                ReflectionHelpers.setField(host, "appNight", night)
                ReflectionHelpers.setField(host, "wirelessEnabled", true)
                ReflectionHelpers.setField(host, "wirelessHotspotMode", WirelessHotspotMode.EXISTING_WIFI)
                val root = ReflectionHelpers.callInstanceMethod<ViewGroup>(host, "buildContentView")
                val panel = ReflectionHelpers.getField<View>(host, "connectionPanel")
                if (safeInsets) {
                    val viewport = panel as ConnectionPreparationLayout
                    viewport.setPadding(24, 20, 24, 32)
                    val content = viewport.contentArea.getChildAt(0) as ViewGroup
                    (content.getChildAt(2) as TextView).text = "测试 iPhone — A very long device name for the connection screen"
                }
                val config = host.resources.configuration
                root.layoutDirection = config.layoutDirection
                val density = host.resources.displayMetrics.density
                val width = (config.screenWidthDp * density).toInt()
                val height = (config.screenHeightDp * density).toInt()
                val failure = CarPlayStatus.Failed("No AirPlay TCP after CarPlay StartSession",
                    startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)
                ReflectionHelpers.callInstanceMethod<Unit>(host, "showConnectionHelp",
                    ReflectionHelpers.ClassParameter.from(CarPlayStatus.Failed::class.java, failure))
                fun layout() = repeat(6) {
                    descendants(root).forEach { it.forceLayout() }
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                }
                layout()
                val visibleText = descendants(panel).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }.toList()
                val fullHelp = host.getString(R.string.connection_help_existing_wifi)
                assertFalse("Detailed troubleshooting stays off the waiting screen", visibleText.any { it.text.toString() == fullHelp })
                val hint = ReflectionHelpers.getField<TextView>(host, "connectionHelpView")
                assertEquals(host.getString(R.string.ui_connection_hint_same_wifi), hint.text.toString())
                assertEquals(fullHelp, ReflectionHelpers.getField<String>(host, "connectionHelpDetail"))
                assertEquals(View.GONE, ReflectionHelpers.getField<View>(host, "settingsGestureHint").visibility)
                val row = descendants(panel).filterIsInstance<GlassActionRow>().single()
                val buttons = (0 until row.childCount).map { row.getChildAt(it) as Button }
                assertEquals(config.layoutDirection, row.layoutDirection)
                assertEquals(buttons[0].height, buttons[1].height)
                assertTrue(buttons.all { it.height >= 48 * density })
                val headline = visibleText.first { it.text.toString() == host.getString(R.string.ui_glass_connecting_title) }
                val title = ReflectionHelpers.getField<TextView>(host, "connectionTitleView")
                fun bounds(view: View): android.graphics.RectF {
                    val rect = android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
                    var current: View = view
                    while (current !== root) {
                        current.matrix.mapRect(rect)
                        rect.offset(current.left.toFloat(), current.top.toFloat())
                        current = current.parent as View
                    }
                    return rect
                }
                assertTrue("Headline must clear the brand header", bounds(headline).top >= bounds(title).bottom)
                assertTrue("Actions stay on screen", bounds(row).bottom <= height + 1)
                assertEquals("Bottom actions keep their safe margin", height - panel.paddingBottom - ConnectionScreenMetrics.BOTTOM_DP * density,
                    maxOf(bounds(row).bottom, bounds(descendants(panel).filterIsInstance<HomeBrandView>().single()).bottom), 1f)
                val stage = ReflectionHelpers.getField<TextView>(host, "stageStatusView")
                val instructions = ReflectionHelpers.getField<TextView>(host, "connectionInstructionsView")
                val ring = ReflectionHelpers.getField<View>(host, "connectionRing")
                assertFalse("The connection screen must contain no scroll container",
                    descendants(panel).any { it is android.widget.ScrollView })
                val originalStage = stage.text
                val originalHint = hint.text
                val originalStageHeight = stage.height
                val originalHintHeight = hint.height
                val headlineTop = bounds(headline).top
                val ringTop = bounds(ring).top
                val actionTop = bounds(row).top
                val body = (panel as ConnectionPreparationLayout).contentArea
                val detailsTop = bounds(stage).top
                val hintTop = bounds(hint).top
                for (stageId in ConnectionScreenCopy.stages) {
                    stage.text = VehicleBranding.text(host, stageId)
                    layout()
                    assertEquals("Status variants reserve the same height", originalStageHeight, stage.height)
                    assertEquals("Headline must not jump", headlineTop, bounds(headline).top, 1f)
                    assertEquals("Ring must not jump", ringTop, bounds(ring).top, 1f)
                    assertEquals("Footer must not jump", actionTop, bounds(row).top, 1f)
                    assertEquals("Status must not jump", detailsTop, bounds(stage).top, 1f)
                    assertEquals("Hint must not jump", hintTop, bounds(hint).top, 1f)
                    assertTrue("Status must remain fully above the actions", bounds(stage).bottom <= bounds(row).top)
                    assertTrue("Hint must remain fully above the actions", bounds(hint).bottom <= bounds(row).top)
                    assertTrue("Status text must not be clipped", stage.layout.getLineBottom(stage.layout.lineCount - 1) <=
                        stage.height - stage.compoundPaddingTop - stage.compoundPaddingBottom + 2)
                }
                stage.text = originalStage
                for (hintId in ConnectionScreenCopy.hints) {
                    hint.text = VehicleBranding.text(host, hintId)
                    layout()
                    assertEquals("Hints reserve the same height", originalHintHeight, hint.height)
                    assertEquals(headlineTop, bounds(headline).top, 1f)
                    assertEquals(ringTop, bounds(ring).top, 1f)
                    assertTrue("Every hint must fit", hint.layout.height <= hint.height - hint.compoundPaddingTop - hint.compoundPaddingBottom + 2)
                }
                hint.visibility = View.GONE
                instructions.visibility = View.VISIBLE
                layout()
                assertEquals("Normal and error hints share the same slot", originalHintHeight, instructions.height)
                assertEquals(headlineTop, bounds(headline).top, 1f)
                instructions.visibility = View.GONE
                hint.visibility = View.VISIBLE
                hint.text = originalHint
                layout()
                for (text in visibleText) {
                    val layout = text.layout ?: continue
                    val room = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
                    assertTrue("Unclipped text: ${text.text} bounds=${text.width}x${text.height} padding=${text.compoundPaddingTop}/${text.compoundPaddingBottom} lines=${layout.lineCount} bottom=${layout.getLineBottom(layout.lineCount - 1)}", layout.lineCount == 0 || layout.getLineBottom(layout.lineCount - 1) <= room + 2)
                }
                val content = body.getChildAt(0) as ViewGroup
                for (index in 0 until content.childCount) {
                    val child = content.getChildAt(index)
                    assertTrue("Content fits its fixed region: ${child.javaClass.simpleName}, ${child.bottom}/${content.height}",
                        child.top >= 0 && child.bottom <= content.height)
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    panel.background.setBounds(0, 0, width, height)
                    panel.background.draw(Canvas(bitmap))
                    for (text in listOf(headline, stage, hint)) {
                        val rect = bounds(text)
                        for (y in rect.top.toInt() until rect.bottom.toInt() step 16)
                            for (x in rect.left.toInt() until rect.right.toInt() step 16) {
                                assertTrue("Reading contrast at $x,$y for $name, night=$night",
                                    androidx.core.graphics.ColorUtils.calculateContrast(text.currentTextColor, bitmap.getPixel(x,y)) >= 4.5)
                            }
                    }
                    root.viewTreeObserver.dispatchOnPreDraw()
                    root.draw(Canvas(bitmap))
                    val file = File("build/reports/connection-ui/$name-${if (night) "night" else "day"}.png")
                    file.parentFile.mkdirs()
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            } finally {
                ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
                ReflectionHelpers.getField<android.os.Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
                for (field in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                    ReflectionHelpers.getField<ExecutorService>(host, field).shutdownNow()
                }
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
