package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.mockito.Mockito.mock
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassUiRegressionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun cleanup() {
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        CarPlayBackgroundSession.clear()
        UpdateAvailability.clearAllForTest(app)
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun landscapeHomeMatchesTheSelectedComposition() = capture("landscape-zh", 1f)

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun shortWideHomeKeepsActionsInTheInitialViewport() = capture("short-wide-zh", 1f)

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-xhdpi")
    fun referenceHomeInThePairedState() = capture("reference-home",1f,true)

    @Test @Config(qualifiers = "zh-rCN-w1200dp-h720dp-land-mdpi")
    fun referenceSettingsInTheConnectedState() = capture("reference-wide",1f,true)

    @Test @Config(qualifiers="zh-rCN-w324dp-h540dp-port-xxhdpi")
    fun referencePortraitAtTheSelectedDensity()=capture("reference-portrait",1f,true)

    @Test @Config(qualifiers = "en-w1200dp-h720dp-land-mdpi")
    fun expandedControlsWithLargeEnglishText() = capture("expanded-en", 1.3f)

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-mdpi")
    fun compactControlsWithLargeChineseText() = capture("compact-zh", 1.3f)

    @Test @Config(qualifiers = "ar-ldrtl-w320dp-h600dp-port-mdpi")
    fun compactControlsWithLargeRtlText() = capture("compact-ar-rtl", 1.3f)

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-mdpi")
    fun portraitReadingSurfaceKeepsSecondaryTextLegibleInBothThemes() {
        for (night in listOf(true, false)) {
            val palette = DiPlayPalette.of(night)
            val backdrop = SceneBackdrop(app, night, quiet = false)
            backdrop.setBounds(0, 0, 320, 600)
            val bitmap = Bitmap.createBitmap(320, 600, Bitmap.Config.ARGB_8888)
            try {
                backdrop.draw(Canvas(bitmap))
                for (y in 0 until bitmap.height step 20) for (x in 0 until bitmap.width step 20) {
                    assertTrue("Photo must not wash out secondary text (night=$night, x=$x, y=$y)",
                        androidx.core.graphics.ColorUtils.calculateContrast(palette.secondaryText, bitmap.getPixel(x, y)) >= 4.5)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test @Config(qualifiers="zh-rCN-w324dp-h540dp-port-mdpi")
    fun portraitSettingsGlassKeepsSecondaryTextLegible() {
        for(night in listOf(true,false)) {
            val p=DiPlayPalette.of(night)
            val bitmap=Bitmap.createBitmap(324,540,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(bitmap)
                SceneBackdrop(app,night,quiet=true).apply { setBounds(0,0,324,540);draw(canvas) }
                GlassChrome.surface(p.surface,p.outline,1f,24f).apply { setBounds(0,0,324,540);draw(canvas) }
                for(y in 24 until 516 step 20)for(x in 24 until 300 step 20) {
                    assertTrue("Portrait settings text contrast (night=$night, x=$x, y=$y)",
                        androidx.core.graphics.ColorUtils.calculateContrast(p.secondaryText,bitmap.getPixel(x,y))>=4.5)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun cloningAStatefulButtonKeepsTheRenderedGlassMaterial() {
        FidelityMaterials.install(app)
        val p=DiPlayPalette.DARK
        val direct=GlassChrome.surface(p.accent,p.outline,1f,32f,primary=true)
        val stateful=android.graphics.drawable.StateListDrawable().apply { addState(intArrayOf(),direct) }
        val cloned=requireNotNull(stateful.constantState).newDrawable().mutate()
        val before=Bitmap.createBitmap(260,64,Bitmap.Config.ARGB_8888)
        val after=Bitmap.createBitmap(260,64,Bitmap.Config.ARGB_8888)
        try {
            direct.setBounds(0,0,260,64);direct.draw(Canvas(before))
            cloned.setBounds(0,0,260,64);cloned.draw(Canvas(after))
            assertTrue("Framework drawable cloning must retain glass artwork and its shape",before.sameAs(after))
        } finally { before.recycle();after.recycle() }
    }

    private fun capture(name: String, scale: Float, paired:Boolean=false) {
        // A native-rendering preview is not a published application version.
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        packageInfo.versionName = "UI preview"
        shadowOf(app.packageManager).installPackage(packageInfo)
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        if(paired)DiPlayPreferences.savePhone(app,"00:11:22:33:44:55","我的 iPhone")
        DiPlayPreferences.saveAutoConnect(app, false)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
        val screen = controller.get()
        try {
            // Local unit tests have no physical authentication chip; render its normal ready-state counterpart.
            ReflectionHelpers.setField(screen, "setupError", null)
            call(screen, "render")
            screenshot(screen, "$name-home")
            val texts = descendants(screen.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            for (id in listOf(R.string.ui_glass_connect_carplay, R.string.choose_iphone, R.string.connect_with_usb, R.string.settings)) {
                if(paired && id==R.string.choose_iphone)continue
                assertTrue("Existing home action remains available", screen.getString(id) in texts)
                val action = descendants(screen.window.decorView).filterIsInstance<TextView>()
                    .first { it.text.toString() == screen.getString(id) }
                assertTrue("Home action must have a usable measured height", action.height >= 40 * screen.resources.displayMetrics.density)
                if (name in listOf("landscape-zh", "short-wide-zh")) {
                    val bounds = android.graphics.Rect()
                    assertTrue("Home action must fit the initial viewport", action.getGlobalVisibleRect(bounds))
                    assertTrue("Home action cannot be clipped", bounds.height() >= action.height - 1)
                }
            }
            if(paired) {
                previewSession(screen)
                try {
                    call(screen,"refreshStatus")
                    screenshot(screen,"$name-home-connecting")
                    val title=ReflectionHelpers.getField<TextView>(screen,"homeHeadline")
                    assertEquals(screen.getString(R.string.ui_glass_connecting_title),title.text.toString())
                    val disconnect=ReflectionHelpers.getField<android.widget.Button>(screen,"disconnectButton")
                    assertEquals(View.VISIBLE,disconnect.visibility)
                    disconnect.performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertFalse("The existing disconnect callback still stops the session",CarPlayBackgroundSession.hasSession())
                } finally { CarPlayBackgroundSession.clear() }
            }
            for (night in listOf(true, false)) {
                AirPlayPersistence.saveAppAppearance(app, if (night) AppAppearance.DARK else AppAppearance.LIGHT)
                ReflectionHelpers.setField(screen, "page", "home")
                call(screen, "render")
                screenshot(screen, "$name-${if (night) "dark" else "light"}-home")
                ReflectionHelpers.setField(screen, "page", "settings")
                for (category in listOf(SettingsCategory.OVERVIEW, SettingsCategory.DISPLAY, SettingsCategory.ABOUT)) {
                    CarPlayBackgroundSession.active=paired && category==SettingsCategory.OVERVIEW
                    ReflectionHelpers.setField(screen, "settingsCategory", category)
                    call(screen, "render")
                    screenshot(screen, "$name-${if (night) "dark" else "light"}-${category.name.lowercase()}")
                    if(category==SettingsCategory.ABOUT && paired) {
                        screen.updateStorageWorker={ it.run() }
                        call(screen,"showUpdateStorage")
                        shadowOf(Looper.getMainLooper()).idle()
                        val storage=ShadowAlertDialog.getLatestAlertDialog()
                        try {
                            val root=storage.window!!.decorView
                            val d=screen.resources.displayMetrics.density
                            root.measure(View.MeasureSpec.makeMeasureSpec(((minOf(screen.resources.configuration.screenWidthDp,600)-32)*d).toInt(),View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(((screen.resources.configuration.screenHeightDp-32)*d).toInt(),View.MeasureSpec.AT_MOST))
                            root.layout(0,0,root.measuredWidth,root.measuredHeight)
                            writeScreenshot(root,"$name-${if(night)"dark"else"light"}-storage")
                        } finally { storage.dismiss() }
                    }
                    if (category == SettingsCategory.DISPLAY) {
                        val rate = descendants(screen.window.decorView).filterIsInstance<android.widget.Button>()
                            .first { it.text.startsWith(screen.getString(R.string.frame_rate) + " · ") }
                        if(paired)previewSession(screen)
                        rate.performClick()
                        val dialog = ShadowAlertDialog.getLatestAlertDialog()
                        shadowOf(Looper.getMainLooper()).idle()
                        try {
                            val root = dialog.window!!.decorView
                            val density = screen.resources.displayMetrics.density
                            val config = screen.resources.configuration
                            val width = ((minOf(config.screenWidthDp, 600) - 32) * density).toInt()
                            val height = ((config.screenHeightDp - 32) * density).toInt()
                            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                            writeScreenshot(root, "$name-${if (night) "dark" else "light"}-choice")
                            val actions=listOf(dialog.getButton(AlertDialog.BUTTON_POSITIVE),dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
                                .sortedBy { it.left }
                            if(actions[0].top==actions[1].top) {
                                assertEquals("Dialog actions must have equal heights",actions[0].height,actions[1].height)
                                assertTrue("Dialog actions need a clear gap",actions[1].left-actions[0].right>=FidelitySettingsMetrics.DIALOG_ACTION_GAP_DP*density-1)
                            }
                            for (id in listOf(android.app.AlertDialog.BUTTON_POSITIVE, android.app.AlertDialog.BUTTON_NEGATIVE)) {
                                val button = dialog.getButton(id)
                                assertTrue("Dialog action must retain its touch target", button.height >= 48 * density)
                                val layout = button.layout
                                for (line in 0 until layout.lineCount) assertEquals("Dialog action cannot truncate", 0, layout.getEllipsisCount(line))
                            }
                        } finally { dialog.dismiss();CarPlayBackgroundSession.clear() }
                    }
                }
            }
            captureWaiting(screen, name)
        } finally { controller.pause().stop().destroy() }
    }

    private fun previewSession(screen: DiPlayActivity) {
        CarPlayBackgroundSession.store(mock(CarPlayController::class.java),mock(AndroidMediaSink::class.java),800,480,screen,
            CarPlaySessionDisplay(800,480,android.view.Surface.ROTATION_0,false,false,800,480)) { done ->
            CarPlayBackgroundSession.clear();done()
        }
        CarPlayBackgroundSession.active=false
    }

    private fun screenshot(screen: DiPlayActivity, name: String) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val config = screen.resources.configuration
        val density = screen.resources.displayMetrics.density
        val width = (config.screenWidthDp * density).toInt()
        val height = (config.screenHeightDp * density).toInt()
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            shadowOf(Looper.getMainLooper()).idle()
        }
        writeScreenshot(root, name)
    }

    private fun captureWaiting(screen: DiPlayActivity, name: String) {
        // Build only the presentation: no controller, decoder or transport is started here.
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            ReflectionHelpers.setField(host, "appNight", true)
            ReflectionHelpers.setField(host,"wirelessEnabled",name.startsWith("reference"))
            val root = ReflectionHelpers.callInstanceMethod<ViewGroup>(host, "buildContentView")
            val density = screen.resources.displayMetrics.density
            val config = screen.resources.configuration
            val width = (config.screenWidthDp * density).toInt()
            val height = (config.screenHeightDp * density).toInt()
            // Insets and the first layout choose compact type/ring sizes, just as on a device.
            // Settle their requested follow-up layout before taking native rendering evidence.
            repeat(3) {
                fun invalidateMeasurement(view: View) {
                    view.forceLayout()
                    if (view is ViewGroup) for (i in 0 until view.childCount) invalidateMeasurement(view.getChildAt(i))
                }
                invalidateMeasurement(root)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            val waiting = ReflectionHelpers.getField<View>(host, "connectionPanel")
            for (text in descendants(waiting).filterIsInstance<TextView>()) {
                if (text.text.isNullOrBlank() || text.visibility != View.VISIBLE) continue
                val layout = text.layout ?: continue
                val room = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
                assertTrue("Connection text must fit its measured height: ${text.text}",
                    layout.lineCount == 0 || layout.getLineBottom(layout.lineCount - 1) <= room + 2)
            }
            writeScreenshot(root, "$name-connecting")
        } finally {
            ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicBoolean>(host, "shuttingDown").set(true)
            ReflectionHelpers.getField<android.os.Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        }
    }

    private fun writeScreenshot(root: View, name: String) {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            val path = Paths.get("build/reports/glass-ui/$name.png")
            Files.createDirectories(path.parent)
            Files.newOutputStream(path).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun call(screen: DiPlayActivity, name: String) = ReflectionHelpers.callInstanceMethod<Unit>(screen, name)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
