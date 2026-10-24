package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import java.io.File
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

/** Native screenshots plus reflow/target checks, including all content below the fold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiPolishPresentationTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun cleanup() {
        RuntimeEnvironment.setFontScale(1f)
        CarPlayBackgroundSession.clear()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun landscape() = capture("landscape", 1f)

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun landscapeLargeFont() = capture("landscape-large", 1.3f)

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
    fun expandedLargeFont() = capture("expanded-large", 1.3f)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h840dp-port-xhdpi")
    fun portraitLargeFont() = capture("portrait-large", 1.3f)

    @Test @Config(qualifiers = "ar-ldrtl-w640dp-h360dp-land-xhdpi")
    fun arabicLargeFont() = capture("arabic-large", 1.3f)

    private fun capture(name: String, fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        DiPlayPreferences.saveAutoConnect(app, false)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "我的 iPhone")
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        for (night in listOf(true, false)) {
            AirPlayPersistence.saveAppAppearance(app, if (night) AppAppearance.DARK else AppAppearance.LIGHT)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup().visible()
            val screen = controller.get()
            try {
                // Presentation state only: source-only test APKs intentionally lack an MFi identity.
                ReflectionHelpers.setField(screen, "setupError", null)
                CarPlayBackgroundSession.active = true
                for (category in listOf(SettingsCategory.OVERVIEW, SettingsCategory.ADVANCED, SettingsCategory.DISPLAY)) {
                    ReflectionHelpers.setField(screen, "settingsCategory", category)
                    ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                    val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    root.layoutDirection = screen.resources.configuration.layoutDirection
                    val density = screen.resources.displayMetrics.density
                    val width = (screen.resources.configuration.screenWidthDp * density).toInt()
                    val height = (screen.resources.configuration.screenHeightDp * density).toInt()
                    repeat(3) {
                        descendants(root).forEach { it.forceLayout() }
                        root.measure(exact(width), exact(height))
                        root.layout(0, 0, width, height)
                        shadowOf(Looper.getMainLooper()).idle()
                    }
                    for (text in descendants(root).filterIsInstance<TextView>()) {
                        if (!visible(text, root) || text is EditText || text.text.isNullOrBlank()) continue
                        val layout = text.layout ?: continue
                        for (line in 0 until layout.lineCount) {
                            assertEquals("Ellipsized ${text.text}", 0, layout.getEllipsisCount(line))
                            assertTrue("Wide text: ${text.text}", layout.getLineMax(line) <= layout.width + 2)
                        }
                        assertTrue("Clipped ${text.text}", layout.lineCount == 0 ||
                            layout.getLineBottom(layout.lineCount - 1) <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
                        if (text is Button) assertTrue("Small action: ${text.text}", text.height >= 48 * density - 1)
                    }
                    val dir = File("build/reports/ui-polish/$name-${if (night) "night" else "day"}")
                    screenshot(root, File(dir, "${category.name.lowercase()}.png"))
                    val scroll = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll")
                    val content = scroll.getChildAt(0)
                    assertTrue(content.height > 0)
                    screenshot(content, File(dir, "${category.name.lowercase()}-content.png"))
                    if (content.height > scroll.height) {
                        scroll.scrollTo(0, content.height)
                        assertTrue("Settings must remain scrollable", scroll.scrollY > 0)
                        scroll.scrollTo(0, 0)
                    }
                    if (category == SettingsCategory.ADVANCED) {
                        val page = descendants(root).filterIsInstance<TextView>().first { it.text == screen.getString(R.string.settings_advanced) && it.textSize > 20 * screen.resources.displayMetrics.scaledDensity }
                        val body = descendants(root).filterIsInstance<TextView>().first { it.text == screen.getString(R.string.settings_advanced_caution_description) }
                        assertTrue("Page title and description have distinct hierarchy", page.textSize > body.textSize * 1.5f)
                    }
                    if (category == SettingsCategory.DISPLAY) {
                        val choice = descendants(root).filterIsInstance<Button>().first {
                            it.text.startsWith(screen.getString(R.string.carplay_night_mode) + " · ")
                        }
                        choice.performClick()
                        shadowOf(Looper.getMainLooper()).idle()
                        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                        try {
                            val decor = dialog.window!!.decorView
                            decor.measure(exact(minOf(width, (560 * density).toInt())),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                            screenshot(decor, File(dir, "display-choice.png"))
                        } finally { dialog.dismiss() }
                    }
                }
            } finally {
                CarPlayBackgroundSession.clear()
                controller.pause().stop().destroy()
            }
        }
    }

    private fun screenshot(view: View, file: File) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            file.parentFile.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun visible(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }
    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
