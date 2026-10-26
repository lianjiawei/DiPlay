package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
@Config(sdk = [29], qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeVehicleBrandUiTest {
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
        CarPlayBackgroundSession.clear()
        screens.asReversed().forEach { it.pause().stop().destroy() }
        RuntimeEnvironment.setFontScale(1f)
        prefs.edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        PendingReconnect.clear()
    }

    @Test @Config(sdk = [25, 29, 33])
    fun twoChoicesCancelAndExplicitDefaultPersistIndependentlyOfCarPlayIdentity() {
        assertEquals(HomeVehicleBrand.LINCOLN, AirPlayPersistence.loadHomeVehicleBrand(app))
        val screen = open()
        val dialog = picker(screen)
        assertEquals(listOf("林肯", "福特"), (0 until dialog.listView.count).map { dialog.listView.adapter.getItem(it).toString() })
        select(dialog, 1)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        assertEquals(HomeVehicleBrand.LINCOLN, AirPlayPersistence.loadHomeVehicleBrand(app))
        picker(screen).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        VehicleBranding.setPreset(app, VehicleBrandPreset.FORD)
        assertEquals("Saving the displayed default is an explicit home choice", HomeVehicleBrand.LINCOLN,
            AirPlayPersistence.loadHomeVehicleBrand(app))
    }

    @Test @Config(sdk = [25, 29, 33])
    fun changingHomeBrandUpdatesTheHeaderWithoutChangingWallpaperPairingOrLiveCarPlay() {
        prefs.edit().putString("vehicle_scene_model", "nautilus").commit()
        AirPlayPersistence.saveOemLabel(app, "My car")
        VehicleBranding.setPreset(app, VehicleBrandPreset.CUSTOM)
        prefs.edit().putString("pairing.keep", "paired-phone").commit()
        val screen = open()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 1280, 720, Any(),
            CarPlaySessionDisplay(1280, 720, Surface.ROTATION_0, false, false, 1280, 720)) { stops++ }
        CarPlayBackgroundSession.active = true
        val dialog = picker(screen)
        select(dialog, 1)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HomeVehicleBrand.FORD, AirPlayPersistence.loadHomeVehicleBrand(app))
        assertEquals(VehicleBrandPreset.CUSTOM, VehicleBranding.preset(app))
        assertEquals("My car", AirPlayPersistence.loadOemLabel(app))
        assertEquals("paired-phone", prefs.getString("pairing.keep", null))
        assertEquals("Removed built-in choices migrate to the Corsair default", VehicleModel.CORSAIR,
            AirPlayPersistence.loadVehicleModel(app))
        assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        assertEquals(0, stops)
        assertFalse(PendingReconnect.isPending(session))
        assertTrue(descendants(root(screen)).filterIsInstance<TextView>().any {
            it.text.startsWith(screen.getString(R.string.settings_vehicle_model) + " · ")
        })
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals("home", ReflectionHelpers.getField<String>(screen, "page"))
        assertHeader(screen, HomeVehicleBrand.FORD)
        assertNull(shadowOf(screen).nextStartedActivity)
        assertEquals(1, picker(open()).listView.checkedItemPosition)
    }

    @Test fun oldFordPreferenceAndBackupKeepTheSelectedHomeBrand() {
        VehicleBranding.setPreset(app, VehicleBrandPreset.FORD)
        assertEquals(HomeVehicleBrand.FORD, AirPlayPersistence.loadHomeVehicleBrand(app))
        AirPlayPersistence.saveHomeVehicleBrand(app, HomeVehicleBrand.FORD)
        val backup = SettingsBackupCodec.export(app, "1.1.6")
        prefs.edit().clear().commit()
        assertFalse(SettingsBackupCodec.import(app, backup).invalid)
        assertEquals(HomeVehicleBrand.FORD, AirPlayPersistence.loadHomeVehicleBrand(app))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fordMarkHasTransparentSurroundingsAndVisibleWhiteArtwork() {
        val drawable = FordHomeMarkDrawable(app, android.graphics.Color.WHITE)
        val bitmap = Bitmap.createBitmap(112, 80, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            for ((x, y) in listOf(0 to 0, 111 to 0, 0 to 79, 111 to 79, 56 to 0, 56 to 79)) {
                assertEquals("The blue square must never cover the photo", 0, android.graphics.Color.alpha(bitmap.getPixel(x, y)))
            }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("The authentic oval and script remain visible", pixels.count { android.graphics.Color.alpha(it) > 200 } > 300)
            assertTrue("The mark stays transparent inside its oval", pixels.count { android.graphics.Color.alpha(it) == 0 } > pixels.size / 2)
        } finally { bitmap.recycle() }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fullBrandsWithLargeText() = capture("1280x720")
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    fun ultraWideBrandsWithLargeText() = capture("1920x720")
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun ultraWideHighDensityBrandsWithLargeText() = capture("1920x720-xhdpi")
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-xhdpi")
    fun compactHighDensityBrandsWithLargeText() = capture("compact")
    private fun capture(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        val screen = open()
        var geometry: List<Rect>? = null
        for (brand in HomeVehicleBrand.entries) {
            AirPlayPersistence.saveHomeVehicleBrand(app, brand)
            ReflectionHelpers.setField(screen, "page", "settings")
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.VEHICLE)
            render(screen)
            screenshot(root(screen), "$name-${brand.key}-settings")
            val dialog = picker(screen)
            val decor = dialog.window!!.decorView
            val metrics = screen.resources.displayMetrics
            decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST))
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
            screenshot(decor, "$name-${brand.key}-picker")
            dialog.dismiss()
            ReflectionHelpers.setField(screen, "page", "home")
            render(screen)
            assertHeader(screen, brand)
            val scene = descendants(root(screen)).filterIsInstance<ReferenceHomeScene>().single()
            val positions = (0 until scene.childCount).map { scene.getChildAt(it).let { v -> Rect(v.left, v.top, v.right, v.bottom) } }
            geometry?.let { assertEquals("Changing the header brand cannot move home controls", it, positions) }
            geometry = positions
            screenshot(root(screen), "$name-${brand.key}-home")
        }
    }

    private fun assertHeader(screen: DiPlayActivity, brand: HomeVehicleBrand) {
        val icon = descendants(root(screen)).filterIsInstance<BrandMarkView>().single()
        assertEquals(brand.displayName(screen), icon.contentDescription)
        assertNotNull(icon.drawable)
        if (brand == HomeVehicleBrand.FORD) assertTrue(icon.drawable is FordHomeMarkDrawable)
        assertFalse("Home keeps only the logo; its spoken brand name stays accessible",
            descendants(icon.parent as ViewGroup).filterIsInstance<TextView>().any {
                it.text.toString() in listOf("Lincoln", "Ford")
            })
    }

    private fun open(): DiPlayActivity {
        val c = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup().visible()
        screens += c
        return c.get().also {
            ReflectionHelpers.setField(it, "settingsCategory", SettingsCategory.VEHICLE)
            render(it)
        }
    }
    private fun picker(screen: DiPlayActivity): AlertDialog {
        descendants(root(screen)).filterIsInstance<TextView>().single {
            it.isShown && it.javaClass.simpleName == "SettingButton" &&
                it.text.startsWith(screen.getString(R.string.settings_home_brand) + " · ")
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
    }
    private fun select(dialog: AlertDialog, index: Int) {
        dialog.listView.performItemClick(dialog.listView.adapter.getView(index, null, dialog.listView), index,
            dialog.listView.adapter.getItemId(index))
    }
    private fun render(screen: DiPlayActivity) {
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val root = root(screen)
        root.layoutDirection = screen.resources.configuration.layoutDirection
        val metrics = screen.resources.displayMetrics
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun root(screen: DiPlayActivity) = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun screenshot(view: View, name: String) {
        for (text in descendants(view).filterIsInstance<TextView>()) {
            if (!text.isShown || text.text.isNullOrBlank()) continue
            val layout = text.layout ?: continue
            for (i in 0 until layout.lineCount) assertEquals("Unclipped brand text: ${text.text}", 0, layout.getEllipsisCount(i))
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val file = File("build/reports/home-vehicle-brand/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
