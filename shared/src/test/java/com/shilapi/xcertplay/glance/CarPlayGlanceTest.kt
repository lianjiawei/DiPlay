package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.hud.BydHudRouteState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CarPlayGlanceTest {
    private fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) = Iap2Messages.buildRaw(id, block)

    @Before
    fun reset() {
        CarPlayGlance.listener = null
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @After
    fun clear() = reset()

    @Test
    fun followsTheNextTurnAndTheSong() {
        val seen = mutableListOf<CarPlayGlance.Snapshot>()
        CarPlayGlance.listener = { seen += it }
        CarPlayGlance.setConnected(true)

        // Maneuver 7 is a right turn onto Main Street; the route update makes it the next one.
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Main Street") })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 350); u16List(0x0d, listOf(7)) })
        CarPlayGlance.onFrame(frame(0x5001) {
            group(0) { string(1, "Numb"); string(12, "Linkin Park") }
            group(1) { u8(0, 1) }
        })

        val now = CarPlayGlance.snapshot()
        assertTrue(now.connected)
        assertEquals(2, now.maneuverType)
        assertEquals(7, now.maneuverIndex)
        assertEquals(350, now.distanceMeters)
        assertEquals("Main Street", now.road)
        assertEquals("Numb — Linkin Park", now.song)
        assertTrue(now.playing)
        assertEquals(now, seen.last())
    }

    @Test
    fun routeEndAndSessionEnd() {
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 1) })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 90); u16List(0x0d, listOf(1)) })
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "Podcast") } })

        // Arrived: no turn any more, the song stays.
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 2) })
        assertNull(CarPlayGlance.snapshot().maneuverType)
        assertEquals("Podcast", CarPlayGlance.snapshot().song)

        CarPlayGlance.setConnected(false)
        assertEquals(CarPlayGlance.Snapshot(), CarPlayGlance.snapshot())
    }

    @Test
    fun calculationNotifiesConsumersEvenWhenAllVisibleGuidanceIsUnchanged() {
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 7); u8(3, 2) })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 200); u16List(0x0d, listOf(7)) })
        val before = CarPlayGlance.snapshot()
        val seen = mutableListOf<CarPlayGlance.Snapshot>()
        CarPlayGlance.listener = { seen += it }
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 5) })
        assertEquals(before.copy(routeProgressGeneration = before.routeProgressGeneration + 1,
            routeProgressPending = true), seen.single())
        // Repeats do not emit a new generation or clear the pending condition.
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 5) })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1) })
        assertEquals(1, seen.size)
        CarPlayGlance.onFrame(frame(0x5201) { u32(0x0a, 200) })
        assertEquals(before.copy(routeProgressGeneration = before.routeProgressGeneration + 1,
            distanceRevision = before.distanceRevision + 1), seen.last())
        assertEquals(2, seen.size)
    }

    @Test
    fun expiredGuidanceClearsWithoutAnotherFrameAndNotifiesTheWidget() {
        withRouteClock { advance ->
            CarPlayGlance.setConnected(true)
            CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 2) })
            CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(1)) })
            assertEquals(2, CarPlayGlance.snapshot().maneuverType)
            val seen = mutableListOf<CarPlayGlance.Snapshot>()
            CarPlayGlance.listener = { seen += it }
            advance(30_000_000_000L)
            assertNull(CarPlayGlance.snapshot().maneuverType)
            assertNull(seen.single().maneuverType)
            assertTrue(CarPlayGlance.snapshot().connected)
        }
    }

    @Test
    fun aQuietRouteIsNotAnEndedRoute() {
        withRouteClock { advance ->
            CarPlayGlance.setConnected(true)
            CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 2) })
            CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(1)) })
            assertEquals(false, CarPlayGlance.snapshot().routeEnded)

            // Parked at a light: no new frame for a minute. The widget may hide the turn, but the
            // cluster must not be told the route ended.
            advance(60_000_000_000L)
            assertNull(CarPlayGlance.snapshot().maneuverType)
            assertEquals(false, CarPlayGlance.snapshot().routeEnded)

            // Arriving does state it.
            CarPlayGlance.onFrame(frame(0x5201) { u8(1, 2) })
            assertEquals(true, CarPlayGlance.snapshot().routeEnded)
        }
    }

    @Test
    fun aPersistentlyEmptyManeuverListExpiresAfterTheGracePeriod() {
        withRouteClock { advance ->
            CarPlayGlance.setConnected(true)
            CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 2) })
            CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(1)) })
            CarPlayGlance.onFrame(frame(0x5201) { u16List(0x0d, emptyList()) })
            advance(2_000_000_000L)
            assertEquals(2, CarPlayGlance.snapshot().maneuverType)
            advance(1_000_000_000L)
            assertNull(CarPlayGlance.snapshot().maneuverType)
        }
    }

    @Test
    fun anEmptySongTitleClearsTheWidgetSong() {
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "Previous song") } })
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "") } })
        assertNull(CarPlayGlance.snapshot().song)
    }

    // Inject the route parser's monotonic clock rather than waiting 30 seconds in each test.
    private fun withRouteClock(test: ((Long) -> Unit) -> Unit) {
        val route = CarPlayGlance.javaClass.getDeclaredField("route").apply { isAccessible = true }
            .get(CarPlayGlance) as BydHudRouteState
        val clock = route.javaClass.getDeclaredField("nanoTime").apply { isAccessible = true }
        val original = clock.get(route)
        var now = 1_000_000_000L
        try {
            clock.set(route, { now })
            test { now += it }
        } finally {
            clock.set(route, original)
        }
    }

    @Test
    fun otherMessagesChangeNothing() {
        CarPlayGlance.setConnected(true)
        val before = CarPlayGlance.snapshot()
        var calls = 0
        CarPlayGlance.listener = { calls++ }

        CarPlayGlance.onFrame(frame(0x4E09) { u8(1, 1) })

        assertEquals(before, CarPlayGlance.snapshot())
        assertEquals(0, calls)
    }

    @Test
    fun twoConsumersBothReceiveUpdates() {
        // The home-screen widget and the Lincoln cluster output both follow the glance; a single
        // listener slot let the later attach replace the earlier one.
        val first = mutableListOf<CarPlayGlance.Snapshot>()
        val second = mutableListOf<CarPlayGlance.Snapshot>()
        val a: (CarPlayGlance.Snapshot) -> Unit = { first += it }
        val b: (CarPlayGlance.Snapshot) -> Unit = { second += it }
        CarPlayGlance.addListener(a)
        CarPlayGlance.addListener(b)

        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 2) })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(1)) })

        assertTrue(first.isNotEmpty())
        assertTrue(second.isNotEmpty())
        assertEquals(first.last(), second.last())

        CarPlayGlance.removeListener(b)
        val beforeSecond = second.size
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "Song") } })
        assertTrue(first.size > 1)
        assertEquals(beforeSecond, second.size)
    }
}
