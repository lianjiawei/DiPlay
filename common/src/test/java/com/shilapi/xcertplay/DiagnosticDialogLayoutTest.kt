package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticDialogLayoutTest {
    private val uri = Uri.parse("content://com.example.reports/report.txt")
    private val report = "Android 9 / API 28\nTest diagnostic report"
    private val path = "Downloads/Lincoln/Lincoln-20261024-074457-330.txt"

    @Test fun savedReportFitsContentInBothOrientationsLargeTextAndRtl() {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        try {
            for ((name, qualifiers) in listOf(
                "compact" to "zh-rCN-w800dp-h480dp-land-mdpi",
                "short-large" to "zh-rCN-w600dp-h360dp-land-mdpi",
                "full-large" to "zh-rCN-w1280dp-h720dp-land-mdpi",
                "portrait-large" to "zh-rCN-w400dp-h840dp-port-xhdpi",
                "rtl-large" to "ar-ldrtl-w640dp-h360dp-land-mdpi")) {
                RuntimeEnvironment.setQualifiers(qualifiers)
                RuntimeEnvironment.setFontScale(if (name == "compact") 1f else 1.3f)
                DiPlayPreferences.saveAutoConnect(app, false)
                UpdateAvailability.saveBackgroundChecksEnabled(app, false)
                AirPlayPersistence.saveAppAppearance(app, AppAppearance.DARK)
                val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                    Intent(app, DiPlayActivity::class.java).putExtra("page", "diagnostics")).setup().visible()
                try {
                    val activity = controller.get()
                    val dialog = savedDialog(activity)
                    // Model an OEM that retains a screen-sized height for short message dialogs.
                    dialog.window!!.setLayout(560, activity.resources.displayMetrics.heightPixels)
                    measure(dialog)
                    val decor = dialog.window!!.decorView as ViewGroup
                    val actions = listOf(AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)
                        .map(dialog::getButton)
                    val rects = actions.map { action ->
                        assertTrue(action.isShown)
                        Rect(0, 0, action.width, action.height).also { decor.offsetDescendantRectToMyCoords(action, it) }
                    }
                    val density = activity.resources.displayMetrics.density
                    assertTrue("No empty lower half: $name", decor.height - rects.maxOf { it.bottom } <= 32 * density)
                    assertTrue("Content-driven short dialog: $name", decor.height < activity.resources.displayMetrics.heightPixels * .9f)
                    for (rect in rects) {
                        assertTrue("Action inside dialog: $name $rect", rect.left >= 0 && rect.top >= 0 && rect.right <= decor.width && rect.bottom <= decor.height)
                        assertTrue(rect.height() >= 48 * density)
                    }
                    for (i in rects.indices) for (j in i + 1 until rects.size) assertFalse(Rect.intersects(rects[i], rects[j]))
                    val message = decor.findViewById<TextView>(android.R.id.message)
                    assertEquals(path, message.text.toString())
                    assertTrue(message.height >= message.layout.height)
                    capture(decor, "$name-saved-report")
                    dialog.dismiss()
                    // Long message content must still leave actions reachable.
                    val long = activity.appDialogBuilder().setTitle(R.string.diagnostic_report_saved)
                        .setMessage((1..35).joinToString("\n") { "$it / $path" })
                        .setPositiveButton(R.string.done, null).show()
                    measure(long)
                    val longDecor = long.window!!.decorView as ViewGroup
                    val done = long.getButton(AlertDialog.BUTTON_POSITIVE)
                    val doneRect = Rect(0, 0, done.width, done.height)
                    longDecor.offsetDescendantRectToMyCoords(done, doneRect)
                    assertTrue("Long message keeps button reachable: $name", doneRect.bottom <= longDecor.height)
                    long.setMessage(path)
                    measure(long)
                    assertTrue("Updated short message shrinks again: $name", long.window!!.attributes.height < activity.resources.displayMetrics.heightPixels * .9f)
                    long.dismiss()
                } finally { controller.pause().stop().destroy() }
            }
        } finally { RuntimeEnvironment.setFontScale(1f); RuntimeEnvironment.setQualifiers("en") }
    }

    @Test fun savedReportActionsStillViewShareAndDismiss() {
        val app = RuntimeEnvironment.getApplication()
        DiPlayPreferences.saveAutoConnect(app, false)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        try {
            val activity = controller.get()
            savedDialog(activity).apply {
                measure(this)
                getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            }
            shadowOf(Looper.getMainLooper()).idle()
            val chooser = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            @Suppress("DEPRECATION") val sharedUri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(uri, sharedUri)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            savedDialog(activity).apply { measure(this); getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            shadowOf(Looper.getMainLooper()).idle()
            val viewer = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(descendants(viewer.window!!.decorView).filterIsInstance<TextView>().any { it.isTextSelectable && it.text.toString() == report })
            viewer.dismiss()
            savedDialog(activity).apply { measure(this); getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); shadowOf(Looper.getMainLooper()).idle(); assertFalse(isShowing) }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun narrowPortraitSavedReportStacksFullWidthActionsBelowItsMessage() {
        val app = RuntimeEnvironment.getApplication()
        try {
            RuntimeEnvironment.setQualifiers("zh-rCN-w320dp-h640dp-port-xhdpi")
            RuntimeEnvironment.setFontScale(1.3f)
            DiPlayPreferences.saveAutoConnect(app, false)
            UpdateAvailability.saveBackgroundChecksEnabled(app, false)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(app, DiPlayActivity::class.java).putExtra("page", "diagnostics")).setup().visible()
            try {
                val dialog = savedDialog(controller.get())
                measure(dialog)
                val decor = dialog.window!!.decorView as ViewGroup
                val actions = listOf(AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE,
                    AlertDialog.BUTTON_POSITIVE).map(dialog::getButton)
                val row = actions.first().parent as GlassDialogActions
                assertEquals(LinearLayout.VERTICAL, row.orientation)
                val available = row.width - row.paddingLeft - row.paddingRight
                assertTrue("Stacked actions should fill the available width", actions.all { it.width == available })
                val message = decor.findViewById<TextView>(android.R.id.message)
                val messageRect = Rect(0, 0, message.width, message.height).also {
                    decor.offsetDescendantRectToMyCoords(message, it)
                }
                val actionRects = actions.map { button ->
                    Rect(0, 0, button.width, button.height).also {
                        decor.offsetDescendantRectToMyCoords(button, it)
                    }
                }
                assertTrue("Actions should follow the report path", actionRects.all { it.top >= messageRect.bottom })
                assertTrue("Actions remain inside the dialog", actionRects.all {
                    it.left >= 0 && it.right <= decor.width && it.bottom <= decor.height
                })
                capture(decor, "narrow-portrait-stacked-actions")
                dialog.dismiss()
            } finally { controller.pause().stop().destroy() }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en")
        }
    }

    @Test fun savedReportOverridesOemSideActionsInPortraitAndLandscape() {
        val app = RuntimeEnvironment.getApplication()
        try {
            for ((name, qualifiers) in listOf(
                "portrait" to "zh-rCN-w400dp-h840dp-port-xhdpi",
                "landscape" to "zh-rCN-w800dp-h480dp-land-mdpi",
            )) {
                RuntimeEnvironment.setQualifiers(qualifiers)
                RuntimeEnvironment.setFontScale(1.3f)
                DiPlayPreferences.saveAutoConnect(app, false)
                UpdateAvailability.saveBackgroundChecksEnabled(app, false)
                val controller = Robolectric.buildActivity(
                    DiPlayActivity::class.java,
                    Intent(app, DiPlayActivity::class.java).putExtra("page", "diagnostics"),
                ).setup().visible()
                try {
                    val dialog = savedDialog(controller.get())
                    shadowOf(Looper.getMainLooper()).idle()
                    val decor = dialog.window!!.decorView as ViewGroup
                    val parentPanel = frameworkPanel<LinearLayout>(decor, "parentPanel")
                    val contentPanel = frameworkPanel<View>(decor, "contentPanel")
                    val buttonPanel = frameworkPanel<View>(decor, "buttonPanel")

                    // Model the Huawei/OEM layout shown in the field report: message on the left
                    // and a narrow, full-height action panel on the right.
                    parentPanel.orientation = LinearLayout.HORIZONTAL
                    contentPanel.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    buttonPanel.layoutParams = LinearLayout.LayoutParams(128, ViewGroup.LayoutParams.MATCH_PARENT)

                    // Message changes are re-fitted by GlassDialogBuilder after OEM layout passes.
                    decor.findViewById<TextView>(android.R.id.message).text = "$path "
                    shadowOf(Looper.getMainLooper()).idle()
                    measure(dialog)

                    assertEquals("Actions belong below the message: $name", LinearLayout.VERTICAL, parentPanel.orientation)
                    assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, buttonPanel.layoutParams.width)
                    assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, buttonPanel.layoutParams.height)
                    val rects = listOf(AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE)
                        .map(dialog::getButton)
                        .map { action ->
                            Rect(0, 0, action.width, action.height).also {
                                decor.offsetDescendantRectToMyCoords(action, it)
                            }
                        }
                    assertEquals("Short actions stay in one row: $name", 1, rects.map { it.top }.distinct().size)
                    assertEquals("Actions use equal widths: $name", 1, rects.map { it.width() }.distinct().size)
                    assertTrue("Action row stays inside the dialog: $name", rects.all {
                        it.left >= 0 && it.right <= decor.width && it.bottom <= decor.height
                    })
                    capture(decor, "$name-oem-side-actions-fixed")
                    dialog.dismiss()
                } finally {
                    controller.pause().stop().destroy()
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f)
            RuntimeEnvironment.setQualifiers("en")
        }
    }

    private fun savedDialog(activity: DiPlayActivity): AlertDialog = DiPlayActivity::class.java
        .getDeclaredMethod("showSavedDiagnosticReport", DiagnosticExportStore.SavedReport::class.java, String::class.java, String::class.java)
        .apply { isAccessible = true }.invoke(activity, DiagnosticExportStore.SavedReport(uri), report, path) as AlertDialog

    private fun measure(dialog: AlertDialog) {
        shadowOf(Looper.getMainLooper()).idle()
        val decor = dialog.window!!.decorView
        val attrs = dialog.window!!.attributes
        assertTrue("Explicitly bounded window", attrs.width > 0 && attrs.height > 0)
        decor.measure(View.MeasureSpec.makeMeasureSpec(attrs.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(attrs.height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
    }

    private fun capture(view: View, name: String) {
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(image))
            File("build/reports/diagnostic-dialog/$name.png").apply { parentFile.mkdirs() }
                .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }
    private inline fun <reified T : View> frameworkPanel(decor: View, name: String): T {
        val id = decor.resources.getIdentifier(name, "id", "android")
        assertNotEquals("Framework panel $name is available", 0, id)
        return decor.findViewById(id)
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
