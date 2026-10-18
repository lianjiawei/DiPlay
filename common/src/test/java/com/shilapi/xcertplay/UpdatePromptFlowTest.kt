package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Intent
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], qualifiers = "en-w900dp-h600dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class UpdatePromptFlowTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DiPlayActivity>>()
    private var server: HttpServer? = null
    private val release = UpdateRelease("99.0.0-precision.3", "CorsairPlay.apk",
        "https://example.com/app.apk", "https://example.com/SHA256SUMS.txt", "## Changes\n- USB recovery")

    @After fun cleanup() {
        controllers.forEach { it.pause().stop().destroy() }
        CarPlayBackgroundSession.clear()
        UpdateAvailability.clearAllForTest(app)
        server?.stop(0)
    }

    @Test fun cachedUpdateOffersNotesWithoutStartingADownload() {
        UpdateAvailability.save(app, release)
        val c = screen()
        val queued = mutableListOf<Runnable>()
        c.get().updateDownloadWorker = queued::add
        val prompt = requireNotNull(prompt(c.get()))
        assertTrue(prompt.dialog.findViewById<TextView>(android.R.id.message).text.contains("USB recovery"))
        assertEquals(c.get().getString(R.string.update_download), prompt.dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertTrue(queued.isEmpty())
        assertNull(shadowOf(c.get()).nextStartedActivity)
    }

    @Test fun laterPersistsAcrossReopeningAndANewerVersionCanBeOffered() {
        UpdateAvailability.save(app, release)
        val first = screen()
        prompt(first.get())!!.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertNull(prompt(first.get()))
        clickHomeUpdate(first.get())
        assertNotNull(prompt(first.get()))
        prompt(first.get())!!.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        first.pause().stop().destroy()
        controllers.remove(first)
        val second = screen()
        assertNull(prompt(second.get()))
        val newer = release.copy(tagName = "99.0.0-precision.4")
        UpdateAvailability.save(app, newer)
        advance()
        assertNotNull(prompt(second.get()))
        assertFalse(UpdateAvailability.shouldPrompt(app, newer))
    }

    @Test fun backgroundResultIsShownWhileHomeIsAlreadyOpen() {
        val c = screen()
        assertNull(prompt(c.get()))
        UpdateAvailability.save(app, release)
        advance()
        assertNotNull(prompt(c.get()))
    }

    @Test fun disabledBackgroundChecksStillAllowExplicitHomeUpdates() {
        UpdateAvailability.save(app, release)
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        val c = screen()
        assertNull(prompt(c.get()))
        clickHomeUpdate(c.get())
        assertNotNull(prompt(c.get()))
    }

    @Test fun queuedAutomaticPromptCannotOpenWhilePaused() {
        val c = screen()
        UpdateAvailability.save(app, release)
        call(c.get(), "refreshUpdateAvailability")
        c.pause()
        idle()
        assertNull(prompt(c.get()))
        c.resume().windowFocusChanged(true)
        idle()
        assertNotNull(prompt(c.get()))
    }

    @Test fun settingsDoNotGetAnAutomaticPromptUntilReturningHome() {
        val c = screen()
        ReflectionHelpers.setField(c.get(), "page", "settings")
        UpdateAvailability.save(app, release)
        advance()
        assertNull(prompt(c.get()))
        ReflectionHelpers.setField(c.get(), "page", "home")
        advance()
        assertNotNull(prompt(c.get()))
    }

    @Test fun connectingOrActiveCarPlayIsNotInterruptedByAnAutomaticPrompt() {
        CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
            800, 480, Any(), CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        UpdateAvailability.save(app, release)
        val c = screen()
        assertNull(prompt(c.get()))
        CarPlayBackgroundSession.active = true
        advance()
        assertNull(prompt(c.get()))
        CarPlayBackgroundSession.clear()
        advance()
        assertNotNull(prompt(c.get()))
    }

    @Test fun anotherWindowDefersTheAutomaticOffer() {
        val c = screen()
        c.windowFocusChanged(false)
        UpdateAvailability.save(app, release)
        advance()
        assertNull(prompt(c.get()))
        c.windowFocusChanged(true)
        advance()
        assertNotNull(prompt(c.get()))
    }

    @Test fun downloadUsesTheSameWindowAndRepeatedClicksCannotStartAnotherTransfer() {
        UpdateAvailability.save(app, release)
        val c = screen()
        val queued = mutableListOf<Runnable>()
        c.get().updateDownloadWorker = queued::add
        val dialog = prompt(c.get())!!
        dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertSame(dialog, prompt(c.get()))
        assertTrue(dialog.progress.isIndeterminate)
        assertEquals(View.GONE, dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
        assertEquals(c.get().getString(R.string.update_hide), dialog.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text)
        call(c.get(), "downloadUpdate")
        dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(1, queued.size)
        assertNull(shadowOf(c.get()).nextStartedActivity)
    }

    @Test fun hidingProgressDoesNotCancelOrAutomaticallyReopenIt() {
        UpdateAvailability.save(app, release)
        val c = screen()
        val queued = mutableListOf<Runnable>()
        c.get().updateDownloadWorker = queued::add
        prompt(c.get())!!.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        prompt(c.get())!!.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertNull(prompt(c.get()))
        advance()
        assertNull(prompt(c.get()))
        descendants(c.get().window.decorView).filterIsInstance<TextView>()
            .single { it.text == c.get().getString(R.string.update_downloading_unknown) }.performClick()
        assertNotNull(prompt(c.get()))
        assertEquals(1, queued.size)
    }

    @Test fun progressIsRestoredAfterReturningFromAnotherApp() {
        UpdateAvailability.save(app, release)
        val c = screen()
        c.get().updateDownloadWorker = {}
        prompt(c.get())!!.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        c.pause()
        assertNull(prompt(c.get()))
        c.resume().windowFocusChanged(true)
        idle()
        assertNotNull(prompt(c.get()))
        assertTrue(prompt(c.get())!!.progress.isIndeterminate)
    }

    @Test fun aVerifiedDownloadOffersInstallButNeverInstallsAutomatically() {
        val published = serveUpdate(validChecksum = { true })
        UpdateAvailability.save(app, published)
        val c = screen()
        val latest = published.copy(tagName = "99.0.0-precision.4")
        c.get().updateLookup = { latest }
        c.get().updateDownloadWorker = { it.run() }
        val original = prompt(c.get())!!
        original.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertSame(original, prompt(c.get()))
        assertEquals(100, original.progress.progress)
        assertFalse(original.progress.isIndeterminate)
        assertEquals(latest, ReflectionHelpers.getField<UpdateRelease>(c.get(), "updateRelease"))
        assertEquals(c.get().getString(R.string.update_install_now), original.dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertNull(shadowOf(c.get()).nextStartedActivity)
        c.get().updateInstallWorker = { it.run() }
        original.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        val installer = requireNotNull(shadowOf(c.get()).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, installer.action)
        assertEquals("application/vnd.android.package-archive", installer.type)
        assertTrue(installer.data!!.authority!!.endsWith(".update-apks"))
        assertTrue(installer.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun checksumFailureOffersRetryAndOnlyTheSuccessfulRetryCanInstall() {
        var valid = false
        val published = serveUpdate(validChecksum = { valid })
        UpdateAvailability.save(app, published)
        val c = screen()
        val latest = published.copy(tagName = "99.0.0-precision.4")
        c.get().updateLookup = { latest }
        c.get().updateDownloadWorker = { it.run() }
        val dialog = prompt(c.get())!!
        dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(c.get().getString(R.string.update_retry), dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertNull(ReflectionHelpers.getField<File?>(c.get(), "updateFile"))
        assertNull(shadowOf(c.get()).nextStartedActivity)
        valid = true
        val retry = mutableListOf<Runnable>()
        c.get().updateDownloadWorker = retry::add
        dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(latest, ReflectionHelpers.getField<UpdateRelease>(c.get(), "updateRelease"))
        retry.single().run()
        assertSame(dialog, prompt(c.get()))
        assertEquals(c.get().getString(R.string.update_install_now), dialog.dialog.getButton(AlertDialog.BUTTON_POSITIVE).text)
        assertNotNull(ReflectionHelpers.getField<File?>(c.get(), "updateFile"))
        assertNull(shadowOf(c.get()).nextStartedActivity)
    }

    private fun screen(): ActivityController<DiPlayActivity> {
        val intent = Intent(app, DiPlayActivity::class.java).putExtra("page", "home")
        return Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup().visible().also {
            controllers += it
            it.windowFocusChanged(true)
            advance()
        }
    }

    private fun serveUpdate(validChecksum: () -> Boolean): UpdateRelease {
        val bytes = ByteArray(140_000) { (it % 251).toByte() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val host = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = host
        host.createContext("/apk") { x ->
            x.sendResponseHeaders(200, bytes.size.toLong())
            x.responseBody.use { it.write(bytes) }
        }
        host.createContext("/checksums") { x ->
            val text = "${if (validChecksum()) digest else "0".repeat(64)}  ${release.apkName}\n".toByteArray()
            x.sendResponseHeaders(200, text.size.toLong())
            x.responseBody.use { it.write(text) }
        }
        host.start()
        val root = "http://127.0.0.1:${host.address.port}"
        return release.copy(apkUrl = "$root/apk", checksumsUrl = "$root/checksums")
    }

    private fun prompt(screen: DiPlayActivity) = ReflectionHelpers.getField<UpdatePromptDialog?>(screen, "updatePrompt")
    private fun call(screen: DiPlayActivity, method: String) = ReflectionHelpers.callInstanceMethod<Unit>(screen, method)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
    private fun clickHomeUpdate(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()
        .single { it.text == screen.getString(R.string.update_available_home) }.performClick()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
