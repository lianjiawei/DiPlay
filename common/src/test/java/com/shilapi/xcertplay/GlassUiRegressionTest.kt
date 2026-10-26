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
        CarPlayBackgroundSession.clear()
        UpdateAvailability.clearAllForTest(app)
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun landscapeHomeMatchesTheSelectedComposition() = capture("landscape-zh", 1f)

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun shortWideHomeKeepsActionsInTheInitialViewport() = capture("short-wide-zh", 1f)

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    fun ultraWideCarHomeAndCommonDialogsWithLargeText() = capture("1920x720-mdpi", 1.3f, true)

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun ultraWideHighDensityHomeAndCommonDialogsWithLargeText() = capture("1920x720-xhdpi", 1.3f, true)

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-xhdpi")
    fun referenceHomeInThePairedState() = capture("reference-home",1f,true)

    @Test @Config(qualifiers = "zh-rCN-w1200dp-h720dp-land-mdpi")
    fun referenceSettingsInTheConnectedState() = capture("reference-wide",1f,true)

    @Test @Config(qualifiers="zh-rCN-w324dp-h540dp-port-xxhdpi")
    fun referencePortraitAtTheSelectedDensity()=capture("reference-portrait",1f,true)

    @Test @Config(qualifiers = "en-w1200dp-h720dp-land-mdpi")
    fun expandedControlsWithLargeEnglishText() = capture("expanded-en", 1.3f, true)

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-mdpi")
    fun compactControlsWithLargeChineseText() = capture("compact-zh", 1.3f)

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-mdpi")
    fun portraitReadingSurfaceKeepsSecondaryTextLegible() {
        val palette = DiPlayPalette.DARK
        val backdrop = SceneBackdrop(app, night = true, quiet = false)
        backdrop.setBounds(0, 0, 320, 600)
        val bitmap = Bitmap.createBitmap(320, 600, Bitmap.Config.ARGB_8888)
        try {
            backdrop.draw(Canvas(bitmap))
            for (y in 0 until bitmap.height step 20) for (x in 0 until bitmap.width step 20) {
                assertTrue("Photo must not wash out secondary text (x=$x, y=$y)",
                    androidx.core.graphics.ColorUtils.calculateContrast(palette.secondaryText, bitmap.getPixel(x, y)) >= 4.5)
            }
        } finally { bitmap.recycle() }
    }

    @Test @Config(qualifiers="zh-rCN-w324dp-h540dp-port-mdpi")
    fun portraitSettingsGlassKeepsSecondaryTextLegible() {
        val p=DiPlayPalette.DARK
        val bitmap=Bitmap.createBitmap(324,540,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap)
            SceneBackdrop(app,night=true,quiet=true).apply { setBounds(0,0,324,540);draw(canvas) }
            GlassChrome.surface(p.surface,p.outline,1f,24f).apply { setBounds(0,0,324,540);draw(canvas) }
            for(y in 24 until 516 step 20)for(x in 24 until 300 step 20) {
                assertTrue("Portrait settings text contrast (x=$x, y=$y)",
                    androidx.core.graphics.ColorUtils.calculateContrast(p.secondaryText,bitmap.getPixel(x,y))>=4.5)
            }
        } finally { bitmap.recycle() }
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
                val label = screen.getString(id)
                val action = descendants(screen.window.decorView).filterIsInstance<TextView>()
                    .firstOrNull { it.text.toString() == label || it.contentDescription?.toString() == label }
                assertNotNull("Existing home action remains available with its full spoken label", action)
                requireNotNull(action)
                assertTrue("Home action must have a usable measured height", action.height >= 40 * screen.resources.displayMetrics.density)
                if (name in listOf("landscape-zh", "short-wide-zh")) {
                    val bounds = android.graphics.Rect()
                    assertTrue("Home action must fit the initial viewport", action.getGlobalVisibleRect(bounds))
                    assertTrue("Home action cannot be clipped", bounds.height() >= action.height - 1)
                }
            }
            if(paired) {
                fun landmarks(): List<Pair<Int, Int>> {
                    val scene = descendants(screen.window.decorView).filterIsInstance<ReferenceHomeScene>().single()
                    return listOf(ReflectionHelpers.getField<View>(screen,"homeConnectionRing"),
                        ReflectionHelpers.getField<View>(screen,"status"),scene.getChildAt(1),scene.getChildAt(2),scene.getChildAt(3))
                        .map { view -> val p=IntArray(2);view.getLocationOnScreen(p);p[0] to p[1] }
                }
                val initial = landmarks()
                previewSession(screen)
                try {
                    call(screen,"refreshStatus")
                    screenshot(screen,"$name-home-connecting")
                    assertEquals("Home landmarks stay fixed while connecting", initial, landmarks())
                    val title=ReflectionHelpers.getField<TextView>(screen,"homeHeadline")
                    assertEquals(screen.getString(R.string.ui_glass_connecting_title),title.text.toString())
                    val disconnect=ReflectionHelpers.getField<android.widget.Button>(screen,"disconnectButton")
                    assertEquals(View.VISIBLE,disconnect.visibility)
                    CarPlayBackgroundSession.active=true
                    call(screen,"refreshStatus")
                    screenshot(screen,"$name-home-connected")
                    assertEquals("Home landmarks stay fixed when connected", initial, landmarks())
                    disconnect.performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertFalse("The existing disconnect callback still stops the session",CarPlayBackgroundSession.hasSession())
                } finally { CarPlayBackgroundSession.clear() }
            }
            // DiPlay-owned chrome is fixed dark. Legacy LIGHT/AUTO migration is covered by
            // AdaptiveSettingsUiTest, so rendering the same page twice adds no coverage.
            AirPlayPersistence.saveAppAppearance(app, AppAppearance.DARK)
            capturePhonePicker(screen, "$name-dark-phone-picker")
            ReflectionHelpers.setField(screen, "page", "settings")
            for (category in listOf(SettingsCategory.OVERVIEW, SettingsCategory.DISPLAY, SettingsCategory.ABOUT)) {
                CarPlayBackgroundSession.active=paired && category==SettingsCategory.OVERVIEW
                ReflectionHelpers.setField(screen, "settingsCategory", category)
                call(screen, "render")
                screenshot(screen, "$name-dark-${category.name.lowercase()}")
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
                        writeScreenshot(root,"$name-dark-storage")
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
                        writeScreenshot(root, "$name-dark-choice")
                        val actions=listOf(dialog.getButton(AlertDialog.BUTTON_POSITIVE),dialog.getButton(AlertDialog.BUTTON_NEGATIVE))
                            .sortedBy { it.left }
                        if(actions[0].top==actions[1].top) {
                            assertEquals("Dialog actions must have equal heights",actions[0].height,actions[1].height)
                            assertTrue("Dialog actions need a clear gap",actions[1].left-actions[0].right>=FidelitySettingsMetrics.DIALOG_ACTION_GAP_DP*density-1)
                        } else {
                            val stacked=actions.sortedBy { it.top }
                            assertTrue("Stacked dialog actions need a clear gap",stacked[1].top-stacked[0].bottom>=FidelitySettingsMetrics.DIALOG_ACTION_GAP_DP*density-1)
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
        descendants(root).filterIsInstance<ReferenceHomeScene>().forEach { scene ->
            val carHome = descendants(root).filterIsInstance<android.widget.Button>()
                .single { it.contentDescription?.toString() == screen.getString(R.string.car_home) }
            val header = carHome.parent as ViewGroup
            assertTrue("The complete car-home button fits the header",carHome.left >= 0 && carHome.right <= header.width)
            for (line in 0 until carHome.layout.lineCount) assertEquals("Header actions must not ellipsize",0,carHome.layout.getEllipsisCount(line))
            val brandLogo=header.getChildAt(0)
            assertTrue("Brand logo must fit its header",brandLogo.left>=0 && brandLogo.right<=header.width)
            assertFalse("Home branding is logo-only",descendants(header).filterIsInstance<TextView>()
                .any { it.text.toString() in listOf("Lincoln", "Ford") })
            val hero = scene.getChildAt(0)
            val firstAction = scene.getChildAt(1)
            val settings = scene.getChildAt(2)
            val brand = scene.getChildAt(3)
            assertEquals("Home footer actions share a row", firstAction.top, settings.top)
            assertEquals("Home footer actions have equal height", firstAction.height, settings.height)
            assertTrue("Home footer cannot overlap the main action", firstAction.top >= hero.bottom)
            assertEquals("Home footer must reach the bottom in either orientation", scene.height,
                maxOf(firstAction.bottom, settings.bottom, brand.bottom))
            val scroll = ReflectionHelpers.getField<android.widget.ScrollView>(screen, "rootScroll")
            val position = IntArray(2).also(scene::getLocationOnScreen)
            val scrollPosition = IntArray(2).also(scroll::getLocationOnScreen)
            val expectedBottom = scrollPosition[1] + scroll.height - scroll.paddingBottom -
                (ReferenceHomeMetrics.BOTTOM_DP * density).toInt()
            assertEquals("Home footer should sit above the safe viewport bottom", expectedBottom,
                position[1] + scene.height)
            for (button in descendants(scene).filterIsInstance<android.widget.Button>().filter { it.visibility == View.VISIBLE }) {
                val textLayout = button.layout ?: continue
                assertTrue("Button text fits vertically: ${button.text}", textLayout.height <=
                    button.height - button.compoundPaddingTop - button.compoundPaddingBottom + 2)
                if (textLayout.lineCount > 1) {
                    val offset = ReflectionHelpers.getField<Float>(button, "contentOffsetY")
                    assertEquals("Wrapped text must not inherit first-line centring", 0f, offset, 0f)
                }
            }
        }
        writeScreenshot(root, name)
    }

    private fun capturePhonePicker(screen: DiPlayActivity, name: String) {
        val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        shadowOf(adapter).setEnabled(true)
        val phone = adapter.getRemoteDevice("00:11:22:33:44:55")
        shadowOf(phone).setName("测试 iPhone")
        shadowOf(adapter).setBondedDevices(setOf(phone))
        call(screen, "choosePhone")
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        try {
            val root = dialog.window!!.decorView
            val density = screen.resources.displayMetrics.density
            val config = screen.resources.configuration
            val width = ((minOf(config.screenWidthDp, 600) - 32) * density).toInt()
            val height = ((config.screenHeightDp - 32) * density).toInt()
            repeat(3) {
                root.forceLayout()
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            }
            assertEquals(screen.getString(R.string.pair_another), dialog.getButton(AlertDialog.BUTTON_NEUTRAL).text)
            for (id in listOf(AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)) {
                val action = dialog.getButton(id)
                assertTrue(action.height >= 48 * density)
                for (line in 0 until action.layout.lineCount) assertEquals(0, action.layout.getEllipsisCount(line))
            }
            writeScreenshot(root, name)
        } finally {
            dialog.dismiss()
            shadowOf(adapter).setBondedDevices(emptySet())
            shadowOf(adapter).setEnabled(false)
        }
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
