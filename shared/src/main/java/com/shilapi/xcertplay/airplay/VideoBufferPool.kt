package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Size-classed buffers for compressed video frames.
 *
 * The pool is deliberately scoped to one screen stream. It keeps common pictures out of ART's
 * large-object allocation path while bounding memory retained by an idle or low-rate stream.
 * Pictures larger than [maxReusableCapacity] still work, but are discarded after use.
 */
internal class VideoBufferPool(
    private val maxPerBucket: Int = DEFAULT_MAX_PER_BUCKET,
    private val maxRetainedBytes: Int = DEFAULT_MAX_RETAINED_BYTES,
    private val maxReusableCapacity: Int = DEFAULT_MAX_REUSABLE_CAPACITY,
) : Closeable {
    init {
        require(maxPerBucket >= 0)
        require(maxRetainedBytes >= 0)
        require(maxReusableCapacity >= 0)
    }

    data class Snapshot(
        val acquisitions: Long,
        val allocations: Long,
        val reuses: Long,
        val releases: Long,
        val discardedBuffers: Long,
        val activeBuffers: Int,
        val activeBytes: Long,
        val peakActiveBytes: Long,
        val retainedBuffers: Int,
        val retainedBytes: Int,
        val closed: Boolean,
    )

    private val buckets = HashMap<Int, ArrayDeque<ByteArray>>()
    private var retainedBuffers = 0
    private var retainedBytes = 0
    private var activeBuffers = 0
    private var activeBytes = 0L
    private var peakActiveBytes = 0L
    private var acquisitions = 0L
    private var allocations = 0L
    private var reuses = 0L
    private var releases = 0L
    private var discardedBuffers = 0L
    private var closed = false

    fun acquire(length: Int): Lease {
        require(length >= 0)
        val capacity = bucketCapacity(length)
        val reusable = capacity > 0 && capacity <= maxReusableCapacity && maxPerBucket > 0
        val bytes = synchronized(this) {
            check(!closed) { "video buffer pool is closed" }
            acquisitions++
            val reused = if (reusable) buckets[capacity]?.pollFirst() else null
            val selected = if (reused != null) {
                retainedBuffers--
                retainedBytes -= reused.size
                reuses++
                reused
            } else {
                allocations++
                ByteArray(capacity)
            }
            activeBuffers++
            activeBytes += selected.size
            peakActiveBytes = maxOf(peakActiveBytes, activeBytes)
            selected
        }
        return Lease(bytes, length) { release(bytes, reusable) }
    }

    @Synchronized
    private fun release(bytes: ByteArray, reusable: Boolean) {
        activeBuffers--
        activeBytes -= bytes.size
        releases++
        check(activeBuffers >= 0 && activeBytes >= 0) { "video buffer released more than once" }

        val bucket = if (!closed && reusable) buckets.getOrPut(bytes.size) { ArrayDeque() } else null
        if (bucket != null && bucket.size < maxPerBucket && retainedBytes <= maxRetainedBytes - bytes.size) {
            bucket.addFirst(bytes)
            retainedBuffers++
            retainedBytes += bytes.size
        } else {
            discardedBuffers++
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        acquisitions = acquisitions,
        allocations = allocations,
        reuses = reuses,
        releases = releases,
        discardedBuffers = discardedBuffers,
        activeBuffers = activeBuffers,
        activeBytes = activeBytes,
        peakActiveBytes = peakActiveBytes,
        retainedBuffers = retainedBuffers,
        retainedBytes = retainedBytes,
        closed = closed,
    )

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        discardedBuffers += retainedBuffers.toLong()
        buckets.clear()
        retainedBuffers = 0
        retainedBytes = 0
    }

    private fun bucketCapacity(length: Int): Int {
        if (length == 0) return 0
        if (length > maxReusableCapacity) return length
        val wanted = maxOf(length, MIN_CAPACITY)
        val highest = Integer.highestOneBit(wanted - 1)
        return if (highest <= 0 || highest > Int.MAX_VALUE / 2) wanted else highest shl 1
    }

    internal class Lease(
        val bytes: ByteArray,
        val length: Int,
        private val recycle: () -> Unit,
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) recycle()
        }
    }

    private companion object {
        const val MIN_CAPACITY = 16 * 1024
        const val DEFAULT_MAX_PER_BUCKET = 4
        const val DEFAULT_MAX_RETAINED_BYTES = 4 * 1024 * 1024
        const val DEFAULT_MAX_REUSABLE_CAPACITY = 2 * 1024 * 1024
    }
}

/**
 * One immutable, complete Annex-B picture.
 *
 * Each handle owns one reference. Call [retain] before handing the same picture to another
 * asynchronous consumer, and close every handle when that consumer has copied the bytes.
 */
class VideoAccessUnit private constructor(
    private val shared: SharedBuffer,
    val size: Int,
) : Closeable {
    private val closed = AtomicBoolean(false)

    init {
        require(size in 0..shared.bytes.size)
    }

    val bytes: ByteArray get() = shared.bytes

    internal constructor(lease: VideoBufferPool.Lease, size: Int) : this(
        SharedBuffer(lease.bytes, lease::close),
        size,
    )

    internal fun retain(): VideoAccessUnit {
        check(!closed.get()) { "video access unit is closed" }
        shared.retain()
        return VideoAccessUnit(shared, size)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) shared.release()
    }

    internal companion object {
        fun unpooled(bytes: ByteArray, size: Int = bytes.size): VideoAccessUnit =
            VideoAccessUnit(SharedBuffer(bytes) {}, size)
    }

    private class SharedBuffer(
        val bytes: ByteArray,
        private val recycle: () -> Unit,
    ) {
        private val references = AtomicInteger(1)

        fun retain() {
            while (true) {
                val current = references.get()
                check(current > 0) { "video buffer has already been recycled" }
                check(current < Int.MAX_VALUE) { "too many video buffer references" }
                if (references.compareAndSet(current, current + 1)) return
            }
        }

        fun release() {
            val remaining = references.decrementAndGet()
            check(remaining >= 0) { "video buffer released more than retained" }
            if (remaining == 0) recycle()
        }
    }
}
