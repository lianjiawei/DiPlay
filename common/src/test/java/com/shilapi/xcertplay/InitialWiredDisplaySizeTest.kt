package com.shilapi.xcertplay

import android.graphics.Insets
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.Window
import android.view.WindowInsets
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "zh-rCN")
@LooperMode(LooperMode.Mode.PAUSED)
class InitialWiredDisplaySizeTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var video: View
    private lateinit var decor: View
    private lateinit var display: Display
    private val frameHandler = Handler(Looper.getMainLooper())
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        video = mock(View::class.java)
        decor = mock(View::class.java)
        display = mock(Display::class.java)
        val window = mock(Window::class.java)
        `when`(window.decorView).thenReturn(decor)
        ReflectionHelpers.setField(activity, "mWindow", window)
        set("videoView", video)
        `when`(video.isAttachedToWindow).thenReturn(true)
        `when`(video.hasWindowFocus()).thenReturn(true)
        `when`(video.display).thenReturn(display)
        `when`(decor.isAttachedToWindow).thenReturn(true)
        `when`(decor.rootView).thenReturn(decor)
        `when`(decor.rootWindowInsets).thenReturn(hiddenBars())
        // Exercise the host's actual scheduling with a virtual frame clock, never wall-clock sleeps.
        doAnswer { frameHandler.postDelayed(it.getArgument<Runnable>(0), 16); null }
            .`when`(video).postOnAnimation(any(Runnable::class.java))
        `when`(video.removeCallbacks(any(Runnable::class.java))).thenAnswer {
            frameHandler.removeCallbacks(it.getArgument(0)); true
        }
        fullscreen(1920, 1080)
    }

    @After fun tearDown() {
        (get("shuttingDown") as AtomicBoolean).set(true)
        invoke("cancelInitialDisplaySizeCheck")
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        frameHandler.removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        (get("controller") as? CarPlayController)?.let(CarPlayMediaKeys::detach)
        (get("sink") as? AndroidMediaSink)?.close()
        CarPlayBackgroundSession.clear()
    }

    @Test fun stableLincolnFullscreenConfirmsOnTwoFramesInsteadOfWaiting500ms() {
        schedule(1920, 1080)
        advance(16)
        assertNull(get("activeDisplaySize"))
        advance(16)
        assertEquals(size(1920, 1080), get("activeDisplaySize"))
        assertNull(get("pendingDisplaySize"))
        assertNull(get("initialDisplaySizeCheck"))
        advance(600)
        assertEquals(size(1920, 1080), get("activeDisplaySize"))
        verify(video, times(2)).postOnAnimation(any(Runnable::class.java))
    }

    @Test fun readyPrerequisitesStartOnlyOneControllerOnTheConfirmedCanvas() {
        mockConstruction(CarPlayController::class.java) { controller, _ ->
            `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        }.use { controllers ->
            set("airPlayIdentity", AirPlayIdentity.generate())
            set("vpnReady", true)
            set("microphonePermissionResolved", true)
            schedule(1920, 1080)
            advance(32)
            val session = get("sessionDisplay") as CarPlaySessionDisplay
            assertEquals(1920, session.windowWidth)
            assertEquals(1080, session.windowHeight)
            assertEquals(1, controllers.constructed().size)
            advance(600)
            verify(controllers.constructed().single(), times(1)).start()
            assertEquals(0, get("restartGeneration"))
        }
    }

    @Test fun ultrawideAndPhoneSizesAreNotHardcodedToTheLincolnResolution() {
        for ((width, height) in listOf(1920 to 720, 2640 to 1200)) {
            set("activeDisplaySize", null)
            fullscreen(width, height)
            schedule(width, height)
            advance(32)
            assertEquals(size(width, height), get("activeDisplaySize"))
        }
    }

    @Test fun wirelessStartupKeepsItsExistingDelay() {
        set("wirelessEnabled", true)
        assertOriginalDelay()
    }

    @Test fun anAlreadyDetectedCanvasKeepsResizeDebounce() {
        set("activeDisplaySize", size(1920, 990))
        schedule(1920, 1080)
        advance(499)
        assertEquals(size(1920, 990), get("activeDisplaySize"))
        advance(1)
        assertEquals(size(1920, 1080), get("activeDisplaySize"))
    }

    @Test fun aRestoredSessionDoesNotTakeTheInitialShortcut() {
        set("sessionDisplay", CarPlaySessionDisplay(1920, 1080, 0, true, true, 1920, 1080))
        assertOriginalDelay()
    }

    @Test fun visibleStatusBarSettingKeepsViewportSettling() {
        set("hideTopBar", false)
        assertOriginalDelay()
    }

    @Test fun visibleNavigationBarSettingKeepsViewportSettling() {
        set("hideBottomBar", false)
        assertOriginalDelay()
    }

    @Test fun missingInsetsKeepTheLegacyFallback() {
        `when`(decor.rootWindowInsets).thenReturn(null)
        assertOriginalDelay()
    }

    @Test fun brokenOemDisplayQueryFallsBackInsteadOfFailingTheConnection() {
        doThrow(IllegalStateException("display removed")).`when`(display)
            .getRealMetrics(any(DisplayMetrics::class.java))
        assertOriginalDelay()
    }

    @Test fun barsStillHidingDoNotStartWithTheOldWindow() {
        `when`(decor.rootWindowInsets).thenReturn(WindowInsets.Builder(hiddenBars())
            .setVisible(WindowInsets.Type.statusBars(), true)
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 48, 0, 0)).build())
        assertOriginalDelay()
    }

    @Test fun keyboardAndCutoutDoNotUseTheFullRectangleShortcut() {
        `when`(decor.rootWindowInsets).thenReturn(WindowInsets.Builder(hiddenBars())
            .setVisible(WindowInsets.Type.ime(), true).build())
        assertOriginalDelay()
        set("activeDisplaySize", null)
        `when`(decor.rootWindowInsets).thenReturn(WindowInsets.Builder(hiddenBars())
            .setInsetsIgnoringVisibility(WindowInsets.Type.displayCutout(), Insets.of(80, 0, 0, 0)).build())
        assertOriginalDelay()
    }

    @Test fun layoutStillRequestedKeepsTheFallback() {
        `when`(video.isLayoutRequested).thenReturn(true)
        assertOriginalDelay()
    }

    @Test fun lateFocusCanStillConfirmWithoutRestartingThe500msDeadline() {
        `when`(video.hasWindowFocus()).thenReturn(false)
        schedule(1920, 1080)
        advance(160)
        assertNull(get("activeDisplaySize"))
        `when`(video.hasWindowFocus()).thenReturn(true)
        advance(16)
        assertNull(get("activeDisplaySize"))
        advance(16)
        assertEquals(size(1920, 1080), get("activeDisplaySize"))
    }

    @Test fun anUncertainFrameRequiresTwoFreshConfirmations() {
        schedule(1920, 1080)
        advance(16)
        `when`(video.isLayoutRequested).thenReturn(true)
        advance(16)
        `when`(video.isLayoutRequested).thenReturn(false)
        advance(16)
        assertNull(get("activeDisplaySize"))
        advance(16)
        assertEquals(size(1920, 1080), get("activeDisplaySize"))
    }

    @Test fun splitWindowAndVendorFullscreenKeepTheirOriginalPath() {
        shadowOf(activity).setInMultiWindowMode(true)
        assertOriginalDelay()
        shadowOf(activity).setInMultiWindowMode(false)
        set("activeDisplaySize", null)
        set("harmanFullscreenEnabled", true)
        assertOriginalDelay()
    }

    @Test fun aCameraWindowIsNotMistakenForTheWholeDisplay() {
        doAnswer { it.getArgument<Rect>(0).set(0, 0, 700, 1080); null }
            .`when`(decor).getWindowVisibleDisplayFrame(any(Rect::class.java))
        assertOriginalDelay()
    }

    @Test fun staleDisplayMetricsDuringRotationKeepTheFallback() {
        doAnswer {
            it.getArgument<DisplayMetrics>(0).apply { widthPixels = 1080; heightPixels = 1920 }; null
        }.`when`(display).getRealMetrics(any(DisplayMetrics::class.java))
        assertOriginalDelay()
    }

    @Test fun focusLossOrDetachBetweenFramesCannotConfirm() {
        schedule(1920, 1080)
        advance(16)
        `when`(video.hasWindowFocus()).thenReturn(false)
        advance(16)
        assertNull(get("activeDisplaySize"))
        advance(468)
        assertEquals(size(1920, 1080), get("activeDisplaySize"))
        set("activeDisplaySize", null)
        `when`(video.hasWindowFocus()).thenReturn(true)
        schedule(1920, 1080)
        advance(16)
        `when`(video.isAttachedToWindow).thenReturn(false)
        advance(16)
        assertNull(get("activeDisplaySize"))
    }

    @Test fun rotationBetweenSamplesCannotConfirmEvenIfDimensionsMatch() {
        schedule(1920, 1080)
        advance(16)
        `when`(display.rotation).thenReturn(Surface.ROTATION_180)
        advance(16)
        assertNull(get("activeDisplaySize"))
        assertNull(get("initialDisplaySizeCheck"))
    }

    @Test fun changedSizeReplacesTheOldConfirmationAndRequiresTwoNewFrames() {
        schedule(1920, 1080)
        advance(16)
        val old = get("initialDisplaySizeCheck") as Runnable
        fullscreen(1920, 720)
        schedule(1920, 720)
        old.run() // A callback already dispatched before cancellation must not apply the old size.
        advance(16)
        assertNull(get("activeDisplaySize"))
        advance(16)
        assertEquals(size(1920, 720), get("activeDisplaySize"))
    }

    @Test fun duplicateSizeEventsDoNotPostponeTheConfirmation() {
        schedule(1920, 1080)
        advance(16)
        schedule(1920, 1080)
        advance(16)
        assertEquals(size(1920, 1080), get("activeDisplaySize"))
    }

    @Test fun changingToWirelessDuringConfirmationKeepsTheFallback() {
        schedule(1920, 1080)
        advance(16)
        set("wirelessEnabled", true)
        advance(16)
        assertNull(get("activeDisplaySize"))
    }

    @Test fun shutdownAndStaleCallbackCannotApplyTheSize() {
        schedule(1920, 1080)
        advance(16)
        val old = get("initialDisplaySizeCheck") as Runnable
        (get("shuttingDown") as AtomicBoolean).set(true)
        advance(16)
        old.run()
        assertNull(get("activeDisplaySize"))
        assertNull(get("initialDisplaySizeCheck"))
        advance(600)
        assertNull(get("activeDisplaySize"))
    }

    private fun assertOriginalDelay() {
        schedule(1920, 1080)
        advance(499)
        assertNull(get("activeDisplaySize"))
        advance(1)
        assertEquals(size(1920, 1080), get("activeDisplaySize"))
    }

    private fun fullscreen(width: Int, height: Int) {
        for (view in listOf(video, decor)) {
            `when`(view.width).thenReturn(width)
            `when`(view.height).thenReturn(height)
        }
        doAnswer {
            it.getArgument<DisplayMetrics>(0).apply { widthPixels = width; heightPixels = height }; null
        }.`when`(display).getRealMetrics(any(DisplayMetrics::class.java))
        doAnswer { it.getArgument<Rect>(0).set(0, 0, width, height); null }
            .`when`(decor).getWindowVisibleDisplayFrame(any(Rect::class.java))
    }

    private fun hiddenBars() = WindowInsets.Builder()
        .setVisible(WindowInsets.Type.systemBars() or WindowInsets.Type.ime(), false)
        .setInsets(WindowInsets.Type.systemBars(), Insets.NONE).build()

    private fun size(width: Int, height: Int): Any = sizeClass
        .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(width, height)

    private fun schedule(width: Int, height: Int) {
        CarPlayHostActivity::class.java.getDeclaredMethod("scheduleDisplaySize", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, width, height)
    }

    private fun invoke(name: String) = CarPlayHostActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun get(name: String): Any? = ReflectionHelpers.getField(activity, name)
    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(activity, name, value)
    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
}
