package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.content.pm.ApplicationInfo
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.airplay.AirPlaySession
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class SidePanelLayoutTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var root: FrameLayout
    private lateinit var panel: LinearLayout
    private lateinit var video: TextureView
    private val sink = AndroidMediaSink()

    @Before fun setup() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.getSharedPreferences("diplay_side_panel", 0).edit().clear().commit()
        activity.applicationInfo.targetSdkVersion = 29
        root = FrameLayout(activity)
        panel = LinearLayout(activity).also { root.addView(it) }
        video = TextureView(activity)
        set("sidePanel", panel)
        set("videoView", video)
        set("sidePanelShown", true)
    }

    @After fun cleanup() {
        MusicWindowSettings.setEnabled(activity, false)
        activity.getSharedPreferences("diplay_side_panel", 0).edit().clear().commit()
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        sink.close()
    }

    @Test fun musicContentFollowsTheDeclaredSessionRatherThanAChangedPreference() {
        val built = activity.javaClass.getDeclaredMethod("buildSidePanel")
            .apply { isAccessible = true }.invoke(activity) as LinearLayout
        set("sidePanel", built)
        val display = CarPlaySessionDisplay(1920, 1080, Surface.ROTATION_0, true, true,
            1920, 1080, musicWindowEnabled = true)
        set("sessionDisplay", display)
        fun apply() = activity.javaClass.getDeclaredMethod("applySidePanelContent")
            .apply { isAccessible = true }.invoke(activity)
        apply()
        val card = get("musicWindow") as CarPlayMusicWindow
        assertEquals(View.VISIBLE, card.visibility)
        assertEquals(View.GONE, (get("sidePanelInfo") as View).visibility)

        MusicWindowSettings.setEnabled(activity, false)
        apply()
        assertSame(card, get("musicWindow"))
        assertEquals(View.VISIBLE, card.visibility)

        set("sessionDisplay", display.copy(musicWindowEnabled = false))
        apply()
        assertNull(get("musicWindow"))
        assertEquals(View.VISIBLE, (get("sidePanelInfo") as View).visibility)
    }

    @Test fun musicWindowAutoShowIgnoresAnEndedSession() {
        val areas = CarPlayViewAreas.build(1920, 1080, CarPlayDock.AUTOMATIC, null, sidePanel = true)!!
        val display = CarPlaySessionDisplay(1920, 1080, Surface.ROTATION_0, true, true,
            1920, 1080, areas, musicWindowEnabled = true)
        val controller = mock(CarPlayController::class.java)
        val session = mock(AirPlaySession::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        video.layout(0, 0, 1920, 1080)
        set("sessionDisplay", display)
        set("controller", controller)
        set("sidePanelShown", false)
        panel.visibility = View.GONE
        CarPlayBackgroundSession.store(controller, sink, 1920, 1080, activity, display) {}
        val generation = get("restartGeneration") as Int
        fun schedule() = activity.javaClass.getDeclaredMethod("autoShowMusicWindow", Int::class.javaPrimitiveType,
            AirPlaySession::class.java).apply { isAccessible = true }.invoke(activity, generation, session)

        set("activeAirPlaySession", session)
        schedule()
        set("activeAirPlaySession", null)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        verify(controller, never()).showViewArea(anyInt())

        set("activeAirPlaySession", session)
        schedule()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        verify(controller).showViewArea(anyInt())
        assertEquals(true, get("sidePanelShown"))
    }

    @Test fun letterboxedPanelKeepsItsPhysicalPassengerSide() {
        for (rightHandDrive in listOf(false, true)) {
            root.layoutDirection = View.LAYOUT_DIRECTION_LTR
            val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, null,
                sidePanel = true, rightHandDrive = rightHandDrive)!!
            areas.use(areas.sidePanel()!!)
            set("sessionDisplay", CarPlaySessionDisplay(2560, 1440, Surface.ROTATION_0,
                true, true, 1200, 1200, areas))
            place(1200, 1200)
            root.measure(exact(1200), exact(1200))
            root.layout(0, 0, 1200, 1200)
            assertEquals(View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
            assertEquals("rightHandDrive=$rightHandDrive", if (rightHandDrive) 0 else 800, panel.left)
            assertEquals(263, panel.top)
            assertEquals(400, panel.width)
            assertEquals(675, panel.height)
        }
    }

    @Test fun portraitPanelCoversTheBottomStripOfTheSquareStream() {
        val areas = CarPlayViewAreas.build(2560, 2560, listOf(
            CarPlayViewAreas.Screen(2560, 1440, false), CarPlayViewAreas.Screen(1440, 2560, true)),
            CarPlayDock.AUTOMATIC, { null }, true, sidePanel = true, rightHandDrive = true)!!
        areas.use(areas.sidePanel(true)!!)
        set("sessionDisplay", CarPlaySessionDisplay(2560, 2560, Surface.ROTATION_90,
            true, true, 900, 1600, areas))
        place(900, 1600)
        val params = panel.layoutParams as FrameLayout.LayoutParams
        assertEquals(0, params.leftMargin)
        assertEquals(1066, params.topMargin)
        assertEquals(900, params.width)
        assertEquals(534, params.height)
    }

    @Test fun adoptingAnOpenSidePanelRestoresItsViewAndRefreshWithoutChangingTheArea() {
        val areas = CarPlayViewAreas.build(2560, 1440, CarPlayDock.AUTOMATIC, null, sidePanel = true)!!
        val selected = areas.sidePanel()!!
        areas.use(selected)
        val display = CarPlaySessionDisplay(2560, 1440, Surface.ROTATION_0, true, true, 2560, 1440, areas)
        val controller = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(controller, sink, 2560, 1440, Any(), display) {}
        set("sidePanelShown", false)
        panel.visibility = View.GONE
        assertEquals(true, activity.javaClass.getDeclaredMethod("adoptBackgroundSession")
            .apply { isAccessible = true }.invoke(activity))
        assertEquals(selected, areas.current)
        assertEquals(true, get("sidePanelShown"))
        assertEquals(View.VISIBLE, panel.visibility)
        assertTrue((get("mainHandler") as Handler).hasCallbacks(get("sidePanelTick") as Runnable))
    }

    @Test fun draggingMusicPanelSnapsToFineWidthsAndStillClosesAtTheEdge() {
        val areas = CarPlayViewAreas.build(1920, 1080, CarPlayDock.AUTOMATIC, null,
            sidePanel = true, narrowMusicPanel = true)!!
        areas.use(areas.sidePanel()!!)
        val controller = mock(CarPlayController::class.java)
        `when`(controller.showViewArea(anyInt())).thenReturn(true)
        video.layout(0, 0, 1920, 1080)
        set("sessionDisplay", CarPlaySessionDisplay(1920, 1080, Surface.ROTATION_0,
            true, true, 1920, 1080, areas, musicWindowEnabled = true))
        set("controller", controller)
        place(1920, 1080)
        root.measure(exact(1920), exact(1080))
        root.layout(0, 0, 1920, 1080)

        for ((delta, share, width) in listOf(
            Triple(64f, 84, 576), Triple(48f, 87, 528), Triple(48f, 90, 480),
            Triple(48f, 93, 432), Triple(48f, 96, 384), Triple(64f, 100, 320), Triple(80f, 105, 240),
        )) {
            dragPanel(delta, MotionEvent.ACTION_UP)
            assertEquals(share, SidePanelSettings.share(activity, music = true))
            assertEquals(width, areas.panelRect(areas.current)!!.width)
            verify(controller).showViewArea(areas.sidePanel(share = share)!!)
        }
        dragPanel(119f, MotionEvent.ACTION_UP)
        assertEquals(true, get("sidePanelShown"))
        assertEquals(240, areas.panelRect(areas.current)!!.width)
        dragPanel(121f, MotionEvent.ACTION_UP)
        assertEquals(false, get("sidePanelShown"))
        assertEquals(CarPlayViewAreas.Kind.FULL_SCREEN, areas.kindOf(areas.current))
        assertEquals(105, SidePanelSettings.share(activity, music = true))

        // Pull out from the right edge and release at the new 1/6-width position.
        val tab = View(activity)
        root.addView(tab, FrameLayout.LayoutParams(64, 160))
        tab.layout(1856, 460, 1920, 620)
        set("sidePanelTab", tab)
        val touch = activity.javaClass.getDeclaredMethod("onSidePanelTabTouch", View::class.java, MotionEvent::class.java)
            .apply { isAccessible = true }
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 64f, 80f, 0)
        val move = MotionEvent.obtain(0, 1, MotionEvent.ACTION_MOVE, -256f, 80f, 0)
        val up = MotionEvent.obtain(0, 2, MotionEvent.ACTION_UP, -256f, 80f, 0)
        try {
            assertEquals(true, touch.invoke(activity, tab, down))
            assertEquals(true, touch.invoke(activity, tab, move))
            assertEquals(true, touch.invoke(activity, tab, up))
        } finally { down.recycle(); move.recycle(); up.recycle() }
        assertEquals(true, get("sidePanelShown"))
        assertEquals(320, areas.panelRect(areas.current)!!.width)
        assertEquals(100, SidePanelSettings.share(activity, music = true))
    }

    @Test fun cancelledOrRejectedResizeDoesNotSaveTheNarrowWidth() {
        val areas = CarPlayViewAreas.build(1920, 1080, CarPlayDock.AUTOMATIC, null,
            sidePanel = true, narrowMusicPanel = true)!!
        areas.use(areas.sidePanel()!!)
        val controller = mock(CarPlayController::class.java)
        video.layout(0, 0, 1920, 1080)
        set("sessionDisplay", CarPlaySessionDisplay(1920, 1080, Surface.ROTATION_0,
            true, true, 1920, 1080, areas, musicWindowEnabled = true))
        set("controller", controller)
        place(1920, 1080)
        root.measure(exact(1920), exact(1080))
        root.layout(0, 0, 1920, 1080)

        dragPanel(160f, MotionEvent.ACTION_CANCEL)
        assertEquals(80, SidePanelSettings.share(activity, music = true))
        assertEquals(640, areas.panelRect(areas.current)!!.width)
        verify(controller, never()).showViewArea(anyInt())
        dragPanel(160f, MotionEvent.ACTION_UP)
        assertEquals(80, SidePanelSettings.share(activity, music = true))
        assertEquals(640, areas.panelRect(areas.current)!!.width)
        verify(controller).showViewArea(areas.sidePanel(share = 90)!!)
    }

    @Test fun narrowMusicSizesDragOnTheLeftAndOnTheBottom() {
        for (portrait in listOf(false, true)) {
            val width = if (portrait) 1080 else 1920
            val height = if (portrait) 1920 else 1080
            val areas = CarPlayViewAreas.build(width, height, CarPlayDock.AUTOMATIC, null,
                sidePanel = true, rightHandDrive = true, narrowMusicPanel = true)!!
            areas.use(areas.sidePanel()!!)
            val controller = mock(CarPlayController::class.java)
            `when`(controller.showViewArea(anyInt())).thenReturn(true)
            video.layout(0, 0, width, height)
            SidePanelSettings.setShare(activity, 80)
            set("sessionDisplay", CarPlaySessionDisplay(width, height, Surface.ROTATION_0,
                true, true, width, height, areas, musicWindowEnabled = true))
            set("controller", controller)
            place(width, height)
            root.measure(exact(width), exact(height))
            root.layout(0, 0, width, height)

            if (portrait) dragPanel(160f, MotionEvent.ACTION_UP, vertical = true)
            else dragPanel(-160f, MotionEvent.ACTION_UP, startX = panel.width - 1f)
            assertEquals(90, SidePanelSettings.share(activity, music = true))
            val panelRect = areas.panelRect(areas.current)!!
            if (portrait) {
                assertEquals(480, panelRect.height)
                assertEquals(1440, panelRect.originY)
            } else {
                assertEquals(480, panelRect.width)
                assertEquals(0, panelRect.originX)
            }
        }
    }

    private fun dragPanel(delta: Float, endAction: Int, startX: Float = 1f, vertical: Boolean = false) {
        val method = activity.javaClass.getDeclaredMethod("onSidePanelTouch", View::class.java, MotionEvent::class.java)
            .apply { isAccessible = true }
        val startY = if (vertical) 1f else 100f
        val x = if (vertical) startX else startX + delta
        val y = if (vertical) startY + delta else startY
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, startX, startY, 0)
        val move = MotionEvent.obtain(0, 1, MotionEvent.ACTION_MOVE, x, y, 0)
        val end = MotionEvent.obtain(0, 2, endAction, x, y, 0)
        try {
            assertEquals(true, method.invoke(activity, panel, down))
            assertEquals(true, method.invoke(activity, panel, move))
            assertEquals(true, method.invoke(activity, panel, end))
        } finally {
            down.recycle(); move.recycle(); end.recycle()
        }
    }

    private fun place(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("placeSidePanel", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, width, height)
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun get(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any?) = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
}
