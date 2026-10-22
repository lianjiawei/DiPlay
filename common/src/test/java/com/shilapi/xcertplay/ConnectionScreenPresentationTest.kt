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

    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h600dp-port-mdpi")
    fun compactWaitingScreenWithLargeRtlText() = capture("compact-ar-rtl")

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun landscapeReadingAreaKeepsSecondaryTextLegibleInBothThemes() {
        val app = RuntimeEnvironment.getApplication()
        for (night in listOf(true, false)) {
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

    private fun capture(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        for (night in listOf(true, false)) {
            val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
            try {
                // The library test manifest does not inherit mobile's android:supportsRtl="true".
                host.applicationInfo.apply {
                    flags = flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
                }
                ReflectionHelpers.setField(host, "appNight", night)
                ReflectionHelpers.setField(host, "wirelessEnabled", true)
                ReflectionHelpers.setField(host, "wirelessHotspotMode", WirelessHotspotMode.EXISTING_WIFI)
                val root = ReflectionHelpers.callInstanceMethod<ViewGroup>(host, "buildContentView")
                val panel = ReflectionHelpers.getField<View>(host, "connectionPanel")
                val config = host.resources.configuration
                // This presentation-only host is not attached to a decor view, so resolve RTL explicitly.
                root.layoutDirection = config.layoutDirection
                val density = host.resources.displayMetrics.density
                val width = (config.screenWidthDp * density).toInt()
                val height = (config.screenHeightDp * density).toInt()
                val failure = CarPlayStatus.Failed("No AirPlay TCP after CarPlay StartSession",
                    startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT)
                ReflectionHelpers.callInstanceMethod<Unit>(host, "showConnectionHelp",
                    ReflectionHelpers.ClassParameter.from(CarPlayStatus.Failed::class.java, failure))
                repeat(6) {
                    descendants(root).forEach { it.forceLayout() }
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                }
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
                if (config.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    assertTrue("Actions must follow RTL order", buttons[0].left > buttons[1].left)
                }
                assertEquals(buttons[0].height, buttons[1].height)
                assertTrue(buttons.all { it.height >= 48 * density })
                val headline = visibleText.first { it.text.toString() == host.getString(R.string.connecting_to_your_iphone) }
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
                for (text in visibleText) {
                    val layout = text.layout ?: continue
                    val room = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
                    assertTrue("Unclipped text: ${text.text}", layout.lineCount == 0 || layout.getLineBottom(layout.lineCount - 1) <= room + 2)
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
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
