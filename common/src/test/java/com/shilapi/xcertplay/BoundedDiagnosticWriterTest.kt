package com.shilapi.xcertplay

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BoundedDiagnosticWriterTest {
    @Test(timeout = 5_000) fun aBlockedWriterCannotBlockEnqueueAndOnlyTheLatest64PendingEntriesSurvive() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val enqueued = CountDownLatch(1)
        val written = Collections.synchronizedList(mutableListOf<Int>())
        val writer = BoundedDiagnosticWriter<Int> { value ->
            if (value == -1) {
                entered.countDown()
                release.await()
            }
            written.add(value)
        }
        try {
            assertTrue(writer.enqueue(-1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            Thread {
                repeat(100) { writer.enqueue(it) }
                enqueued.countDown()
            }.start()
            assertTrue("enqueue must finish while disk remains blocked", enqueued.await(2, TimeUnit.SECONDS))
            assertEquals(64, writer.pendingCount)
            assertEquals(36L, writer.droppedCount)
            assertFalse(writer.awaitIdle(10))
            release.countDown()
            assertTrue(writer.awaitIdle(2_000))
            assertEquals(listOf(-1) + (36 until 100).toList(), written.toList())
        } finally {
            release.countDown()
            writer.close()
        }
    }

    @Test(timeout = 5_000) fun aCallbackFailureDoesNotStopDrainingAndCloseNeverWaitsForDisk() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val written = mutableListOf<Int>()
        val writer = BoundedDiagnosticWriter<Int> { value ->
            if (value == 0) {
                entered.countDown()
                release.await()
                throw IllegalStateException("private failure detail")
            }
            written.add(value)
        }
        try {
            writer.enqueue(0)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            writer.enqueue(1)
            writer.close()
            assertFalse(writer.enqueue(2))
            release.countDown()
            assertTrue(writer.awaitIdle(2_000))
            assertEquals(listOf(1), written)
        } finally {
            release.countDown()
            writer.close()
        }
    }

    @Test(timeout = 8_000) fun flushDoesNotWaitForTrafficAcceptedAfterItsSnapshot() = snapshotFlush(evict = false)

    @Test(timeout = 8_000) fun evictingASnapshotEntryDoesNotLoseItsFlushBoundary() = snapshotFlush(evict = true)

    private fun snapshotFlush(evict: Boolean) {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val futureEntered = CountDownLatch(1)
        val releaseFuture = CountDownLatch(1)
        val flushed = CountDownLatch(1)
        var success = false
        val writer = BoundedDiagnosticWriter<Int>(maxPending = if (evict) 1 else 4) { value ->
            when (value) {
                1 -> { firstEntered.countDown(); releaseFirst.await() }
                3 -> { futureEntered.countDown(); releaseFuture.await() }
            }
        }
        val flusher = Thread { success = writer.flush(3_000); flushed.countDown() }
        try {
            writer.enqueue(1)
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            writer.enqueue(2)
            flusher.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (flusher.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals("flush must have captured its boundary", Thread.State.TIMED_WAITING, flusher.state)
            writer.enqueue(3)
            releaseFirst.countDown()
            assertTrue(futureEntered.await(2, TimeUnit.SECONDS))
            assertTrue("later traffic must not hold this flush", flushed.await(1, TimeUnit.SECONDS))
            assertTrue(success)
            assertEquals(if (evict) 1L else 0L, writer.droppedCount)
        } finally {
            releaseFirst.countDown(); releaseFuture.countDown()
            flusher.join(3_000)
            writer.close()
            assertTrue(writer.awaitIdle(2_000))
        }
    }

    @Test(timeout = 5_000) fun aWriterCallbackCannotWaitForItself() {
        var result: Boolean? = null
        lateinit var writer: BoundedDiagnosticWriter<Int>
        writer = BoundedDiagnosticWriter { result = writer.flush(3_000) }
        try {
            writer.enqueue(1)
            assertTrue(writer.awaitIdle(1_000))
            assertEquals(false, result)
        } finally { writer.close() }
    }

    @Test(timeout = 5_000) fun zeroTimeoutNeverTurnsIntoAnInfiniteWait() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = BoundedDiagnosticWriter<Int> { entered.countDown(); release.await() }
        try {
            assertTrue(writer.flush(0))
            writer.enqueue(1)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(writer.flush(0))
            release.countDown()
            assertTrue(writer.flush(2_000))
        } finally { release.countDown(); writer.close() }
    }
}
