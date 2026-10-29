package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.update.UpdateAvailability
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class VehicleModelUiTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    private val screens = mutableListOf<ActivityController<DiPlayActivity>>()

    @Before fun prepare() {
        prefs.edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        DiPlayPreferences.saveAutoConnect(app, false)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
    }

    @After fun cleanup() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        screens.asReversed().forEach { it.pause().stop().destroy() }
        RuntimeEnvironment.setFontScale(1f)
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        prefs.edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
    }

    @Test @Config(sdk = [25, 27, 29])
    fun defaultIsCorsairAndRemovedOrUnknownModelsMigrateToIt() {
        assertEquals(VehicleModel.CORSAIR, AirPlayPersistence.loadVehicleModel(app))
        for (legacy in listOf("nautilus", "aviator", "unknown-future-model")) {
            prefs.edit().putString("vehicle_scene_model", legacy).commit()
            assertEquals("$legacy must use the packaged default", VehicleModel.CORSAIR,
                AirPlayPersistence.loadVehicleModel(app))
        }
        val screen = open()
        assertFalse("A single model choice should not have a separate vehicle-appearance heading",
            descendants(screen.window.decorView).filterIsInstance<TextView>()
                .any { it.isShown && it.text.toString() == screen.getString(R.string.settings_vehicle_appearance) })
        assertEquals("品牌标识 · 林肯", brandChoiceButton(screen).text.toString())
        assertEquals("自定义背景 · 默认", wallpaperButton(screen).text.toString())
    }

    @Test @Config(sdk = [25, 27, 29])
    fun defaultBackgroundMenuOffersImageSelectionAndAnExplicitDefaultChoice() {
        val screen = open()
        val dialog = picker(screen)
        assertEquals(screen.getString(R.string.settings_wallpaper_choose),
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertEquals(screen.getString(R.string.settings_wallpaper_use_default),
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text)
        assertNull("Opening the setting must not launch the external picker",
            shadowOf(screen).nextStartedActivityForResult)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val picker = shadowOf(screen).nextStartedActivityForResult
        assertTrue(picker.intent.action in listOf(Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT))
        if (picker.intent.action == Intent.ACTION_OPEN_DOCUMENT) {
            assertEquals(listOf("image/*"), picker.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        } else assertEquals("image/*", picker.intent.type)
        screen.activityResultRegistry.dispatchResult(picker.requestCode, android.app.Activity.RESULT_CANCELED, null)
        assertEquals(VehicleModel.CORSAIR, AirPlayPersistence.loadVehicleModel(app))
        assertFalse(CustomWallpaper.available(app))

        val reset = picker(screen)
        reset.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VehicleModel.CORSAIR, AirPlayPersistence.loadVehicleModel(app))
        assertEquals("自定义背景 · 默认", wallpaperButton(screen).text.toString())
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun savedCustomImageCanBeReplacedOrResetWithoutChangingTheRunningSession() {
        installCustomFixture()
        val id = AirPlayPersistence.loadCustomWallpaperId(app)
        val screen = open()
        val session = mock(CarPlayController::class.java)
        var stopped = false
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stopped = true }
        CarPlayBackgroundSession.active = true
        val dialog = picker(screen)
        assertEquals(screen.getString(R.string.settings_wallpaper_replace),
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertEquals(screen.getString(R.string.settings_wallpaper_use_default),
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val launched = shadowOf(screen).nextStartedActivityForResult
        screen.activityResultRegistry.dispatchResult(launched.requestCode, android.app.Activity.RESULT_CANCELED, null)
        assertEquals(id, AirPlayPersistence.loadCustomWallpaperId(app))
        assertEquals(VehicleModel.CUSTOM, AirPlayPersistence.loadVehicleModel(app))
        val reset = picker(screen)
        reset.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(VehicleModel.CORSAIR, AirPlayPersistence.loadVehicleModel(app))
        assertEquals("自定义背景 · 默认", wallpaperButton(screen).text.toString())
        assertEquals("Resetting the choice need not destructively delete the driver's image", id,
            AirPlayPersistence.loadCustomWallpaperId(app))
        assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        assertFalse(stopped)
        assertFalse(PendingReconnect.isPending(session))
    }

    @Test fun removedModelInAnOldSettingsBackupFallsBackToCorsair() {
        prefs.edit().putString("vehicle_scene_model", "nautilus").commit()
        val document = SettingsBackupCodec.export(app, "1.1.0")
        prefs.edit().clear().commit()
        assertFalse(SettingsBackupCodec.import(app, document).invalid)
        assertEquals(VehicleModel.CORSAIR, AirPlayPersistence.loadVehicleModel(app))
    }

    @Test @Config(sdk = [25, 29, 33])
    fun customBackgroundEntrySurvivesBrandChangesOldCustomIdentityAndReopeningSettings() {
        // An old custom name (or a cropped CarPlay icon) migrates the brand to CUSTOM.
        AirPlayPersistence.saveOemLabel(app, "My car")
        assertEquals(VehicleBrandPreset.CUSTOM, VehicleBranding.preset(app))
        val screen = open()
        assertTrue(wallpaperButton(screen).isShown)
        for (brand in VehicleBrandPreset.entries) {
            VehicleBranding.setPreset(app, brand)
            ReflectionHelpers.setField(screen, "page", "home")
            render(screen)
            ReflectionHelpers.setField(screen, "page", "settings")
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.VEHICLE)
            render(screen)
            assertTrue("Background entry missing for $brand", wallpaperButton(screen).isShown)
            assertTrue(wallpaperButton(open()).isShown)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun defaultAndCustomImagesAreIndependentOfCarPlayBrandOrIcon() {
        fun photo(): Bitmap? = ReflectionHelpers.getField(SceneBackdrop(app, true, false), "photo")
        // Both supported home brands share the packaged default until the driver chooses an image.
        VehicleBranding.setPreset(app, VehicleBrandPreset.FORD)
        val defaultImage = requireNotNull(photo())
        for (brand in VehicleBrandPreset.entries) {
            VehicleBranding.setPreset(app, brand)
            assertSame("The default image must stay visible for $brand", defaultImage, photo())
        }
        installCustomFixture()
        val customImage = requireNotNull(photo())
        assertNotSame(defaultImage, customImage)
        val customId = AirPlayPersistence.loadCustomWallpaperId(app)
        for (brand in VehicleBrandPreset.entries) {
            VehicleBranding.setPreset(app, brand)
            assertSame("The custom image must stay visible for $brand", customImage, photo())
            assertEquals(customId, AirPlayPersistence.loadCustomWallpaperId(app))
            assertEquals(VehicleModel.CUSTOM, AirPlayPersistence.loadVehicleModel(app))
            assertTrue(wallpaperButton(open()).isShown)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun landscapeModelsKeepTheSameLayout() = capture("landscape", 1f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun compactSettingsWithLargeText() = capture("compact-large", 1.3f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w360dp-h760dp-port-mdpi")
    fun portraitModelsWithLargeTextKeepTheSameLayout() = capture("portrait-large", 1.3f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
    fun fullSettingsWithLargeText() = capture("full-large", 1.3f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    fun ultraWideModelsAndPickerWithLargeText() = capture("1920x720-mdpi-large", 1.3f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun ultraWideHighDensityModelsAndPickerWithLargeText() = capture("1920x720-xhdpi-large", 1.3f)

    private fun capture(name: String, fontScale: Float) {
        RuntimeEnvironment.setFontScale(fontScale)
        installCustomFixture()
        val screen = open()
        var geometry: List<Rect>? = null
        val homeImages = mutableSetOf<Int>()
        for (model in VehicleModel.entries) {
            AirPlayPersistence.saveVehicleModel(app, model)
            ReflectionHelpers.setField(screen, "page", "home")
            ReflectionHelpers.setField(screen, "setupError", null)
            render(screen)
            val scene = descendants(screen.window.decorView).filterIsInstance<ReferenceHomeScene>().single()
            val positions = (0 until scene.childCount).map { index ->
                scene.getChildAt(index).let { Rect(it.left, it.top, it.right, it.bottom) }
            }
            geometry?.let { assertEquals("Changing the car cannot move the controls", it, positions) }
            geometry = positions
            homeImages += writeScreenshot(root(screen), "$name-${model.key}-home")
            assertTrue(descendants(scene).filterIsInstance<TextView>().any { it.text.toString() == "CarPlay" })
            ReflectionHelpers.setField(screen, "page", "settings")
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.VEHICLE)
            render(screen)
            val row = wallpaperButton(screen)
            val rowImage = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
            try {
                row.draw(Canvas(rowImage))
                assertEquals("The last appearance row must not draw an extra divider", 0,
                    Color.alpha(rowImage.getPixel(row.width / 2, row.height - 1)))
            } finally { rowImage.recycle() }
            writeScreenshot(root(screen), "$name-${model.key}-settings")
        }
        assertEquals("Default and custom choices must show different images after caching", 2, homeImages.size)
        AirPlayPersistence.saveVehicleModel(app, VehicleModel.CUSTOM)
        ReflectionHelpers.setField(screen, "page", "settings")
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.VEHICLE)
        render(screen)
        val dialog = picker(screen)
        val decor = dialog.window!!.decorView
        val metrics = screen.resources.displayMetrics
        decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        for (text in descendants(decor).filterIsInstance<TextView>()) {
            val layout = text.layout ?: continue
            for (line in 0 until layout.lineCount) assertEquals("Picker text must fit: ${text.text}", 0, layout.getEllipsisCount(line))
        }
        writeScreenshot(decor, "$name-picker")
    }

    private fun installCustomFixture() {
        // Simulate a user selecting a local image; do not change any packaged artwork.
        val source = File(app.cacheDir, "chosen-wallpaper.png")
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.rgb(96, 24, 120))
                drawRect(320f, 0f, 640f, 360f, android.graphics.Paint().apply {
                    color = Color.rgb(0, 160, 210)
                })
            }
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        CustomWallpaper.activate(app, CustomWallpaper.prepare(app, Uri.fromFile(source)))
        source.delete()
    }

    private fun open(): DiPlayActivity {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup().visible()
        screens += controller
        return controller.get().also {
            ReflectionHelpers.setField(it, "settingsCategory", SettingsCategory.VEHICLE)
            render(it)
        }
    }

    private fun picker(screen: DiPlayActivity): AlertDialog {
        wallpaperButton(screen).performClick()
        // Let WindowManager attach the dialog before native scroll indicators are drawn.
        shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    }

    private fun wallpaperButton(screen: DiPlayActivity): TextView {
        val title = screen.getString(R.string.settings_vehicle_model)
        return descendants(screen.window.decorView).filterIsInstance<TextView>()
            .single { it.isShown && it.javaClass.simpleName == "SettingButton" &&
                it.text.toString().startsWith("$title · ") }
    }

    private fun brandChoiceButton(screen: DiPlayActivity): TextView {
        val title = screen.getString(R.string.settings_home_brand)
        return descendants(screen.window.decorView).filterIsInstance<TextView>()
            .single { it.isShown && it.javaClass.simpleName == "SettingButton" &&
                it.text.toString().startsWith("$title · ") }
    }

    private fun render(screen: DiPlayActivity) {
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val root = root(screen)
        val metrics = screen.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun root(screen: DiPlayActivity) = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun writeScreenshot(view: View, name: String): Int {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val file = File("build/reports/vehicle-model/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.contentHashCode()
        } finally { bitmap.recycle() }
    }
}
