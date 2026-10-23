package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.text.StaticLayout
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

/** Custom-drawn rows need geometry and touch checks in addition to TextView's own layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsInlineHelpTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null

    @Before fun prepare() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        DiPlayPreferences.saveAutoConnect(app, false)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        AirPlayPersistence.saveAppAppearance(app, AppAppearance.DARK)
        AirPlayPersistence.saveAudioFocusEnabled(app, true)
        CarPlayBackgroundSession.clear()
    }

    @After fun cleanup() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        CarPlayBackgroundSession.clear()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test @Config(sdk = [25, 27, 29])
    fun inlineHelpAndTheSettingHaveSeparateActionsAndDraggingDoesNotOpenHelp() {
        val screen = open()
        render(screen, SettingsCategory.VEHICLE)
        val row = gestureRow(screen)
        // Robolectric's pre-28 legacy StaticLayout does not return real glyph line ends.
        // Those SDKs exercise hit testing; the native capture cases check complete text.
        verifyRow(row, checkTextMetrics = Build.VERSION.SDK_INT >= 28)
        val icon = field<Rect>(row, "helpIconBounds")
        val target = field<RectF>(row, "helpTouchBounds")
        tap(row, icon.exactCenterX(), icon.exactCenterY())
        assertHelp(screen)

        val value = field<StaticLayout>(row, "valueLayout")
        tap(row, field<Float>(row, "valueX") + value.width / 2f,
            field<Float>(row, "rowHeaderTop") + field<Int>(row, "rowHeaderHeight") / 2f)
        val choices = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("Tapping the value still opens the setting", choices.listView.count > 0)
        choices.dismiss()

        val slop = ViewConfiguration.get(screen).scaledTouchSlop * 2f
        touch(row, MotionEvent.ACTION_DOWN, target.centerX(), target.centerY())
        touch(row, MotionEvent.ACTION_MOVE, target.centerX() - slop, target.centerY())
        touch(row, MotionEvent.ACTION_UP, target.centerX(), target.centerY())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("A drag must not open a help dialog", ShadowAlertDialog.getLatestAlertDialog().isShowing)
        touch(row, MotionEvent.ACTION_DOWN, target.centerX(), target.centerY())
        touch(row, MotionEvent.ACTION_CANCEL, target.centerX(), target.centerY())
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)

        val summary = field<StaticLayout>(row, "summaryLayout")
        tap(row, field<Float>(row, "summaryX") + summary.width / 2f,
            field<Float>(row, "summaryY") + summary.height / 2f)
        assertHelp(screen)
        assertTrue(row.performLongClick())
        assertHelp(screen)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun landscapeAllCategories() = capture("landscape", 1f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun landscapeLargeTextAllCategories() = capture("landscape-large", 1.3f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w360dp-h760dp-port-xhdpi")
    fun narrowPortraitAllCategories() = capture("portrait-large", 1.3f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
    fun fullLargeTextAllCategories() = capture("full-large", 1.3f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w640dp-h360dp-land-xhdpi")
    fun arabicLargeTextAllCategories() = capture("rtl-large", 1.3f)

    @Test fun longValuesUseSpareTitleSpaceAndDescriptionsDoNotReserveAnEmptyIconColumn() {
        val screen = open()
        render(screen, SettingsCategory.VEHICLE)
        val row = gestureRow(screen)
        row.text = "A · A longer selected value that fits beside a short title"
        row.forceLayout()
        row.measure(exact(row.width), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        verifyRow(row)
        assertEquals(1, field<StaticLayout>(row, "titleLayout").lineCount)
        assertEquals("A short title must leave room for the value", 1, field<StaticLayout>(row, "valueLayout").lineCount)
        val summary = field<StaticLayout>(row, "summaryLayout")
        val density = row.resources.displayMetrics.density
        assertEquals("The description can use the complete text width",
            row.width - ((FidelitySettingsMetrics.ROW_START_DP + FidelitySettingsMetrics.ROW_END_DP) * density).toInt(),
            summary.width)
        ReflectionHelpers.setField(row, "rowSummary", "")
        row.forceLayout()
        row.measure(exact(row.width), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("Do not add new markers to rows that had no visible help", field<Rect>(row, "helpIconBounds").isEmpty)
    }

    private fun capture(name: String, scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        val screen = open()
        var rowsWithHelp = 0
        for (category in SettingsCategory.entries) {
            render(screen, category)
            val rows = descendants(root(screen)).filterIsInstance<Button>().filter {
                it.javaClass.simpleName == "SettingButton" && field<Boolean>(it, "rowPresentation") && visible(it)
            }.toList()
            rows.forEach { row ->
                verifyRow(row)
                if (!field<Rect>(row, "helpIconBounds").isEmpty) rowsWithHelp++
            }
            val folder = File("build/reports/settings-inline-help/$name")
            screenshot(root(screen), File(folder, "${category.name.lowercase()}.png"))
            val content = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll").getChildAt(0)
            screenshot(content, File(folder, "${category.name.lowercase()}-content.png"))
        }
        assertTrue("Audit must exercise help markers across categories", rowsWithHelp >= 5)
    }

    private fun verifyRow(row: Button, checkTextMetrics: Boolean = true) {
        val title = field<StaticLayout>(row, "titleLayout")
        val value = field<StaticLayout?>(row, "valueLayout")
        val summary = field<StaticLayout?>(row, "summaryLayout")
        val headerTop = field<Float>(row, "rowHeaderTop")
        val headerHeight = field<Int>(row, "rowHeaderHeight")
        val centre = headerTop + headerHeight / 2f
        val icon = field<Rect>(row, "helpIconBounds")
        val target = field<RectF>(row, "helpTouchBounds")
        val density = row.resources.displayMetrics.density
        fun rect(layout: StaticLayout, x: Float) = RectF(x, centre - layout.height / 2f, x + layout.width, centre + layout.height / 2f)
        val titleBounds = rect(title, field(row, "titleX"))
        assertTrue("Title fits the row: ${row.text}", titleBounds.left >= 0 && titleBounds.right <= row.width + 1)
        value?.let {
            val valueBounds = rect(it, field(row, "valueX"))
            assertFalse("Title and value cannot overlap: ${row.text}", RectF.intersects(titleBounds, valueBounds))
            assertTrue(valueBounds.left >= 0 && valueBounds.right <= row.width + 1)
            if (!icon.isEmpty) assertFalse("Value cannot overlap the help target", RectF.intersects(valueBounds, target))
        }
        for (layout in if (checkTextMetrics) listOfNotNull(title, value, summary) else emptyList()) {
            assertEquals("All text must be laid out", layout.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) {
                assertEquals(0, layout.getEllipsisCount(line))
                assertTrue("Text must fit its column: ${layout.text}", layout.getLineMax(line) <= layout.width + 2)
            }
        }
        if (!icon.isEmpty) {
            assertEquals("The marker shares the title/value line", centre, icon.exactCenterY(), 1f)
            assertTrue("The icon must fit the help touch target", target.contains(RectF(icon)))
            assertTrue("Help retains a 48dp target", target.width() >= 48 * density - 1 && target.height() >= 48 * density - 1)
            assertFalse("Help cannot overlap the title", RectF.intersects(titleBounds, target))
            assertTrue("Help stays inside the row", target.left >= 0 && target.right <= row.width && target.top >= 0 && target.bottom <= row.height)
        }
        summary?.let {
            val summaryY = field<Float>(row, "summaryY")
            val summaryX = field<Float>(row, "summaryX")
            assertTrue("Description follows the entire header", summaryY >= headerTop + headerHeight)
            assertTrue("Description fits vertically", summaryY + it.height <= row.height)
            assertTrue("Description fits horizontally", summaryX >= 0 && summaryX + it.width <= row.width + 1)
            if (!icon.isEmpty) assertTrue("Help cannot fall onto a separate description line", icon.bottom <= summaryY)
        }
    }

    private fun assertHelp(screen: DiPlayActivity) {
        shadowOf(Looper.getMainLooper()).idle()
        val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertNull("Help must not open the choice picker", dialog.listView)
        assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString().contains(screen.getString(R.string.settings_gesture_fingers_hint))
        })
        dialog.dismiss()
    }

    private fun open(): DiPlayActivity {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup().visible()
        return controller!!.get()
    }

    private fun gestureRow(screen: DiPlayActivity) = descendants(root(screen)).filterIsInstance<Button>()
        .single { it.text.startsWith(screen.getString(R.string.settings_gesture_fingers_label) + " · ") }

    private fun render(screen: DiPlayActivity, category: SettingsCategory) {
        ReflectionHelpers.setField(screen, "settingsCategory", category)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val root = root(screen)
        root.layoutDirection = screen.resources.configuration.layoutDirection
        val metrics = screen.resources.displayMetrics
        repeat(2) {
            descendants(root).forEach { it.forceLayout() }
            root.measure(exact(metrics.widthPixels), exact(metrics.heightPixels))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun tap(row: Button, x: Float, y: Float) {
        touch(row, MotionEvent.ACTION_DOWN, x, y)
        touch(row, MotionEvent.ACTION_UP, x, y)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun touch(row: Button, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 20, action, x, y, 0).let { event ->
            try { row.dispatchTouchEvent(event) } finally { event.recycle() }
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
    private fun visible(view: View): Boolean = generateSequence(view) { it.parent as? View }.all { it.visibility == View.VISIBLE }
    private fun root(screen: DiPlayActivity) = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    private fun <T> field(view: View, name: String): T = ReflectionHelpers.getField(view, name)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
