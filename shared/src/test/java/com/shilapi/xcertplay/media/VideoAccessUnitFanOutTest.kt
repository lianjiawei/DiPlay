package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoAccessUnit
import com.shilapi.xcertplay.airplay.VideoBufferPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class VideoAccessUnitFanOutTest {
    @Test fun mainAndMirrorsShareOneBufferUntilEveryConsumerFinishes() {
        val pool = VideoBufferPool(maxPerBucket = 1, maxRetainedBytes = 64 * 1024)
        val lease = pool.acquire(100)
        val handles = mutableListOf<VideoAccessUnit>()

        fanOutVideoAccessUnit(VideoAccessUnit(lease, 100), 3) { _, handle -> handles += handle }

        assertEquals(3, handles.size)
        assertSame(handles[0].bytes, handles[1].bytes)
        assertSame(handles[1].bytes, handles[2].bytes)
        assertEquals(1, pool.snapshot().activeBuffers)
        handles.take(2).forEach(VideoAccessUnit::close)
        assertEquals(1, pool.snapshot().activeBuffers)
        handles.last().close()
        assertEquals(0, pool.snapshot().activeBuffers)
        assertEquals(1, pool.snapshot().retainedBuffers)
        pool.close()
    }

    @Test fun failedConsumerReleasesUndeliveredOwnershipButKeepsEarlierConsumerValid() {
        val pool = VideoBufferPool(maxPerBucket = 1, maxRetainedBytes = 64 * 1024)
        val lease = pool.acquire(100)
        var first: VideoAccessUnit? = null

        try {
            fanOutVideoAccessUnit(VideoAccessUnit(lease, 100), 3) { index, handle ->
                if (index == 0) first = handle else error("consumer failed")
            }
            fail("expected consumer failure")
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertEquals(1, pool.snapshot().activeBuffers)
        first!!.close()
        assertEquals(0, pool.snapshot().activeBuffers)
        pool.close()
    }

    @Test fun noConsumersImmediatelyReturnsTheBuffer() {
        val pool = VideoBufferPool(maxPerBucket = 1, maxRetainedBytes = 64 * 1024)
        val lease = pool.acquire(100)
        fanOutVideoAccessUnit(VideoAccessUnit(lease, 100), 0) { _, _ -> fail("no delivery expected") }
        assertEquals(0, pool.snapshot().activeBuffers)
        pool.close()
    }
}
