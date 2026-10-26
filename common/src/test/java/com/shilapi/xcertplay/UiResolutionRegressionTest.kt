package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.setup.SetupGuide
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Pairwise screen coverage: representative profiles are exhaustive; the remaining profiles smoke key pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiResolutionRegressionTest {
    private enum class Coverage { FULL, SMOKE }

    private val app get() = RuntimeEnvironment.getApplication()
    private val findings = mutableListOf<String>()
    private var steps = 0
    private var texts = 0

    @After fun cleanup() {
        RuntimeEnvironment.setFontScale(1f)
        CarPlayBackgroundSession.clear()
        UpdateAvailability.clearAllForTest(app)
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun landscape800x480() = matrix("800x480-mdpi", Coverage.FULL, listOf(1f, 1.3f))

    @Test @Config(qualifiers = "zh-rCN-w1024dp-h600dp-land-mdpi")
    fun landscape1024x600() = matrix("1024x600-mdpi")

    @Test @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-xhdpi")
    fun landscape1280x720HighDensity() = matrix("1280x720-xhdpi")

    @Test @Config(qualifiers = "zh-rCN-w960dp-h540dp-land-xhdpi")
    fun landscape1920x1080HighDensity() = matrix("1920x1080-xhdpi", Coverage.FULL)

    /** Huawei debug phone: 2640×1200 physical pixels at the usual 3× Android density. */
    @Test @Config(qualifiers = "zh-rCN-w880dp-h400dp-land-xxhdpi")
    fun huaweiPhone2640x1200() = matrix("2640x1200-xxhdpi", Coverage.FULL)

    @Test @Config(qualifiers = "zh-rCN-w960dp-h360dp-land-xhdpi")
    fun ultraWide1920x720() = matrix("1920x720-xhdpi", Coverage.FULL)

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h720dp-land-mdpi")
    fun ultraWide1920x720StandardDensity() = matrix("1920x720-mdpi")

    @Test @Config(qualifiers = "zh-rCN-w1280dp-h480dp-land-hdpi")
    fun ultraWide1920x720MediumDensity() = matrix("1920x720-hdpi")

    @Test @Config(qualifiers = "zh-rCN-w512dp-h300dp-land-xhdpi")
    fun smallLandscape1024x600HighDensity() = matrix("1024x600-xhdpi")

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-mdpi")
    fun portrait320x600() = matrix("320x600-mdpi")

    @Test @Config(qualifiers = "zh-rCN-w400dp-h300dp-land-mdpi")
    fun splitWindow400x300() = matrix("400x300-mdpi")

    @Test @Config(qualifiers = "zh-rCN-w800dp-h480dp-land-mdpi")
    fun hiddenSetupGuideFallbackRemainsRenderable() {
        RuntimeEnvironment.setFontScale(1.3f)
        prepareFixture()
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
        val screen = controller.get()
        val directory = Paths.get("build/reports/ui-resolution-regression/setup-guide-fallback")
        try {
            for (step in SetupGuide.STEP_CAR..SetupGuide.STEP_DONE) {
                ReflectionHelpers.setField(screen, "setupStep", step)
                navigate(screen, "setup")
                capture(screen, directory, "setup-$step")
                assertSubpageHeader(screen, "setup-$step")
            }
        } finally {
            controller.pause().stop().destroy()
        }
        writeResults(directory.resolve("results.txt"), "setup-guide-fallback")
    }

    private fun matrix(
        name: String,
        coverage: Coverage = Coverage.SMOKE,
        fontScales: List<Float> = listOf(1.3f),
    ) {
        findings.clear()
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        packageInfo.versionName = "precision.8"
        shadowOf(app.packageManager).installPackage(packageInfo)
        for (fontScale in fontScales) {
            RuntimeEnvironment.setFontScale(fontScale)
            prepareFixture()
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
            val screen = controller.get()
            val directory = Paths.get("build/reports/ui-resolution-regression/$name/font-$fontScale")
            try {
                capture(screen, directory, "01-home")
                navigate(screen, "connection")
                capture(screen, directory, "02-connection")
                assertSubpageHeader(screen, "02-connection")
                val categories = if (coverage == Coverage.FULL) SettingsCategory.entries else listOf(
                    SettingsCategory.OVERVIEW,
                    SettingsCategory.DISPLAY,
                    SettingsCategory.VEHICLE,
                    SettingsCategory.ABOUT,
                )
                for (category in categories) {
                    ReflectionHelpers.setField(screen, "settingsCategory", category)
                    navigate(screen, "settings")
                    val state = "04-settings-${category.name.lowercase()}"
                    capture(screen, directory, state)
                    if (coverage == Coverage.FULL && category == SettingsCategory.ABOUT) {
                        call(screen, "checkForUpdates")
                        capture(screen, directory, "$state-offline")
                    }
                }
                if (coverage == Coverage.FULL) {
                    navigate(screen, "backup")
                    capture(screen, directory, "05-phone-backup")
                    assertSubpageHeader(screen, "05-phone-backup")
                    val release = UpdateRelease("precision.99", "CorsairPlay.apk", "https://example.com/app.apk",
                        "https://example.com/SHA256SUMS.txt", "USB recovery\n更新提示和屏幕适配")
                    val prompt = UpdatePromptDialog(screen, {}, {}, {})
                    try {
                        prompt.show(release, UpdatePromptStage.OFFER, null, null)
                        shadowOf(Looper.getMainLooper()).idle()
                        for (stage in listOf(UpdatePromptStage.OFFER, UpdatePromptStage.DOWNLOADING, UpdatePromptStage.READY)) {
                            prompt.update(release, stage, if (stage == UpdatePromptStage.DOWNLOADING) 42 else null, null)
                            shadowOf(Looper.getMainLooper()).idle()
                            val root = prompt.dialog.window!!.decorView
                            val metrics = screen.resources.displayMetrics
                            val width = ((minOf(screen.resources.configuration.screenWidthDp, 600) - 32) * metrics.density).toInt()
                            val height = ((screen.resources.configuration.screenHeightDp - 32) * metrics.density).toInt()
                            root.measure(exact(width), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
                            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                            inspect(root, "${directory.fileName}/06-update-${stage.name}")
                            screenshot(root, directory.resolve("06-update-${stage.name.lowercase()}.png"))
                            steps++
                        }
                    } finally { prompt.dismiss() }
                }
            } finally {
                controller.pause().stop().destroy()
            }
        }
        writeResults(Paths.get("build/reports/ui-resolution-regression/$name/results.txt"), name)
    }

    private fun prepareFixture() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        DiPlayPreferences.saveAutoConnect(app, false)
        SettingsLayoutPreferences.saveForceFull(app, false)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        shadowOf(app.getSystemService(ConnectivityManager::class.java)).apply {
            clearAllNetworks()
            setActiveNetworkInfo(null)
        }
    }

    private fun writeResults(output: Path, name: String) {
        Files.createDirectories(output.parent)
        Files.writeString(output, "states=$steps\ntextViews=$texts\nfindings=${findings.size}\n" + findings.joinToString("\n"))
        assertTrue("Layout findings for $name:\n${findings.take(30).joinToString("\n")}", findings.isEmpty())
    }

    private fun assertSubpageHeader(screen: DiPlayActivity, state: String) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val back = descendants(root).filterIsInstance<TextView>().singleOrNull {
            it.contentDescription == screen.getString(R.string.back)
        }
        assertNotNull("$state must expose one Back action", back)
        val header = back!!.parent as ViewGroup
        assertSame("$state Back must be the first logical header control", back, header.getChildAt(0))
        val brandNames = HomeVehicleBrand.entries.map { it.displayName(screen) }.toSet()
        assertFalse("$state must not show home branding", descendants(header).any {
            it.contentDescription?.toString() in brandNames ||
                it is TextView && it.text.toString() in brandNames
        })
        assertEquals("$state Back must stay on the LTR start edge", 0, back.left)
    }

    private fun navigate(screen: DiPlayActivity, page: String) {
        ReflectionHelpers.setField(screen, "page", page)
        call(screen, "render")
    }

    private fun capture(screen: DiPlayActivity, directory: Path, state: String) {
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = screen.resources.displayMetrics
        val config = screen.resources.configuration
        val width = (config.screenWidthDp * metrics.density).toInt()
        val height = (config.screenHeightDp * metrics.density).toInt()
        root.layoutDirection = config.layoutDirection
        root.measure(exact(width), exact(height))
        root.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
        // Posted scroll restoration can request a final layout pass.
        root.measure(exact(width), exact(height))
        root.layout(0, 0, width, height)
        inspect(root, "${directory.fileName}/$state")
        screenshot(root, directory.resolve("$state.png"))
        for (scroll in descendants(root).filterIsInstance<ScrollView>()) {
            val content = scroll.getChildAt(0) ?: continue
            if (content.height > scroll.height) {
                scroll.scrollTo(0, content.height)
                if (scroll.scrollY <= 0) findings += "$state: overflowing content cannot scroll"
                scroll.scrollTo(0, 0)
            }
        }
        // Keep all settings content as evidence, including controls initially below the fold.
        val scroll = ReflectionHelpers.getField<ScrollView?>(screen, "rootScroll")
        if (scroll != null && state.startsWith("04-settings")) {
            screenshot(scroll.getChildAt(0), directory.resolve("$state-content.png"))
        }
        steps++
    }

    private fun inspect(root: View, state: String) {
        for (text in descendants(root).filterIsInstance<TextView>()) {
            if (text is EditText || text.text.isNullOrBlank() || !ancestorsVisible(text, root)) continue
            val layout = text.layout ?: continue
            texts++
            val value = text.text.toString().replace('\n', ' ').take(100)
            if ((0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }) {
                findings += "$state: ellipsized [$value]"
            }
            if ((0 until layout.lineCount).any { layout.getLineMax(it) > layout.width + 2 }) {
                findings += "$state: horizontally clipped [$value]"
            }
            val room = text.height - text.compoundPaddingTop - text.compoundPaddingBottom
            if (layout.lineCount > 0 && layout.getLineBottom(layout.lineCount - 1) > room + 2) {
                findings += "$state: vertically clipped [$value] height=${text.height} text=${layout.height} room=$room"
            }
            val parent = text.parent as? ViewGroup ?: continue
            if (text.left < -1 || text.right > parent.width + 1) {
                findings += "$state: outside parent [$value] ${text.left}..${text.right} parent=${parent.width}"
            }
        }
    }

    private fun ancestorsVisible(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun screenshot(view: View, path: Path) {
        if (view.width <= 0 || view.height <= 0) { findings += "$path: empty screenshot"; return }
        Files.createDirectories(path.parent)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(20, 23, 36))
            view.draw(Canvas(bitmap))
            Files.newOutputStream(path).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun call(screen: DiPlayActivity, method: String) = ReflectionHelpers.callInstanceMethod<Unit>(screen, method)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
