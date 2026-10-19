package com.shilapi.xcertplay

import java.io.Closeable
import java.util.ArrayDeque

/** One daemon writer, with at most [maxPending] queued entries plus its active write. */
internal class BoundedDiagnosticWriter<T : Any>(
    private val maxPending: Int = 64,
    private val write: (T) -> Unit,
) : Closeable {
    private val monitor = Object()
    private data class Pending<T>(val sequence: Long, val value: T)
    private val pending = ArrayDeque<Pending<T>>()
    private var closed = false
    private var active = false
    private var dropped = 0L
    private var acceptedSequence = 0L
    private var retiredSequence = 0L

    init { require(maxPending > 0) }
    private val thread = Thread(::drain, "diplay-diagnostic-writer").apply {
        isDaemon = true
        start()
    }

    /** Never waits for disk. Under overload, discard the oldest pending entry. */
    fun enqueue(entry: T): Boolean = synchronized(monitor) {
        if (closed) return@synchronized false
        if (pending.size == maxPending) {
            pending.removeFirst()
            dropped++
        }
        pending.addLast(Pending(++acceptedSequence, entry))
        monitor.notifyAll()
        true
    }

    internal val pendingCount: Int get() = synchronized(monitor) { pending.size }
    internal val droppedCount: Long get() = synchronized(monitor) { dropped }

    private fun drain() {
        while (true) {
            val next = synchronized(monitor) {
                while (pending.isEmpty() && !closed) monitor.wait()
                if (pending.isEmpty()) return
                active = true
                pending.removeFirst().also {
                    // Every earlier entry has been attempted or evicted. A flush need not wait
                    // for later traffic that arrived after its own snapshot.
                    retiredSequence = it.sequence - 1
                    monitor.notifyAll()
                }
            }
            // Disk failures or a broken diagnostic callback must not stop later writes.
            try {
                runCatching { write(next.value) }
            } finally {
                synchronized(monitor) {
                    active = false
                    retiredSequence = next.sequence
                    monitor.notifyAll()
                }
            }
        }
    }

    /** Wait only for entries accepted before this call; never enqueue a droppable barrier. */
    fun flush(timeoutMillis: Long): Boolean = synchronized(monitor) {
        val target = acceptedSequence
        if (retiredSequence >= target) return@synchronized true
        if (Thread.currentThread() === thread) return@synchronized false
        val started = System.nanoTime()
        val timeoutNs = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(0))
        while (retiredSequence < target) {
            val remaining = timeoutNs - (System.nanoTime() - started)
            if (remaining <= 0) return@synchronized false
            monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
        true
    }

    /** For a bounded best-effort flush outside transport/UI callbacks, or deterministic tests. */
    fun awaitIdle(timeoutMillis: Long): Boolean = synchronized(monitor) {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        while (active || pending.isNotEmpty()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@synchronized false
            monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
        true
    }

    /** Stop accepting new entries; drain pending work without waiting on the caller. */
    override fun close() = synchronized(monitor) {
        closed = true
        monitor.notifyAll()
    }
}
