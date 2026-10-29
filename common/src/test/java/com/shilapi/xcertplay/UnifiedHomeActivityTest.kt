package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.LiveVideoCounters
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.shadows.ShadowAlertDialog
import java.time.Duration

/** UI and lifecycle tests simulate controller events, without supplying any CarPlay credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29, 33], qualifiers = "en-w900dp-h600dp-land", shadows = [VpnConsentTestShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class UnifiedHomeActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var c: org.robolectric.android.controller.ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private lateinit var home: View
    private lateinit var engine: FrameLayout
    private val teardown = PausedExecutorService()
    private val commands = PausedExecutorService()

    @Before fun setup() {
        CarPlayBackgroundSession.clear()
        DiPlayPreferences.saveAutoConnect(app, false)
        VpnConsentTestShadow.failure = null
        VpnConsentTestShadow.consent = Intent("test-vpn-consent")
        c = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "home")).setup().visible()
        activity = c.get()
        set("setupError", null)
        home = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        // The actual video/chrome root is exercised, while the media transport is mocked below.
        engine = call("buildContentView")
        set("hostInitialized", true)
        set("teardownExecutor", teardown)
        set("airPlayCommandExecutor", commands)
        (home.parent as ViewGroup).removeView(home)
        activity.setContentView(engine)
        call<Unit>("installUnifiedHomeContent", ClassParameter.from(View::class.java, home))
        assertSame(home, field<View>("unifiedHomeContent"))
    }

    @After fun cleanup() {
        CarPlayBackgroundSession.clear()
        set("unifiedConnectionRequested", false)
        set("controller", null)
        set("sink", null)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        c.pause().stop().destroy()
        teardown.shutdownNow(); commands.shutdownNow()
    }

    @Test fun idleConnectionAndRecoveryStatesUseExactlyTheSameHomeAndControls() {
        val title = field<View>("homeHeadline")
        val primary = field<View>("homeConnectionRing")
        assertTrue(descendants(engine).none { it is ConnectionPreparationLayout })
        repeat(3) {
            set("unifiedConnectionRequested", true)
            set("startupRetryStopped", false)
            call<Unit>("onProjectionStateChanged")
            assertFalse(primary.isEnabled)
            set("startupRetryStopped", true)
            call<Unit>("onProjectionStateChanged")
            assertEquals(activity.getString(R.string.home_ring_retry_hint), field<TextView>("homeActionHint").text.toString())
            assertTrue(primary.isEnabled)
            set("unifiedConnectionRequested", false)
            set("startupRetryStopped", false)
            call<Unit>("onProjectionStateChanged")
            assertSame(home, field<View>("unifiedHomeContent"))
            assertSame(primary, field<View>("homeConnectionRing"))
            assertSame(title, field<View>("homeHeadline"))
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test fun authenticationFailureNeverOffersAnUnavailableRetryAction() {
        set("setupError", "test authentication failure")
        set("unifiedConnectionRequested", true)
        set("startupRetryStopped", true)
        call<Unit>("onProjectionStateChanged")
        val ring = field<View>("homeConnectionRing")
        assertFalse(ring.isEnabled)
        assertEquals(activity.getString(R.string.home_connection_help_hint), field<TextView>("homeActionHint").text.toString())
        assertEquals(activity.getString(R.string.setup_needs_attention), ring.contentDescription.toString())
        var starts = 0
        activity.projectionStarter = { starts++; true }
        ring.performClick()
        assertEquals(0, starts)
        field<View>("homeActionHint").performClick()
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }

    @Test fun failedConnectionRetainsActionableReasonWithoutAnActiveSessionAndCancelClearsIt() {
        set("connectionFailureSummary", "Check the USB cable")
        set("connectionHelpDetail", "Use a data cable and unlock the iPhone")
        set("unifiedConnectionRequested", false)
        call<Unit>("onProjectionStateChanged")
        assertEquals(activity.getString(R.string.ui_glass_connection_incomplete), field<TextView>("homeHeadline").text)
        assertEquals("Check the USB cable", field<TextView>("status").text)
        field<View>("homeActionHint").performClick()
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
        ShadowAlertDialog.getLatestAlertDialog().dismiss()
        val completion: () -> Unit = {}
        call<Unit>("stopUnifiedProjection", ClassParameter.from(kotlin.jvm.functions.Function0::class.java, completion))
        assertNotEquals("Check the USB cable", field<TextView>("status").text)
    }

    @Test fun retryAndFailureHavePriorityOverOrdinaryStages() {
        set("unifiedConnectionRequested", true)
        set("connectionFailureSummary", "Check the cable")
        set("connectionHelpDetail", "Try another data cable")
        set("reconnectScheduled", true)
        call<Unit>("onProjectionStateChanged")
        assertEquals(activity.getString(R.string.home_reconnecting), field<TextView>("homeHeadline").text)
        assertEquals("Check the cable", field<TextView>("status").text)
        set("startupRetryStopped", true)
        call<Unit>("onProjectionStateChanged")
        assertEquals(activity.getString(R.string.ui_glass_connection_incomplete), field<TextView>("homeHeadline").text)
        assertEquals(activity.getString(R.string.home_retry_and_help_hint), field<TextView>("homeActionHint").text)
    }

    @Test fun fastStagesCoalesceButFailureIsImmediateAndCannotBeOverwrittenByTimer() {
        set("unifiedConnectionRequested", true)
        val stage = field<TextView>("stageStatusView")
        stage.text = "First stage"
        call<Unit>("onProjectionStateChanged")
        assertEquals("First stage", field<TextView>("status").text)
        stage.text = "Second stage"
        call<Unit>("onProjectionStateChanged")
        assertEquals("First stage", field<TextView>("status").text)
        set("connectionFailureSummary", "Permission denied")
        call<Unit>("onProjectionStateChanged")
        assertEquals("Permission denied", field<TextView>("status").text)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals("Permission denied", field<TextView>("status").text)
    }

    @Test fun latestOrdinaryStageAppearsAfterDelayAndPermissionIsImmediate() {
        set("unifiedConnectionRequested", true)
        val stage = field<TextView>("stageStatusView")
        stage.text = "First stage"
        call<Unit>("onProjectionStateChanged")
        assertFalse(field<View>("homeActionHint").isClickable)
        stage.text = "Second stage"
        call<Unit>("onProjectionStateChanged")
        stage.text = "Latest stage"
        call<Unit>("onProjectionStateChanged")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertEquals("Latest stage", field<TextView>("status").text)
        stage.text = activity.getString(R.string.ui_connection_stage_usb_permission)
        call<Unit>("onProjectionStateChanged")
        assertEquals(stage.text, field<TextView>("status").text)
    }

    @Test fun openingProjectionAndBackingOutOnlyChangeVisibilityOfTheSameHome() {
        store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java))
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.mainPictureReady = true
        call<Unit>("onProjectionStateChanged")
        val ring = field<View>("homeConnectionRing")
        assertTrue(ring.isEnabled)
        assertTrue(ring.isClickable && ring.isFocusable)
        assertEquals(android.widget.Button::class.java.name, ring.accessibilityClassName)
        assertEquals(activity.getString(R.string.home_ring_open_hint), field<TextView>("homeActionHint").text.toString())
        ring.performClick()
        assertEquals(View.GONE, home.visibility)
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, home.visibility)
        assertSame(home, field<View>("unifiedHomeContent"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
    }

    @Test fun relayingOutChromeKeepsProjectionAndItsOriginalVideoLayerVisible() {
        store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java))
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.mainPictureReady = true
        val video = field<View>("videoView")
        call<Boolean>("showUnifiedProjection")
        call<Unit>("render")
        assertTrue(field<Boolean>("unifiedProjectionVisible"))
        assertEquals(View.GONE, field<View>("unifiedHomeContent").visibility)
        assertSame(video, field<View>("videoView"))
        assertSame(engine, activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun videoLossRevealsTheSameHomeAndRecoveryReusesTheVideoLayer() {
        c.windowFocusChanged(true)
        store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java))
        val title = field<View>("homeHeadline")
        val video = field<View>("videoView")
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.mainPictureReady = true
        assertTrue(call<Boolean>("showUnifiedProjection"))
        CarPlayBackgroundSession.mainPictureReady = false
        call<Unit>("onProjectionStateChanged")
        assertFalse(field<Boolean>("unifiedProjectionVisible"))
        assertEquals(View.VISIBLE, home.visibility)
        assertSame(home, field<View>("unifiedHomeContent"))
        assertSame(title, field<View>("homeHeadline"))
        assertTrue(field<Boolean>("openProjectionWhenReady"))
        CarPlayBackgroundSession.mainPictureReady = true
        call<Unit>("onProjectionStateChanged")
        assertTrue(field<Boolean>("unifiedProjectionVisible"))
        assertEquals(View.GONE, home.visibility)
        assertSame(video, field<View>("videoView"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun terminalConnectionFailureCannotKeepAnOldFrameCoveringHome() {
        store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java))
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.mainPictureReady = true
        assertTrue(call<Boolean>("showUnifiedProjection"))
        set("startupRetryStopped", true)
        call<Unit>("onProjectionStateChanged")
        assertEquals(View.VISIBLE, home.visibility)
        assertFalse(field<Boolean>("unifiedProjectionVisible"))
        assertFalse(call<Boolean>("showUnifiedProjection"))
        assertEquals(activity.getString(R.string.ui_glass_connection_incomplete), field<TextView>("homeHeadline").text)
        assertTrue(field<View>("homeConnectionRing").isEnabled)
        assertSame(home, field<View>("unifiedHomeContent"))
    }

    @Test fun settingsOpenedFromProjectionReturnInsideTheSameActivity() {
        store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java))
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.mainPictureReady = true
        set("activeAirPlaySession", mock(AirPlaySession::class.java))
        field<MutableSet<Int>>("activeScreenStreamTypes").add(110)
        `when`(field<AndroidMediaSink>("sink").liveVideoCounters()).thenReturn(LiveVideoCounters(1, 1, 0, 0))
        call<Boolean>("showUnifiedProjection")
        c.windowFocusChanged(true)
        set("settingsCategory", SettingsCategory.AUDIO)
        set("settingsSearchQuery", "music")
        set("settingsSearchExpanded", true)
        call<Unit>("showDiPlayHome", ClassParameter.from(String::class.java, "settings"))
        assertEquals("settings", field<String>("page"))
        assertEquals(SettingsCategory.OVERVIEW, field<SettingsCategory>("settingsCategory"))
        assertEquals("", field<String>("settingsSearchQuery"))
        assertFalse(field<Boolean>("settingsSearchExpanded"))
        assertFalse(field<Boolean>("unifiedProjectionVisible"))
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals("home", field<String>("page"))
        assertTrue(field<Boolean>("unifiedProjectionVisible"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun onlyMainVideoReadinessCanOpenProjectionAndItNeverCreatesAnotherActivity() {
        val controller = mock(CarPlayController::class.java)
        val sink = mock(AndroidMediaSink::class.java)
        store(controller, sink)
        `when`(sink.liveVideoCounters()).thenReturn(LiveVideoCounters(1, 1, 0, 0))
        set("activeAirPlaySession", mock(AirPlaySession::class.java))
        CarPlayBackgroundSession.active = true
        set("openProjectionWhenReady", true)
        val streams = field<MutableSet<Int>>("activeScreenStreamTypes")
        streams.add(111)
        call<Unit>("updateDebugOverlays")
        assertFalse(CarPlayBackgroundSession.mainPictureReady)
        assertEquals(View.VISIBLE, home.visibility)
        streams.add(110)
        // Window focus is delivered explicitly in this off-device UI test.
        c.windowFocusChanged(true)
        call<Unit>("updateDebugOverlays")
        assertTrue(CarPlayBackgroundSession.mainPictureReady)
        assertTrue(field<Boolean>("unifiedProjectionVisible"))
        assertSame(home, field<View>("unifiedHomeContent"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun completingAConnectionCannotPullTheDriverOutOfSettings() {
        store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java))
        set("openProjectionWhenReady", true)
        set("page", "settings")
        call<Unit>("render")
        val settings = field<View>("unifiedHomeContent")
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.mainPictureReady = true
        c.windowFocusChanged(true)
        call<Unit>("onProjectionStateChanged")
        assertSame(settings, field<View>("unifiedHomeContent"))
        assertEquals(View.VISIBLE, settings.visibility)
        assertFalse(field<Boolean>("unifiedProjectionVisible"))
        assertEquals("settings", field<String>("page"))
    }

    @Test fun connectFromConnectionSetupShowsHomeProgressThenOpensCarPlay() {
        set("page", "connection")
        call<Unit>("render")
        activity.getSharedPreferences("diplay", Activity.MODE_PRIVATE).edit()
            .putBoolean("notification_asked", true).commit()
        var starts = 0
        activity.projectionStarter = {
            starts++
            set("unifiedConnectionRequested", true)
            true
        }

        call<Unit>("connect",
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            ClassParameter.from(Boolean::class.javaPrimitiveType, true))

        assertEquals(1, starts)
        assertEquals("home", field<String>("page"))
        assertTrue(field<Boolean>("openProjectionWhenReady"))
        assertEquals(activity.getString(R.string.ui_glass_connecting_title),
            field<TextView>("homeHeadline").text.toString())
        assertFalse(field<View>("homeConnectionRing").isEnabled)
        assertFalse(field<Boolean>("unifiedProjectionVisible"))

        store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java))
        CarPlayBackgroundSession.active = true
        CarPlayBackgroundSession.mainPictureReady = true
        c.windowFocusChanged(true)
        call<Unit>("onProjectionStateChanged")

        assertTrue(field<Boolean>("unifiedProjectionVisible"))
        assertEquals(View.GONE, field<View>("unifiedHomeContent").visibility)
        assertFalse(field<Boolean>("openProjectionWhenReady"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun failedManualConnectStaysOnSetupInsteadOfShowingFalseProgress() {
        set("page", "connection")
        set("setupError", "test authentication failure")
        call<Unit>("render")
        var starts = 0
        activity.projectionStarter = { starts++; true }

        call<Unit>("connect",
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            ClassParameter.from(Boolean::class.javaPrimitiveType, true))

        assertEquals(0, starts)
        assertEquals("connection", field<String>("page"))
        assertFalse(field<Boolean>("openProjectionWhenReady"))
    }

    @Test fun settingTriggeredReconnectKeepsTheSettingsPage() {
        set("page", "settings")
        call<Unit>("render")
        activity.getSharedPreferences("diplay", Activity.MODE_PRIVATE).edit()
            .putBoolean("notification_asked", true).commit()
        var starts = 0
        activity.projectionStarter = {
            starts++
            set("unifiedConnectionRequested", true)
            true
        }

        call<Unit>("connect",
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false))

        assertEquals(1, starts)
        assertEquals("settings", field<String>("page"))
        assertTrue(field<Boolean>("openProjectionWhenReady"))
        assertFalse(field<Boolean>("unifiedProjectionVisible"))
    }

    @Test fun repeatedDisconnectsKeepTheActivityHomeAndExecutorsReusable() {
        repeat(2) {
            val controller = mock(CarPlayController::class.java)
            store(controller, mock(AndroidMediaSink::class.java))
            set("unifiedConnectionRequested", true)
            call<Unit>("onProjectionStateChanged")
            field<View>("disconnectButton").performClick()
            teardown.runAll()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(CarPlayBackgroundSession.hasSession())
            assertFalse(field<java.util.concurrent.atomic.AtomicBoolean>("shuttingDown").get())
            assertFalse(teardown.isShutdown)
            assertFalse(commands.isShutdown)
            assertSame(home, field<View>("unifiedHomeContent"))
            assertFalse(activity.isFinishing)
            verify(controller).close()
        }
    }

    @Test fun refusedVpnConsentCanBeRetriedInPlaceAndLateConsentCannotRestartAfterCancel() {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        AirPlayPersistence.saveWirelessEnabled(activity, false)
        var requests = 0
        set("vpnConsent", object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) { requests++ }
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.StartActivityForResult()
        })
        set("unifiedConnectionRequested", true)
        call<Unit>("requestVpnConsent")
        call<Unit>("onVpnConsentResult", ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_CANCELED))
        val primary = field<View>("homeConnectionRing")
        assertEquals(activity.getString(R.string.home_ring_retry_hint), field<TextView>("homeActionHint").text.toString())
        assertTrue(primary.isEnabled)
        primary.performClick()
        assertEquals(2, requests)
        assertSame(primary, field<View>("homeConnectionRing"))
        field<View>("disconnectButton").performClick()
        call<Unit>("onVpnConsentResult", ClassParameter.from(Int::class.javaPrimitiveType, Activity.RESULT_OK))
        assertFalse(field<Boolean>("unifiedConnectionRequested"))
        assertNull(field<CarPlayController?>("controller"))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertSame(home, field<View>("unifiedHomeContent"))
    }

    @Test fun retryDoesNotAskForAnAlreadyDeclinedOptionalMicrophoneAgain() {
        shadowOf(app).denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        AirPlayPersistence.saveWirelessEnabled(activity, false)
        set("microphonePermissionResolved", true)
        var vpnRequests = 0
        set("vpnConsent", object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) { vpnRequests++ }
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.StartActivityForResult()
        })
        call<Boolean>("requestUnifiedProjection")
        assertFalse(field<Boolean>("awaitingMicrophonePermission"))
        assertEquals(1, vpnRequests)
        assertSame(home, field<View>("unifiedHomeContent"))
    }

    @Test fun aNewConnectionRequestedDuringTeardownStartsAfterTheOldOneCloses() {
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        AirPlayPersistence.saveWirelessEnabled(activity, false)
        var permissionRequests = 0
        set("vpnConsent", object : ActivityResultLauncher<Intent>() {
            override fun launch(input: Intent, options: ActivityOptionsCompat?) { permissionRequests++ }
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.StartActivityForResult()
        })
        store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java))
        set("unifiedConnectionRequested", true)
        call<Unit>("onProjectionStateChanged")
        field<View>("disconnectButton").performClick()
        assertTrue(field<java.util.concurrent.atomic.AtomicBoolean>("shuttingDown").get())
        assertTrue(call<Boolean>("requestUnifiedProjection"))
        assertTrue(field<Boolean>("restartUnifiedAfterStop"))
        teardown.runAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(field<Boolean>("restartUnifiedAfterStop"))
        assertTrue(field<Boolean>("unifiedConnectionRequested"))
        assertEquals(1, permissionRequests)
        assertSame(home, field<View>("unifiedHomeContent"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun homeAndSettingsKeysAreNotForwardedToTheIphone() {
        val controller = mock(CarPlayController::class.java)
        store(controller, mock(AndroidMediaSink::class.java))
        set("activeAirPlaySession", mock(AirPlaySession::class.java))
        clearInvocations(controller)
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            activity.dispatchKeyEvent(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        }
        verifyNoInteractions(controller)
    }

    @Test fun permissionPendingAndProjectionStateAreSavedForActivityRecreation() {
        set("unifiedConnectionRequested", true)
        set("awaitingVpnConsent", true)
        set("awaitingMicrophonePermission", true)
        val state = android.os.Bundle()
        c.saveInstanceState(state)
        assertTrue(state.getBoolean("unified_connection_requested"))
        assertTrue(state.getBoolean("host_awaiting_vpn"))
        assertTrue(state.getBoolean("host_awaiting_microphone"))
    }

    @Test fun projectionPendingIntentsCannotBeOverwrittenByPlainHomeWidgetIntents() {
        val projection = CarPlayEntry.intent(app)
        val plainHome = Intent(app, DiPlayActivity::class.java)
        assertEquals(DiPlayActivity::class.java.name, projection.component?.className)
        assertTrue(projection.getBooleanExtra(EXTRA_OPEN_PROJECTION, false))
        assertFalse(projection.filterEquals(plainHome))
    }

    @Test fun oldHostEntryForwardsToTheSingleLauncherAndDoesNotOwnAnotherConnection() {
        val listener: () -> Unit = {}
        AirPlayPersistence.overlaySettingsListener = listener
        val old = Robolectric.buildActivity(CarPlayHostActivity::class.java,
            Intent(app, CarPlayHostActivity::class.java).putExtra("picture_controls", true)).create()
        val target = requireNotNull(shadowOf(old.get()).nextStartedActivity)
        assertEquals(DiPlayActivity::class.java.name, target.component?.className)
        assertTrue(target.getBooleanExtra(EXTRA_OPEN_PROJECTION, false))
        assertTrue(target.getBooleanExtra("picture_controls", false))
        assertTrue(old.get().isFinishing)
        old.destroy()
        assertSame("A forwarding Activity must not clear the live session callbacks", listener, AirPlayPersistence.overlaySettingsListener)
    }

    private fun store(controller: CarPlayController, sink: AndroidMediaSink) {
        set("controller", controller); set("sink", sink)
        CarPlayBackgroundSession.store(controller, sink, 900, 600, activity,
            CarPlaySessionDisplay(900, 600, Surface.ROTATION_0, false, false, 900, 600)) { complete ->
            call<Unit>("shutdown", ClassParameter.from(Boolean::class.javaPrimitiveType, false),
                ClassParameter.from(String::class.java, "test disconnect"),
                ClassParameter.from(Function0::class.java, complete))
        }
    }
    private fun set(name: String, value: Any?) = ReflectionHelpers.setField(activity, name, value)
    private fun <T> field(name: String): T = ReflectionHelpers.getField(activity, name)
    private fun <T> call(name: String, vararg parameters: ClassParameter<*>): T = ReflectionHelpers.callInstanceMethod(activity, name, *parameters)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
