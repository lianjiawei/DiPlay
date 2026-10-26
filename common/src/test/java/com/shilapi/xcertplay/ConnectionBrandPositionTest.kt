package com.shilapi.xcertplay

import android.graphics.Rect
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.ApplicationInfo
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 27, 29, 31], qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionBrandPositionTest {
    @After fun resetFontScale() { RuntimeEnvironment.setFontScale(1f) }

    @Test fun repeatedMeasurementsDoNotMoveTheBrandTextInsideItsHeader() = withHost { root, title ->
        fun layout(height: Int) {
            // Connection status updates remeasure the viewport, while the unchanged brand
            // row is eligible for Android's normal measurement cache.
            (title.parent.parent as View).requestLayout()
            root.measure(exact(800), exact(height))
            root.layout(0, 0, 800, height)
            root.viewTreeObserver.dispatchOnPreDraw()
        }
        // Cover both header sizes and resizing back, without forcing every descendant's layout:
        // production status updates leave the unchanged header eligible for measurement caching.
        for (height in listOf(480, 300, 299, 240, 480)) {
            layout(height)
            val initial = textPosition(root, title)
            repeat(5) {
                layout(height)
                assertEquals("Brand glyph position at height $height after traversal $it", initial, textPosition(root, title))
                assertEquals("The label keeps the parent's final height", title.height, title.measuredHeight)
            }
        }
    }

    @Test @Config(sdk = [29], qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fullHeaderPixelsStayFixedWithLargeText() = capture("full-zh")

    @Test @Config(sdk = [29], qualifiers = "zh-rCN-w320dp-h600dp-port-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactHeaderPixelsStayFixedWithLargeText() = capture("compact-zh")

    private fun capture(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        withHost { root, title ->
            val config = root.resources.configuration
            val density = root.resources.displayMetrics.density
            val width = (config.screenWidthDp * density).toInt()
            val height = (config.screenHeightDp * density).toInt()
            root.layoutDirection = config.layoutDirection
            val viewport = title.parent.parent as ConnectionPreparationLayout
            val stage = (viewport.contentArea.getChildAt(0) as ViewGroup).getChildAt(3) as TextView
            val header = title.parent as View
            fun layout() {
                viewport.requestLayout()
                root.measure(exact(width), exact(height))
                root.layout(0, 0, width, height)
                root.viewTreeObserver.dispatchOnPreDraw()
            }
            layout()
            val before = bitmap(header)
            save(root, "$name-first")
            try {
                for (message in listOf("正在连接 iPhone…", "正在等待 iPhone 接入 CarPlay…", "CarPlay 已连接")) {
                    stage.text = message
                    repeat(3) { layout() }
                    val after = bitmap(header)
                    try { assertTrue("$name brand pixels changed after $message", before.sameAs(after)) }
                    finally { after.recycle() }
                }
                save(root, "$name-updated")
            } finally { before.recycle() }
        }
    }

    private fun bitmap(view: View) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        .also { view.draw(Canvas(it)) }

    private fun save(view: View, name: String) {
        val image = bitmap(view)
        try {
            val file = File("build/reports/connection-brand/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun textPosition(root: ViewGroup, title: TextView): Pair<Int, Int> {
        val bounds = Rect(0, 0, title.width, title.height)
        root.offsetDescendantRectToMyCoords(title, bounds)
        return Pair(bounds.top + title.baseline - title.scrollY, title.height)
    }

    private fun withHost(check: (ViewGroup, TextView) -> Unit) {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val root = ReflectionHelpers.callInstanceMethod<ViewGroup>(host, "buildContentView")
            val title = ReflectionHelpers.getField<TextView>(host, "connectionTitleView")
            check(root, title)
        } finally {
            ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicBoolean>(host, "shuttingDown").set(true)
            ReflectionHelpers.getField<android.os.Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            for (field in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, field).shutdownNow()
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
