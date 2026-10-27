package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.WiredStartupTiming
import com.shilapi.xcertplay.orchestration.WiredStartupTiming.Stage.*
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], qualifiers = "zh-rCN")
class WiredStartupTimingLogTest {
    private lateinit var activity: CarPlayHostActivity
    private val log get() = File(activity.filesDir, "logs/diplay.log").readText()

    @Before fun setup() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        call("initializeSessionLog")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
    }

    @After fun cleanup() {
        (field("sessionLog") as Closeable).close()
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (field(name) as ExecutorService).shutdownNow()
        }
    }

    @Test fun hostStartsOnlyOneRecorderAndWirelessHasNone() {
        val a = call("prepareWiredStartupTiming") as WiredStartupTiming
        assertSame(a, call("prepareWiredStartupTiming"))
        set("wirelessEnabled", true)
        assertNull(call("prepareWiredStartupTiming"))
        a.mark(AIRPLAY_ACTIVE)
        set("wirelessEnabled", false)
        val b = call("prepareWiredStartupTiming") as WiredStartupTiming
        assertNotSame(a, b)
        b.mark(MAIN_FRAME_SUBMITTED)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertFalse(log.contains("stage=AIRPLAY_ACTIVE"))
        assertEquals(2, log.lineSequence().count { it.contains("stage=HOST_REQUESTED") })
    }

    @Test fun allMilestonesSurviveRedactionAndAreExportedWithoutDebugMode() {
        val trace = call("prepareWiredStartupTiming") as WiredStartupTiming
        for (stage in WiredStartupTiming.Stage.entries) trace.mark(stage)
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        for (stage in WiredStartupTiming.Stage.entries.takeWhile { it != FAILED }) {
            assertTrue("missing $stage", log.contains("stage=$stage "))
        }
        assertFalse(log.contains("stage=FAILED")) // first picture already completed timing
    }

    @Test fun stageLoggingReturnsWhileTheDiskWriterIsBlocked() {
        val trace = call("prepareWiredStartupTiming") as WiredStartupTiming
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val handle = field("sessionLog") as SessionDiagnosticLog
        val storage = requireNotNull(handle.storage)
        val lock = SessionLogFile::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(storage)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Thread { synchronized(lock) { entered.countDown(); release.await() } }.apply { start() }
        val done = CountDownLatch(1)
        val producer = Thread { trace.mark(CONTROLLER_START); trace.mark(MAIN_FRAME_SUBMITTED); done.countDown() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            producer.start()
            assertTrue("timing must not wait for storage", done.await(500, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            holder.join(2_000)
            producer.join(2_000)
        }
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertTrue(log.contains("stage=MAIN_FRAME_SUBMITTED"))
    }

    private fun call(name: String): Any? = CarPlayHostActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun field(name: String): Any? = CarPlayHostActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any) = CarPlayHostActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
}
