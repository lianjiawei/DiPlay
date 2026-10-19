package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import android.os.Looper
import android.os.Process
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowProcess

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33])
class CarPlayHostAsyncLogTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setup() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        call("initializeSessionLog")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
    }

    @After fun cleanup() {
        (field("sessionLog") as? Closeable)?.close()
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (field(name) as ExecutorService).shutdownNow()
        }
    }

    @Test(timeout = 8_000) fun aSlowLogCannotBlockTheHostLoggingCaller() {
        whileStorageIsBlocked {
            val returned = CountDownLatch(1)
            val caller = Thread {
                call("appendLog", "THEME_DIAGNOSTIC caller remains responsive")
                returned.countDown()
            }.apply { start() }
            try {
                assertTrue("Host logging must return while file storage remains locked",
                    returned.await(500, TimeUnit.MILLISECONDS))
            } finally { track(caller) }
        }
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertTrue(logFile().readText().contains("caller remains responsive"))
    }

    @Test(timeout = 8_000) fun closeReturnsWithoutDiskAndRetainsAnAlreadyAcceptedTail() {
        whileStorageIsBlocked {
            val accepted = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val caller = Thread {
                call("appendLog", "accepted tail before close")
                accepted.countDown()
                (field("sessionLog") as Closeable).close()
                closed.countDown()
            }.apply { start() }
            try {
                assertTrue("enqueue must not wait for disk", accepted.await(500, TimeUnit.MILLISECONDS))
                assertTrue("close must not wait for disk", closed.await(500, TimeUnit.MILLISECONDS))
            } finally { track(caller) }
        }
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertTrue(logFile().readText().contains("accepted tail before close"))
    }

    @Test(timeout = 8_000) fun explicitExitDrainsTheAcceptedTailBeforeKillingTheProcess() {
        ShadowProcess.clearKilledProcesses()
        val processId = Process.myPid()
        val teardownPosted = CountDownLatch(1)
        whileStorageIsBlocked {
            call("appendLog", "accepted before explicit exit")
            shutdown { teardownPosted.countDown() }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
            while (teardownPosted.count > 0 && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertEquals("teardown must reach the background drain", 0L, teardownPosted.count)
            assertFalse("process must not be killed before the pending tail drains", ShadowProcess.wasKilled(processId))
        }
        awaitKilled(processId)
        assertTrue(logFile().readText().contains("accepted before explicit exit"))
    }

    @Test(timeout = 8_000) fun explicitExitHasABoundedWaitEvenIfStorageNeverProgresses() {
        ShadowProcess.clearKilledProcesses()
        val processId = Process.myPid()
        whileStorageIsBlocked {
            call("appendLog", "pending during slow explicit exit")
            shutdown {}
            awaitKilled(processId)
        }
    }

    private fun shutdown(completion: () -> Unit) {
        CarPlayHostActivity::class.java.getDeclaredMethod("shutdown", Boolean::class.javaPrimitiveType,
            String::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            .invoke(activity, true, "test explicit exit", completion)
    }

    private fun awaitKilled(processId: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!ShadowProcess.wasKilled(processId) && System.nanoTime() < deadline) Thread.yield()
        assertTrue("the background exit must finish within its deadline", ShadowProcess.wasKilled(processId))
    }

    private val callers = mutableListOf<Thread>()
    private fun track(thread: Thread) { callers += thread }

    private fun whileStorageIsBlocked(test: () -> Unit) {
        val handle = requireNotNull(field("sessionLog"))
        val storage = if (handle is SessionLogFile) handle else
            handle.javaClass.getDeclaredField("storage").apply { isAccessible = true }.get(handle) as SessionLogFile
        val monitor = requireNotNull(SessionLogFile::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }.get(storage))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val locker = Thread {
            synchronized(monitor) { entered.countDown(); release.await() }
        }.apply { start() }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            test()
        } finally {
            release.countDown()
            locker.join(2_000)
            callers.forEach { it.join(2_000); assertFalse(it.isAlive) }
            callers.clear()
        }
    }

    private fun logFile() = File(activity.filesDir, "logs/diplay.log")
    private fun field(name: String) = CarPlayHostActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun call(name: String, message: String? = null) {
        if (message == null) CarPlayHostActivity::class.java.getDeclaredMethod(name)
            .apply { isAccessible = true }.invoke(activity)
        else CarPlayHostActivity::class.java.getDeclaredMethod(name, String::class.java)
            .apply { isAccessible = true }.invoke(activity, message)
    }
}
