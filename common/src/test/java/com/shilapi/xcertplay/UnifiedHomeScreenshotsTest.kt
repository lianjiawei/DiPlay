package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.io.File
import org.junit.After
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
class UnifiedHomeScreenshotsTest {
    @After fun cleanup() { RuntimeEnvironment.setFontScale(1f); CarPlayBackgroundSession.clear() }
    @Test @Config(qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
    fun fullHomeStatesWithLargeText() = capture("full")
    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-xhdpi")
    fun compactHighDensityHomeStatesWithLargeText() = capture("compact")
    @Test @Config(qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    fun ultraWideHomeStatesWithLargeText() = capture("1920x720-mdpi")
    @Test @Config(qualifiers = "zh-rCN-w1280dp-h480dp-land-hdpi")
    fun ultraWideMediumDensityHomeStatesWithLargeText() = capture("1920x720-hdpi")
    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun ultraWideHighDensityHomeStatesWithLargeText() = capture("1920x720-xhdpi")

    private fun capture(name: String) {
        val app = RuntimeEnvironment.getApplication()
        RuntimeEnvironment.setFontScale(1.3f)
        DiPlayPreferences.saveAutoConnect(app, false)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "GarveyPhone")
        CarPlayBackgroundSession.clear()
        val c = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
        val a = c.get()
        try {
            ReflectionHelpers.setField(a, "setupError", null)
            val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            root.layoutDirection = a.resources.configuration.layoutDirection
            val density = a.resources.displayMetrics.density
            val width = (a.resources.configuration.screenWidthDp * density).toInt()
            val height = (a.resources.configuration.screenHeightDp * density).toInt()
            fun field(name: String): View = ReflectionHelpers.getField(a, name)
            val primary = field("connectButton") as TextView
            val settings = descendants(root).filterIsInstance<TextView>().single { it.text == a.getString(R.string.settings) }
            val points = listOf(field("homeHeadline"), field("homeConnectionRing"), field("status"), primary, settings,
                descendants(root).filterIsInstance<HomeBrandView>().single())
            fun rect(v: View) = Rect(0, 0, v.width, v.height).also { root.offsetDescendantRectToMyCoords(v, it) }
            var baseline: List<Rect>? = null
            for (state in listOf("idle", "connecting", "incomplete", "connected")) {
                if (state == "connecting") CarPlayBackgroundSession.store(mock(CarPlayController::class.java),
                    mock(AndroidMediaSink::class.java), width, height, a,
                    CarPlaySessionDisplay(width, height, Surface.ROTATION_0, false, false, width, height)) {}
                ReflectionHelpers.setField(a, "unifiedConnectionRequested", state != "idle")
                ReflectionHelpers.setField(a, "startupRetryStopped", state == "incomplete")
                ReflectionHelpers.setField(a, "connectionFailureSummary", if (state == "incomplete") a.getString(R.string.ui_connection_hint_permissions) else null)
                CarPlayBackgroundSession.active = state == "connected"
                CarPlayBackgroundSession.mainPictureReady = state == "connected"
                ReflectionHelpers.callInstanceMethod<Unit>(a, "onProjectionStateChanged")
                repeat(3) {
                    shadowOf(android.os.Looper.getMainLooper()).idle()
                    root.setPadding(0, (8*density).toInt(), 0, (24*density).toInt())
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                }
                root.viewTreeObserver.dispatchOnPreDraw()
                assertSame("Connection state changes keep the original home", root, a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                assertSame(primary, field("connectButton"))
                val positions = points.map(::rect)
                if (baseline == null) baseline = positions else assertEquals("$name/$state no position changes", baseline, positions)
                for (button in listOf(primary, settings)) {
                    assertTrue("Unclipped action ${button.text}", button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                    assertTrue(rect(button).bottom <= height - root.paddingBottom)
                }
                assertTrue("Primary action must clear the footer", rect(primary).bottom <= rect(settings).top)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    val file = File("build/reports/unified-home/$name-$state.png")
                    file.parentFile.mkdirs()
                    file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { bitmap.recycle() }
            }
        } finally {
            CarPlayBackgroundSession.clear()
            c.pause().stop().destroy()
        }
    }
    private fun descendants(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(descendants(v.getChildAt(i)))
    }
}
