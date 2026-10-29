package com.shilapi.xcertplay

import android.os.Handler
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** The stage line must not fall back to "Getting CarPlay ready" just because the UI is not English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayStageTextTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "stageStatusView", TextView(activity))
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test @Config(qualifiers = "en") fun englishStages() = stagesFollowTheConnection()

    @Test @Config(qualifiers = "zh-rCN") fun concreteFailureIsShownUntilAConfirmedRecovery() {
        ReflectionHelpers.setField(activity, "wirelessEnabled", true)
        val reporter = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(activity, "createStatusReporter",
            ClassParameter.from(Int::class.javaPrimitiveType, ReflectionHelpers.getField<Int>(activity, "restartGeneration")))
        reporter(CarPlayStatus.Failed("Could not establish WIFI_P2P hotspot: Turn on Wi-Fi in the head unit's settings, then reconnect"))
        assertEquals(activity.getString(R.string.ui_connection_wifi_off), ReflectionHelpers.getField<String>(activity, "connectionFailureSummary"))
        reporter(CarPlayStatus.StartingHotspot)
        assertEquals(activity.getString(R.string.ui_connection_wifi_off), ReflectionHelpers.getField<String>(activity, "connectionFailureSummary"))
        reporter(CarPlayStatus.HotspotReady("", "", 0, "", "", ""))
        assertEquals(null, ReflectionHelpers.getField<String?>(activity, "connectionFailureSummary"))
    }

    @Test @Config(qualifiers = "zh-rCN") fun chineseStages() {
        assertNotEquals("Connection interrupted. Retrying…", activity.getString(R.string.connection_interrupted_retrying))
        stagesFollowTheConnection()
    }

    @Test fun conciseStagesKeepAccessibleDetailsAndDirectRecoveryActions() {
        ReflectionHelpers.callInstanceMethod<android.view.ViewGroup>(activity, "buildContentView")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setConnectionStage",
            ClassParameter.from(String::class.java, "CarPlay configuration request failed"),
            ClassParameter.from(String::class.java, "CarPlay configuration request failed"))
        val stage = ReflectionHelpers.getField<TextView>(activity, "stageStatusView")
        assertEquals(activity.getString(R.string.ui_connection_hint_usb), stage.text.toString())
        assertEquals(activity.getString(R.string.iphone_usb_mode_rejected), stage.contentDescription)
        val back = ReflectionHelpers.getField<android.widget.Button>(activity, "connectionBackButton")
        assertEquals(activity.getString(R.string.disconnect), back.text.toString())
        var retries = 0
        var resets = 0
        val retry = ReflectionHelpers.getField<android.widget.Button>(activity, "startupRetryButton")
        val reset = ReflectionHelpers.getField<android.widget.Button>(activity, "wifiRecoveryButton")
        retry.setOnClickListener { retries++ }
        reset.setOnClickListener { resets++ }
        ReflectionHelpers.setField(activity, "startupRetryStopped", true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateDebugOverlays")
        assertEquals(android.view.View.VISIBLE, retry.visibility)
        assertTrue(retry.parent is android.view.ViewGroup)
        retry.performClick()
        reset.visibility = android.view.View.VISIBLE
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "updateDebugOverlays")
        assertEquals(android.view.View.GONE, retry.visibility)
        assertTrue(reset.parent is android.view.ViewGroup)
        reset.performClick()
        assertEquals(1, retries)
        assertEquals(1, resets)
    }

    private fun stagesFollowTheConnection() {
        // A wired Android 7.1 failure (#557) is a retry, not a stage that never ends.
        assertStage(R.string.connection_interrupted_retrying, CarPlayStatus.Failed("Invalid argument"))
        assertStage(R.string.ui_connection_hint_usb,
            CarPlayStatus.Failed("CarPlay configuration request transferred -1 of 1 bytes"))
        assertStage(R.string.connect_your_iphone_with_a_usb_cable, CarPlayStatus.WaitingForIphone)
        assertStage(R.string.ui_connection_stage_usb_permission, CarPlayStatus.RequestingIphonePermission)
        assertStage(R.string.looking_for_your_paired_iphone, CarPlayStatus.WaitingForPairedIphone)
        assertStage(R.string.getting_carplay_ready, CarPlayStatus.ConnectingBluetooth)
        assertStage(R.string.ui_connection_waiting_network, CarPlayStatus.RunningWireless)
        assertStage(R.string.opening_carplay, CarPlayStatus.RunningControl)
        assertStage(R.string.reconnecting_to_your_iphone, CarPlayStatus.ControlEnded)
        assertStage(R.string.waiting_for_mfi_coprocessor, CarPlayStatus.WaitingForMfi)
        assertStage(R.string.ui_connection_stage_usb_data, CarPlayStatus.OpeningDataPaths)
        assertStage(R.string.ui_connection_stage_usb_transition, CarPlayStatus.WaitingForReenumeration)
        assertStage(R.string.ui_connection_stage_pairing, CarPlayStatus.Pairing)
        assertStage(R.string.ui_connection_network_timeout, CarPlayStatus.Failed(
            "No AirPlay TCP after CarPlay StartSession", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT))
    }

    private fun assertStage(expected: Int, status: CarPlayStatus) {
        val reporter = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(
            activity, "createStatusReporter",
            ClassParameter.from(Int::class.javaPrimitiveType, ReflectionHelpers.getField<Int>(activity, "restartGeneration")),
        )
        reporter(status)
        val shown = ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()
        assertEquals(status.toString(), activity.getString(expected), shown)
    }
}
