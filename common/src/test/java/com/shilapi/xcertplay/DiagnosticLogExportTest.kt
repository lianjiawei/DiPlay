package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.lincoln.LincolnClusterOutput
import com.shilapi.xcertplay.lincoln.LincolnClusterSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33])
class DiagnosticLogExportTest {
    @Test fun aReportIncludesSanitizedNavigationFrames() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        LincolnClusterOutput.detach()
        CarPlayGlance.setConnected(false)
        LincolnClusterSettings.setNavigation(activity, true)
        try {
            LincolnClusterOutput.attach(activity)
            CarPlayGlance.setConnected(true)
            CarPlayGlance.onFrame(Iap2Messages.buildRaw(0x5202) {
                u16(1, 45789); u8(3, 2); string(4, "private-test-street")
            })
            val report = report(activity)
            assertTrue(report.contains("Lincoln navigation frames v1"))
            val frame = report.lineSequence().first { it.startsWith("frame ") && it.contains("maneuverIndex=45789") }
            assertTrue(frame.contains("maneuverType=2"))
            assertFalse(frame.contains("private-test-street"))
        } finally {
            LincolnClusterOutput.detach()
            CarPlayGlance.setConnected(false)
            LincolnClusterSettings.setNavigation(activity, false)
        }
    }
    @Test(timeout = 8_000) fun aReportIncludesRecordsAcceptedBeforeExport() = export(blockUntilTimeout = false)
    @Test(timeout = 8_000) fun aReportSaysWhenTheBoundedFlushTimesOutWithoutCancellingTheTail() = export(blockUntilTimeout = true)

    private fun export(blockUntilTimeout: Boolean) {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val file = File(activity.filesDir, "logs/diplay.log")
        val session = AsyncDiagnosticLog.open(file, "export session")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        val storage = requireNotNull(session.storage)
        val monitor = requireNotNull(SessionLogFile::class.java.getDeclaredField("lock")
            .apply { isAccessible = true }.get(storage))
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val text = AtomicReference<String>()
        val error = AtomicReference<Throwable>()
        val message = "accepted before export ${System.nanoTime()}"
        val locker = Thread { synchronized(monitor) { locked.countDown(); release.await() } }
        val exporter = Thread {
            try { text.set(report(activity)) } catch (failure: Throwable) { error.set(failure) }
            finally { completed.countDown() }
        }
        try {
            locker.start()
            assertTrue(locked.await(2, TimeUnit.SECONDS))
            session.append(message)
            exporter.start()
            if (!blockUntilTimeout) release.countDown()
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            error.get()?.let { throw AssertionError(it) }
            assertTrue(text.get(), text.get().contains("pendingCompleted=${!blockUntilTimeout}"))
            assertEquals(!blockUntilTimeout, text.get().contains(message))
            release.countDown()
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            assertTrue(report(activity).contains(message))
        } finally {
            release.countDown()
            locker.join(2_000); exporter.join(3_000)
            session.close()
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        }
    }

    private fun report(activity: DiPlayActivity) = DiPlayActivity::class.java
        .getDeclaredMethod("diagnosticReportText", Context::class.java).apply { isAccessible = true }
        .invoke(activity, activity.applicationContext) as String
}
