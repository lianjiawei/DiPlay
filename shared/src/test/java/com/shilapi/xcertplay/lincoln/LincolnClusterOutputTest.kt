package com.shilapi.xcertplay.lincoln

import android.app.Application
import android.content.Intent
import com.shilapi.xcertplay.glance.CarPlayGlance
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Before fun setUp() {
        LincolnClusterSettings.setNavigation(app, true)
        LincolnClusterSettings.setMusic(app, false)
        LincolnClusterSettings.setRemaining(app, false)
        LincolnClusterOutput.nanoTime = { now }
        CarPlayGlance.setConnected(false)
        LincolnClusterOutput.attach(app)
        shadowOf(app).clearBroadcastIntents()
    }

    @After fun tearDown() {
        LincolnClusterOutput.detach()
        LincolnClusterSettings.setNavigation(app, false)
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

    @Test fun sendsTheBoundedRoadNameAndKeepsItDuringQuietGuidance() {
        val expected = "中".repeat(18)
        val road = expected + "𠀀路终点"
        send(guidance().copy(road = road))
        assertEquals(expected, infos().last().getStringExtra(LincolnClusterProtocol.EXTRA_ROAD))

        now += 1_000_000_000L
        send(quiet())
        assertEquals(expected, infos().last().getStringExtra(LincolnClusterProtocol.EXTRA_ROAD))
    }

    @Test fun sendsAndMaintainsFactoryPerManeuverBargraphSteps() {
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 400))
        assertEquals("256", infos().last()
            .getStringExtra(LincolnClusterProtocol.EXTRA_BARGRAPH_STEPS))

        now += 1_000_000_000L
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 200))
        assertEquals("128", infos().last()
            .getStringExtra(LincolnClusterProtocol.EXTRA_BARGRAPH_STEPS))

        // A temporary empty list must keep both the card and its last progress value.
        now += 1_000_000_000L
        send(quiet())
        assertEquals("128", infos().last()
            .getStringExtra(LincolnClusterProtocol.EXTRA_BARGRAPH_STEPS))

        // The next iAP2 maneuver gets a fresh full-scale bar even when its distance is larger.
        now += 1_000_000_000L
        send(guidance().copy(maneuverIndex = 8, distanceMeters = 600, road = "Next Road"))
        assertEquals("256", infos().last()
            .getStringExtra(LincolnClusterProtocol.EXTRA_BARGRAPH_STEPS))
    }

    @Test fun neverBroadcastsANegativeManeuverDistance() {
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 400))
        now += 1_000_000_000L
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 200))
        assertEquals("128", infos().last()
            .getStringExtra(LincolnClusterProtocol.EXTRA_BARGRAPH_STEPS))

        send(guidance().copy(maneuverIndex = 7, distanceMeters = -1))
        assertEquals("A bad sample must not be broadcast", 2, infos().size)

        // Rejecting a bad sample must not poison the next valid maneuver or its progress baseline.
        send(guidance().copy(maneuverIndex = 7, distanceMeters = 300))
        assertEquals(300, infos().last()
            .getIntExtra(LincolnClusterProtocol.EXTRA_DISTANCE, -1))
        assertEquals("256", infos().last()
            .getStringExtra(LincolnClusterProtocol.EXTRA_BARGRAPH_STEPS))
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

    private fun send(snapshot: CarPlayGlance.Snapshot) {
        LincolnClusterOutput.javaClass.getDeclaredMethod("onSnapshot", CarPlayGlance.Snapshot::class.java)
            .apply { isAccessible = true }
            .invoke(LincolnClusterOutput, snapshot)
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
}
