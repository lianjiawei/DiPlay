package com.shilapi.xcertplay

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], qualifiers = "en-w900dp-h600dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateCheckUiTest {
    private var controller: ActivityController<DiPlayActivity>? = null
    private val app get() = RuntimeEnvironment.getApplication()
    private val workers = mutableListOf<Runnable>()

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        UpdateAvailability.clearAllForTest(app)
        CarPlayBackgroundSession.clear()
    }

    @Test fun offlineCheckShowsFriendlyMessageWithoutStartingWorkOrReplacingViews() {
        val screen = screen(online = false)
        screen.updateLookup = { error("Offline checks must not access the server") }
        val page = field<View>(screen, "rootScroll")
        val button = checkButton(screen)
        repeat(3) { button.performClick() }
        assertTrue(workers.isEmpty())
        assertSame(page, field<View>(screen, "rootScroll"))
        assertSame(button, checkButton(screen))
        assertEquals(screen.getString(R.string.update_no_network), status(screen))
        assertTrue(button.isEnabled)
    }

    @Test fun checkingAndSuccessKeepThePageAndButtonAndIgnoreRepeatedChecks() {
        val screen = screen(online = true)
        screen.updateLookup = { null }
        val page = field<View>(screen, "rootScroll")
        val button = checkButton(screen)
        button.performClick()
        call(screen, "checkForUpdates")
        assertEquals(1, workers.size)
        assertSame(button, checkButton(screen))
        assertEquals(screen.getString(R.string.update_checking), button.text.toString())
        assertFalse(button.isEnabled)
        workers.single().run()
        idle()
        assertSame(page, field<View>(screen, "rootScroll"))
        assertSame(button, checkButton(screen))
        assertFalse(button.isEnabled)
        assertEquals(screen.getString(R.string.update_up_to_date), button.text.toString())
        assertEquals("", status(screen))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals(screen.getString(R.string.update_check), button.text.toString())
        assertTrue(button.isEnabled)
    }

    @Test fun latestVersionFeedbackStillExpiresWhileTheActivityIsPaused() {
        val screen = screen(online = true)
        screen.updateLookup = { null }
        val button = checkButton(screen)
        button.performClick()
        workers.single().run()
        idle()
        assertEquals(screen.getString(R.string.update_up_to_date), button.text.toString())
        assertFalse(button.isEnabled)

        controller!!.pause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        controller!!.resume().visible()
        idle()

        assertEquals(screen.getString(R.string.update_check), checkButton(screen).text.toString())
        assertTrue(checkButton(screen).isEnabled)
    }

    @Test fun dnsFailureAndTimeoutHaveFriendlyMessagesAndRetainThePage() {
        val screen = screen(online = true)
        val page = field<View>(screen, "rootScroll")
        val button = checkButton(screen)
        for ((failure, message) in listOf(
            UnknownHostException("api.github.com: internal DNS details") to R.string.update_server_unreachable,
            SocketTimeoutException("raw timeout details") to R.string.update_connection_timeout,
            IllegalStateException("raw internal details") to R.string.update_failed,
        )) {
            screen.updateLookup = { throw failure }
            button.performClick()
            workers.removeAt(0).run()
            idle()
            assertSame(page, field<View>(screen, "rootScroll"))
            assertSame(button, checkButton(screen))
            assertEquals(screen.getString(message), status(screen))
            assertTrue(button.isEnabled)
        }
    }

    @Test fun losingNetworkDuringACheckShowsOfflineAndCanRetryAfterReconnecting() {
        val screen = screen(online = true)
        screen.updateLookup = { throw UnknownHostException("api.github.com") }
        val button = checkButton(screen)
        button.performClick()
        setOnline(false)
        workers.removeAt(0).run()
        idle()
        assertEquals(screen.getString(R.string.update_no_network), status(screen))
        setOnline(true)
        screen.updateLookup = { null }
        button.performClick()
        workers.removeAt(0).run()
        idle()
        assertEquals(screen.getString(R.string.update_up_to_date), button.text.toString())
        assertEquals("", status(screen))
        assertSame(button, checkButton(screen))
    }

    @Test fun discoveringANewReleaseOnlyReplacesTheUpdateBlock() {
        val screen = screen(online = true)
        val page = field<View>(screen, "rootScroll")
        screen.updateLookup = { UpdateRelease("99.0.0-precision.99", "app.apk", "https://example.com/app.apk", "https://example.com/SHA256SUMS.txt") }
        checkButton(screen).performClick()
        workers.removeAt(0).run()
        idle()
        assertSame(page, field<View>(screen, "rootScroll"))
        assertTrue(descendants(page).filterIsInstance<TextView>().any { it.text == screen.getString(R.string.update_download) })
    }

    @Test fun historyAlsoExplainsOfflineWithoutRebuildingThePage() {
        val screen = screen(online = false)
        val page = field<View>(screen, "rootScroll")
        screen.updateConnection = { error("Offline history must not access the server") }
        call(screen, "loadHistoryReleases")
        assertSame(page, field<View>(screen, "rootScroll"))
        assertEquals(screen.getString(R.string.update_no_network), field<String>(screen, "historyMessage"))
    }

    @Test fun testChannelIsOffByDefaultAndHasItsOwnManualEntry() {
        UpdateAvailability.clearAllForTest(app)
        val screen = screen(online = false)
        assertFalse(UpdateAvailability.testReleasesEnabled(app))
        assertFalse(descendants(field<View>(screen, "rootScroll")).filterIsInstance<TextView>()
            .any { it.text == screen.getString(R.string.update_test_versions) })
        UpdateAvailability.saveTestReleasesEnabled(app, true)
        call(screen, "render")
        assertTrue(descendants(field<View>(screen, "rootScroll")).filterIsInstance<TextView>()
            .any { it.text == screen.getString(R.string.update_test_versions) })
        assertFalse(descendants(field<View>(screen, "rootScroll")).filterIsInstance<TextView>()
            .any { it.text.toString() in setOf("在后台检查更新", "Check for updates in the background") })
    }

    @Test fun testAndHistoryActionsRemainVisibleAndShowTheActiveOperation() {
        val screen = screen(online = true)
        UpdateAvailability.saveTestReleasesEnabled(app, true)
        ReflectionHelpers.setField(screen, "historyCheckingTests", true)
        val stage = DiPlayActivity::class.java.getDeclaredField("historyStage").apply { isAccessible = true }
        for (name in listOf("CHECKING", "DOWNLOADING", "VERIFYING")) {
            stage.set(screen, stage.type.enumConstants.single { (it as Enum<*>).name == name })
            ReflectionHelpers.setField(screen, "historyProgress", 35)
            call(screen, "render")
            val history = field<TextView>(screen, "historyActionButton")
            val test = field<TextView>(screen, "testActionButton")
            assertEquals(screen.getString(R.string.update_history_download), history.text)
            assertEquals(when (name) {
                "CHECKING" -> screen.getString(R.string.update_history_checking)
                "DOWNLOADING" -> screen.getString(R.string.update_history_downloading, 35)
                else -> screen.getString(R.string.update_history_verifying)
            }, test.text.toString())
            assertFalse(history.isEnabled)
            assertFalse(test.isEnabled)
            if (name == "DOWNLOADING") {
                UpdateAvailability.saveTestReleasesEnabled(app, false)
                call(screen, "render")
                assertEquals(screen.getString(R.string.update_history_downloading, 35),
                    field<TextView>(screen, "testActionButton").text.toString())
                UpdateAvailability.saveTestReleasesEnabled(app, true)
            }
        }
        stage.set(screen, stage.type.enumConstants.single { (it as Enum<*>).name == "IDLE" })
        call(screen, "render")
        assertTrue(field<TextView>(screen, "historyActionButton").isEnabled)
        assertTrue(field<TextView>(screen, "testActionButton").isEnabled)
    }

    @Test fun testDownloadUpdatesTheSameActionsAndRemovesOptedOutEntryAfterCompletion() {
        UpdateAvailability.saveTestReleasesEnabled(app, true)
        val screen = screen(online = true)
        val history = field<TextView>(screen, "historyActionButton")
        val test = field<TextView>(screen, "testActionButton")
        ReflectionHelpers.setField(screen, "historyCheckingTests", true)
        val stage = DiPlayActivity::class.java.getDeclaredField("historyStage").apply { isAccessible = true }
        fun refresh(name: String) {
            stage.set(screen, stage.type.enumConstants.single { (it as Enum<*>).name == name })
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "refreshUpdateRow",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        }
        for (name in listOf("CHECKING", "DOWNLOADING", "VERIFYING", "IDLE")) {
            refresh(name)
            assertSame(history, field<TextView>(screen, "historyActionButton"))
            assertSame(test, field<TextView>(screen, "testActionButton"))
        }
        refresh("DOWNLOADING")
        UpdateAvailability.saveTestReleasesEnabled(app, false)
        call(screen, "render")
        assertNotNull(field<TextView?>(screen, "testActionButton"))
        refresh("IDLE")
        assertNull(field<TextView?>(screen, "testActionButton"))
    }

    @Test @Config(sdk = [29], qualifiers = "zh-rCN-w320dp-h600dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactChineseLargeFontScreenshot() = capture("compact-zh")

    @Test @Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun expandedEnglishLargeFontScreenshot() = capture("expanded-en")

    private fun capture(name: String) {
        RuntimeEnvironment.setFontScale(1.3f)
        val screen = screen(online = false)
        checkButton(screen).performClick()
        val root = field<View>(screen, "updateRowContainer")
        val metrics = screen.resources.displayMetrics
        val width = ((minOf(screen.resources.configuration.screenWidthDp, 600) - 32) * metrics.density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        for (text in descendants(root).filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }) {
            val layout = requireNotNull(text.layout)
            assertTrue("Truncated: ${text.text}", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
        }
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(android.graphics.Color.rgb(20, 23, 36))
            root.draw(Canvas(image))
            val path = Paths.get("build/reports/update-check-ui/$name-offline.png")
            Files.createDirectories(path.parent)
            Files.newOutputStream(path).use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { image.recycle() }
    }

    private fun screen(online: Boolean): DiPlayActivity {
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        setOnline(online)
        val c = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup().visible()
        controller = c
        val screen = c.get()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ABOUT)
        call(screen, "render")
        screen.updateCheckWorker = workers::add
        return screen
    }

    private fun setOnline(online: Boolean) {
        val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        connectivity.clearAllNetworks()
        connectivity.setActiveNetworkInfo(null)
        if (online) {
            val network = ShadowNetwork.newInstance(99)
            connectivity.addNetwork(network, mock(NetworkInfo::class.java))
            // Deliberately not VALIDATED: some vehicle firmware never sets that capability.
            val capabilities = mock(NetworkCapabilities::class.java)
            `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
            connectivity.setNetworkCapabilities(network, capabilities)
        }
    }

    private fun checkButton(screen: DiPlayActivity): TextView = field(screen, "updateCheckButton")
    private fun status(screen: DiPlayActivity) = field<TextView>(screen, "updateStatusLabel").text.toString()
    private fun <T> field(screen: DiPlayActivity, name: String): T = ReflectionHelpers.getField(screen, name)
    private fun call(screen: DiPlayActivity, method: String) = ReflectionHelpers.callInstanceMethod<Unit>(screen, method)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
