package com.shilapi.xcertplay.lincoln

import android.app.Application
import com.shilapi.xcertplay.glance.CarPlayGlance
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LincolnClusterLifecycleTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var now = 1_000_000_000L
    private val hud = RecordingLincolnHudSocket()
    @Before fun setUp() {
        LincolnClusterOutput.detach()
        LincolnClusterOutput.hudSocket = hud
        LincolnClusterSettings.setNavigation(app, true)
        LincolnClusterSettings.setMusic(app, false)
        LincolnClusterSettings.setRemaining(app, true)
        LincolnClusterOutput.nanoTime = { now }
        CarPlayGlance.setConnected(false)
        LincolnClusterOutput.attach(app)
        // Tests call the same callback as the ticker, with a deterministic clock/snapshot.
        LincolnClusterOutput.javaClass.getDeclaredMethod("stopTicking").apply { isAccessible = true }
            .invoke(LincolnClusterOutput)
        shadowOf(app).clearBroadcastIntents()
    }
    @After fun tearDown() {
        LincolnClusterOutput.detach()
        LincolnClusterOutput.hudSocket = LincolnHudSocketServer
        LincolnClusterSettings.setNavigation(app, false)
        LincolnClusterSettings.setRemaining(app, false)
        CarPlayGlance.setConnected(false)
        shadowOf(app).clearBroadcastIntents()
    }
    private fun send(snapshot: CarPlayGlance.Snapshot) {
        LincolnClusterOutput.javaClass.getDeclaredMethod("onSnapshot", CarPlayGlance.Snapshot::class.java)
            .apply { isAccessible = true }.invoke(LincolnClusterOutput, snapshot)
    }
    private fun guidance() = CarPlayGlance.Snapshot(connected = true, maneuverType = 11,
        routeActive = true, distanceMeters = 300, road = "Test", remainingMeters = 12_000,
        remainingSeconds = 900)
    private fun remains() = shadowOf(app).broadcastIntents
        .filter { it.action == LincolnClusterProtocol.ACTION_NAV_REMAIN }
    @Test fun endingRouteMustClearRemainingDistanceAndTimeAfterSettle() {
        send(guidance())
        assertEquals(12_000, remains().last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
        val ended = CarPlayGlance.Snapshot(connected = true, routeEnded = true)
        send(ended)
        now += 3_000_000_000L
        send(ended)
        assertTrue(shadowOf(app).broadcastIntents.any {
            it.action == LincolnClusterProtocol.ACTION_NAV_STATE &&
                it.getStringExtra(LincolnClusterProtocol.EXTRA_STATE) == LincolnClusterProtocol.STATE_OFF
        })
        assertEquals("Finished route left stale remaining distance", 0,
            remains().last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
    }
    @Test fun switchingRemainingOffMustClearAnUnchangedSnapshot() {
        val current = guidance()
        send(current)
        LincolnClusterSettings.setRemaining(app, false)
        now += 1_000_000_000L
        send(current)
        assertEquals("Remaining switch did not clear displayed distance", 0,
            remains().last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
    }
    @Test fun endingRouteWithChangedSnapshotMustAlsoClearRemaining() {
        send(guidance())
        send(CarPlayGlance.Snapshot(connected = true, routeEnded = true))
        now += 3_000_000_000L
        // Bypass the unchanged-snapshot shortcut: clearing navigation resets the end clock.
        send(CarPlayGlance.Snapshot(connected = true, routeEnded = true, songTitle = "changed"))
        assertEquals("Nav clearing reset the end clock before remaining was cleared", 0,
            remains().last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
    }

    @Test fun aBriefRerouteKeepsRemainingAndANewRouteCanStartAfterASettledEnd() {
        send(guidance())
        send(CarPlayGlance.Snapshot(connected = true, routeEnded = true))
        now += 1_000_000_000L
        send(guidance())
        assertEquals(1, remains().size)
        assertEquals(12_000, remains().last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
        send(CarPlayGlance.Snapshot(connected = true, routeEnded = true))
        now += 3_000_000_000L
        send(CarPlayGlance.Snapshot(connected = true, routeEnded = true))
        assertEquals(0, remains().last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
        send(guidance().copy(remainingMeters = 2_000))
        assertEquals(2_000, remains().last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
    }

    @Test fun remainingCanBeEnabledWithoutANewMetadataFrame() {
        LincolnClusterSettings.setRemaining(app, false)
        val current = guidance()
        send(current)
        assertTrue(remains().isEmpty())
        LincolnClusterSettings.setRemaining(app, true)
        send(current)
        assertEquals(12_000, remains().last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
    }

    @Test fun settledEndCannotRedisplayStaleGuidanceOrRemainingValues() {
        send(guidance())
        val endedWithStaleValues = guidance().copy(routeEnded = true)
        send(endedWithStaleValues)
        now += 3_000_000_000L
        send(endedWithStaleValues)
        assertEquals(0, remains().last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
        send(endedWithStaleValues.copy(songTitle = "next line"))
        val states = shadowOf(app).broadcastIntents.filter { it.action == LincolnClusterProtocol.ACTION_NAV_STATE }
        assertEquals(LincolnClusterProtocol.STATE_OFF, states.last().getStringExtra(LincolnClusterProtocol.EXTRA_STATE))
    }
}
