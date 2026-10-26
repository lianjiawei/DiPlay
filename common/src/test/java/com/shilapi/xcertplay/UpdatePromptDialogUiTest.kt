package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], qualifiers = "en-w900dp-h600dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class UpdatePromptDialogUiTest {
    private var controller: ActivityController<DiPlayActivity>? = null
    private var prompt: UpdatePromptDialog? = null
    private val release = UpdateRelease("0.2.27-precision.1", "CorsairPlay.apk", "https://example.com/app.apk",
        "https://example.com/SHA256SUMS.txt", "## Changes\n- USB recovery\n- Audio fixes\n- Update dialog")
    private var downloads = 0
    private var installs = 0

    @After fun cleanup() {
        prompt?.dismiss()
        controller?.pause()?.stop()?.destroy()
        UpdateAvailability.clearAllForTest(RuntimeEnvironment.getApplication())
    }

    @Test fun unknownLengthIsIndeterminateAndCannotInstall() {
        val dialog = open(UpdatePromptStage.DOWNLOADING, null)
        assertTrue(dialog.progress.isIndeterminate)
        assertEquals(View.GONE, dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(0, downloads)
        assertEquals(0, installs)
    }

    @Test fun knownProgressHasAnAccessiblePercentage() {
        val dialog = open(UpdatePromptStage.DOWNLOADING, 42)
        assertFalse(dialog.progress.isIndeterminate)
        assertEquals(42, dialog.progress.progress)
        assertTrue(dialog.progress.contentDescription.contains("42"))
        assertEquals(0, installs)
    }

    @Test fun verificationHasNoInstallActionEvenAtOneHundredPercentDownloaded() {
        val dialog = open(UpdatePromptStage.DOWNLOADING, 100)
        dialog.update(release, UpdatePromptStage.VERIFYING, 100, null)
        assertTrue(dialog.progress.isIndeterminate)
        assertEquals(View.GONE, dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(0, installs)
    }

    @Test fun readyWaitsForAnExplicitInstallTap() {
        val dialog = open(UpdatePromptStage.READY, 100)
        assertEquals(0, installs)
        dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, installs)
        assertEquals(0, downloads)
    }

    @Test fun failureCanOnlyRetryTheDownload() {
        val dialog = open(UpdatePromptStage.FAILED, null)
        assertEquals(controller!!.get().getString(R.string.update_retry), dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, downloads)
        assertEquals(0, installs)
    }

    @Test @Config(sdk = [29], qualifiers = "zh-rCN-w320dp-h600dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactChineseLargeFontScreenshots() = capture("compact-zh")

    @Test @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun expandedEnglishLargeFontScreenshots() = capture("expanded-en")

    private fun open(stage: UpdatePromptStage, percent: Int?): UpdatePromptDialog {
        val app = RuntimeEnvironment.getApplication()
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        val c = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
        controller = c
        return UpdatePromptDialog(c.get(), { downloads++ }, { installs++ }, {}).also {
            prompt = it
            it.show(release, stage, percent, "Test download failure")
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun capture(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        val dialog = open(UpdatePromptStage.OFFER, null)
        val screen = controller!!.get()
        val metrics = screen.resources.displayMetrics
        val config = screen.resources.configuration
        val width = ((minOf(config.screenWidthDp, 600) - 32) * metrics.density).toInt()
        val height = ((config.screenHeightDp - 32) * metrics.density).toInt()
        for ((state, percent) in listOf(UpdatePromptStage.OFFER to null,
            UpdatePromptStage.DOWNLOADING to 42, UpdatePromptStage.READY to 100)) {
            dialog.update(release, state, percent, null)
            val root = dialog.dialog.window!!.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            for (button in listOf(dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE),dialog.dialog.getButton(AlertDialog.BUTTON_NEGATIVE)).filter { it.visibility==View.VISIBLE }) {
                assertTrue("Dialog action must keep its touch target",button.width>=96*metrics.density && button.height>=48*metrics.density)
                val bar=button.parent as ViewGroup
                assertTrue("Dialog action must fit its container",button.left>=0 && button.right<=bar.width && button.top>=0 && button.bottom<=bar.height)
                val layout=button.layout
                assertTrue("Dialog action must have room for its text: ${button.text}, width=${button.width}, layout=${layout.width}",
                    (0 until layout.lineCount).all { layout.getLineMax(it)<=layout.width+2 })
            }
            for (text in descendants(root).filterIsInstance<TextView>()) {
                if (text.visibility != View.VISIBLE || text.id == android.R.id.message) continue
                val layout = text.layout ?: continue
                assertTrue("Truncated dialog control: ${text.text}",
                    (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
            }
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(image))
                val path = Paths.get("build/reports/update-prompt-ui/$name-${state.name.lowercase()}.png")
                Files.createDirectories(path.parent)
                Files.newOutputStream(path).use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
