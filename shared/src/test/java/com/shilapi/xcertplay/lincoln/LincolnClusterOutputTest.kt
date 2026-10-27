package com.shilapi.xcertplay.lincoln

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 27, 28, 33])
class LincolnClusterOutputTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var now = 1_000_000_000L
    private val hud = RecordingLincolnHudSocket()

    @Before fun setUp() {
        LincolnClusterSettings.setClusterPackage(app, LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE)
        LincolnClusterSettings.setNavigation(app, true)
        LincolnClusterSettings.setEnhancedNavigation(app, false)
        LincolnClusterSettings.setMusic(app, false)
        LincolnClusterSettings.setRemaining(app, false)
        LincolnClusterOutput.nanoTime = { now }
        LincolnClusterOutput.hudSocket = hud
        CarPlayGlance.setConnected(false)
        LincolnClusterOutput.attach(app)
        shadowOf(app).clearBroadcastIntents()
    }

    @After fun tearDown() {
        LincolnClusterOutput.detach()
        LincolnClusterOutput.hudSocket = LincolnHudSocketServer
        LincolnClusterSettings.setNavigation(app, false)
        LincolnClusterSettings.setEnhancedNavigation(app, false)
        LincolnClusterSettings.setClusterPackage(app, LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE)
        CarPlayGlance.setConnected(false)
        shadowOf(app).clearBroadcastIntents()
    }

    @Test fun aStatedEndMustStickBeforeTheClusterIsCleared() {
        send(guidance())
        assertEquals(1, states().size)
        assertEquals(LincolnClusterProtocol.STATE_ON, states().single())

        // Route start / reroute sends NoRouteSet for a moment. The card must stay.
        send(ended())
        assertFalse("A momentary end must not clear the card", states().contains(LincolnClusterProtocol.STATE_OFF))
        // Even a second later, with the same end repeated, the card is still held.
        now += 1_000_000_000L
        send(ended())
        assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))

        // Once the end has held for the settle window, the card is cleared exactly once.
        now += 2_000_000_000L
        send(ended())
        assertTrue(states().contains(LincolnClusterProtocol.STATE_OFF))
    }

    @Test fun aQuietRouteKeepsTheCardAndKeepsRefreshingIt() {
        send(guidance())
        val info = infos().size
        // Parked at a light for ten seconds: no new guidance at all, but the route did not end.
        repeat(10) {
            now += 1_000_000_000L
            send(quiet())
        }
        assertFalse("A quiet route must not clear the card", states().contains(LincolnClusterProtocol.STATE_OFF))
        assertTrue("The card must keep being refreshed", infos().size > info)
    }

    @Test fun factoryBaiduChannelsSupplementTheVerifiedProgressBroadcast() {
        LincolnClusterSettings.setEnhancedNavigation(app, true)
        val first = guidance().copy(maneuverType = 1, maneuverIndex = 7, distanceMeters = 400)
        send(first)
        val broadcasts = shadowOf(app).broadcastIntents
        assertEquals(1, infos().size)
        assertTrue(broadcasts.any { it.action == LincolnClusterProtocol.ACTION_NAV_ACTIVE_OPEN })
        assertTrue(broadcasts.any { it.action == LincolnClusterProtocol.ACTION_START_NAVIGATION })
        assertTrue(broadcasts.any {
            it.action == LincolnClusterProtocol.ACTION_STANDARD_BROADCAST_SEND &&
                it.getStringExtra(LincolnClusterProtocol.EXTRA_KEY_TYPE) == LincolnClusterProtocol.VALUE_KEY_TYPE_NAV_START
        })
        val turn = broadcasts.single { it.action == LincolnClusterProtocol.ACTION_TURN_BY_TURN }
        assertEquals(LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE, turn.`package`)
        assertEquals(7, turn.getIntExtra("KEY_TURNTYPE", -1))
        assertEquals(400, turn.getIntExtra("remainDist", -1))
        assertEquals(1, hud.starts)
        assertEquals(400, hud.turns.single().metres)
        assertEquals(token(infos().single()).first, hud.turns.single().latitude)

        now += 1_000_000_000L
        send(first.copy(distanceMeters = 200))
        assertEquals(200, infos().last().getIntExtra("distance", -1))
        assertEquals(200, hud.turns.last().metres)
        assertEquals(1, broadcasts.count { it.action == LincolnClusterProtocol.ACTION_NAV_ACTIVE_OPEN })

        send(ended())
        now += 3_000_000_000L
        send(ended())
        assertTrue(broadcasts.any { it.action == LincolnClusterProtocol.ACTION_NAV_ACTIVE_CLOSE })
        assertTrue(broadcasts.any { it.action == LincolnClusterProtocol.ACTION_STOP_NAVIGATION })
        assertEquals(1, hud.ends)
        assertTrue(hud.running) // A second route in this CarPlay session can reuse the socket.
    }

    @Test fun extraFactoryChannelsWaitForARealFirstDistanceButLegacyInfoKeepsWorking() {
        LincolnClusterSettings.setEnhancedNavigation(app, true)
        val start = guidance().copy(maneuverType = 1, maneuverIndex = 7, distanceMeters = 0)
        send(start)
        assertEquals(0, infos().last().getIntExtra("distance", -1))
        assertTrue(shadowOf(app).broadcastIntents.none {
            it.action == LincolnClusterProtocol.ACTION_TURN_BY_TURN
        })
        now += 2_000_000_000L
        send(start)
        assertTrue(hud.turns.isEmpty())
        now += 1_000_000_000L
        send(start)
        assertEquals(0, hud.turns.single().metres)
    }

    @Test fun customClusterPackageKeepsItsExistingBroadcastWithoutFactoryExtras() {
        LincolnClusterSettings.setEnhancedNavigation(app, true)
        LincolnClusterSettings.setClusterPackage(app, "example.custom.cluster")
        send(guidance().copy(maneuverType = 1, maneuverIndex = 7))
        assertEquals("example.custom.cluster", infos().single().`package`)
        assertTrue(shadowOf(app).broadcastIntents.none {
            it.action == LincolnClusterProtocol.ACTION_TURN_BY_TURN ||
                it.action == LincolnClusterProtocol.ACTION_START_NAVIGATION
        })
        assertEquals(0, hud.starts)
    }

    @Test fun existingClusterOutputIsUnchangedUntilCompatibilityIsExplicitlyEnabled() {
        send(guidance().copy(maneuverType = 1, maneuverIndex = 7))
        assertEquals(1, infos().size)
        assertTrue(shadowOf(app).broadcastIntents.none {
            it.action == LincolnClusterProtocol.ACTION_TURN_BY_TURN ||
                it.action == LincolnClusterProtocol.ACTION_START_NAVIGATION ||
                it.action == LincolnClusterProtocol.ACTION_NAV_ACTIVE_OPEN
        })
        assertEquals(0, hud.starts)
    }

    @Test fun compatibilityCanBeEnabledAndDisabledWithoutResettingTheVerifiedProgressStream() {
        val receiver = FactoryNavigationReceiver()
        val first = guidance().copy(maneuverType = 1, maneuverIndex = 7, distanceMeters = 400)
        send(first)
        assertEquals(256, receiver.receive(infos().last()))
        val firstToken = token(infos().last())

        LincolnClusterSettings.setEnhancedNavigation(app, true)
        now += 1_000_000_000L
        send(first.copy(distanceMeters = 200))
        assertEquals(128, receiver.receive(infos().last()))
        assertEquals(firstToken, token(infos().last()))
        assertEquals(200, hud.turns.last().metres)

        LincolnClusterSettings.setEnhancedNavigation(app, false)
        now += 1_000_000_000L
        send(first.copy(distanceMeters = 100))
        assertEquals(64, receiver.receive(infos().last()))
        assertEquals(firstToken, token(infos().last()))
        assertTrue(hud.stops > 0)
        assertTrue(shadowOf(app).broadcastIntents.any {
            it.action == LincolnClusterProtocol.ACTION_NAV_ACTIVE_CLOSE
        })
        assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))
    }

    @Test fun sendsTheBoundedRoadNameAndKeepsItDuringQuietGuidance() {
        val expected = "中".repeat(18)
        val road = expected + "𠀀路终点"
        send(guidance().copy(road = road))
        assertEquals(expected, infos().last().getStringExtra(LincolnClusterProtocol.EXTRA_ROAD))

        now += 1_000_000_000L
        send(quiet())
        assertEquals(expected, infos().last().getStringExtra(LincolnClusterProtocol.EXTRA_ROAD))
    }

    @Test fun receiverCalculatesProgressAcrossUpdatesQuietFramesAndNextManeuver() {
        val receiver = FactoryNavigationReceiver()
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 400))
        assertEquals(256, receiver.receive(infos().last()))
        assertFalse(infos().last().hasExtra("BargraphSteps"))
        val firstToken = token(infos().last())

        now += 1_000_000_000L
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 200))
        assertEquals(128, receiver.receive(infos().last()))
        assertEquals(firstToken, token(infos().last()))

        // A temporary empty list must keep both the card and its last progress value.
        now += 1_000_000_000L
        send(quiet())
        assertEquals(128, receiver.receive(infos().last()))
        assertEquals(firstToken, token(infos().last()))

        // The next iAP2 maneuver gets a fresh full-scale bar even when its distance is larger.
        now += 1_000_000_000L
        send(guidance().copy(maneuverIndex = 8, distanceMeters = 600, road = "Next Road"))
        assertEquals(256, receiver.receive(infos().last()))
        assertNotEquals(firstToken, token(infos().last()))
        send(guidance().copy(maneuverIndex = 8, distanceMeters = 0, road = "Next Road"))
        assertEquals(256, receiver.receive(infos().last())) // First distant zero is a candidate.
        now += 500_000_000L
        send(guidance().copy(maneuverIndex = 8, distanceMeters = 0, road = "Next Road"))
        assertEquals(0, receiver.receive(infos().last()))
    }

    @Test fun aDelayedLyricCallbackCannotReplayOldGuidanceAndRestartProgress() {
        LincolnClusterSettings.setMusic(app, true)
        LincolnClusterSettings.setRemaining(app, true)
        CarPlayGlance.setConnected(true)
        LincolnClusterOutput.detach()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val slowConsumer: (CarPlayGlance.Snapshot) -> Unit = { snapshot ->
            if (snapshot.songTitle == "Delayed lyric" && snapshot.distanceMeters == 125) {
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) {
                    failure.set(AssertionError("Timed out releasing delayed metadata"))
                }
            }
        }
        // Listeners run outside the glance lock. Another consumer may delay an older music
        // callback while a newer navigation frame reaches the instrument on another thread.
        CarPlayGlance.addListener(slowConsumer)
        LincolnClusterOutput.attach(app)
        var delayed: Thread? = null
        try {
            frame(0x5001) { group(0) { string(1, "Original lyric") } }
            frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Road") }
            frame(0x5201) {
                u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400)
                u32(7, 12_000); u64(6, 900)
            }
            frame(0x5201) { u32(0x0a, 125) }
            delayed = Thread {
                try {
                    frame(0x5001) { group(0) { string(1, "Delayed lyric") } }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }.apply { start() }
            assertTrue("The older callback must be held", entered.await(5, TimeUnit.SECONDS))
            frame(0x5201) { u32(0x0a, 90); u32(7, 8_000); u64(6, 600) }
            frame(0x5001) { group(0) { string(1, "Latest lyric") } }
            release.countDown()
            delayed.join(5_000)
            assertFalse("Delayed callback must finish", delayed.isAlive)
            failure.get()?.let { throw it }
            frame(0x5201) { u32(0x0a, 80) }

            assertEquals(listOf(400, 125, 90, 80), infos().map { it.getIntExtra("distance", -1) })
            assertEquals(1, infos().map(::token).distinct().size)
            val receiver = FactoryNavigationReceiver()
            assertEquals(listOf(256, 80, 57, 51), infos().map(receiver::receive))
            assertEquals(listOf("Original lyric", "Delayed lyric", "Latest lyric"),
                shadowOf(app).broadcastIntents.filter { it.action == LincolnClusterProtocol.ACTION_MEDIA_INFO }
                    .map { it.getStringExtra("title") })
            assertEquals(listOf(12_000, 8_000),
                shadowOf(app).broadcastIntents.filter { it.action == LincolnClusterProtocol.ACTION_NAV_REMAIN }
                    .map { it.getIntExtra("remainDistance", -1) })
        } finally {
            release.countDown()
            delayed?.join(5_000)
            CarPlayGlance.removeListener(slowConsumer)
        }
    }

    @Test fun aTimerReadSupersededBeforeDispatchCannotRestoreOldDistance() {
        CarPlayGlance.setConnected(true)
        frame(0x5202) { u16(1, 7); u8(3, 2) }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
        frame(0x5201) { u32(0x0a, 125) }
        val timerRead = CarPlayGlance.snapshot()
        frame(0x5201) { u32(0x0a, 90) }
        now += 1_000_000_000L
        // The tick reads before taking the output lock. Navigation can publish in that gap.
        sendPublished(timerRead)
        sendPublished(CarPlayGlance.snapshot())
        assertEquals(listOf(400, 125, 90, 90), infos().map { it.getIntExtra("distance", -1) })
        assertEquals(1, infos().map(::token).distinct().size)
        val receiver = FactoryNavigationReceiver()
        assertEquals(listOf(256, 80, 57, 57), infos().map(receiver::receive))
    }

    @Test fun aSupersededTickUsesLatestGuidanceWithoutWaitingForItsDelayedNotification() {
        CarPlayGlance.setConnected(true)
        LincolnClusterOutput.detach()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val slowConsumer: (CarPlayGlance.Snapshot) -> Unit = { snapshot ->
            if (snapshot.distanceMeters == 100) {
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) {
                    failure.set(AssertionError("Timed out releasing latest guidance notification"))
                }
            }
        }
        CarPlayGlance.addListener(slowConsumer)
        LincolnClusterOutput.attach(app)
        var delayed: Thread? = null
        try {
            frame(0x5202) { u16(1, 7); u8(3, 2) }
            frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
            val timerRead = CarPlayGlance.snapshot()
            delayed = Thread {
                try {
                    frame(0x5201) { u32(0x0a, 100) }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }.apply { start() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            now += 1_000_000_000L
            sendPublished(timerRead)
            assertEquals("A due tick must not be lost while newer notification is delayed",
                listOf(400, 100), infos().map { it.getIntExtra("distance", -1) })
            // Reading the published value must not notify the blocked consumer recursively.
            now += 1_000_000_000L
            sendPublished(timerRead)
            assertEquals(listOf(400, 100, 100), infos().map { it.getIntExtra("distance", -1) })
            assertEquals(1, infos().map(::token).distinct().size)
            release.countDown()
            delayed.join(5_000)
            assertFalse(delayed.isAlive)
            failure.get()?.let { throw it }
            assertEquals(3, infos().size)
        } finally {
            release.countDown()
            delayed?.join(5_000)
            CarPlayGlance.removeListener(slowConsumer)
        }
    }

    @Test fun aCallbackWaitingForTheOutputLockCannotUndoTheNextManeuver() {
        CarPlayGlance.setConnected(true)
        frame(0x5202) { u16(1, 7); u8(3, 2) }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
        val oldRead = CarPlayGlance.snapshot()
        val outputLock = checkNotNull(LincolnClusterOutput.javaClass.getDeclaredField("lock")
            .apply { isAccessible = true }.get(LincolnClusterOutput))
        val failure = AtomicReference<Throwable?>()
        val delayed = Thread {
            try {
                sendPublished(oldRead)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            synchronized(outputLock) {
                delayed.start()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (delayed.state != Thread.State.BLOCKED && delayed.isAlive && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                assertEquals("Older dispatch must already be waiting for the output lock",
                    Thread.State.BLOCKED, delayed.state)
                // The state source must not keep its own lock while entering this output.
                // These real frames can advance and deliver even while a different writer waits.
                frame(0x5202) { u16(1, 8); u8(3, 1) }
                frame(0x5201) { u16List(0x0d, listOf(8)); u32(0x0a, 100) }
            }
            delayed.join(5_000)
            assertFalse(delayed.isAlive)
            failure.get()?.let { throw it }
            val next = token(infos().last())
            frame(0x5201) { u32(0x0a, 50) }
            assertEquals(listOf(400, 100, 50), infos().map { it.getIntExtra("distance", -1) })
            assertNotEquals(token(infos().first()), next)
            assertEquals(next, token(infos().last()))
            val receiver = FactoryNavigationReceiver()
            assertEquals(listOf(256, 256, 128), infos().map(receiver::receive))
        } finally {
            delayed.join(5_000)
        }
    }

    @Test fun supersededEndAndDisconnectStillResetTheNewRouteExactlyOnce() {
        for (disconnect in listOf(false, true)) {
            CarPlayGlance.setConnected(false)
            CarPlayGlance.setConnected(true)
            LincolnClusterOutput.detach()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val slowConsumer: (CarPlayGlance.Snapshot) -> Unit = { snapshot ->
                if ((disconnect && !snapshot.connected) || (!disconnect && snapshot.routeEnded)) {
                    entered.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        failure.set(AssertionError("Timed out releasing route boundary"))
                    }
                }
            }
            CarPlayGlance.addListener(slowConsumer)
            LincolnClusterOutput.attach(app)
            shadowOf(app).clearBroadcastIntents()
            var delayed: Thread? = null
            try {
                frame(0x5202) { u16(1, 7); u8(3, 2) }
                frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
                frame(0x5201) { u32(0x0a, 200) }
                val oldToken = token(infos().last())
                delayed = Thread {
                    try {
                        if (disconnect) CarPlayGlance.setConnected(false)
                        else frame(0x5201) { u8(1, 2) } // Confirmed arrival, not debounced NoRouteSet.
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                }.apply { start() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (disconnect) CarPlayGlance.setConnected(true)
                frame(0x5202) { u16(1, 7); u8(3, 2) }
                frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 100) }
                val nextToken = token(infos().last())
                assertNotEquals("An end must survive callback coalescing", oldToken, nextToken)
                release.countDown()
                delayed.join(5_000)
                assertFalse(delayed.isAlive)
                failure.get()?.let { throw it }
                frame(0x5201) { u32(0x0a, 50) }
                assertEquals("A stale end must not reset the resumed route again", nextToken, token(infos().last()))
                assertEquals(listOf(400, 200, 100, 50), infos().map { it.getIntExtra("distance", -1) })
                val receiver = FactoryNavigationReceiver()
                assertEquals(listOf(256, 128, 256, 128), infos().map(receiver::receive))
                assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))
            } finally {
                release.countDown()
                delayed?.join(5_000)
                CarPlayGlance.removeListener(slowConsumer)
            }
        }
    }

    @Test fun aStaleTickStillClearsEndedOrDisconnectedOutputWhileTerminalCallbackIsDelayed() {
        LincolnClusterSettings.setMusic(app, true)
        LincolnClusterSettings.setRemaining(app, true)
        for (disconnect in listOf(false, true)) {
            CarPlayGlance.setConnected(false)
            CarPlayGlance.setConnected(true)
            LincolnClusterOutput.detach()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val armed = AtomicBoolean(false)
            val slowConsumer: (CarPlayGlance.Snapshot) -> Unit = { snapshot ->
                if (armed.get() && ((disconnect && !snapshot.connected) || (!disconnect && snapshot.routeEnded))) {
                    entered.countDown()
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        failure.set(AssertionError("Timed out releasing terminal state"))
                    }
                }
            }
            CarPlayGlance.addListener(slowConsumer)
            LincolnClusterOutput.attach(app)
            shadowOf(app).clearBroadcastIntents()
            var delayed: Thread? = null
            try {
                frame(0x5001) { group(0) { string(1, "Current song") } }
                frame(0x5202) { u16(1, 7); u8(3, 2) }
                frame(0x5201) {
                    u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400)
                    u32(7, 12_000); u64(6, 900)
                }
                val timerRead = CarPlayGlance.snapshot()
                armed.set(true)
                delayed = Thread {
                    try {
                        if (disconnect) CarPlayGlance.setConnected(false)
                        else frame(0x5201) { u8(1, 2) }
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                }.apply { start() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                sendPublished(timerRead)
                if (!disconnect) assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))
                now += 3_000_000_000L
                sendPublished(timerRead)
                assertEquals(listOf(LincolnClusterProtocol.STATE_ON, LincolnClusterProtocol.STATE_OFF), states())
                val remaining = shadowOf(app).broadcastIntents.last { it.action == LincolnClusterProtocol.ACTION_NAV_REMAIN }
                assertEquals(0, remaining.getIntExtra("remainDistance", -1))
                assertEquals(0, remaining.getIntExtra("remainTime", -1))
                val media = shadowOf(app).broadcastIntents.last { it.action == LincolnClusterProtocol.ACTION_MEDIA_INFO }
                assertEquals(if (disconnect) "" else "Current song", media.getStringExtra("title"))
                release.countDown()
                delayed.join(5_000)
                assertFalse(delayed.isAlive)
                failure.get()?.let { throw it }
                assertEquals(2, states().size)
            } finally {
                release.countDown()
                delayed?.join(5_000)
                CarPlayGlance.removeListener(slowConsumer)
            }
        }
    }

    @Test fun neverBroadcastsANegativeManeuverDistance() {
        val receiver = FactoryNavigationReceiver()
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 400))
        receiver.receive(infos().last())
        now += 1_000_000_000L
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 200))
        assertEquals(128, receiver.receive(infos().last()))

        send(guidance().copy(maneuverIndex = 7, distanceMeters = -1))
        assertEquals("A bad sample must not be broadcast", 2, infos().size)

        // Rejecting a bad sample must not poison the next valid maneuver or its progress baseline.
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 300))
        assertEquals(300, infos().last()
            .getIntExtra(LincolnClusterProtocol.EXTRA_DISTANCE, -1))
        assertEquals(256, receiver.receive(infos().last()))
    }

    @Test fun legacyExtraDoesNotDriveTheFactoryReceiver() {
        val receiver = FactoryNavigationReceiver()
        val legacy = Intent("com.baidu.car.navi.INFO")
            .putExtra("name", "turn_right").putExtra("nextRoadName", "Test Road")
            .putExtra("distance", 400).putExtra("BargraphSteps", "256")
        assertEquals(1, receiver.receive(legacy))
        assertEquals(1, receiver.receive(legacy.putExtra("distance", 200).putExtra("BargraphSteps", "128")))
    }

    @Test fun sameLookingNextManeuverIsSentImmediatelyWithANewIdentity() {
        val current = guidance().copy(maneuverIndex = 7, distanceMeters = 400)
        send(current)
        val old = token(infos().last())
        // No distance, road, arrow or time change; the iAP2 index alone changed.
        send(current.copy(maneuverIndex = 8))
        assertEquals(2, infos().size)
        assertNotEquals(old, token(infos().last()))
    }

    @Test fun rerouteAndGpsCorrectionsKeepTheReceiverRatioWithinRange() {
        val receiver = FactoryNavigationReceiver()
        fun advance(distance: Int): Int {
            now += 1_000_000_000L
            send(guidance().copy(maneuverIndex = 7, distanceMeters = distance))
            return receiver.receive(infos().last())
        }
        assertEquals(256, advance(400))
        assertEquals(128, advance(200))
        val old = token(infos().last())
        assertEquals(128, advance(205))
        assertEquals(old, token(infos().last()))
        assertEquals(128, advance(300)) // First large correction is held.
        assertEquals(256, advance(300)) // Second fresh confirmation establishes a new baseline.
        assertNotEquals(old, token(infos().last()))
        assertEquals(128, advance(150))
        assertEquals(128, advance(300))
        assertEquals(256, advance(305))
        assertEquals(256, advance(2_000_000)) // Held at 305 until the next fresh sample.
        assertEquals(256, advance(2_000_000))
        assertEquals(128, advance(1_000_000))
        val count = infos().size
        send(guidance().copy(distanceMeters = Int.MAX_VALUE))
        assertEquals(count, infos().size)
    }

    @Test fun zeroFirstDistanceRecoversAndRatioUsesTheDistanceActuallyTransmitted() {
        val receiver = FactoryNavigationReceiver()
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 0))
        assertEquals(1, receiver.receive(infos().last()))
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 1_099))
        assertEquals(1_000, infos().last().getIntExtra("distance", -1))
        assertEquals(256, receiver.receive(infos().last()))
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 500))
        assertEquals(128, receiver.receive(infos().last()))
    }

    @Test fun aOneMeterRoundingBoundaryCorrectionKeepsTheReceiverBaseline() {
        val receiver = FactoryNavigationReceiver()
        val current = guidance().copy(maneuverIndex = 7, distanceMeters = 2_000)
        send(current)
        assertEquals(256, receiver.receive(infos().last()))
        val original = token(infos().last())
        send(current.copy(distanceMeters = 1_499))
        assertEquals(179, receiver.receive(infos().last()))
        send(current.copy(distanceMeters = 1_500))
        assertEquals(1_400, infos().last().getIntExtra("distance", -1))
        assertEquals(179, receiver.receive(infos().last()))
        val refreshed = token(infos().last())
        assertEquals(original, refreshed)
        send(current.copy(distanceMeters = 1_499))
        assertEquals(1_400, infos().last().getIntExtra("distance", -1))
        assertEquals(179, receiver.receive(infos().last()))
        assertEquals(refreshed, token(infos().last()))
        // Raw correction inside the same transmitted bucket needs no new identity.
        send(current.copy(distanceMeters = 1_498))
        assertEquals(refreshed, token(infos().last()))
    }

    @Test fun aRestartedReceiverNeverExceedsFullScaleOnASmallDistanceIncrease() {
        val current = guidance().copy(maneuverIndex = 7, distanceMeters = 400)
        var receiver = FactoryNavigationReceiver()
        send(current)
        assertEquals(256, receiver.receive(infos().last()))
        send(current.copy(distanceMeters = 200))
        assertEquals(128, receiver.receive(infos().last()))
        // Only the instrument restarts. DiPlay receives no notification or acknowledgement.
        receiver = FactoryNavigationReceiver()
        now += 1_000_000_000L
        send(quiet())
        assertEquals(256, receiver.receive(infos().last()))
        send(current.copy(distanceMeters = 205))
        assertEquals(256, receiver.receive(infos().last())) // formerly 262
        send(current.copy(distanceMeters = 100))
        assertEquals(128, receiver.receive(infos().last()))
    }

    @Test fun silentlyMissingTheFirstPacketDoesNotAssumeTheSenderBaselineWasReceived() {
        val current = guidance().copy(maneuverIndex = 7, distanceMeters = 400)
        val receiver = FactoryNavigationReceiver()
        send(current) // The OS accepted this packet, but the receiver does not consume it.
        assertEquals(1, infos().size)
        send(current.copy(distanceMeters = 200))
        assertEquals(256, receiver.receive(infos().last()))
        send(current.copy(distanceMeters = 205))
        assertEquals(200, infos().last().getIntExtra("distance", -1))
        assertEquals(256, receiver.receive(infos().last())) // formerly 262
    }

    @Test fun orderedPacketLossAndReceiverRestartsKeepTheUnclampedRatioBounded() {
        val current = guidance().copy(maneuverIndex = 7)
        val distances = listOf(400, 200, 205, 100, 99, 105, 0, 1, 0, 2_000,
            1_499, 1_500, 1_498, 99_999, 100_001, 99_999, 2_000_000, 1_000_000, 0)
        for (distance in distances) {
            now += 1_000_000_000L
            send(current.copy(distanceMeters = distance))
        }
        val packets = infos()
        assertEquals(distances.size, packets.size)
        // A fresh receiver may first see ANY packet. Try varied subsequent loss patterns too.
        for (start in packets.indices) for (stride in 1..4) {
            val receiver = FactoryNavigationReceiver()
            for (index in start until packets.size step stride) {
                val progress = receiver.receive(packets[index])
                assertTrue("start=$start stride=$stride packet=$index progress=$progress", progress in 0..256)
            }
        }
    }

    @Test fun roadNameCompletionAndInstructionRefinementPreserveAnIndexedManeuver() {
        val current = guidance().copy(maneuverIndex = 7, road = "", distanceMeters = 400)
        val receiver = FactoryNavigationReceiver()
        send(current)
        assertEquals(256, receiver.receive(infos().last()))
        val original = token(infos().last())
        send(current.copy(road = "Completed Road", distanceMeters = 200))
        assertEquals("Completed Road", infos().last().getStringExtra("nextRoadName"))
        assertEquals(128, receiver.receive(infos().last()))
        assertEquals(original, token(infos().last()))
        send(current.copy(road = "Refined Road", maneuverType = 1, distanceMeters = 100))
        assertEquals("turn_left", infos().last().getStringExtra("name"))
        assertEquals(64, receiver.receive(infos().last()))
        assertEquals(original, token(infos().last()))
        send(current.copy(maneuverIndex = 8, road = "Refined Road", maneuverType = 1, distanceMeters = 100))
        assertEquals(256, receiver.receive(infos().last()))
        assertNotEquals(original, token(infos().last()))
    }

    @Test fun aBriefExplicitEndResetsTheNextBaselineWhileKeepingTheHeldCard() {
        val receiver = FactoryNavigationReceiver()
        val current = guidance().copy(maneuverIndex = 7, distanceMeters = 400)
        send(current)
        receiver.receive(infos().last())
        send(current.copy(distanceMeters = 200))
        assertEquals(128, receiver.receive(infos().last()))
        val original = token(infos().last())
        send(ended())
        now += 1_000_000_000L
        // Even stale non-null fields on the ended snapshot must not replace the held packet.
        send(current.copy(routeEnded = true, maneuverIndex = 99, distanceMeters = 999))
        assertEquals(200, infos().last().getIntExtra("distance", -1))
        assertEquals(original, token(infos().last()))
        assertEquals(128, receiver.receive(infos().last()))
        assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))
        // Reopen before 2.5 s, with the same index and road, but a shorter new leg.
        send(current.copy(distanceMeters = 100))
        assertEquals(256, receiver.receive(infos().last())) // formerly 64
        assertNotEquals(original, token(infos().last()))
        assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))
    }

    @Test fun aReusedIndexAfterZeroStartsANewManeuverEvenBelowTheOldBaseline() {
        val receiver = FactoryNavigationReceiver()
        val current = guidance().copy(maneuverIndex = 7, distanceMeters = 400)
        send(current)
        assertEquals(256, receiver.receive(infos().last()))
        send(current.copy(distanceMeters = 0))
        assertEquals(256, receiver.receive(infos().last()))
        now += 500_000_000L
        send(current.copy(distanceMeters = 0))
        assertEquals(0, receiver.receive(infos().last()))
        send(current.copy(distanceMeters = 200))
        now += 1_000_000_000L
        send(current.copy(distanceMeters = 200))
        assertEquals(256, receiver.receive(infos().last()))
    }

    @Test fun freshNavigationGetsANewBaselineEvenWhenTheClusterOutlivesDiPlay() {
        val receiver = FactoryNavigationReceiver()
        val current = guidance().copy(maneuverIndex = 7, distanceMeters = 400)
        send(current)
        receiver.receive(infos().last())
        send(current.copy(distanceMeters = 200))
        assertEquals(128, receiver.receive(infos().last()))
        val old = token(infos().last())
        send(ended())
        now += 3_000_000_000L
        send(ended())
        send(current.copy(distanceMeters = 200))
        assertEquals(256, receiver.receive(infos().last()))
        assertNotEquals(old, token(infos().last()))
        val endedToken = token(infos().last())
        // Restart only DiPlay; the original receiver's cached coordinates survive.
        LincolnClusterOutput.detach()
        LincolnClusterOutput.attach(app)
        send(current.copy(distanceMeters = 100))
        assertEquals(256, receiver.receive(infos().last()))
        assertNotEquals(endedToken, token(infos().last()))
    }

    @Test fun alternativeReceiversDoNotReceiveOpaqueCoordinateTokens() {
        LincolnClusterSettings.setClusterPackage(app, "com.example.other.cluster")
        send(guidance())
        now += 1_000_000_000L
        send(quiet())
        for (intent in infos()) {
            assertEquals("com.example.other.cluster", intent.`package`)
            assertEquals(300, intent.getIntExtra("distance", -1))
            assertFalse(intent.hasExtra("latitude"))
            assertFalse(intent.hasExtra("longitude"))
            assertFalse(intent.hasExtra("BargraphSteps"))
        }
    }

    @Test fun rejectedStartPacketsCannotLeaveTheReceiverWithASmallerBaseline() {
        for (failedAction in listOf(LincolnClusterProtocol.ACTION_NAV_STATE, LincolnClusterProtocol.ACTION_NAV_INFO)) {
            var reject = true
            withBroadcastFailures { reject && it.action == failedAction }
            val receiver = FactoryNavigationReceiver()
            val current = guidance().copy(maneuverIndex = 7, distanceMeters = 400)
            send(current)
            assertTrue("The rejected start must not reach the receiver", infos().isEmpty())
            reject = false
            send(current.copy(distanceMeters = 200))
            assertEquals(256, receiver.receive(infos().last()))
            // Without resetting an unsent baseline this small correction produces 262.
            send(current.copy(distanceMeters = 205))
            assertEquals(256, receiver.receive(infos().last()))
            send(current.copy(distanceMeters = 100))
            assertEquals(128, receiver.receive(infos().last()))
        }
    }

    @Test fun failedClearKeepsTheLastSuccessfulPacketForQuietGuidance() {
        var rejectOff = false
        withBroadcastFailures {
            rejectOff && it.action == LincolnClusterProtocol.ACTION_NAV_STATE &&
                it.getStringExtra("state") == LincolnClusterProtocol.STATE_OFF
        }
        val receiver = FactoryNavigationReceiver()
        val current = guidance().copy(maneuverIndex = 7, distanceMeters = 400)
        send(current)
        receiver.receive(infos().last())
        send(current.copy(distanceMeters = 200))
        assertEquals(128, receiver.receive(infos().last()))
        val previous = token(infos().last())
        rejectOff = true
        send(current.copy(distanceMeters = -1))
        val count = infos().size
        now += 1_000_000_000L
        send(quiet())
        assertEquals(count + 1, infos().size)
        assertEquals(previous, token(infos().last()))
        assertEquals(128, receiver.receive(infos().last()))
        send(current.copy(distanceMeters = 100))
        assertEquals(256, receiver.receive(infos().last()))
        assertNotEquals(previous, token(infos().last()))
    }

    @Test fun anAmbiguousSendFailureCannotReplayALargerDistanceUnderTheSameIdentity() {
        var receiver = FactoryNavigationReceiver()
        var failAfterDelivery = false
        var deliveredProgress: Int? = null
        LincolnClusterOutput.detach()
        LincolnClusterOutput.attach(object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun sendBroadcast(intent: Intent) {
                super.sendBroadcast(intent)
                if (failAfterDelivery && intent.action == LincolnClusterProtocol.ACTION_NAV_INFO) {
                    deliveredProgress = receiver.receive(intent)
                    throw SecurityException("Injected failure with an ambiguous delivery outcome")
                }
            }
        })
        shadowOf(app).clearBroadcastIntents()
        val current = guidance().copy(maneuverIndex = 7, distanceMeters = 400)
        send(current)
        assertEquals(256, receiver.receive(infos().last()))
        receiver = FactoryNavigationReceiver()
        failAfterDelivery = true
        send(current.copy(distanceMeters = 150))
        assertEquals(256, deliveredProgress)
        val attempted = token(infos().last())
        failAfterDelivery = false
        now += 1_000_000_000L
        send(quiet())
        assertEquals(400, infos().last().getIntExtra("distance", -1))
        assertNotEquals(attempted, token(infos().last()))
        assertEquals(256, receiver.receive(infos().last())) // formerly 682
        send(current.copy(distanceMeters = 100))
        assertEquals(256, receiver.receive(infos().last()))
    }

    @Test fun sendsTheBoundedLyricWithoutAddingTheSongName() {
        LincolnClusterSettings.setMusic(app, true)
        val expected = "中".repeat(17) + "🎵"
        val lyric = expected + "这一行歌词需要截取完整字符"
        send(CarPlayGlance.Snapshot(connected = true, songTitle = lyric,
            song = "$lyric — Song name", songArtist = "Song name", playing = true))

        val media = shadowOf(app).broadcastIntents.last { it.action == LincolnClusterProtocol.ACTION_MEDIA_INFO }
        assertEquals(expected, media.getStringExtra(LincolnClusterProtocol.EXTRA_TITLE))
        assertEquals("", media.getStringExtra(LincolnClusterProtocol.EXTRA_ARTIST))
        assertTrue(media.getBooleanExtra(LincolnClusterProtocol.EXTRA_PLAYING, false))
    }

    @Test fun rawIap2CalculationResetsAReusedIndexOnlyAfterFreshResumedDistance() {
        for (calculationState in listOf(3, 5)) {
            CarPlayGlance.setConnected(false)
            CarPlayGlance.setConnected(true)
            shadowOf(app).clearBroadcastIntents()
            val receiver = FactoryNavigationReceiver()
            frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Road") }
            frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
            assertEquals(256, receiver.receive(infos().last()))
            frame(0x5201) { u32(0x0a, 200) }
            assertEquals(128, receiver.receive(infos().last()))
            val oldToken = token(infos().last())

            now += 1_000_000_000L
            frame(0x5201) { u8(1, calculationState); u16List(0x0d, emptyList()) }
            assertEquals(128, receiver.receive(infos().last()))
            frame(0x5201) { u8(1, calculationState); u16List(0x0d, listOf(7)); u32(0x0a, 180) }
            now += 1_000_000_000L
            frame(0x5201) { u8(1, 1) } // Resume alone cannot use the cached 180 m.
            frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "New Road") }
            frame(0x5001) { group(0) { string(1, "New Song") } }
            assertEquals(oldToken, token(infos().last()))
            assertEquals(200, infos().last().getIntExtra("distance", -1))
            assertEquals("Road", infos().last().getStringExtra("nextRoadName"))
            assertEquals(128, receiver.receive(infos().last()))

            // No end event, no index change and no distance increase: explicit calculation
            // still gives the next valid instruction a new baseline, through real TLV parsing.
            frame(0x5201) { u32(0x0a, 100) }
            assertNotEquals(oldToken, token(infos().last()))
            assertEquals("New Road", infos().last().getStringExtra("nextRoadName"))
            assertEquals(256, receiver.receive(infos().last()))
            val newToken = token(infos().last())
            frame(0x5201) { u8(1, 1); u32(0x0a, 50) }
            assertEquals(newToken, token(infos().last()))
            assertEquals(128, receiver.receive(infos().last()))
            assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))
        }
    }

    @Test fun rawIap2RerouteWithIdenticalVisibleFieldsStillResetsImmediately() {
        CarPlayGlance.setConnected(true)
        val receiver = FactoryNavigationReceiver()
        frame(0x5202) { u16(1, 7); u8(3, 2) }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
        receiver.receive(infos().last())
        frame(0x5201) { u32(0x0a, 200) }
        assertEquals(128, receiver.receive(infos().last()))
        val oldToken = token(infos().last())
        val count = infos().size
        frame(0x5201) { u8(1, 5) }
        frame(0x5201) { u8(1, 5) }
        assertEquals(count, infos().size)
        frame(0x5201) { u8(1, 1); u32(0x0a, 200) }
        assertEquals(count + 1, infos().size)
        assertNotEquals(oldToken, token(infos().last()))
        assertEquals(256, receiver.receive(infos().last()))
        val next = token(infos().last())
        frame(0x5201) { u8(1, 1); u32(0x0a, 100) }
        assertEquals(next, token(infos().last()))
        assertEquals(128, receiver.receive(infos().last()))
    }

    @Test fun rawIap2OrdinaryGapsAndShorterDistancesDoNotGuessAReroute() {
        CarPlayGlance.setConnected(true)
        val receiver = FactoryNavigationReceiver()
        frame(0x5202) { u16(1, 7); u8(3, 2) }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
        receiver.receive(infos().last())
        val original = token(infos().last())
        frame(0x5201) { u8(1, 1); u16List(0x0d, emptyList()) }
        frame(0x5201) { u16List(0x0d, listOf(7)); u32(0x0a, 200) }
        assertEquals(128, receiver.receive(infos().last()))
        frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Road name completed") }
        frame(0x5201) { u8(1, 4); u32(0x0a, 100) }
        frame(0x5201) { u8(1, 1) }
        assertEquals(original, token(infos().last()))
        assertEquals(64, receiver.receive(infos().last()))
        // Without any boundary signal this is indistinguishable from continuing the old leg.
        frame(0x5201) { u32(0x0a, 50) }
        assertEquals(original, token(infos().last()))
        assertEquals(32, receiver.receive(infos().last()))
    }

    @Test fun aPendingRecalculationDoesNotPreventExplicitEndOrDisconnect() {
        CarPlayGlance.setConnected(true)
        frame(0x5202) { u16(1, 7); u8(3, 2) }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
        frame(0x5201) { u8(1, 5) }
        frame(0x5201) { u8(1, 2) }
        now += 3_000_000_000L
        send(CarPlayGlance.snapshot())
        assertEquals(LincolnClusterProtocol.STATE_OFF, states().last())

        // A fresh session does not inherit the pending gate or require a new RouteSet field.
        CarPlayGlance.setConnected(false)
        CarPlayGlance.setConnected(true)
        frame(0x5202) { u16(1, 7); u8(3, 2) }
        frame(0x5201) { u16List(0x0d, listOf(7)); u32(0x0a, 100) }
        assertEquals(100, infos().last().getIntExtra("distance", -1))
        assertEquals(LincolnClusterProtocol.STATE_ON, states().last())
        frame(0x5201) { u8(1, 3) }
        CarPlayGlance.setConnected(false)
        assertEquals(LincolnClusterProtocol.STATE_OFF, states().last())
    }

    @Test fun customReceiversKeepTheirExistingGuidanceFlowDuringRecalculation() {
        LincolnClusterSettings.setClusterPackage(app, "example.other.cluster")
        CarPlayGlance.setConnected(true)
        frame(0x5202) { u16(1, 7); u8(3, 2) }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 400) }
        frame(0x5201) { u8(1, 5); u32(0x0a, 200) }
        assertEquals(200, infos().last().getIntExtra("distance", -1))
        assertEquals("example.other.cluster", infos().last().`package`)
        assertFalse(infos().last().hasExtra("latitude"))
        assertFalse(infos().last().hasExtra("longitude"))
        frame(0x5201) { u32(0x0a, 201) }
        assertEquals(201, infos().last().getIntExtra("distance", -1))
    }

    @Test fun rawIap2SmallRisesAreHeldAndKeepalivesAndSongsDoNotConfirmReroutes() {
        CarPlayGlance.setConnected(true)
        frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Road") }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 600) }
        val original = token(infos().last())
        for (distance in listOf(580, 582, 575, 650)) {
            now += 1_000_000_000L
            frame(0x5201) { u32(0x0a, distance.toLong()) }
        }
        assertEquals(listOf(600, 580, 580, 575, 575), infos().map { it.getIntExtra("distance", -1) })
        repeat(6) {
            now += 1_000_000_000L
            frame(0x5001) { group(0) { string(1, "Lyric $it") } }
            sendPublished(CarPlayGlance.snapshot())
            assertEquals(original, token(infos().last()))
            assertEquals(575, infos().last().getIntExtra("distance", -1))
        }
        frame(0x5201) { u32(0x0a, 650) } // The second fresh large-rise frame confirms it.
        val confirmed = token(infos().last())
        assertNotEquals(original, confirmed)
        assertEquals(650, infos().last().getIntExtra("distance", -1))
        now += 1_000_000_000L
        frame(0x5201) { u32(0x0a, 649) }
        assertEquals(confirmed, token(infos().last()))
        assertEquals(649, infos().last().getIntExtra("distance", -1))
        val receiver = FactoryNavigationReceiver()
        assertTrue(infos().map(receiver::receive).all { it in 0..256 })
    }

    @Test fun rawIap2BriefNoRouteSetRetainsBaselineAndCachedManeuverUntilFreshDistance() = withRouteClock {
        CarPlayGlance.setConnected(true)
        frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Road") }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 600) }
        frame(0x5201) { u32(0x0a, 300) }
        val original = token(infos().last())
        frame(0x5201) { u8(1, 0); u32(0x0a, 999); string(3, "Stale") }
        assertTrue(CarPlayGlance.snapshot().routeEndPending)
        now += 1_000_000_000L
        frame(0x5201) { u8(1, 1) }
        frame(0x5001) { group(0) { string(1, "Lyric") } }
        assertEquals(300, infos().last().getIntExtra("distance", -1))
        assertEquals(original, token(infos().last()))
        frame(0x5201) { u32(0x0a, 290) } // No repeated 0x5202 or current-list required.
        assertFalse(CarPlayGlance.snapshot().routeEndPending)
        assertEquals(290, infos().last().getIntExtra("distance", -1))
        assertEquals("Road", infos().last().getStringExtra("nextRoadName"))
        assertEquals(original, token(infos().last()))
        val receiver = FactoryNavigationReceiver()
        assertEquals(123, infos().map(receiver::receive).last())
        assertFalse(states().contains(LincolnClusterProtocol.STATE_OFF))
    }

    @Test fun rawIap2NoRouteDeadlineSurvivesNoTimerRepeatedEndsAndStatusOnlyFrames() = withRouteClock {
        for (resumeWithoutTick in listOf(false, true)) {
            CarPlayGlance.setConnected(false)
            CarPlayGlance.setConnected(true)
            shadowOf(app).clearBroadcastIntents()
            frame(0x5202) { u16(1, 7); u8(3, 2) }
            frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 600) }
            val original = token(infos().last())
            frame(0x5201) { u8(1, 0) }
            now += 2_000_000_000L
            frame(0x5201) { u8(1, 0) } // Must not extend the first deadline.
            frame(0x5201) { u8(1, 1) } // Neither can a status-only resume.
            now += 500_000_000L
            if (!resumeWithoutTick) {
                sendPublished(CarPlayGlance.snapshot())
                assertEquals(LincolnClusterProtocol.STATE_OFF, states().last())
            }
            frame(0x5202) { u16(1, 7); u8(3, 2) }
            frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 200) }
            assertNotEquals(original, token(infos().last()))
            assertEquals(200, infos().last().getIntExtra("distance", -1))
        }
    }

    @Test fun noRouteResumeListAndDistanceCanArriveSeparatelyWithoutStatus() = withRouteClock {
        CarPlayGlance.setConnected(true)
        frame(0x5202) { u16(1, 7); u8(3, 2) }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 600) }
        val original = token(infos().last())
        frame(0x5201) { u8(1, 0) }
        frame(0x5201) { u16List(0x0d, listOf(7)) }
        assertTrue(CarPlayGlance.snapshot().routeEndPending)
        frame(0x5201) { u32(0x0a, 290) }
        assertFalse(CarPlayGlance.snapshot().routeEndPending)
        assertEquals(290, infos().last().getIntExtra("distance", -1))
        assertEquals(original, token(infos().last()))
    }

    @Test fun locatingAndUnknownStatesDoNotCreateOrRevokeANoRouteResume() = withRouteClock {
        CarPlayGlance.setConnected(true)
        frame(0x5202) { u16(1, 7); u8(3, 2) }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 600) }
        frame(0x5201) { u32(0x0a, 300) }
        val original = token(infos().last())
        frame(0x5201) { u8(1, 0) }
        frame(0x5201) { u8(1, 4) }
        frame(0x5201) { u32(0x0a, 290) }
        assertTrue(CarPlayGlance.snapshot().routeEndPending)
        assertEquals(300, infos().last().getIntExtra("distance", -1))

        frame(0x5201) { u8(1, 1) }
        frame(0x5201) { u8(1, 4) }
        frame(0x5201) { u8(1, 9) }
        frame(0x5201) { u32(0x0a, 280) }
        assertFalse(CarPlayGlance.snapshot().routeEndPending)
        assertEquals(280, infos().last().getIntExtra("distance", -1))
        assertEquals(original, token(infos().last()))
    }

    @Test fun locatingAndUnknownStatesDoNotRevokeARerouteResume() {
        CarPlayGlance.setConnected(true)
        frame(0x5202) { u16(1, 7); u8(3, 2) }
        frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 600) }
        val original = token(infos().last())
        frame(0x5201) { u8(1, 5) }
        frame(0x5201) { u8(1, 1) }
        frame(0x5201) { u8(1, 4) }
        frame(0x5201) { u8(1, 9) }
        frame(0x5201) { u32(0x0a, 200) }
        assertFalse(CarPlayGlance.snapshot().routeProgressPending)
        assertEquals(200, infos().last().getIntExtra("distance", -1))
        assertNotEquals(original, token(infos().last()))
    }

    @Test fun realManeuverRerouteArrivalAndDisconnectOverrideNoRouteGrace() = withRouteClock {
        for (boundary in listOf("maneuver", "reroute", "arrival", "disconnect")) {
            CarPlayGlance.setConnected(false)
            CarPlayGlance.setConnected(true)
            frame(0x5202) { u16(1, 7); u8(3, 2) }
            frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(7)); u32(0x0a, 600) }
            val original = token(infos().last())
            frame(0x5201) { u8(1, 0) }
            when (boundary) {
                "reroute" -> {
                    frame(0x5201) { u8(1, 5) }
                    frame(0x5201) { u16List(0x0d, listOf(7)); u32(0x0a, 100) }
                    assertTrue(CarPlayGlance.snapshot().routeEndPending)
                    assertEquals(original, token(infos().last()))
                    assertEquals(600, infos().last().getIntExtra("distance", -1))
                }
                "arrival" -> frame(0x5201) { u8(1, 2) }
                "disconnect" -> {
                    CarPlayGlance.setConnected(false)
                    CarPlayGlance.setConnected(true)
                }
            }
            val index = if (boundary == "maneuver") 8 else 7
            frame(0x5202) { u16(1, index); u8(3, 2) }
            frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(index)); u32(0x0a, 200) }
            assertNotEquals(boundary, original, token(infos().last()))
            assertEquals(200, infos().last().getIntExtra("distance", -1))
        }
    }

    private fun withRouteClock(test: () -> Unit) {
        val route = CarPlayGlance.javaClass.getDeclaredField("route").apply { isAccessible = true }
            .get(CarPlayGlance) as BydHudRouteState
        val clock = route.javaClass.getDeclaredField("nanoTime").apply { isAccessible = true }
        val original = clock.get(route)
        try {
            clock.set(route, { now })
            test()
        } finally {
            CarPlayGlance.setConnected(false)
            clock.set(route, original)
        }
    }

    private fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) =
        CarPlayGlance.onFrame(Iap2Messages.buildRaw(id, block))

    private var manualDistanceRevision = 0L

    private fun send(snapshot: CarPlayGlance.Snapshot) {
        LincolnClusterOutput.javaClass.getDeclaredMethod("onSnapshot", CarPlayGlance.Snapshot::class.java)
            .apply { isAccessible = true }
            .invoke(LincolnClusterOutput, snapshot.copy(distanceRevision = ++manualDistanceRevision))
    }

    private fun sendPublished(snapshot: CarPlayGlance.Snapshot) {
        LincolnClusterOutput.javaClass.getDeclaredMethod("onPublishedSnapshot", CarPlayGlance.Snapshot::class.java)
            .apply { isAccessible = true }
            .invoke(LincolnClusterOutput, snapshot)
    }

    private fun withBroadcastFailures(shouldFail: (Intent) -> Boolean) {
        LincolnClusterOutput.detach()
        LincolnClusterOutput.attach(object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun sendBroadcast(intent: Intent) {
                if (shouldFail(intent)) throw SecurityException("Injected broadcast rejection")
                super.sendBroadcast(intent)
            }
        })
        shadowOf(app).clearBroadcastIntents()
    }

    private fun guidance() = CarPlayGlance.Snapshot(
        connected = true, maneuverType = 11, routeActive = true, distanceMeters = 300,
        road = "Test Road",
    )

    private fun quiet() = CarPlayGlance.Snapshot(connected = true)

    private fun ended() = CarPlayGlance.Snapshot(connected = true, routeEnded = true)

    private fun states(): List<String> = shadowOf(app).broadcastIntents
        .filter { it.action == LincolnClusterProtocol.ACTION_NAV_STATE }
        .map { it.getStringExtra(LincolnClusterProtocol.EXTRA_STATE).orEmpty() }

    private fun infos(): List<Intent> = shadowOf(app).broadcastIntents
        .filter { it.action == LincolnClusterProtocol.ACTION_NAV_INFO }

    private fun token(intent: Intent): Pair<Double, Double> {
        // getDoubleExtra must really work; strings or floats silently fall back to zero in the APK.
        val latitude = intent.getDoubleExtra("latitude", 0.0)
        val longitude = intent.getDoubleExtra("longitude", 0.0)
        assertTrue(latitude in 1.0..2.0 && longitude in 1.0..2.0)
        return latitude to longitude
    }

    /**
     * Independent consumer contract from InstrumentPanel_Optional 1.0 runtime DEX:
     * INFO adapter at code_item 0x23aea0, NavPresenterImpl.sendNaviInfo at 0x223850.
     * Deliberately keeps its actual default=1 and lack of clamping. Do not "fix" this fixture
     * to match the sender: it reproduces the old ineffective-extra bug and validates our packets.
     */
    private class FactoryNavigationReceiver {
        private var latitude = 0.0
        private var longitude = 0.0
        private var initialDistance = 0

        fun receive(intent: Intent): Int {
            val distance = intent.getIntExtra("distance", 0)
            val nextLatitude = intent.getDoubleExtra("latitude", 0.0)
            val nextLongitude = intent.getDoubleExtra("longitude", 0.0)
            if (latitude != nextLatitude || longitude != nextLongitude) initialDistance = distance
            latitude = nextLatitude
            longitude = nextLongitude
            return if (initialDistance == 0) 1 else (distance.toFloat() / initialDistance * 256f).toInt()
        }
    }
}
