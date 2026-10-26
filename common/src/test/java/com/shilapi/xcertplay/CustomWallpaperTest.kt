package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CustomWallpaperTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("xcertplay_airplay", 0)
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun reset() { prefs.edit().clear().commit() }
    @After fun cleanup() {
        stores.forEach { it.clear() }
        prefs.edit().clear().commit()
    }

    @Test fun localCopySurvivesOriginalDeletionAndPresetRoundTrip() {
        val source = image(Color.RED)
        val prepared = CustomWallpaper.prepare(app, Uri.fromFile(source))
        assertEquals(VehicleModel.CORSAIR, AirPlayPersistence.loadVehicleModel(app))
        CustomWallpaper.activate(app, prepared)
        assertTrue(source.delete())
        assertEquals(VehicleModel.CUSTOM, AirPlayPersistence.loadVehicleModel(app))
        assertTrue(CustomWallpaper.file(app)!!.length() > 0)
        assertTrue(CustomWallpaper.file(app, quiet = true)!!.length() > 0)
        assertFalse(File(prepared.directory, "source").exists())
        AirPlayPersistence.saveVehicleModel(app, VehicleModel.CORSAIR)
        assertTrue(CustomWallpaper.available(app))
        AirPlayPersistence.saveVehicleModel(app, VehicleModel.CUSTOM)
        assertEquals(VehicleModel.CUSTOM, AirPlayPersistence.loadVehicleModel(app))
    }

    @Test fun oversizedPhotoIsBoundedAndSettingsCopyIsSmall() {
        val prepared = CustomWallpaper.prepare(app, Uri.fromFile(image(Color.BLUE, 5301, 1400)))
        CustomWallpaper.activate(app, prepared)
        val full = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(CustomWallpaper.file(app)!!.path, full)
        assertTrue(full.outWidth in 1..CustomWallpaper.MAX_EDGE)
        assertTrue(full.outHeight in 1..CustomWallpaper.MAX_EDGE)
        assertTrue(full.outWidth.toLong() * full.outHeight <= CustomWallpaper.MAX_PIXELS)
        val soft = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(CustomWallpaper.file(app, true)!!.path, soft)
        assertTrue(maxOf(soft.outWidth, soft.outHeight) <= CustomWallpaper.SOFT_EDGE)
    }

    @Test fun readingBackgroundSoftensDetailWhileHomeKeepsTheOriginalDetail() {
        val source = image(Color.BLACK, 100, 100)
        val pattern = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 100) pattern.setPixel(x, y,
            if ((x / 5 + y / 5) % 2 == 0) Color.WHITE else Color.BLACK)
        source.outputStream().use { pattern.compress(Bitmap.CompressFormat.PNG, 100, it) }
        pattern.recycle()
        CustomWallpaper.activate(app, CustomWallpaper.prepare(app, Uri.fromFile(source)))
        val home = BitmapFactory.decodeFile(CustomWallpaper.file(app)!!.path)
        val reading = BitmapFactory.decodeFile(CustomWallpaper.file(app, true)!!.path)
        try {
            assertTrue(Color.red(home.getPixel(52, 52)) > 230)
            assertTrue(Color.red(reading.getPixel(52, 52)) in 80..190)
        } finally { home.recycle(); reading.recycle() }
    }

    @Test fun invalidAndOversizedFilesKeepThePreviousImage() {
        CustomWallpaper.activate(app, CustomWallpaper.prepare(app, Uri.fromFile(image(Color.RED))))
        val previous = CustomWallpaper.appearanceKey(app)
        val invalid = File(app.cacheDir, "invalid-image").apply { writeText("not an image") }
        assertTrue(runCatching { CustomWallpaper.prepare(app, Uri.fromFile(invalid)) }.isFailure)
        java.io.RandomAccessFile(invalid, "rw").use { it.setLength(41L * 1024 * 1024) }
        assertTrue(runCatching { CustomWallpaper.prepare(app, Uri.fromFile(invalid)) }.isFailure)
        assertTrue(runCatching { CustomWallpaper.prepare(app, Uri.parse("https://example.com/photo.jpg")) }.isFailure)
        assertEquals(previous, CustomWallpaper.appearanceKey(app))
        assertEquals(1, File(app.noBackupFilesDir, "wallpapers").listFiles()!!.size)
    }

    @Test fun missingFileOrForeignBackupFallsBackToTheOriginalCorsair() {
        CustomWallpaper.activate(app, CustomWallpaper.prepare(app, Uri.fromFile(image(Color.RED))))
        val backup = SettingsBackupCodec.export(app, "test")
        assertFalse(backup.decodeToString().contains("custom_wallpaper_id"))
        assertTrue(CustomWallpaper.file(app)!!.delete())
        assertEquals(VehicleModel.CORSAIR, AirPlayPersistence.loadVehicleModel(app))
        prefs.edit().clear().commit()
        assertFalse(SettingsBackupCodec.import(app, backup).invalid)
        assertEquals(VehicleModel.CORSAIR, AirPlayPersistence.loadVehicleModel(app))
        prefs.edit().putString("custom_wallpaper_id", "../outside").commit()
        assertFalse(CustomWallpaper.available(app))
    }

    @Test fun replacementRefreshesBothBackgroundVariantsWithoutRecyclingTheVisibleImage() {
        fun rendered(quiet: Boolean): Bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).also {
            SceneBackdrop(app, true, quiet).apply { setBounds(0, 0, 80, 40) }.draw(Canvas(it))
        }
        CustomWallpaper.activate(app, CustomWallpaper.prepare(app, Uri.fromFile(image(Color.RED))))
        val oldBackdrop = SceneBackdrop(app, true, false).apply { setBounds(0, 0, 80, 40) }
        val oldKey = CustomWallpaper.appearanceKey(app)
        val oldPhoto = rendered(false)
        val oldSoft = rendered(true)
        CustomWallpaper.activate(app, CustomWallpaper.prepare(app, Uri.fromFile(image(Color.GREEN))))
        assertNotEquals(oldKey, CustomWallpaper.appearanceKey(app))
        val newPhoto = rendered(false)
        val newSoft = rendered(true)
        assertNotEquals(oldPhoto.getPixel(70, 20), newPhoto.getPixel(70, 20))
        assertNotEquals(oldSoft.getPixel(70, 20), newSoft.getPixel(70, 20))
        oldBackdrop.draw(Canvas(oldPhoto)) // Still safe after replacing/evicting the cache entry.
        listOf(oldPhoto, oldSoft, newPhoto, newSoft).forEach { it.recycle() }
    }

    @Test fun allExifDirectionsKeepTheExpectedCornerOrder() {
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(intArrayOf(Color.RED, Color.GREEN, Color.BLACK, Color.WHITE, Color.BLUE, Color.YELLOW), 0, 2, 0, 0, 2, 3)
        val expected = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
        )
        for (orientation in 1..8) {
            val result = CustomWallpaper.orient(bitmap, orientation)
            assertEquals("EXIF $orientation", expected[orientation - 1], listOf(
                result.getPixel(0, 0), result.getPixel(result.width - 1, 0),
                result.getPixel(0, result.height - 1), result.getPixel(result.width - 1, result.height - 1)))
            if (result !== bitmap) result.recycle()
        }
        bitmap.recycle()
    }

    @Test fun cancelDuringDecodeNeverAppliesItsLateResult() {
        val prepared = CustomWallpaper.prepare(app, Uri.fromFile(image(Color.BLUE)))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val model = tracked(WallpaperImportModel { _, _ ->
            entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)); prepared
        })
        model.import(app, Uri.EMPTY)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        model.cancel()
        AirPlayPersistence.saveVehicleModel(app, VehicleModel.CORSAIR)
        resume.countDown()
        await { !prepared.directory.exists() }
        assertEquals(VehicleModel.CORSAIR, AirPlayPersistence.loadVehicleModel(app))
        assertEquals(WallpaperImportModel.State.IDLE, model.state)
    }

    @Test fun importFinishesWhileActivityIsPausedAndCanBeConsumedAfterRecreation() {
        val model = tracked(WallpaperImportModel())
        model.import(app, Uri.fromFile(image(Color.GREEN)))
        assertEquals(WallpaperImportModel.State.LOADING, model.state)
        await { model.state == WallpaperImportModel.State.SAVED }
        assertEquals(VehicleModel.CUSTOM, AirPlayPersistence.loadVehicleModel(app))
        model.acknowledge()
        assertEquals(WallpaperImportModel.State.IDLE, model.state)
    }

    @Test fun disappearingPreparedFileReportsFailureInsteadOfCrashingTheActivity() {
        val prepared = CustomWallpaper.prepare(app, Uri.fromFile(image(Color.BLUE)))
        prepared.discard()
        val model = tracked(WallpaperImportModel { _, _ -> prepared })
        model.import(app, Uri.EMPTY)
        await { model.state == WallpaperImportModel.State.FAILED }
        assertEquals(VehicleModel.CORSAIR, AirPlayPersistence.loadVehicleModel(app))
    }

    private fun tracked(model: WallpaperImportModel): WallpaperImportModel {
        stores += ViewModelStore().also { it.put("wallpaper", model) }
        return model
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Import worker did not finish", condition())
    }

    private fun image(color: Int, width: Int = 320, height: Int = 180): File {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        image.eraseColor(color)
        return File.createTempFile("wallpaper", ".png", app.cacheDir).also { file ->
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            image.recycle()
        }
    }
}
