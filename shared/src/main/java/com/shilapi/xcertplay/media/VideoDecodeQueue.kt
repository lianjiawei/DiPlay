package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoAccessUnit
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val accessUnit: VideoAccessUnit,
        val receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
    ) : VideoJob {
        /** Compatibility seam for focused queue tests and callers that already own a standalone array. */
        constructor(
            nalus: ByteArray,
            receivedNs: Long = System.nanoTime(),
            senderNanos: Long = 0L,
            arrivalNanos: Long = 0L,
        ) : this(VideoAccessUnit.unpooled(nalus), receivedNs, senderNanos, arrivalNanos)

        val nalus: ByteArray get() = accessUnit.bytes
        val size: Int get() = accessUnit.size
        fun close() = accessUnit.close()
    }
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec, length: Int = bytes.size): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec, length)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Recovery observes sustained backlog; short delayed bursts keep their reference chain.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val jobs = ArrayDeque<VideoJob>()
    private var accepting = true

    data class Backlog(val pendingFrames: Int, val newestPendingReceivedNs: Long?)

    /** Called by the sole consumer after taking its current frame. Control jobs end this chain. */
    fun backlogAfterCurrent(): Backlog = lock.withLock {
        var frameCount = 0
        var newestReceived: Long? = null
        for (job in jobs) {
            if (job !is VideoJob.Frame) break
            frameCount++
            newestReceived = newestReceived?.let { maxOf(it, job.receivedNs) } ?: job.receivedNs
        }
        Backlog(frameCount, newestReceived)
    }

    fun offer(job: VideoJob) {
        lock.withLock {
            if (!accepting) {
                reject(job)
                return
            }
            if (job is VideoJob.Frame) {
                // Count in place: allocating a temporary frame list for every received picture
                // creates continuous garbage, especially while the decoder is catching up.
                var frameCount = 0
                var frameBytes = job.size.toLong()
                for (pending in jobs) {
                    if (pending is VideoJob.Frame) {
                        frameCount++
                        frameBytes += pending.size
                    }
                }
                if (frameCount >= maxFrames || frameBytes > maxBytes) {
                    discardFramesLocked()
                    jobs.addLast(VideoJob.Resync)
                }
                // A single oversized frame is also a lost reference chain.
                if (job.size > maxBytes) {
                    job.close()
                    notEmpty.signal()
                    return
                }
            }
            jobs.addLast(job)
            notEmpty.signal()
        }
    }

    /** Drop only the invalidated chain; the next config/surface owns later frames. */
    fun discardCurrentChain() {
        lock.withLock {
            val iterator = jobs.iterator()
            while (iterator.hasNext()) {
                when (val job = iterator.next()) {
                    is VideoJob.Frame -> {
                        iterator.remove()
                        job.close()
                    }
                    VideoJob.Resync -> iterator.remove()
                    else -> return
                }
            }
        }
    }

    fun discardFrames() = lock.withLock { discardFramesLocked() }

    private fun discardFramesLocked() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (val job = iterator.next()) {
                is VideoJob.Frame -> {
                    iterator.remove()
                    job.close()
                }
                VideoJob.Resync -> iterator.remove()
                else -> Unit
            }
        }
    }

    @Throws(InterruptedException::class)
    fun poll(timeoutMillis: Long): VideoJob? {
        lock.lockInterruptibly()
        try {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
            while (jobs.isEmpty() && accepting && remaining > 0L) {
                remaining = notEmpty.awaitNanos(remaining)
            }
            return jobs.pollFirst()
        } finally {
            lock.unlock()
        }
    }

    /** Removes and returns every queued job, for a worker that is shutting down. */
    fun drain(): List<VideoJob> = lock.withLock { drainLocked() }

    /** Atomically rejects later work and transfers every pending job to the shutting-down worker. */
    fun closeAndDrain(): List<VideoJob> = lock.withLock {
        accepting = false
        notEmpty.signalAll()
        drainLocked()
    }

    private fun drainLocked(): List<VideoJob> = ArrayList<VideoJob>(jobs.size).also {
        while (jobs.isNotEmpty()) it += jobs.removeFirst()
    }

    private fun reject(job: VideoJob) {
        when (job) {
            is VideoJob.Frame -> job.close()
            is VideoJob.DetachSurface -> job.request.complete(DetachOutcome.RELEASED)
            else -> Unit
        }
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. Video and audio decoders share it. */
internal object DecoderInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
