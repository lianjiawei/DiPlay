package com.shilapi.xcertplay

import android.content.Intent
import android.os.Handler
import android.view.View
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29, 33])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHomeNavigationTest {
    private val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
        for (field in listOf("teardownExecutor", "airPlayCommandExecutor"))
            ReflectionHelpers.getField<ExecutorService>(host, field).shutdownNow()
    }

    @Test fun returningToHomeOrSettingsReusesTheLauncherTaskAndPutsProjectionBehindTheLauncher() {
        val session = mock(CarPlayController::class.java)
        ReflectionHelpers.setField(host, "controller", session)
        for (page in listOf("settings", "home", "settings", "home")) {
            ReflectionHelpers.callInstanceMethod<Unit>(host, "showDiPlayHome",
                ReflectionHelpers.ClassParameter(String::class.java, page))
            val intent = requireNotNull(shadowOf(host).nextStartedActivity)
            assertEquals(DiPlayActivity::class.java.name, intent.component?.className)
            assertEquals(page, intent.getStringExtra("page"))
            assertEquals(page == "settings", intent.getBooleanExtra(DiPlayActivity.EXTRA_RETURN_TO_CARPLAY, false))
            // REORDER_TO_FRONT alone only searches the projection task and can create another
            // home above the host: Back then reveals the host, which opens home again forever.
            assertTrue("Home belongs in its own launcher task", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertTrue("Reuse the existing home", intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            assertTrue("Deliver the page to the existing home", intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            assertTrue("Leaving home must not reveal projection again", shadowOf(host).isTaskMovedToBack)
            assertFalse(host.isFinishing)
            assertSame(session, ReflectionHelpers.getField<CarPlayController>(host, "controller"))
        }
        verify(session, never()).close()
    }
    @Test fun waitingHomeBackExitsToCarLauncherAndKeepsTheConnectionAlive() {
        val root = ReflectionHelpers.callInstanceMethod<android.view.ViewGroup>(host, "buildContentView")
        val session = mock(CarPlayController::class.java)
        ReflectionHelpers.setField(host, "controller", session)
        ReflectionHelpers.callInstanceMethod<Unit>(host, "leaveConnectionHome")
        val intent = requireNotNull(shadowOf(host).nextStartedActivity)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_HOME))
        assertTrue(shadowOf(host).isTaskMovedToBack)
        verify(session, never()).close()
        val actions = descendants(root).filterIsInstance<GlassActionRow>().single()
        assertEquals(listOf(host.getString(com.shilapi.xcertplay.host.R.string.disconnect),
            host.getString(com.shilapi.xcertplay.host.R.string.settings)),
            (0 until actions.childCount).map { (actions.getChildAt(it) as android.widget.TextView).text.toString() })
        actions.getChildAt(1).performClick()
        assertEquals("settings", shadowOf(host).nextStartedActivity.getStringExtra("page"))
    }

    @Test fun settingsOpenedFromProjectionReturnDirectlyToProjection() {
        storeSession()
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val intent = Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")
            .putExtra(DiPlayActivity.EXTRA_RETURN_TO_CARPLAY, true)
        val c = Robolectric.buildActivity(DiPlayActivity::class.java, intent).setup()
        try {
            c.get().onBackPressedDispatcher.onBackPressed()
            assertEquals("home", ReflectionHelpers.getField<String>(c.get(), "page"))
            assertNull("Back never launches a second connection Activity", shadowOf(c.get()).nextStartedActivity)
            assertFalse(c.get().isFinishing)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun controlConnectionDoesNotEnableOpenUntilMainPictureIsReady() {
        storeSession()
        CarPlayBackgroundSession.active = true
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val c = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup()
        try {
            val activity = c.get()
            ReflectionHelpers.setField(activity, "setupError", null)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
            val button = ReflectionHelpers.getField<View>(activity, "homeConnectionRing")
            assertFalse(button.isEnabled)
            assertEquals("", ReflectionHelpers.getField<android.widget.TextView>(activity, "homeActionHint").text.toString())
            CarPlayBackgroundSession.mainPictureReady = true
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "refreshStatus")
            assertTrue(button.isEnabled)
            assertEquals(activity.getString(com.shilapi.xcertplay.host.R.string.home_ring_open_hint), ReflectionHelpers.getField<android.widget.TextView>(activity, "homeActionHint").text.toString())
            assertSame(button, ReflectionHelpers.getField<View>(activity, "homeConnectionRing"))
            CarPlayBackgroundSession.clear()
            assertFalse(CarPlayBackgroundSession.mainPictureReady)
        } finally { c.pause().stop().destroy() }
    }

    @Test fun homeBackGoesDirectlyToTheCarLauncherInsteadOfRevealingOtherTasks() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val c = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup()
        try {
            c.get().onBackPressedDispatcher.onBackPressed()
            val intent = requireNotNull(shadowOf(c.get()).nextStartedActivity)
            assertEquals(Intent.ACTION_MAIN, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_HOME))
            assertTrue(shadowOf(c.get()).isTaskMovedToBack)
        } finally { c.pause().stop().destroy() }
    }

    private fun storeSession() = CarPlayBackgroundSession.store(mock(CarPlayController::class.java),
        mock(com.shilapi.xcertplay.media.AndroidMediaSink::class.java), 1280, 720, host,
        CarPlaySessionDisplay(1280, 720, android.view.Surface.ROTATION_0, false, false, 1280, 720)) {}

    private fun descendants(view: android.view.View): Sequence<android.view.View> = sequence {
        yield(view)
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

}
