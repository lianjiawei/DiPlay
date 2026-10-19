package com.shilapi.xcertplay

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Answers
import org.mockito.Mockito.mock

class SessionDiagnosticLogWriterTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "diplay.log")

    @Test(timeout = 8_000) fun initializationAppendAndCloseDoNotWaitForBlockedStorage() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        val writer = SessionDiagnosticLogWriter(storageFactory = { path ->
            assertNotSame(caller, Thread.currentThread())
            storage(path, beforeReset = { entered.countDown(); release.await() })
        })
        try {
            val session = writer.open(file, "session header")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(session.append("accepted tail"))
            session.close()
            assertFalse(session.append("after close"))
            assertFalse(file.exists())
            release.countDown()
            assertTrue(writer.flush(2_000))
            assertTrue(file.readText().startsWith("session header\n"))
            assertTrue(file.readText().contains("accepted tail"))
            assertFalse(file.readText().contains("after close"))
        } finally { release.countDown(); writer.close(); assertTrue(writer.awaitIdle(2_000)) }
    }

    @Test(timeout = 8_000) fun aNewSessionArchivesTheAcceptedOldTailAndRejectsLateOldCallbacks() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = SessionDiagnosticLogWriter(storageFactory = { path ->
            storage(path, beforeAppend = { line ->
                if (line.endsWith("old blocked")) { entered.countDown(); release.await() }
            })
        })
        try {
            val old = writer.open(file, "old session")
            old.append("old blocked")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            old.append("accepted old tail")
            val next = writer.open(file, "new session")
            next.append("new data")
            assertFalse(old.append("late old callback"))
            old.finish("late old destroy")
            assertTrue(next.append("new tail"))
            next.finish("new destroy")
            release.countDown()
            assertTrue(writer.flush(2_000))
            val archived = File(folder.root, "previous.log").readText()
            assertTrue(archived.startsWith("old session\n"))
            assertTrue(archived.contains("accepted old tail"))
            assertFalse(archived.contains("new data"))
            val current = file.readText()
            assertTrue(current.startsWith("new session\n"))
            assertTrue(current.contains("new tail"))
            assertTrue(current.trimEnd().endsWith("new destroy"))
            assertFalse(current.contains("old"))
        } finally { release.countDown(); writer.close(); assertTrue(writer.awaitIdle(2_000)) }
    }

    @Test(timeout = 8_000) fun queuePressureCannotLoseTheNewSessionsInitializationOrItsLatestLines() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resets = AtomicInteger()
        val writer = SessionDiagnosticLogWriter(maxPending = 2, storageFactory = { path ->
            storage(path, beforeReset = {
                if (resets.incrementAndGet() == 1) { entered.countDown(); release.await() }
            })
        })
        try {
            writer.open(file, "old header")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val next = writer.open(file, "new header")
            repeat(10) { next.append("line $it") }
            next.close()
            assertEquals(2, writer.pendingCount)
            assertEquals(9L, writer.droppedCount)
            release.countDown()
            assertTrue(writer.flush(2_000))
            val current = file.readText()
            assertTrue(current.startsWith("new header\n"))
            assertEquals(1, current.lineSequence().count { it == "new header" })
            assertTrue(current.contains("line 8")); assertTrue(current.contains("line 9"))
            assertFalse(current.contains("line 0"))
            assertTrue(File(folder.root, "previous.log").readText().contains("discardedPendingEntries=9"))
            assertTrue(writer.status().contains("discardedPendingEntries=9"))
        } finally { release.countDown(); writer.close(); assertTrue(writer.awaitIdle(2_000)) }
    }

    @Test fun queuedSessionsKeepTheExistingEightFileHistoryAndRedaction() {
        val writer = SessionDiagnosticLogWriter()
        try {
            repeat(10) { i ->
                val session = writer.open(file, "session=$i")
                session.append(if (i == 3) "Wi-Fi P2P create rejected code=0" else "connected")
                assertFalse(session.append("password=secret"))
                session.finish("session ended")
            }
            assertTrue(writer.flush(3_000))
            val history = SessionLogFile.REPORT_NAMES.map { File(folder.root, it).readText() }
            assertEquals(8, folder.root.listFiles()!!.size)
            assertTrue(history.first().startsWith("session=2\n"))
            assertTrue(history.last().startsWith("session=9\n"))
            assertTrue(history.any { it.contains("rejected code=0") })
            assertTrue(history.all { it.trimEnd().endsWith("session ended") })
            assertFalse(history.any { it.contains("secret") })
        } finally { writer.close(); assertTrue(writer.awaitIdle(2_000)) }
    }

    @Test fun sizeRotationStillKeepsTheTailAndBoundedFiles() {
        val writer = SessionDiagnosticLogWriter(maxPending = 2_048)
        try {
            val session = writer.open(file, "started")
            repeat(1_600) { session.append("connection state " + "x".repeat(690)) }
            session.finish("CarPlay connected")
            assertTrue(writer.flush(5_000))
            assertEquals(0L, writer.droppedCount)
            assertTrue(file.length() <= SessionLogFile.MAX_BYTES + 701)
            assertTrue(File(folder.root, "previous.log").length() <= SessionLogFile.MAX_BYTES + 701)
            assertTrue(file.readText().trimEnd().endsWith("CarPlay connected"))
            assertTrue(file.readLines().all { it.length <= 700 })
        } finally { writer.close(); assertTrue(writer.awaitIdle(2_000)) }
    }

    @Test fun aFailedInitializationIsRetriedAndADiskFailureDoesNotStopLaterRecords() {
        val resets = AtomicInteger()
        val writes = AtomicInteger()
        val writer = SessionDiagnosticLogWriter(storageFactory = { path ->
            storage(path,
                beforeReset = { if (resets.incrementAndGet() == 1) throw IOException("temporarily unavailable") },
                beforeAppend = { if (writes.incrementAndGet() == 1) throw IOException("temporarily unavailable") })
        })
        try {
            val session = writer.open(file, "header")
            session.append("failed write")
            session.append("later evidence")
            session.close()
            assertTrue(writer.flush(2_000))
            assertEquals(2, resets.get())
            assertTrue(file.readText().startsWith("header\n"))
            assertTrue(file.readText().contains("later evidence"))
        } finally { writer.close(); assertTrue(writer.awaitIdle(2_000)) }
    }

    @Test(timeout = 8_000) fun aFlushTimeoutDoesNotCancelTheTailAndAnInterruptRemainsAnInterrupt() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = SessionDiagnosticLogWriter(storageFactory = { path ->
            storage(path, beforeReset = { entered.countDown(); release.await() })
        })
        try {
            val session = writer.open(file, "header")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.finish("accepted tail")
            assertFalse(writer.flush(5))
            Thread.currentThread().interrupt()
            try {
                assertFalse(writer.flush(100))
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
            release.countDown()
            assertTrue(writer.flush(2_000))
            assertTrue(file.readText().contains("accepted tail"))
        } finally { release.countDown(); writer.close(); assertTrue(writer.awaitIdle(2_000)) }
    }

    @Test fun differentFileTargetsRemainIndependentAndCaptureTheEnqueueTimestamp() {
        val writer = SessionDiagnosticLogWriter()
        try {
            val old = writer.open(file, "first")
            val other = writer.open(File(folder.root, "other.log"), "other")
            old.append("peer=192.168.49.1", 0)
            old.close()
            assertTrue(other.append("other evidence", 0))
            other.close()
            assertTrue(writer.flush(2_000))
            val prefix = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(0))
            assertTrue(file.readText().contains("$prefix  peer=[ip]"))
            assertFalse(file.readText().contains("other evidence"))
            assertTrue(File(folder.root, "other.log").readText().contains("$prefix  other evidence"))
        } finally { writer.close(); assertTrue(writer.awaitIdle(2_000)) }
    }

    private fun storage(file: File, beforeReset: (String) -> Unit = {}, beforeAppend: (String) -> Unit = {}): SessionLogFile {
        val actual = SessionLogFile(file)
        return mock(SessionLogFile::class.java) { call ->
            when (call.method.name) {
                "reset" -> { val line = call.getArgument<String>(0); beforeReset(line); actual.reset(line); null }
                "append" -> { val line = call.getArgument<String>(0); beforeAppend(line); actual.append(line); null }
                else -> Answers.RETURNS_DEFAULTS.answer(call)
            }
        }
    }
}
