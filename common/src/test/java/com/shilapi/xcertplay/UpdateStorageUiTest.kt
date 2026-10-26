package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateStorage
import java.io.File
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
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w900dp-h600dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateStorageUiTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null
    private val queued = mutableListOf<Runnable>()

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        UpdateAvailability.clearAllForTest(app)
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test fun storageScanAndCleanupKeepThePageAndLeaveInstalledSettingsAlone() {
        val screen = screen()
        val file = stored()
        app.getSharedPreferences("diplay", 0).edit().putString("keep-setting", "unchanged").commit()
        val page = ReflectionHelpers.getField<View>(screen, "rootScroll")
        open(screen)
        val dialog = ReflectionHelpers.getField<AlertDialog>(screen, "updateStorageDialog")
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        queued.removeAt(0).run()
        idle()
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        queued.removeAt(0).run()
        idle()
        assertFalse(file.exists())
        assertEquals("unchanged", app.getSharedPreferences("diplay", 0).getString("keep-setting", null))
        assertSame(page, ReflectionHelpers.getField<View>(screen, "rootScroll"))
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
    }

    @Test fun aReadyUpdateCannotBeDeletedByManualCleanup() {
        val screen = screen()
        val directory = UpdateStorage.createAttempt(app, history = false)
        val apk = File(directory, "ready.apk").apply { writeText("ready") }
        ReflectionHelpers.setField(screen, "updateFile", apk)
        open(screen)
        queued.removeAt(0).run()
        idle()
        val dialog = ReflectionHelpers.getField<AlertDialog>(screen, "updateStorageDialog")
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        assertTrue(apk.exists())
        val message = dialog.findViewById<TextView>(android.R.id.message).text.toString()
        assertTrue(message.contains(screen.getString(R.string.settings_update_storage_protected, 1)))
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactChineseLargeFontScreenshot() = capture("compact-zh")

    @Test @Config(qualifiers = "en-w1000dp-h700dp") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun expandedEnglishLargeFontScreenshot() = capture("expanded-en")

    private fun capture(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        val screen = screen()
        stored()
        open(screen)
        queued.removeAt(0).run()
        idle()
        val dialog = ReflectionHelpers.getField<AlertDialog>(screen, "updateStorageDialog")
        val root = dialog.window!!.decorView
        val density = screen.resources.displayMetrics.density
        val config = screen.resources.configuration
        val width = ((minOf(config.screenWidthDp, 600) - 32) * density).toInt()
        val height = ((config.screenHeightDp - 32) * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        for (text in descendants(root).filterIsInstance<TextView>()) {
            if (!text.isShown || text.text.isNullOrBlank()) continue
            val layout = text.layout ?: continue
            assertTrue("Ellipsized ${text.text}", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
            assertTrue("Clipped ${text.text}", layout.getLineBottom(layout.lineCount - 1) <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom + 2)
        }
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(image))
            val path = Paths.get("build/reports/update-storage-ui/$name.png")
            Files.createDirectories(path.parent)
            Files.newOutputStream(path).use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun screen(): DiPlayActivity {
        val provider = EmptyDownloads()
        provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        DiPlayPreferences.saveAutoConnect(app, false)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        val c = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup().visible()
        controller = c
        val screen = c.get()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ABOUT)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        screen.updateStorageWorker = queued::add
        return screen
    }
    private fun stored() = File(app.getExternalFilesDir(null), "update/stored.apk").apply {
        parentFile!!.mkdirs(); writeBytes(ByteArray(4096))
    }
    private fun open(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()
        .single { it.text == screen.getString(R.string.settings_update_storage) }.performClick()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private class EmptyDownloads : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = MatrixCursor(projection!!)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun getType(uri: Uri) = UpdateStorage.APK_MIME
    }
}
