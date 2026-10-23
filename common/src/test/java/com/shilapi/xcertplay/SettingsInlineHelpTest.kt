package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Looper
import android.text.StaticLayout
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.ScrollView
import android.widget.TextView
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

/** Custom-drawn rows need geometry and touch checks in addition to TextView's own layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsInlineHelpTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null

    @Before fun prepare() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        DiPlayPreferences.saveAutoConnect(app, false)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        AirPlayPersistence.saveAppAppearance(app, AppAppearance.DARK)
        AirPlayPersistence.saveAudioFocusEnabled(app, true)
        CarPlayBackgroundSession.clear()
    }

    @After fun cleanup() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        controller?.pause()?.stop()?.destroy()
        RuntimeEnvironment.setFontScale(1f)
        AppAppearanceRuntime.resetForTest()
        CarPlayBackgroundSession.clear()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test @Config(sdk = [25, 27, 29])
    fun inlineHelpAndTheSettingHaveSeparateActionsAndDraggingDoesNotOpenHelp() {
        val screen = open()
        render(screen, SettingsCategory.VEHICLE)
        val row = gestureRow(screen)
        // Robolectric's pre-28 legacy StaticLayout does not return real glyph line ends.
        // Those SDKs exercise hit testing; the native capture cases check complete text.
        verifyRow(row, checkTextMetrics = Build.VERSION.SDK_INT >= 28)
        val icon = field<Rect>(row, "helpIconBounds")
        val target = field<RectF>(row, "helpTouchBounds")
        tap(row, icon.exactCenterX(), icon.exactCenterY())
        assertHelp(screen)

        val value = field<StaticLayout>(row, "valueLayout")
        tap(row, field<Float>(row, "valueX") + value.width / 2f,
            field<Float>(row, "rowHeaderTop") + field<Int>(row, "rowHeaderHeight") / 2f)
        val choices = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue("Tapping the value still opens the setting", choices.listView.count > 0)
        choices.dismiss()

        val slop = ViewConfiguration.get(screen).scaledTouchSlop * 2f
        touch(row, MotionEvent.ACTION_DOWN, target.centerX(), target.centerY())
        touch(row, MotionEvent.ACTION_MOVE, target.centerX() - slop, target.centerY())
        touch(row, MotionEvent.ACTION_UP, target.centerX(), target.centerY())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("A drag must not open a help dialog", ShadowAlertDialog.getLatestAlertDialog().isShowing)
        touch(row, MotionEvent.ACTION_DOWN, target.centerX(), target.centerY())
        touch(row, MotionEvent.ACTION_CANCEL, target.centerX(), target.centerY())
        assertFalse(ShadowAlertDialog.getLatestAlertDialog().isShowing)

        assertTrue(row.performLongClick())
        assertHelp(screen)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun landscapeAllCategories() = capture("landscape", 1f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun landscapeLargeTextAllCategories() = capture("landscape-large", 1.3f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w360dp-h760dp-port-xhdpi")
    fun portraitAllCategories() = capture("portrait", 1f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w360dp-h760dp-port-xhdpi")
    fun narrowPortraitAllCategories() = capture("portrait-large", 1.3f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
    fun fullAllCategories() = capture("full", 1f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
    fun fullLargeTextAllCategories() = capture("full-large", 1.3f)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w640dp-h360dp-land-xhdpi")
    fun arabicLargeTextAllCategories() = capture("rtl-large", 1.3f)

    @Test fun longValuesUseSpareTitleSpaceAndHelpIsIndependentOfDescriptions() {
        val screen = open()
        render(screen, SettingsCategory.VEHICLE)
        val row = gestureRow(screen)
        row.text = "A · A longer selected value that fits beside a short title"
        row.forceLayout()
        row.measure(exact(row.width), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        verifyRow(row)
        assertEquals(1, field<StaticLayout>(row, "titleLayout").lineCount)
        assertEquals("A short title must leave room for the value", 1, field<StaticLayout>(row, "valueLayout").lineCount)
        assertFalse("Help remains discoverable without a second-line description", field<Rect>(row, "helpIconBounds").isEmpty)
        ReflectionHelpers.setField(row, "rowHelp", null)
        row.forceLayout()
        row.measure(exact(row.width), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue("Only rows with explanations reserve a help target", field<Rect>(row, "helpIconBounds").isEmpty)
    }

    @Test @Config(sdk = [25, 27, 29])
    fun compactSwitchHelpDoesNotToggleAndTheWholeRowStillSaves() {
        val screen = open()
        render(screen, SettingsCategory.VEHICLE)
        val toggle = descendants(root(screen)).filterIsInstance<Switch>().single {
            it.contentDescription == screen.getString(R.string.right_hand_drive)
        }
        val row = toggle.parent as ViewGroup
        val note = descendants(row).filterIsInstance<TextView>().single {
            it.text == screen.getString(R.string.place_carplay_s_controls_closer_to_the_driver)
        }
        assertEquals(View.GONE, note.visibility)
        val initial = toggle.isChecked
        descendants(row).filterIsInstance<ImageButton>().single().performClick()
        val help = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(help.isShowing)
        assertEquals(initial, toggle.isChecked)
        assertTrue(descendants(help.window!!.decorView).filterIsInstance<TextView>().any { it.text == note.text })
        help.dismiss()
        row.performClick()
        assertEquals(!initial, toggle.isChecked)
        assertEquals(!initial, AirPlayPersistence.loadRightHandDrive(screen))
    }

    @Test @Config(sdk = [25, 27, 29])
    fun cornerSliderRetainsAllStepsLiveSavingAndReopening() {
        val screen = open()
        render(screen, SettingsCategory.DISPLAY)
        val entry = descendants(root(screen)).filterIsInstance<Button>().single {
            it.text.startsWith(screen.getString(R.string.settings_carplay_corner_radius) + " · ")
        }
        assertFalse("The settings page has no exposed slider", descendants(root(screen)).filterIsInstance<SeekBar>().any(::visible))
        repeat(2) { pass ->
            entry.performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val slider = descendants(dialog.window!!.decorView).filterIsInstance<SeekBar>().single()
            assertEquals(AirPlayPersistence.MAX_CARPLAY_CORNER_RADIUS_DP / AirPlayPersistence.CARPLAY_CORNER_RADIUS_STEP_DP, slider.max)
            assertEquals(if (pass == 0) 0 else slider.max, slider.progress)
            val listener = ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(slider, "mOnSeekBarChangeListener")
            val selected = if (pass == 0) slider.max else 0
            slider.progress = selected
            listener.onProgressChanged(slider, selected, true)
            listener.onStopTrackingTouch(slider)
            assertEquals(selected * AirPlayPersistence.CARPLAY_CORNER_RADIUS_STEP_DP, AirPlayPersistence.loadCarPlayCornerRadiusDp(screen))
            assertTrue("Spoken state follows the current value", entry.contentDescription.toString().startsWith(entry.text.toString()))
            assertTrue(entry.text.contains(if (selected == 0) screen.getString(R.string.settings_carplay_corner_radius_off)
                else screen.getString(R.string.settings_carplay_corner_radius_value, AirPlayPersistence.MAX_CARPLAY_CORNER_RADIUS_DP)))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        }
    }

    @Test @Config(sdk = [25, 27, 29])
    fun reconnectingSwitchCannotSaveUntilTheDriverConfirms() {
        val screen = open()
        val session = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        CarPlayBackgroundSession.active = true
        var saves = 0
        val save: (Boolean) -> Unit = { selected -> assertTrue(selected); saves++ }
        val method = DiPlayActivity::class.java.declaredMethods.single { it.name == "toggle" }.apply { isAccessible = true }
        repeat(2) { pass ->
            val body = LinearLayout(screen)
            method.invoke(screen, body, screen.getString(R.string.smooth_video),
                screen.getString(R.string.smooth_video_description), false, true, true, save)
            body.getChildAt(0).performClick()
            assertEquals("The live setting remains unchanged while confirmation is pending", 0, saves)
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing)
            assertEquals(screen.getString(R.string.apply_and_reconnect), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            dialog.getButton(if (pass == 0) AlertDialog.BUTTON_NEGATIVE else AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(if (pass == 0) 0 else 1, saves)
        }
    }

    @Test @Config(sdk = [25, 27, 29])
    fun diagnosticActionPaintAndSpokenStateFollowExportProgress() {
        val screen = open()
        render(screen, SettingsCategory.DIAGNOSTICS)
        val export = ReflectionHelpers.getField<Button>(screen, "exportButton")
        for (title in listOf(R.string.saving_report, R.string.save_diagnostic_report)) {
            export.text = screen.getString(title)
            export.forceLayout()
            export.measure(exact(export.width), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            assertEquals(export.text.toString(), field<StaticLayout>(export, "titleLayout").text.toString())
            assertTrue(export.contentDescription.toString().startsWith(export.text.toString()))
        }
    }

    @Test @Config(sdk = [25, 27, 29])
    fun dividersOnlySeparateAdjacentSettingsAndNeverFinishASingleItemCard() {
        val screen = open()
        render(screen, SettingsCategory.VEHICLE)
        val model = descendants(root(screen)).filterIsInstance<Button>().single {
            it.text.startsWith(screen.getString(R.string.settings_vehicle_model) + " · ")
        }
        assertFalse("The one-setting model card stays clean", field<Boolean>(model, "drawRowDivider"))
        assertTrue("Different settings within the same card have a separator", field<Boolean>(gestureRow(screen), "drawRowDivider"))
        val drivingSide = descendants(root(screen)).filterIsInstance<Switch>().single {
            it.contentDescription == screen.getString(R.string.right_hand_drive)
        }
        assertFalse("No separator below the last switch", (drivingSide.parent as View).foreground is SettingsRowForeground)

        render(screen, SettingsCategory.ADVANCED)
        val firstSwitch = descendants(root(screen)).filterIsInstance<Switch>().single {
            it.contentDescription == screen.getString(R.string.settings_home_card_media)
        }
        assertTrue("Switch-to-switch rows use the same divider style", (firstSwitch.parent as View).foreground is SettingsRowForeground)

        render(screen, SettingsCategory.AUDIO)
        val gain = descendants(root(screen)).filterIsInstance<Button>().single {
            it.text.startsWith(screen.getString(R.string.settings_navigation_attenuation) + " · ")
        }
        assertTrue("Slider entries follow the same rule", field<Boolean>(gain, "drawRowDivider"))
        val card = gain.parent.parent as LinearLayout
        val speaker = descendants(card).filterIsInstance<Switch>().single {
            it.contentDescription == screen.getString(R.string.settings_call_speakerphone)
        }.parent as View
        val lastChoice = descendants(card).filterIsInstance<Button>().last { it.javaClass.simpleName == "SettingButton" }
        assertTrue(field<Boolean>(lastChoice, "drawRowDivider"))
        speaker.visibility = View.GONE
        val update = DiPlayActivity::class.java.getDeclaredMethod("updateSettingsDividers", LinearLayout::class.java).apply { isAccessible = true }
        repeat(2) { update.invoke(screen, card) }
        assertFalse("A hidden final setting cannot leave a trailing line", field<Boolean>(lastChoice, "drawRowDivider"))
    }

    @Test fun aboutHeaderIsMinimalAndProductAndLicenseDetailsRemainAvailable() {
        val screen = open()
        render(screen, SettingsCategory.ABOUT)
        val title = descendants(root(screen)).filterIsInstance<TextView>().single {
            it.text == VehicleBranding.text(screen, R.string.about_diplay)
        }
        val header = title.parent as ViewGroup
        assertEquals("Only the title and version remain", 2, header.childCount)
        assertFalse("The About header needs no separate help action", descendants(header).any { it is ImageButton })
        assertFalse(descendants(root(screen)).filterIsInstance<TextView>().any {
            it.text == screen.getString(R.string.carplay_at_home_in_your_car) ||
                it.text == screen.getString(R.string.ui_glass_not_certified)
        })
        descendants(root(screen)).filterIsInstance<Button>().single {
            it.text == screen.getString(R.string.made_possible_by_open_source)
        }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.contains(VehicleBranding.text(screen, R.string.an_independent_carplay_receiver_for_android_head_units_wir))
        })
        assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.contains(VehicleBranding.text(screen, R.string.receiver_based_on_xcertplay_licensed_under_gpl_3_0_diplay))
        })
        dialog.dismiss()
    }

    private fun capture(name: String, scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        ReflectionHelpers.setStaticField(Build::class.java, "MODEL", "MEK-MX8Q")
        val screen = open()
        // Presentation fixtures: source-only tests intentionally lack the signed build's identity.
        ReflectionHelpers.setField(screen, "setupError", null)
        var rowsWithHelp = 0
        for (category in SettingsCategory.entries) {
            render(screen, category)
            val rows = descendants(root(screen)).filterIsInstance<Button>().filter {
                it.javaClass.simpleName == "SettingButton" && field<Boolean>(it, "rowPresentation") && visible(it)
            }.toList()
            rows.forEach { row ->
                verifyRow(row)
                if (!field<Rect>(row, "helpIconBounds").isEmpty) rowsWithHelp++
            }
            descendants(root(screen)).filterIsInstance<Switch>().filter(::visible).forEach { toggle ->
                val row = toggle.parent as ViewGroup
                val help = descendants(row).filterIsInstance<ImageButton>().singleOrNull()
                help?.let {
                    val density = row.resources.displayMetrics.density
                    assertTrue(it.width >= FidelitySettingsMetrics.ROW_HELP_TARGET_DP * density - 1)
                    assertTrue(it.height >= FidelitySettingsMetrics.ROW_HELP_TARGET_DP * density - 1)
                    val drawing = RectF(0f, 0f, it.drawable.intrinsicWidth.toFloat(), it.drawable.intrinsicHeight.toFloat())
                    it.imageMatrix.mapRect(drawing)
                    assertTrue("Switch help matches the choice-row marker size", drawing.width() <= FidelitySettingsMetrics.ROW_HELP_ICON_DP * density + 1)
                }
            }
            val folder = File("build/reports/settings-inline-help/$name")
            screenshot(root(screen), File(folder, "${category.name.lowercase()}.png"))
            val content = ReflectionHelpers.getField<ScrollView>(screen, "rootScroll").getChildAt(0)
            screenshot(content, File(folder, "${category.name.lowercase()}-content.png"))
        }
        render(screen, SettingsCategory.DISPLAY)
        descendants(root(screen)).filterIsInstance<Button>().single {
            it.text.startsWith(screen.getString(R.string.settings_carplay_corner_radius) + " · ")
        }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val decor = dialog.window!!.decorView
        decor.measure(exact(minOf(screen.resources.displayMetrics.widthPixels, (560 * screen.resources.displayMetrics.density).toInt())),
            View.MeasureSpec.makeMeasureSpec(screen.resources.displayMetrics.heightPixels, View.MeasureSpec.AT_MOST))
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        screenshot(decor, File("build/reports/settings-inline-help/$name/corner-adjustment.png"))
        dialog.dismiss()
        assertTrue("Audit must exercise help markers across categories", rowsWithHelp >= 5)
    }

    private fun verifyRow(row: Button, checkTextMetrics: Boolean = true) {
        val title = field<StaticLayout>(row, "titleLayout")
        val value = field<StaticLayout?>(row, "valueLayout")
        val headerTop = field<Float>(row, "rowHeaderTop")
        val headerHeight = field<Int>(row, "rowHeaderHeight")
        val centre = headerTop + headerHeight / 2f
        val icon = field<Rect>(row, "helpIconBounds")
        val target = field<RectF>(row, "helpTouchBounds")
        val density = row.resources.displayMetrics.density
        fun rect(layout: StaticLayout, x: Float) = RectF(x, centre - layout.height / 2f, x + layout.width, centre + layout.height / 2f)
        val titleBounds = rect(title, field(row, "titleX"))
        assertTrue("Title fits the row: ${row.text}", titleBounds.left >= 0 && titleBounds.right <= row.width + 1)
        value?.let {
            val valueBounds = rect(it, field(row, "valueX"))
            assertFalse("Title and value cannot overlap: ${row.text}", RectF.intersects(titleBounds, valueBounds))
            assertTrue(valueBounds.left >= 0 && valueBounds.right <= row.width + 1)
            if (!icon.isEmpty) assertFalse("Value cannot overlap the help target", RectF.intersects(valueBounds, target))
        }
        for (layout in if (checkTextMetrics) listOfNotNull(title, value) else emptyList()) {
            assertEquals("All text must be laid out", layout.text.length, layout.getLineEnd(layout.lineCount - 1))
            for (line in 0 until layout.lineCount) {
                assertEquals(0, layout.getEllipsisCount(line))
                assertTrue("Text must fit its column: ${layout.text}", layout.getLineMax(line) <= layout.width + 2)
            }
        }
        if (!icon.isEmpty) {
            assertEquals("The marker shares the title/value line", centre, icon.exactCenterY(), 1f)
            assertTrue("The icon must fit the help touch target", target.contains(RectF(icon)))
            assertTrue("Help retains a 48dp target", target.width() >= 48 * density - 1 && target.height() >= 48 * density - 1)
            assertFalse("Help cannot overlap the title", RectF.intersects(titleBounds, target))
            assertTrue("Help stays inside the row", target.left >= 0 && target.right <= row.width && target.top >= 0 && target.bottom <= row.height)
        }
        assertEquals("Only the title/value header and shared row padding determine height",
            maxOf((FidelitySettingsMetrics.SETTING_ROW_DP * density).toInt(), headerHeight + (FidelitySettingsMetrics.ROW_VERTICAL_DP * 2 * density).toInt()), row.height)

    }

    private fun assertHelp(screen: DiPlayActivity) {
        shadowOf(Looper.getMainLooper()).idle()
        val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        assertNull("Help must not open the choice picker", dialog.listView)
        assertTrue(descendants(dialog.window!!.decorView).filterIsInstance<TextView>().any {
            it.text.toString().contains(screen.getString(R.string.settings_gesture_fingers_hint))
        })
        dialog.dismiss()
    }

    private fun open(): DiPlayActivity {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup().visible()
        return controller!!.get()
    }

    private fun gestureRow(screen: DiPlayActivity) = descendants(root(screen)).filterIsInstance<Button>()
        .single { it.text.startsWith(screen.getString(R.string.settings_gesture_fingers_label) + " · ") }

    private fun render(screen: DiPlayActivity, category: SettingsCategory) {
        ReflectionHelpers.setField(screen, "settingsCategory", category)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val root = root(screen)
        root.layoutDirection = screen.resources.configuration.layoutDirection
        val metrics = screen.resources.displayMetrics
        repeat(2) {
            descendants(root).forEach { it.forceLayout() }
            root.measure(exact(metrics.widthPixels), exact(metrics.heightPixels))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun tap(row: Button, x: Float, y: Float) {
        touch(row, MotionEvent.ACTION_DOWN, x, y)
        touch(row, MotionEvent.ACTION_UP, x, y)
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun touch(row: Button, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(0, 20, action, x, y, 0).let { event ->
            try { row.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
    private fun screenshot(view: View, file: File) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            file.parentFile.mkdirs()
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
    private fun visible(view: View): Boolean = generateSequence(view) { it.parent as? View }.all { it.visibility == View.VISIBLE }
    private fun root(screen: DiPlayActivity) = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    private fun exact(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)
    private fun <T> field(view: View, name: String): T = ReflectionHelpers.getField(view, name)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
