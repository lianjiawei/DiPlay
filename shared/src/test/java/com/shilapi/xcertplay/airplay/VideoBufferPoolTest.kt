package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VideoBufferPoolTest {
    @Test
    fun commonSizeClassIsReusedWithTheRequestedLength() {
        val pool = VideoBufferPool()
        val first = pool.acquire(17_000)
        val bytes = first.bytes
        assertEquals(32 * 1024, bytes.size)
        assertEquals(17_000, first.length)
        first.close()

        val second = pool.acquire(20_000)
        assertSame(bytes, second.bytes)
        assertEquals(20_000, second.length)
        second.close()

        val snapshot = pool.snapshot()
        assertEquals(2, snapshot.acquisitions)
        assertEquals(1, snapshot.allocations)
        assertEquals(1, snapshot.reuses)
        assertEquals(0, snapshot.activeBuffers)
        assertEquals(1, snapshot.retainedBuffers)
    }

    @Test
    fun retentionIsBoundedByBucketAndTotalBytes() {
        val pool = VideoBufferPool(maxPerBucket = 1, maxRetainedBytes = 32 * 1024)
        val first = pool.acquire(16_000)
        val second = pool.acquire(16_000)
        val third = pool.acquire(24_000)
        first.close()
        second.close() // Same bucket is already full.
        third.close() // The retained-byte budget is already exhausted.

        val snapshot = pool.snapshot()
        assertEquals(1, snapshot.retainedBuffers)
        assertEquals(16 * 1024, snapshot.retainedBytes)
        assertEquals(2, snapshot.discardedBuffers)
        assertEquals(0, snapshot.activeBuffers)
    }

    @Test
    fun oversizedPictureWorksButIsNeverRetained() {
        val pool = VideoBufferPool(maxReusableCapacity = 32 * 1024)
        val lease = pool.acquire(40_001)
        assertEquals(40_001, lease.bytes.size)
        lease.close()

        val snapshot = pool.snapshot()
        assertEquals(0, snapshot.retainedBuffers)
        assertEquals(1, snapshot.discardedBuffers)
    }

    @Test
    fun closingPoolDropsIdleBuffersAndLateReturnsAreNotRetained() {
        val pool = VideoBufferPool()
        pool.acquire(16_000).close()
        val active = pool.acquire(32_000)
        pool.close()
        var snapshot = pool.snapshot()
        assertTrue(snapshot.closed)
        assertEquals(0, snapshot.retainedBuffers)
        assertEquals(1, snapshot.activeBuffers)

        active.close()
        snapshot = pool.snapshot()
        assertEquals(0, snapshot.activeBuffers)
        assertEquals(0, snapshot.retainedBuffers)
        try {
            pool.acquire(16_000)
            fail("closed pool accepted another allocation")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun sharedAccessUnitReturnsBufferOnlyAfterLastConsumer() {
        val pool = VideoBufferPool()
        val original = VideoAccessUnit(pool.acquire(18_000), size = 17_321)
        val bytes = original.bytes
        val mirror = original.retain()
        assertEquals(17_321, mirror.size)

        original.close()
        original.close() // Each handle is idempotent.
        assertEquals(1, pool.snapshot().activeBuffers)
        val whileHeld = pool.acquire(18_000)
        assertFalse(whileHeld.bytes === bytes)
        whileHeld.close()

        mirror.close()
        assertEquals(0, pool.snapshot().activeBuffers)
        val reused = pool.acquire(18_000)
        assertTrue(reused.bytes === bytes || reused.bytes === whileHeld.bytes)
        reused.close()
    }

    @Test
    fun closedHandleCannotBeRetained() {
        val unit = VideoAccessUnit.unpooled(ByteArray(8), size = 3)
        unit.close()
        try {
            unit.retain()
            fail("closed access unit was retained")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }
}
