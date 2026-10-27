package com.shilapi.xcertplay.lincoln

import android.app.Application
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Synthetic v1.2.0 characterization and regression coverage of a stale destination handoff.
 * Inputs do not establish what the reporting iPhone actually transmitted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], qualifiers = "zh-rCN")
class LincolnPrematureDestinationInvestigationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var now = 1_000_000_000L
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
        CarPlayGlance.setConnected(true)
        shadowOf(app).clearBroadcastIntents()
    }

    @After fun tearDown() {
        LincolnClusterOutput.detach()
        CarPlayGlance.setConnected(false)
        LincolnClusterSettings.setNavigation(app, false)
        LincolnClusterSettings.setEnhancedNavigation(app, false)
        LincolnClusterSettings.setClusterPackage(app, LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE)
        LincolnClusterOutput.hudSocket = LincolnHudSocketServer
        LincolnClusterOutput.nanoTime = System::nanoTime
        shadowOf(app).clearBroadcastIntents()
    }

    @Test fun baselineCanEmitDestinationDuringActiveRoute() {
        // Characterization of v1.2.0, not proof of the real car incident or desired final behavior.
        maneuver(7, 24, "Destination")
        update { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400); u32(0x07, 5_000) }
        assertTrue(CarPlayGlance.snapshot().routeActive)
        assertEquals("turn_dest", infos().last().getStringExtra("name"))
        assertEquals(400, infos().last().getIntExtra("distance", -1))
    }

    @Test fun baselineReusesDistanceWhenIndexChangesWithoutADistanceFrame() {
        // Record the existing behavior so a future repair must deliberately evaluate this change.
        maneuver(7, 1, "First")
        update { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
        maneuver(8, 2, "Second")
        update { u16List(0x0d, listOf(8)) }
        assertEquals("turn_right", infos().last().getStringExtra("name"))
        assertEquals(400, infos().last().getIntExtra("distance", -1))
    }

    @Test fun changedIndexWithoutDetailsMustNotKeepRepeatingPreviousDestination() {
        for (enhanced in listOf(false, true)) {
            LincolnClusterOutput.detach()
            CarPlayGlance.setConnected(false)
            LincolnClusterSettings.setEnhancedNavigation(app, enhanced)
            LincolnClusterOutput.attach(app)
            CarPlayGlance.setConnected(true)
            shadowOf(app).clearBroadcastIntents()
            maneuver(7, 24, "Destination")
            update { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
            assertEquals("turn_dest", infos().last().getStringExtra("name"))
            shadowOf(app).clearBroadcastIntents()

            update { u16List(0x0d, listOf(8)) }
            assertEquals(LincolnClusterProtocol.STATE_OFF, states().last())
            assertTrue(infos().isEmpty())

            // New details can arrive without a new distance. Preserve the 1.2.0 behavior:
            // show the new instruction immediately with the last available distance.
            maneuver(8, 2, "Next")
            assertEquals("turn_right", infos().last().getStringExtra("name"))
            assertEquals(400, infos().last().getIntExtra("distance", -1))
        }
    }

    @Test fun emptyListDoesNotInvalidateDestinationAndNormalTurnStillHolds() {
        maneuver(7, 24, "Destination")
        update { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 100) }
        shadowOf(app).clearBroadcastIntents()
        update { u16List(0x0d, emptyList()) }
        assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))

        maneuver(8, 2, "Next")
        update { u16List(0x0d, listOf(8)); u32(0x0a, 90) }
        assertEquals("turn_right", infos().last().getStringExtra("name"))
        shadowOf(app).clearBroadcastIntents()
        update { u16List(0x0d, listOf(9)) }
        assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))
    }

    @Test fun anAmbiguousClearFailureReannouncesBeforeTheNewManeuver() {
        var failOff = false
        LincolnClusterOutput.detach()
        LincolnClusterOutput.attach(object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun sendBroadcast(intent: Intent) {
                super.sendBroadcast(intent)
                if (failOff && intent.action == LincolnClusterProtocol.ACTION_NAV_STATE &&
                    intent.getStringExtra(LincolnClusterProtocol.EXTRA_STATE) == LincolnClusterProtocol.STATE_OFF
                ) throw SecurityException("OFF may already have reached the receiver")
            }
        })
        maneuver(7, 24, "Destination")
        update { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 100) }
        failOff = true
        update { u16List(0x0d, listOf(8)) }
        failOff = false
        shadowOf(app).clearBroadcastIntents()
        maneuver(8, 2, "Next")
        val events = shadowOf(app).broadcastIntents.filter {
            it.action == LincolnClusterProtocol.ACTION_NAV_STATE || it.action == LincolnClusterProtocol.ACTION_NAV_INFO
        }
        assertEquals(LincolnClusterProtocol.STATE_ON,
            events.first().getStringExtra(LincolnClusterProtocol.EXTRA_STATE))
        assertEquals("turn_right", events.last().getStringExtra("name"))
    }

    @Test fun failedClearDoesNotRepeatAStaleDestinationWhileWaiting() {
        var failOff = false
        LincolnClusterOutput.detach()
        LincolnClusterOutput.attach(object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun sendBroadcast(intent: Intent) {
                if (failOff && intent.action == LincolnClusterProtocol.ACTION_NAV_STATE &&
                    intent.getStringExtra(LincolnClusterProtocol.EXTRA_STATE) == LincolnClusterProtocol.STATE_OFF
                ) throw SecurityException("Injected rejection")
                super.sendBroadcast(intent)
            }
        })
        maneuver(7, 24, "Destination")
        update { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 100) }
        failOff = true
        update { u16List(0x0d, listOf(8)) }
        shadowOf(app).clearBroadcastIntents()
        now += 3_000_000_000L
        val callback = LincolnClusterOutput.javaClass.getDeclaredMethod("onPublishedSnapshot", CarPlayGlance.Snapshot::class.java)
            .apply { isAccessible = true }
        callback.invoke(LincolnClusterOutput, CarPlayGlance.snapshot())
        assertTrue("a failed clear must not restart the stale terminal", infos().isEmpty())
        failOff = false
        callback.invoke(LincolnClusterOutput, CarPlayGlance.snapshot())
        assertEquals(LincolnClusterProtocol.STATE_OFF, states().last())
        maneuver(8, 2, "Next")
        assertEquals("turn_right", infos().last().getStringExtra("name"))
    }

    @Test fun aKnownNewDestinationAndAnActualEndAreNotSuppressed() {
        maneuver(7, 24, "First")
        update { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 100) }
        maneuver(8, 24, "Destination")
        shadowOf(app).clearBroadcastIntents()
        update { u16List(0x0d, listOf(8)); u32(0x0a, 50) }
        assertEquals("turn_dest", infos().last().getStringExtra("name"))
        assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))
        update { u8(1, 2) }
        now += 3_000_000_000L
        // Cause a snapshot/tick without manufacturing a new route.
        val callback = LincolnClusterOutput.javaClass.getDeclaredMethod("onPublishedSnapshot", CarPlayGlance.Snapshot::class.java)
            .apply { isAccessible = true }
        callback.invoke(LincolnClusterOutput, CarPlayGlance.snapshot())
        assertTrue(states().contains(LincolnClusterProtocol.STATE_OFF))
    }

    @Test fun customReceiverKeepsItsPreviousHoldBehavior() {
        LincolnClusterSettings.setClusterPackage(app, "example.custom.cluster")
        maneuver(7, 24, "Destination")
        update { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 100) }
        shadowOf(app).clearBroadcastIntents()
        update { u16List(0x0d, listOf(8)) }
        assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))
        maneuver(8, 2, "Next")
        assertEquals("turn_right", infos().last().getStringExtra("name"))
    }

    private fun maneuver(index: Int, type: Int, road: String) = frame(0x5202) {
        u16(1, index); u8(3, type); string(4, road)
    }

    private fun update(block: Iap2BodyBuilder.() -> Unit) = frame(0x5201, block)

    private fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) =
        CarPlayGlance.onFrame(Iap2Messages.buildRaw(id, block))

    private fun infos(): List<Intent> = shadowOf(app).broadcastIntents.filter {
        it.action == LincolnClusterProtocol.ACTION_NAV_INFO
    }

    private fun states(): List<String?> = shadowOf(app).broadcastIntents
        .filter { it.action == LincolnClusterProtocol.ACTION_NAV_STATE }
        .map { it.getStringExtra(LincolnClusterProtocol.EXTRA_STATE) }
}
