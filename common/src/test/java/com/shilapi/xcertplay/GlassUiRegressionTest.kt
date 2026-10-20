package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
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
class GlassUiRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun cleanup() {
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        CarPlayBackgroundSession.clear()
        UpdateAvailability.clearAllForTest(app)
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun landscapeHomeMatchesTheSelectedComposition() = capture("landscape-zh", 1f)

    @Test @Config(qualifiers = "en-w1200dp-h720dp-land-mdpi")
    fun expandedControlsWithLargeEnglishText() = capture("expanded-en", 1.3f)

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-mdpi")
    fun compactControlsWithLargeChineseText() = capture("compact-zh", 1.3f)

    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h600dp-port-mdpi")
    fun compactControlsWithLargeRtlText() = capture("compact-ar-rtl", 1.3f)

    private fun capture(name: String, scale: Float) {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        DiPlayPreferences.saveAutoConnect(app, false)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
        val screen = controller.get()
        try {
            // Local unit tests have no physical authentication chip; render its normal ready-state counterpart.
            ReflectionHelpers.setField(screen, "setupError", null)
            call(screen, "render")
            screenshot(screen, "$name-home")
            val texts = descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            for (id in listOf(R.string.connect_phone, R.string.choose_iphone, R.string.connect_with_usb, R.string.settings)) {
                assertTrue("Existing home action remains available", screen.getString(id) in texts)
                val action = descendants(screen.window.decorView).filterIsInstance<TextView>()
                    .first { it.text.toString() == screen.getString(id) }
                assertTrue("Home action must have a usable measured height", action.height >= 40 * screen.resources.displayMetrics.density)
                if (name == "landscape-zh") {
                    val bounds = android.graphics.Rect()
                    assertTrue("Home action must fit the initial viewport", action.getGlobalVisibleRect(bounds))
                    assertTrue("Home action cannot be clipped", bounds.height() >= action.height - 1)
                }
            }
            for (night in listOf(true, false)) {
                AirPlayPersistence.saveAppAppearance(app, if (night) AppAppearance.DARK else AppAppearance.LIGHT)
                ReflectionHelpers.setField(screen, "page", "settings")
                for (category in listOf(SettingsCategory.OVERVIEW, SettingsCategory.DISPLAY, SettingsCategory.ABOUT)) {
                    ReflectionHelpers.setField(screen, "settingsCategory", category)
                    call(screen, "render")
                    screenshot(screen, "$name-${if (night) "dark" else "light"}-${category.name.lowercase()}")
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun screenshot(screen: DiPlayActivity, name: String) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val config = screen.resources.configuration
        val density = screen.resources.displayMetrics.density
        val width = (config.screenWidthDp * density).toInt()
        val height = (config.screenHeightDp * density).toInt()
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val path = Paths.get("build/reports/glass-ui/$name.png")
            Files.createDirectories(path.parent)
            Files.newOutputStream(path).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun call(screen: DiPlayActivity, name: String) = ReflectionHelpers.callInstanceMethod<Unit>(screen, name)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
