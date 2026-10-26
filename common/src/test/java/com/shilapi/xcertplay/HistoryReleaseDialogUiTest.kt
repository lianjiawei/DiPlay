package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w320dp-h600dp")
class HistoryReleaseDialogUiTest {
    private var controller: ActivityController<DiPlayActivity>? = null
    private var prompt: HistoryReleaseDialog? = null

    @After fun cleanup() {
        prompt?.dialog?.dismiss()
        controller?.pause()?.stop()?.destroy()
        UpdateAvailability.clearAllForTest(RuntimeEnvironment.getApplication())
    }

    @Test fun longNotesScrollWhileBothActionsRemainVisible() {
        val dialog = open()
        layout(dialog)
        assertTrue(dialog.messageView.measuredHeight > dialog.messageScroll.measuredHeight)
        assertTrue(dialog.messageScroll.canScrollVertically(1))
        for (button in listOf(dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE),
            dialog.dialog.getButton(AlertDialog.BUTTON_NEGATIVE))) {
            assertEquals(View.VISIBLE, button.visibility)
            assertTrue(button.height >= 48 * button.resources.displayMetrics.density)
            assertEquals(1, button.layout.lineCount)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactChineseLargeFontScreenshot() = capture("compact-zh")

    @Test @Config(qualifiers = "en-w1000dp-h700dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun expandedEnglishLargeFontScreenshot() = capture("expanded-en")

    private fun open(): HistoryReleaseDialog {
        RuntimeEnvironment.setFontScale(1.3f)
        val app = RuntimeEnvironment.getApplication()
        val c = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
        controller = c
        val release = UpdateRelease("1.1.7", "CarPlay-1.1.7.apk", "https://example.com/app.apk",
            "https://example.com/SHA256SUMS.txt", (1..80).joinToString("\n") { "- 第 $it 项更新说明" })
        return HistoryReleaseDialog(c.get(), release) {}.also {
            prompt = it
            it.show()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun layout(dialog: HistoryReleaseDialog): View {
        val root = dialog.dialog.window!!.decorView
        val metrics = root.resources.displayMetrics
        val config = root.resources.configuration
        val width = ((minOf(config.screenWidthDp, 600) - 32) * metrics.density).toInt()
        val height = ((config.screenHeightDp - 32) * metrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        return root
    }

    private fun capture(name: String) {
        val dialog = open()
        val root = layout(dialog)
        for (text in descendants(root).filterIsInstance<TextView>()) {
            if (text.visibility != View.VISIBLE || text === dialog.messageView) continue
            val layout = text.layout ?: continue
            assertTrue("Truncated dialog control: ${text.text}",
                (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
        }
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(image))
            val path = Paths.get("build/reports/history-release-ui/$name.png")
            Files.createDirectories(path.parent)
            Files.newOutputStream(path).use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
