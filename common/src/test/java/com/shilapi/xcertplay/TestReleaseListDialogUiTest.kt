package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.update.UpdateRelease
import java.io.File
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w320dp-h600dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TestReleaseListDialogUiTest {
    @Test fun rowsShowLocalTimeLatestAndSeparatorsAndSelectTheRightBuild() {
        val oldZone = TimeZone.getDefault()
        RuntimeEnvironment.setFontScale(1.3f)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible()
        try {
            val releases = listOf("test-20261028045539-8db690b6", "test-20261028033553-8245fef5")
                .map { UpdateRelease(it, "$it.apk", "https://example.com/a", "https://example.com/b") }
            var selected: UpdateRelease? = null
            val picker = TestReleaseListDialog(activity.get(), releases) { selected = it }
            picker.show()
            val first = picker.rows.getChildAt(0) as LinearLayout
            val second = picker.rows.getChildAt(2) as LinearLayout
            assertEquals("2026-10-28 12:55:39 · 最新", (first.getChildAt(0) as TextView).text.toString())
            assertEquals("8db690b6", (first.getChildAt(1) as TextView).text.toString())
            assertEquals("2026-10-28 11:35:53", (second.getChildAt(0) as TextView).text.toString())
            assertEquals(View.VISIBLE, picker.rows.getChildAt(1).visibility)
            val view = picker.rows
            val width = activity.get().resources.displayMetrics.widthPixels
            val height = (300 * activity.get().resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(image))
                val file = File("build/reports/test-release-list/compact-zh.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
            second.performClick()
            assertEquals(releases[1], selected)
            assertFalse(picker.dialog.isShowing)
        } finally {
            activity.pause().stop().destroy()
            TimeZone.setDefault(oldZone)
            RuntimeEnvironment.setFontScale(1f)
        }
    }
}
