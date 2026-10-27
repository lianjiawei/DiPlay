package com.shilapi.xcertplay.lincoln

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.shilapi.xcertplay.glance.CarPlayGlance
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], qualifiers = "zh-rCN")
class LincolnZeroDistanceGuardTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var now = 1_000_000_000L
    private var revision = 0L
    private val hud = RecordingLincolnHudSocket()

    @Before fun setUp() {
        LincolnClusterOutput.detach()
        CarPlayGlance.setConnected(false)
        LincolnClusterSettings.setClusterPackage(app, LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE)
        LincolnClusterSettings.setNavigation(app, true)
        LincolnClusterSettings.setEnhancedNavigation(app, false)
        LincolnClusterSettings.setMusic(app, false)
        LincolnClusterSettings.setRemaining(app, false)
        LincolnClusterOutput.nanoTime = { now }
        LincolnClusterOutput.hudSocket = hud
        LincolnClusterOutput.attach(app)
        shadowOf(app).clearBroadcastIntents()
    }

    @After fun tearDown() {
        LincolnClusterOutput.detach()
        LincolnClusterOutput.nanoTime = System::nanoTime
        LincolnClusterOutput.hudSocket = LincolnHudSocketServer
        LincolnClusterSettings.setNavigation(app, false)
        LincolnClusterSettings.setEnhancedNavigation(app, false)
        LincolnClusterSettings.setRemaining(app, false)
        LincolnClusterSettings.setClusterPackage(app, LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE)
        CarPlayGlance.setConnected(false)
        shadowOf(app).clearBroadcastIntents()
    }

    @Test fun transientZeroHoldsTheLastPacketButKeepsItsOneSecondRefresh() {
        val route = guidance(900)
        send(route)
        send(route.copy(distanceMeters = 800))
        val original = identity(infos().last())
        val zero = route.copy(distanceMeters = 0)
        send(zero)
        assertEquals(800, distance())
        val count = infos().size
        now += 1_000_000_000L
        send(zero, fresh = false)
        assertEquals(count + 1, infos().size)
        assertEquals(800, distance())
        assertEquals(original, identity(infos().last()))
        send(route.copy(distanceMeters = 780))
        assertEquals(780, distance())
        assertEquals(original, identity(infos().last()))
    }

    @Test fun onlyDistinctTimeSeparatedZeroFramesConfirmAndATickCannotConfirm() {
        val route = guidance(800)
        send(route)
        val zero = route.copy(distanceMeters = 0)
        send(zero)
        now += 100_000_000L
        send(zero)
        now += 500_000_000L
        send(zero, fresh = false)
        assertEquals(800, distance())
        send(zero)
        assertEquals(0, distance())
    }

    @Test fun candidateHasABoundedTimeoutEvenWithoutNewFrames() {
        val route = guidance(800)
        send(route)
        val zero = route.copy(distanceMeters = 0)
        send(zero)
        now += 1_999_000_000L
        send(zero, fresh = false)
        assertEquals(800, distance())
        now += 1_000_000L
        send(zero, fresh = false)
        assertEquals(0, distance())
    }

    @Test fun aTransientEmptySnapshotCannotRestartTheCandidateDeadline() {
        val route = guidance(800)
        send(route)
        val zero = route.copy(distanceMeters = 0)
        send(zero)
        now += 1_000_000_000L
        send(zero.copy(maneuverType = null, maneuverIndex = -1, selectedManeuverIndex = 7), fresh = false)
        assertEquals(800, distance())
        now += 1_000_000_000L
        send(zero, fresh = false)
        assertEquals("empty snapshot must not give the same zero another two seconds", 0, distance())
    }

    @Test fun aRoadNameUpdateDoesNotLetAnUnconfirmedZeroLeak() {
        val route = guidance(800)
        send(route)
        send(route.copy(distanceMeters = 0))
        send(route.copy(distanceMeters = 0, road = "Updated road"), fresh = false)
        assertEquals(800, distance())
        send(route.copy(distanceMeters = 780, road = "Updated road"))
        assertEquals(780, distance())
        assertEquals("Updated road", infos().last().getStringExtra("nextRoadName"))
    }

    @Test fun nearTurnAndFirstGuidanceStillSendZeroImmediately() {
        val route = guidance(49)
        send(route)
        send(route.copy(distanceMeters = 0))
        assertEquals(0, distance())
        LincolnClusterOutput.detach()
        LincolnClusterOutput.attach(app)
        shadowOf(app).clearBroadcastIntents()
        send(guidance(0))
        assertEquals(0, distance())
    }

    @Test fun zeroAtFiftyMetersIsHeldUntilAnotherFreshFrameConfirmsIt() {
        val route = guidance(50)
        send(route)
        val zero = route.copy(distanceMeters = 0)
        send(zero)
        assertEquals(50, distance())
        now += 500_000_000L
        send(zero)
        assertEquals(0, distance())
    }

    @Test fun newManeuverEndAndRerouteCannotBeBlockedByCandidate() {
        val route = guidance(800)
        send(route)
        send(route.copy(distanceMeters = 0))
        send(route.copy(maneuverIndex = 8, distanceMeters = 150, road = "Next"))
        assertEquals(150, distance())
        assertEquals("Next", infos().last().getStringExtra("nextRoadName"))

        val next = route.copy(maneuverIndex = 8, distanceMeters = 150, road = "Next")
        send(next.copy(distanceMeters = 0))
        send(next.copy(routeEnded = true))
        now += 3_000_000_000L
        send(next.copy(routeEnded = true), fresh = false)
        assertEquals(LincolnClusterProtocol.STATE_OFF, states().last())

        send(route)
        send(route.copy(distanceMeters = 0))
        send(route.copy(routeProgressPending = true, routeProgressGeneration = 1))
        send(route.copy(distanceMeters = 700, routeProgressGeneration = 1))
        assertEquals(700, distance())
    }

    @Test fun customReceiverRetainsImmediateZeroAndRemainingUpdatesStillUseTheirOwnChannel() {
        LincolnClusterSettings.setClusterPackage(app, "custom.cluster")
        val route = guidance(800)
        send(route)
        send(route.copy(distanceMeters = 0))
        assertEquals(0, distance())

        LincolnClusterOutput.detach()
        LincolnClusterSettings.setClusterPackage(app, LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE)
        LincolnClusterSettings.setRemaining(app, true)
        LincolnClusterOutput.attach(app)
        shadowOf(app).clearBroadcastIntents()
        send(route.copy(remainingMeters = 3000, remainingSeconds = 600))
        val infoCount = infos().size
        now += 100_000_000L
        send(route.copy(remainingMeters = 2999, remainingSeconds = 599))
        assertEquals(infoCount, infos().size)
        val remains = shadowOf(app).broadcastIntents.filter { it.action == LincolnClusterProtocol.ACTION_NAV_REMAIN }
        assertEquals(2999, remains.last().getIntExtra(LincolnClusterProtocol.EXTRA_REMAIN_DISTANCE, -1))
        now += 900_000_000L
        send(route.copy(remainingMeters = 2999, remainingSeconds = 599), fresh = false)
        assertEquals(infoCount + 1, infos().size)
    }

    @Test fun compatibilityOutputHoldsTheSameDistanceAndIdentityAsThePrimaryOutput() {
        LincolnClusterSettings.setEnhancedNavigation(app, true)
        val route = guidance(800)
        send(route)
        val original = identity(infos().last())
        send(route.copy(distanceMeters = 0))
        now += 1_000_000_000L
        send(route.copy(distanceMeters = 0), fresh = false)
        assertTrue(hud.turns.isNotEmpty())
        assertTrue(hud.turns.all { it.metres == 800 })
        assertEquals(original.first, hud.turns.last().latitude)
        assertEquals(original.second, hud.turns.last().longitude)
        send(route.copy(distanceMeters = 780))
        assertEquals(780, distance())
        assertEquals(780, hud.turns.last().metres)
        assertEquals(original, identity(infos().last()))
    }

    @Test fun anAuxiliaryTurnChangeIsNotHiddenByAZeroCandidate() {
        LincolnClusterSettings.setEnhancedNavigation(app, true)
        val entering = guidance(800).copy(maneuverType = 6)
        send(entering)
        assertEquals(9, hud.turns.last().index)
        val original = identity(infos().last())
        now += 100_000_000L
        // Both map to the same primary "turn_ring", but the HUD distinguishes enter and exit.
        send(entering.copy(maneuverType = 7, distanceMeters = 0))
        assertEquals("the new HUD direction must not wait for zero confirmation", 10, hud.turns.last().index)
        assertEquals("the unconfirmed zero still must not be sent", 800, hud.turns.last().metres)
        assertEquals(original.first, hud.turns.last().latitude)
        assertEquals(original.second, hud.turns.last().longitude)
        assertEquals(800, distance())
    }

    @Test fun enablingCompatibilityDuringAZeroCandidateStartsWithTheHeldInstruction() {
        val route = guidance(800)
        send(route)
        val original = identity(infos().last())
        send(route.copy(distanceMeters = 0))
        LincolnClusterSettings.setEnhancedNavigation(app, true)
        now += 100_000_000L
        send(route.copy(distanceMeters = 0), fresh = false)
        assertEquals(800, hud.turns.last().metres)
        assertEquals(original.first, hud.turns.last().latitude)
        assertEquals(original.second, hud.turns.last().longitude)
        assertEquals(800, distance())
    }

    @Test fun failedInfoDoesNotPermitAStalePacketToBecomeTheZeroHoldBaseline() {
        var fail = false
        LincolnClusterOutput.detach()
        LincolnClusterOutput.attach(object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun sendBroadcast(intent: Intent) {
                if (fail && intent.action == LincolnClusterProtocol.ACTION_NAV_INFO) {
                    throw SecurityException("Injected rejection")
                }
                super.sendBroadcast(intent)
            }
        })
        val route = guidance(800)
        send(route)
        fail = true
        send(route.copy(distanceMeters = 700))
        fail = false
        send(route.copy(distanceMeters = 0))
        assertEquals(0, distance())
    }

    private fun guidance(distance: Int) = CarPlayGlance.Snapshot(
        connected = true, routeActive = true, maneuverType = 2, maneuverIndex = 7,
        selectedManeuverIndex = 7, distanceMeters = distance, road = "Road",
    )

    private fun send(snapshot: CarPlayGlance.Snapshot, fresh: Boolean = true) {
        if (fresh) revision++
        LincolnClusterOutput.javaClass.getDeclaredMethod("onSnapshot", CarPlayGlance.Snapshot::class.java)
            .apply { isAccessible = true }.invoke(LincolnClusterOutput, snapshot.copy(distanceRevision = revision))
    }

    private fun infos() = shadowOf(app).broadcastIntents.filter { it.action == LincolnClusterProtocol.ACTION_NAV_INFO }
    private fun distance() = infos().last().getIntExtra(LincolnClusterProtocol.EXTRA_DISTANCE, -1)
    private fun states() = shadowOf(app).broadcastIntents.filter { it.action == LincolnClusterProtocol.ACTION_NAV_STATE }
        .map { it.getStringExtra(LincolnClusterProtocol.EXTRA_STATE) }
    private fun identity(intent: Intent) = intent.getDoubleExtra("latitude", 0.0) to intent.getDoubleExtra("longitude", 0.0)
}
