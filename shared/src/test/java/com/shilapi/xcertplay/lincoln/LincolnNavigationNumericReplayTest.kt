package com.shilapi.xcertplay.lincoln

import android.app.Application
import android.content.Intent
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], qualifiers = "zh-rCN")
class LincolnNavigationNumericReplayTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var now = 1_000_000_000L
    private val hud = RecordingLincolnHudSocket()
    private val route = CarPlayGlance.javaClass.getDeclaredField("route").apply { isAccessible = true }.get(CarPlayGlance)
    private val routeClock = route.javaClass.getDeclaredField("nanoTime").apply { isAccessible = true }
    private val originalRouteClock = routeClock.get(route)

    @Before fun setup() {
        LincolnClusterOutput.detach()
        CarPlayGlance.setConnected(false)
        LincolnNavigationDiagnostics.clearFramesForTests()
        routeClock.set(route, { now })
        LincolnClusterOutput.nanoTime = { now }
        LincolnClusterOutput.hudSocket = hud
        LincolnClusterSettings.setClusterPackage(app, LincolnClusterProtocol.DEFAULT_CLUSTER_PACKAGE)
        LincolnClusterSettings.setNavigation(app, true)
        LincolnClusterSettings.setMusic(app, false)
        LincolnClusterSettings.setRemaining(app, false)
    }

    @After fun cleanup() {
        LincolnClusterOutput.detach()
        CarPlayGlance.setConnected(false)
        routeClock.set(route, originalRouteClock)
        LincolnClusterOutput.nanoTime = System::nanoTime
        LincolnClusterOutput.hudSocket = LincolnHudSocketServer
        LincolnClusterSettings.setNavigation(app, false)
        LincolnClusterSettings.setEnhancedNavigation(app, false)
    }

    @Test fun numericReplayMatchesBaselineForBothCompatibilityModes() {
        for (enhanced in listOf(false, true)) for (events in listOf(syntheticEvents(), indexFirstAndRerouteEvents())) {
            val captured = trace(events)
            assertFalse(captured.contains("private destination"))
            val replay = LincolnNavigationNumericReplay.decode(captured)
            assertEquals(1, replay.warnings.size) // Always partial: no names, audio or exact scheduler timing.
            val baseline = run(events, enhanced, capture = false)
            val observed = run(events, enhanced, capture = true)
            val replayed = run(replay.events, enhanced, capture = false)
            assertEquals("diagnostics must not alter output", baseline, observed)
            assertEquals("numeric turn/distance/identity progression", baseline, replayed)
            assertTrue(baseline.any { it.startsWith("nav:") })
        }
    }

    @Test fun replayClearsOnlyStaleDestinationThenResumesNormalGuidance() {
        var sequence = 0L
        fun e(ms: Long, frame: Iap2Frame? = null) =
            LincolnNavigationNumericReplay.Event(++sequence, ms, 1, true, frame)
        fun maneuver(idx: Int, type: Int) = Iap2Messages.buildRaw(0x5202) { u16(1, idx); u8(3, type) }
        fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
            Iap2Messages.buildRaw(0x5201, block)
        val events = listOf(
            e(1000), e(1010, maneuver(7, 24)),
            e(1020, update { u8(1, 1); u16List(13, listOf(7)); u32(10, 400) }),
            e(2020, update { u16List(13, emptyList()) }),
            e(3020, update { u16List(13, listOf(8)) }),
            e(4020, maneuver(8, 2)),
            e(5020, update { u32(10, 200) }),
        )
        val replay = LincolnNavigationNumericReplay.decode(trace(events))
        for (enhanced in listOf(false, true)) {
            val output = run(replay.events, enhanced, capture = false)
            val first = output.indexOfFirst { it.startsWith("nav:turn_dest:") }
            val off = output.indexOf("state:${LincolnClusterProtocol.STATE_OFF}")
            val next = output.indexOfFirst { it.startsWith("nav:turn_right:") }
            assertTrue("destination must precede clear: $output", first >= 0 && off > first && next > off)
            assertTrue("new road starts at the existing distance: $output", output[next].contains(":400:"))
            assertTrue("new road progress falls with the next distance: $output",
                output.any { it.startsWith("nav:turn_right:200:") && it.endsWith("progress=128") })
        }
    }

    @Test fun fallingCorrectionResumesWithoutResettingTheProgressIdentity() {
        var sequence = 0L
        fun event(ms: Long, frame: Iap2Frame? = null) =
            LincolnNavigationNumericReplay.Event(++sequence, ms, 1, true, frame)
        fun update(distance: Int) = Iap2Messages.buildRaw(0x5201) { u32(10, distance) }
        val events = listOf(
            event(1000),
            event(1010, Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) }),
            event(1020, Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)); u32(10, 900) }),
            event(2020, update(800)), event(3020, update(820)), event(4020, update(818)),
            event(5020, update(816)), event(6020, update(814)), event(7020, update(812)),
            event(8020, update(810)), event(9020, update(790)),
        )
        for (enhanced in listOf(false, true)) {
            val output = run(LincolnNavigationNumericReplay.decode(trace(events)).events, enhanced, capture = false)
            assertTrue("expected held 800 m under original identity: $output",
                output.any { it.startsWith("nav:turn_right:800:identity=1:") })
            assertFalse("falling correction should not reset before returning below the minimum: $output",
                output.any { it.startsWith("nav:turn_right:810:identity=2:") })
            assertTrue("normal progress resumes under the original identity: $output",
                output.any { it.startsWith("nav:turn_right:790:identity=1:") })
        }
    }

    @Test fun crossingFiveHundredMetersAloneDoesNotResetTheProgressIdentity() {
        var sequence = 0L
        fun event(ms: Long, frame: Iap2Frame? = null) =
            LincolnNavigationNumericReplay.Event(++sequence, ms, 1, true, frame)
        val events = mutableListOf(
            event(1000),
            event(1010, Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) }),
            event(1020, Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)); u32(10, 900) }),
        )
        listOf(800, 600, 501, 500, 499, 483, 482, 400).forEachIndexed { i, distance ->
            events += event(2020L + i * 1000L, Iap2Messages.buildRaw(0x5201) { u32(10, distance) })
        }
        for (enhanced in listOf(false, true)) {
            val nav = run(LincolnNavigationNumericReplay.decode(trace(events)).events, enhanced, capture = false)
                .filter { it.startsWith("nav:") }
            assertTrue(nav.isNotEmpty())
            assertTrue("all decreasing samples must use the same identity: $nav", nav.all { it.contains(":identity=1:") })
            assertTrue(nav.any { it.startsWith("nav:turn_right:482:") })
        }
    }

    @Test fun remainingDistanceUpdatesDoNotResendIdenticalGuidanceWithinOneSecond() {
        var sequence = 0L
        fun event(ms: Long, frame: Iap2Frame? = null) =
            LincolnNavigationNumericReplay.Event(++sequence, ms, 1, true, frame)
        val events = listOf(
            event(1000),
            event(1010, Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) }),
            event(1020, Iap2Messages.buildRaw(0x5201) {
                u8(1, 1); u16List(13, listOf(7)); u32(10, 800); u32(7, 3000)
            }),
            event(1120, Iap2Messages.buildRaw(0x5201) { u32(7, 2999) }),
            event(1220, Iap2Messages.buildRaw(0x5201) { u32(7, 2998) }),
            event(1320, Iap2Messages.buildRaw(0x5201) { u32(7, 2997) }),
        )
        val nav = run(LincolnNavigationNumericReplay.decode(trace(events)).events,
            enhanced = false, capture = false).filter { it.startsWith("nav:") }
        assertEquals("unchanged guidance is not resent in response to remaining distance alone: $nav", 1, nav.size)
    }

    @Test fun transientZeroThenOrdinaryDistancePreservesProgressAndTheBaseline() {
        var sequence = 0L
        fun e(ms: Long, frame: Iap2Frame? = null) =
            LincolnNavigationNumericReplay.Event(++sequence, ms, 1, true, frame)
        fun distance(meters: Int) = Iap2Messages.buildRaw(0x5201) { u32(10, meters) }
        val events = listOf(
            e(1000), e(1010, Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) }),
            e(1020, Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)); u32(10, 900) }),
            e(2020, distance(800)), e(3020, distance(0)),
            e(4020, distance(780)), e(5020, distance(760)), e(6020, distance(740)),
        )
        val output = run(LincolnNavigationNumericReplay.decode(trace(events)).events, enhanced = false, capture = false)
        assertTrue("valid starting progress must be nonzero: $output",
            output.any { it.startsWith("nav:turn_right:800:identity=1:progress=227") })
        assertFalse("one suspect zero must not reach the receiver: $output",
            output.any { it.startsWith("nav:turn_right:0:") })
        assertTrue("ordinary descending distance resumes using the prior identity: $output",
            output.any { it.startsWith("nav:turn_right:760:identity=1:") })
        assertTrue("progress continues to advance: $output",
            output.any { it.startsWith("nav:turn_right:740:identity=1:") })
    }

    @Test fun missingInitialDistanceIsIndistinguishableFromDefaultZeroInCurrentOutput() {
        var sequence = 0L
        fun e(ms: Long, frame: Iap2Frame? = null) =
            LincolnNavigationNumericReplay.Event(++sequence, ms, 1, true, frame)
        val events = listOf(
            e(1000), e(1010, Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2) }),
            e(1020, Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)) }),
            e(2020, Iap2Messages.buildRaw(0x5201) { u32(10, 800) }),
        )
        val output = run(LincolnNavigationNumericReplay.decode(trace(events)).events, enhanced = false, capture = false)
        assertTrue("no distance field still produces default zero: $output",
            output.any { it.startsWith("nav:turn_right:0:identity=1:progress=1") })
        assertTrue("first actual distance starts a new baseline: $output",
            output.any { it.startsWith("nav:turn_right:800:identity=2:progress=256") })
    }

    @Test fun missingOrMalformedFramesAreReportedNotSilentlyReplayed() {
        val gap = LincolnNavigationFrameTrace({ 1_000_000_000L }, capacity = 2)
        repeat(3) { gap.connection(1, it % 2 == 0, 0, 0) }
        assertThrows(IllegalStateException::class.java) { LincolnNavigationNumericReplay.decode(gap.report()) }
        val incomplete = LincolnNavigationNumericReplay.decode(gap.report(), allowIncomplete = true)
        assertTrue(incomplete.warnings.any { it.contains("missing") })
        assertTrue(incomplete.warnings.any { it.contains("gap") })
        val invalid = LincolnNavigationFrameTrace({ 1_000_000_000L })
        invalid.connection(1, true, 0, 0)
        invalid.record(0x5201, byteArrayOf(0, 3, 0, 10), 1, true, 0, 0)
        assertThrows(IllegalStateException::class.java) { LincolnNavigationNumericReplay.decode(invalid.report()) }
        assertTrue(LincolnNavigationNumericReplay.decode(invalid.report(), allowIncomplete = true)
            .warnings.any { it.contains("cannot be faithfully reconstructed") })
    }

    @Test fun frameSectionCanBeExtractedFromALargeDiagnosticReport() {
        val events = syntheticEvents()
        val file = File.createTempFile("diplay-replay-test", ".txt")
        try {
            file.writeText("private unrelated text\n".repeat(70_000) + trace(events) + "\n\nother logs\n")
            assertTrue(file.length() > 1_000_000)
            val decoded = LincolnNavigationNumericReplay.decodeFile(file)
            assertEquals(events.size, decoded.events.size)
            assertEquals(1, decoded.warnings.size)
        } finally { file.delete() }
    }

    @Test fun liveGlanceTraceRecordsSeparateFramesAndOmitsStrings() {
        LincolnClusterOutput.attach(app)
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(Iap2Messages.buildRaw(0x5202) { u16(1, 54321); u8(3, 2); string(4, "private-live-road") })
        CarPlayGlance.onFrame(Iap2Messages.buildRaw(0x5201) { u32(10, 1234567) })
        CarPlayGlance.onFrame(Iap2Messages.buildRaw(0x5201) { u16List(13, listOf(54321)) })
        val report = LincolnNavigationDiagnostics.report()
        val frames = report.lines().filter { it.startsWith("frame ") }
        assertTrue(frames.any { it.contains("distance=1234567") && it.contains("indices=-") })
        assertTrue(frames.any { it.contains("distance=-") && it.contains("indices=54321") })
        assertFalse(report.contains("private-live-road"))
    }

    @Test fun replaysOptionalLocalDiagnosticReport() {
        val reportPath = System.getenv("DIPLAY_NAV_REPLAY_REPORT")
        assumeTrue("Set DIPLAY_NAV_REPLAY_REPORT to replay a local report", !reportPath.isNullOrBlank())
        val trace = LincolnNavigationNumericReplay.decodeFile(File(reportPath!!), allowIncomplete = true)
        require(trace.events.isNotEmpty()) { "report contains no replayable navigation events" }
        val output = run(trace.events, enhanced = false, capture = false)
        println("Numeric replay is partial: ${trace.warnings.joinToString(" ")}")
        println("Replayed events=${trace.events.size}; sanitized output=${output.joinToString(";")}")
    }

    private fun syntheticEvents(): List<LincolnNavigationNumericReplay.Event> {
        var seq = 0L
        fun event(ms: Long, connected: Boolean = true, frame: Iap2Frame? = null) =
            LincolnNavigationNumericReplay.Event(++seq, ms, 1, connected, frame)
        fun maneuver(idx: Int, type: Int) = Iap2Messages.buildRaw(0x5202) {
            u16(1, idx); u8(3, type); string(4, "private destination")
        }
        fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) = Iap2Messages.buildRaw(0x5201, block)
        return listOf(
            event(1000), event(1010, frame = maneuver(7, 1)),
            event(1020, frame = update { u8(1, 1); u16List(13, listOf(7)); u32(10, 400) }),
            event(2020, frame = update { u32(10, 380) }),
            event(3020, frame = update { u32(10, 382) }),
            event(4020, frame = update { u16List(13, emptyList()) }),
            event(5020, frame = maneuver(8, 2)),
            event(5030, frame = update { u32(10, 150) }),
            event(5040, frame = update { u16List(13, listOf(8)) }),
            event(6040, frame = update { u32(10, 130) }),
            event(7040, frame = maneuver(9, 24)),
            event(7050, frame = update { u16List(13, listOf(9)); u32(10, 100) }),
            event(8050, frame = update { u8(1, 2) }),
            event(11050, frame = update { u8(1, 0) }),
            event(12050, connected = false),
        )
    }

    private fun indexFirstAndRerouteEvents(): List<LincolnNavigationNumericReplay.Event> {
        var seq = 0L
        fun e(ms: Long, frame: Iap2Frame? = null) = LincolnNavigationNumericReplay.Event(++seq, ms, 1, true, frame)
        fun maneuver(idx: Int, type: Int) = Iap2Messages.buildRaw(0x5202) { u16(1, idx); u8(3, type) }
        fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) = Iap2Messages.buildRaw(0x5201, block)
        return listOf(
            e(1000), e(1010, maneuver(1, 2)), e(1020, update { u8(1, 1); u16List(13, listOf(1)); u32(10, 400) }),
            e(2020, maneuver(2, 6)), e(2030, update { u16List(13, listOf(2)) }),
            e(3030, update { u32(10, 150) }), e(4030, update { u8(1, 5); u16List(13, emptyList()) }),
            e(5030, update { u8(1, 1) }), e(6030, update { u16List(13, listOf(2)); u32(10, 140) }),
            e(7030, update { u8(1, 0) }), e(8030, update { u8(1, 1); u16List(13, listOf(2)); u32(10, 100) }),
            e(9030, update { u32(10, 0) }),
        )
    }

    private fun trace(events: List<LincolnNavigationNumericReplay.Event>): String {
        var clock = 0L
        val trace = LincolnNavigationFrameTrace({ clock })
        events.forEach { event ->
            clock = event.timeMs * 1_000_000
            if (event.frame == null) trace.connection(event.session, event.connected, 0, 0)
            else trace.record(event.frame.messageId, event.frame.payload, event.session, event.connected, 0, 0)
        }
        return trace.report()
    }

    private fun run(events: List<LincolnNavigationNumericReplay.Event>, enhanced: Boolean, capture: Boolean): List<String> {
        LincolnClusterOutput.detach()
        CarPlayGlance.setConnected(false)
        now = 1_000_000_000L
        hud.turns.clear()
        LincolnClusterSettings.setEnhancedNavigation(app, enhanced)
        LincolnClusterOutput.attach(app)
        LincolnNavigationDiagnostics.enableFrameCapture(capture)
        shadowOf(app).clearBroadcastIntents()
        val firstMs = events.firstOrNull()?.timeMs ?: run { LincolnClusterOutput.detach(); return emptyList() }
        events.forEach { event ->
            val elapsed = Math.subtractExact(event.timeMs, firstMs)
            require(elapsed in 0..604_800_000L) { "replay duration is negative or exceeds seven days" }
            now = 1_000_000_000L + elapsed * 1_000_000
            if (event.frame == null) CarPlayGlance.setConnected(event.connected)
            else {
                if (CarPlayGlance.snapshot().connected != event.connected) CarPlayGlance.setConnected(event.connected)
                CarPlayGlance.onFrame(event.frame)
            }
        }
        val ids = mutableMapOf<Pair<Double, Double>, Int>()
        var baseline = 0
        var previous: Pair<Double, Double>? = null
        val output = mutableListOf<String>()
        shadowOf(app).broadcastIntents.forEach { intent: Intent ->
            when (intent.action) {
                LincolnClusterProtocol.ACTION_NAV_STATE -> output += "state:${intent.getStringExtra("state")}"
                LincolnClusterProtocol.ACTION_NAV_INFO -> {
                    val key = intent.getDoubleExtra("latitude", 0.0) to intent.getDoubleExtra("longitude", 0.0)
                    val distance = intent.getIntExtra("distance", -1)
                    if (key != previous) baseline = distance
                    previous = key
                    val ordinal = ids.getOrPut(key) { ids.size + 1 }
                    val progress = if (baseline == 0) 1 else (distance.toFloat() / baseline * 256f).toInt()
                    output += "nav:${intent.getStringExtra("name")}:$distance:identity=$ordinal:progress=$progress"
                }
            }
        }
        if (enhanced) output += hud.turns.map { "hud:${it.index}:${it.metres}" }
        LincolnClusterOutput.detach()
        return output
    }
}
