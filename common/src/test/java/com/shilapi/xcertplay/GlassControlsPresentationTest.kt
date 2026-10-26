package com.shilapi.xcertplay

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassControlsPresentationTest {
    @Test fun glassMaterialsStayContinuousAcrossTheirCapsAtFractionalDisplayScales() {
        FidelityMaterials.install(RuntimeEnvironment.getApplication())
        val output = File("build/reports/glass-materials").apply { mkdirs() }
        for (primary in listOf(false, true))
        for ((width, height) in listOf(96 to 48, 160 to 56, 320 to 64))
        for (scale in listOf(1f, 1.5f, 2.625f)) for (opacity in listOf(112, 255)) {
            val palette = DiPlayPalette.DARK
            val material = GlassChrome.surface(if (primary) palette.accent else palette.button,
                palette.outline, 1f, height / 2f, primary = primary).apply { alpha = opacity }
            val bitmap = Bitmap.createBitmap(((width + 16) * scale).roundToInt(), ((height + 16) * scale).roundToInt(), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                canvas.translate(.35f, .2f)
                material.setBounds(8, 8, width + 8, height + 8)
                material.draw(canvas)
                if (opacity == 255 && scale == 2.625f) {
                    File(output, "night-${if (primary) "primary" else "secondary"}-$width.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
                // A narrow dip at either cap is a compositing seam, not the broad glass highlight.
                for (join in listOf(8 + height / 2f, 8 + width - height / 2f)) {
                    val center = ((join + .35f) * scale).roundToInt()
                    val y1 = ((8 + height / 3f) * scale).roundToInt()
                    val y2 = ((8 + height * 2 / 3f) * scale).roundToInt()
                    fun alphaAt(x: Int) = (y1 until y2).map { Color.alpha(bitmap.getPixel(x, it)) }.average()
                    for (x in center - 1..center + 1) {
                        val surrounding = (alphaAt(x - 2) + alphaAt(x + 2)) / 2
                        assertTrue("Vertical alpha seam: primary=$primary width=$width opacity=$opacity scale=$scale x=$x " +
                            "difference=${abs(alphaAt(x) - surrounding)}",
                            abs(alphaAt(x) - surrounding) <= 3.0)
                    }
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun glassMaterialsDoNotPaintABoxOutsideTheirRoundedEnds() {
        val app = RuntimeEnvironment.getApplication()
        FidelityMaterials.install(app)
        for (primary in listOf(false, true)) for (width in listOf(96, 240)) {
            val palette = DiPlayPalette.DARK
            val material = GlassChrome.surface(if (primary) palette.accent else palette.button,
                palette.outline, 1f, 24f, primary = primary)
            val bitmap = Bitmap.createBitmap(width + 16, 64, Bitmap.Config.ARGB_8888)
            try {
                material.draw(Canvas(bitmap)) // Unmeasured/invisible controls have empty bounds.
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                material.setBounds(8, 8, width + 8, 56)
                material.draw(Canvas(bitmap))
                for ((x, y) in listOf(8 to 8, width + 7 to 8, 8 to 55, width + 7 to 55,
                    4 to 32, width + 12 to 32, width / 2 to 4, width / 2 to 60)) {
                    assertEquals("Rectangular material/glow at $x,$y (primary=$primary, width=$width)",
                        0, Color.alpha(bitmap.getPixel(x, y)))
                }
                val interior = IntArray(width * 48)
                bitmap.getPixels(interior, 0, width, 8, 8, width, 48)
                assertTrue("The rounded glass material must remain visible", interior.any { Color.alpha(it) > 0 })
            } finally { bitmap.recycle() }
        }
    }

    @Test fun shortDialogActionsKeepCompactEqualWidthsAndMinimumTouchHeight() {
        withDialog(listOf("Cancel", "Save"), 560) { actions, bar ->
            assertEquals(actions[0].width, actions[1].width)
            assertEquals(actions[0].height, actions[1].height)
            assertTrue(actions.all { it.width <= FidelitySettingsMetrics.DIALOG_ACTION_MAX_WIDTH_DP })
            assertTrue("Short actions should keep the standard height: ${actions.map { it.height }}",
                actions.all { it.height == FidelitySettingsMetrics.DIALOG_ACTION_DP })
            assertTrue("Actions must leave space before their compact end-aligned group", actions.minOf { it.left } > 0)
            assertTrue(actions.maxOf { it.right } <= bar.width)
        }
    }

    @Test fun longThreeActionDialogsWrapAndRemainReachableOnNarrowWindows() {
        RuntimeEnvironment.setFontScale(1.3f)
        try {
            withDialog(listOf("Cancel", "Apply and reconnect CarPlay", "Reset to current values"), 280) { actions, bar ->
                assertTrue(actions.all { it.height >= FidelitySettingsMetrics.DIALOG_ACTION_DP })
                assertTrue(actions.all { it.left >= 0 && it.right <= bar.width && it.bottom <= bar.height })
                for (action in actions) {
                    for (line in 0 until action.layout.lineCount) {
                        assertEquals(0, action.layout.getEllipsisCount(line))
                        assertTrue(action.layout.getLineMax(line) <= action.layout.width + 2)
                    }
                    assertTrue(action.layout.getLineBottom(action.layout.lineCount - 1) <=
                        action.height - action.compoundPaddingTop - action.compoundPaddingBottom + 2)
                }
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun withDialog(labels: List<String>, width: Int,
        check: (List<android.widget.Button>, ViewGroup) -> Unit) {
        val screen = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val builder = GlassDialogBuilder(screen).setTitle("CarPlay")
            .setNegativeButton(labels[0], null).setPositiveButton(labels[1], null)
        if (labels.size > 2) builder.setNeutralButton(labels[2], null)
        val dialog = builder.show()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val decor = dialog.window!!.decorView
            repeat(3) {
                decor.forceLayout()
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST))
                decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            }
            val actions = listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL)
                .map(dialog::getButton).filter { it.visibility == View.VISIBLE }
            check(actions, actions.first().parent as ViewGroup)
        } finally { dialog.dismiss(); screen.finish() }
    }
}
