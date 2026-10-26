package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.Switch
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 27, 31, 34], qualifiers = "en-w800dp-h600dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsSearchDismissalTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null
    private val screen get() = controller!!.get()
    private val root get() = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        RuntimeEnvironment.setFontScale(1f)
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun keyboardBackCollapsesSearchWithoutLeavingOrRebuildingTheSettingsPage() {
        val box = openSearch()
        val originalRoot = root
        box.setText("frame rate")
        idle()
        val popup = ReflectionHelpers.getField<PopupWindow>(screen, "settingsSearchPopup")
        assertTrue(popup.isShowing)

        assertTrue(box.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertTrue(box.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        idle()

        assertCollapsed()
        assertFalse(popup.isShowing)
        assertSame(originalRoot, root)
        assertTrue(searchControl().hasFocus())
        searchControl().performClick()
        idle()
        assertEquals("", (searchControl() as EditText).text.toString())
    }

    @Test @Config(sdk = [31, 34])
    fun keyboardHideCollapsesSearchButItsInitialHiddenInsetsDoNot() {
        val box = openSearch()
        keyboardInsets(visible = false)
        assertSame(box, searchControl())
        keyboardInsets(visible = true)
        assertSame(box, searchControl())

        keyboardInsets(visible = false)

        assertCollapsed()
    }

    @Test @Config(sdk = [31, 34])
    fun transientKeyboardHideDoesNotCloseAnEditorThatIsVisibleAgain() {
        val box = openSearch()
        keyboardInsets(visible = true, runPostedTasks = false)
        keyboardInsets(visible = false, runPostedTasks = false)
        keyboardInsets(visible = true)
        assertSame(box, searchControl())
    }

    @Test fun tappingInsideKeepsSearchAndTappingOutsideClosesItsResults() {
        val box = openSearch()
        tap(box)
        assertSame(box, searchControl())
        box.setText("frame rate")
        idle()
        val popup = ReflectionHelpers.getField<PopupWindow>(screen, "settingsSearchPopup")
        assertTrue(popup.isShowing)
        val before = root

        // Bottom-left content padding, outside both the editor and its separate results window.
        touch(MotionEvent.ACTION_DOWN, 2f, root.height - 2f)
        assertSame(box, searchControl())
        touch(MotionEvent.ACTION_UP, 2f, root.height - 2f)
        idle()

        assertCollapsed()
        assertFalse(popup.isShowing)
        assertSame(before, root)
    }

    @Test fun tappingAnotherSettingStillCompletesItsClickWhenSearchCloses() {
        openSearch()
        val before = root
        val toggle = descendants(root).filterIsInstance<Switch>().first()
        assertFalse(DiPlayPreferences.autoConnect(app))

        tap(toggle)

        assertTrue("The original DOWN/UP must reach the tapped setting", DiPlayPreferences.autoConnect(app))
        assertTrue(toggle.isChecked)
        assertCollapsed()
        assertSame("Closing search must preserve the setting just changed", before, root)
    }

    @Test @Config(sdk = [29], qualifiers = "zh-rCN-w500dp-h400dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactLargeFontSearchRestoresItsHeader() = captureDismissal("compact-large")

    @Test @Config(sdk = [29], qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fullLargeFontSearchRestoresItsHeader() = captureDismissal("full-large")

    private fun captureDismissal(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        val box = openSearch()
        screenshot(name, "expanded")
        box.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        box.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        idle()
        layout()
        assertCollapsed()
        screenshot(name, "collapsed")
    }

    private fun openSearch(): EditText {
        DiPlayPreferences.saveAutoConnect(app, false)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup().visible()
        layout()
        searchControl().performClick()
        idle()
        layout()
        return (searchControl() as EditText).also { assertTrue(it.hasFocus()) }
    }

    private fun keyboardInsets(visible: Boolean, runPostedTasks: Boolean = true) {
        // Robolectric has typed platform IME insets on API 30+. Older SDKs above exercise the
        // real pre-IME Back and outside-touch paths; they have no live IME/window-resize producer.
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (visible) 200 else 0))
            .setVisible(WindowInsetsCompat.Type.ime(), visible)
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        if (runPostedTasks) idle()
    }

    private fun assertCollapsed() {
        assertEquals("settings", ReflectionHelpers.getField<String>(screen, "page"))
        assertFalse(searchControl() is EditText)
        assertTrue(descendants(root).none { it is EditText })
    }

    private fun searchControl() = descendants(root).single {
        it.contentDescription == screen.getString(R.string.settings_search)
    }

    private fun tap(view: View) {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0] + view.width / 2f
        val y = location[1] + view.height / 2f
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_UP, x, y)
        idle()
    }

    private fun touch(action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 10, action, x, y, 0)
        try { screen.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun layout() {
        val density = screen.resources.displayMetrics.density
        val width = (screen.resources.configuration.screenWidthDp * density).toInt()
        val height = (screen.resources.configuration.screenHeightDp * density).toInt()
        val decor = screen.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, width, height)
        idle()
    }

    private fun screenshot(name: String, state: String) {
        val file = File("build/reports/search-dismissal/$name-$state.png")
        file.parentFile.mkdirs()
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
