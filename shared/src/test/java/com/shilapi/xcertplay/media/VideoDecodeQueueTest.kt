package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoAccessUnit
import com.shilapi.xcertplay.airplay.VideoBufferPool
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    private fun pooledFrame(pool: VideoBufferPool, size: Int): VideoJob.Frame {
        val lease = pool.acquire(size)
        return VideoJob.Frame(VideoAccessUnit(lease, size))
    }

    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = DecoderInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, DecoderInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(3, attempts)
        assertEquals(-1, DecoderInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }
    @Test fun aWaitShorterThanOneDrainStillDrainsAndRetriesOnce() {
        // Audio caps the wait at 100 ms (#649); a blocking track write can take longer than that.
        var time = 0L
        var drains = 0
        var dequeues = 0
        assertEquals(-1, DecoderInputPump.acquire(
            running = { true }, drain = { drains++; time += 260 }, dequeue = { dequeues++; -1 },
            nanoTime = { time }, timeoutNs = 100,
        ))
        assertEquals(1, drains)
        assertEquals(1, dequeues)
        time = 0
        assertEquals(4, DecoderInputPump.acquire(
            running = { true }, drain = { time += 260 }, dequeue = { 4 },
            nanoTime = { time }, timeoutNs = 100,
        ))
    }

    @Test fun invalidatedChainDropsOldFramesAndResyncButPreservesTheNextControlSegment() {
        for (barrier in listOf(
            VideoJob.Config(VideoCodec.H264, byteArrayOf(1)), VideoJob.SurfaceChanged(null),
            VideoJob.DetachSurface(SurfaceDetachRequest(Any(), park = true)), VideoJob.RefreshPicture,
        )) {
            val queue = VideoDecodeQueue()
            queue.offer(VideoJob.Frame(byteArrayOf(1)))
            queue.offer(VideoJob.Resync)
            queue.offer(VideoJob.Frame(byteArrayOf(2)))
            queue.offer(barrier)
            val next = VideoJob.Frame(byteArrayOf(3))
            queue.offer(next)
            queue.offer(VideoJob.Resync)
            queue.discardCurrentChain()
            assertSame(barrier, queue.poll(0))
            assertSame(next, queue.poll(0))
            assertSame(VideoJob.Resync, queue.poll(0))
            assertNull(queue.poll(0))
        }
    }

    @Test fun invalidatedChainWithoutControlBarrierIsCompletelyDiscarded() {
        val queue = VideoDecodeQueue()
        queue.offer(VideoJob.Resync)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.discardCurrentChain()
        assertNull(queue.poll(0))
        assertEquals(VideoDecodeQueue.Backlog(0, null), queue.backlogAfterCurrent())
        val fresh = VideoJob.Frame(byteArrayOf(2))
        queue.offer(fresh)
        assertSame(fresh, queue.poll(0))
    }

    @Test fun slowConfigurationRejectsOnlyHardStaleKeyframesWithNewerBacklog() {
        val now = 2_000_000_000L
        assertTrue(VideoRecoveryFrameAge.isObsolete(now, 500_000_000L,
            VideoDecodeQueue.Backlog(3, 600_000_000L)))
        assertFalse(VideoRecoveryFrameAge.isObsolete(now, 500_000_001L,
            VideoDecodeQueue.Backlog(3, 600_000_001L)))
        assertFalse(VideoRecoveryFrameAge.isObsolete(now, 0,
            VideoDecodeQueue.Backlog(0, null))) // A static tail is still useful.
        assertFalse(VideoRecoveryFrameAge.isObsolete(now, 0,
            VideoDecodeQueue.Backlog(2, 99_999_999L)))
        assertFalse(VideoRecoveryFrameAge.isObsolete(now, 0,
            VideoDecodeQueue.Backlog(3, now + 1)))
        assertFalse(VideoRecoveryFrameAge.isObsolete(now, now + 1,
            VideoDecodeQueue.Backlog(3, now + 2)))
    }

    @Test fun backlogStopsAtEachControlAndKeepsPacingTimestamps() {
        for (control in listOf(
            VideoJob.Config(VideoCodec.H264, byteArrayOf(1)), VideoJob.SurfaceChanged(null),
            VideoJob.DetachSurface(SurfaceDetachRequest(Any(), park = true)), VideoJob.RefreshPicture,
        )) {
            val queue = VideoDecodeQueue()
            val current = VideoJob.Frame(byteArrayOf(1), receivedNs = 100, senderNanos = 80, arrivalNanos = 90)
            queue.offer(current)
            queue.offer(VideoJob.Frame(byteArrayOf(2), receivedNs = 200))
            queue.offer(control)
            val paced = VideoJob.Frame(byteArrayOf(3), receivedNs = 900, senderNanos = 700, arrivalNanos = 800)
            queue.offer(paced)
            assertSame(current, queue.poll(0))
            assertEquals(VideoDecodeQueue.Backlog(1, 200), queue.backlogAfterCurrent())
            queue.discardCurrentChain()
            assertSame(control, queue.poll(0))
            assertSame(paced, queue.poll(0))
            assertEquals(700L, paced.senderNanos)
            assertEquals(800L, paced.arrivalNanos)
        }
    }

    @Test fun everyQueueDiscardPathReleasesPooledPictures() {
        val pool = VideoBufferPool(maxPerBucket = 1, maxRetainedBytes = 64 * 1024)

        val overflow = VideoDecodeQueue(maxFrames = 1)
        overflow.offer(pooledFrame(pool, 10))
        overflow.offer(pooledFrame(pool, 11))
        assertEquals(1, pool.snapshot().activeBuffers)
        assertEquals(VideoJob.Resync, overflow.poll(0))
        (overflow.poll(0) as VideoJob.Frame).close()
        assertEquals(0, pool.snapshot().activeBuffers)

        val invalidated = VideoDecodeQueue()
        invalidated.offer(pooledFrame(pool, 12))
        invalidated.discardCurrentChain()
        assertEquals(0, pool.snapshot().activeBuffers)

        val oversized = VideoDecodeQueue(maxBytes = 5)
        oversized.offer(pooledFrame(pool, 6))
        assertEquals(VideoJob.Resync, oversized.poll(0))
        assertEquals(0, pool.snapshot().activeBuffers)

        val closing = VideoDecodeQueue()
        closing.offer(pooledFrame(pool, 13))
        closing.closeAndDrain().forEach { (it as? VideoJob.Frame)?.close() }
        assertEquals(0, pool.snapshot().activeBuffers)
        closing.offer(pooledFrame(pool, 14))
        assertEquals(0, pool.snapshot().activeBuffers)
        assertNull(closing.poll(0))
        pool.close()
    }

    @Test fun overflowCannotRecycleAFrameAlreadyOwnedByTheConsumer() {
        val pool = VideoBufferPool(maxPerBucket = 4, maxRetainedBytes = 64 * 1024)
        val queue = VideoDecodeQueue(maxFrames = 1)
        val current = pooledFrame(pool, 10)
        current.nalus[0] = 17
        queue.offer(current)
        val polled = queue.poll(0) as VideoJob.Frame

        val queued = pooledFrame(pool, 11)
        queued.nalus[0] = 23
        queue.offer(queued)
        val newest = pooledFrame(pool, 12)
        newest.nalus[0] = 42
        queue.offer(newest) // Drops only the still-queued picture.

        assertEquals(17, polled.nalus[0].toInt())
        assertEquals(2, pool.snapshot().activeBuffers) // Current consumer plus newest queue entry.
        polled.close()
        assertEquals(VideoJob.Resync, queue.poll(0))
        (queue.poll(0) as VideoJob.Frame).close()
        assertEquals(0, pool.snapshot().activeBuffers)
        pool.close()
    }

}
