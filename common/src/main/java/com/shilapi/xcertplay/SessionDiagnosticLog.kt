package com.shilapi.xcertplay

import java.io.Closeable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Admission handle, containing no Activity, Context or open file descriptor. */
internal class SessionDiagnosticLog internal constructor(
    private val owner: SessionDiagnosticLogWriter,
    internal val file: File,
    internal val header: String,
) : Closeable {
    internal var accepting = true // owner admission lock
    internal var initialized = false // sole writer thread
    internal var storage: SessionLogFile? = null // sole writer thread

    fun append(message: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        owner.append(this, message, nowMillis)

    fun finish(message: String, nowMillis: Long = System.currentTimeMillis()) = owner.finish(this, message, nowMillis)
    override fun close() = owner.finish(this)
}

/** Serializes every session for a path, including resets, without queued lifecycle commands. */
internal class SessionDiagnosticLogWriter(
    private val maxPending: Int = 1_024,
    private val storageFactory: (File) -> SessionLogFile = ::SessionLogFile,
) : Closeable {
    private data class Entry(val session: SessionDiagnosticLog, val line: String?)
    private val admission = Any()
    private val owners = mutableMapOf<String, SessionDiagnosticLog>()
    private var closed = false
    private var reportedDrops = 0L // sole writer thread
    private val writer: BoundedDiagnosticWriter<Entry> = BoundedDiagnosticWriter(maxPending, ::write)

    fun open(file: File, header: String): SessionDiagnosticLog = synchronized(admission) {
        check(!closed)
        val absolute = file.absoluteFile // No canonicalization or filesystem access on the caller.
        owners.remove(absolute.path)?.accepting = false
        val session = SessionDiagnosticLog(this, absolute, DiagnosticRedactor.redact(header).orEmpty())
        owners[absolute.path] = session
        // A dropped opening entry cannot lose initialization: the first surviving entry resets
        // the file and writes the header before its own line.
        writer.enqueue(Entry(session, null))
        session
    }

    fun append(session: SessionDiagnosticLog, message: String, nowMillis: Long): Boolean {
        synchronized(admission) {
            if (closed || !session.accepting || owners[session.file.path] !== session) return false
        }
        val line = format(message, nowMillis) ?: return false
        return synchronized(admission) {
            if (closed || !session.accepting || owners[session.file.path] !== session) return@synchronized false
            writer.enqueue(Entry(session, line))
        }
    }

    fun finish(session: SessionDiagnosticLog, message: String? = null, nowMillis: Long = System.currentTimeMillis()) {
        val finalLine = message?.let { format(it, nowMillis) }
        synchronized(admission) {
            if (!session.accepting || owners[session.file.path] !== session) return
            if (finalLine != null) writer.enqueue(Entry(session, finalLine))
            session.accepting = false
            owners.remove(session.file.path)
        }
        // Accepted entries still own the session. SessionLogFile opens/closes each append's
        // stream internally; there is no persistent file descriptor to close on this thread.
    }

    private fun write(entry: Entry) {
        val session = entry.session
        val storage = session.storage ?: storageFactory(session.file).also { session.storage = it }
        if (!session.initialized) {
            storage.reset(session.header)
            session.initialized = true
        }
        val drops = writer.droppedCount
        if (drops > reportedDrops) {
            storage.append("DIAGNOSTIC_LOG discardedPendingEntries=${drops - reportedDrops} totalDiscarded=$drops queueLimit=$maxPending")
            reportedDrops = drops
        }
        entry.line?.let(storage::append)
    }

    private fun format(message: String, nowMillis: Long): String? {
        val safe = DiagnosticRedactor.redact(message) ?: return null
        return SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis)) + "  " + safe
    }

    /** Background export/explicit exit only. A timeout does not cancel queued entries. */
    fun flush(timeoutMillis: Long): Boolean = try {
        writer.flush(timeoutMillis)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    fun status(): String = "Diagnostic log writer: pendingEntries=${writer.pendingCount}" +
        " discardedPendingEntries=${writer.droppedCount} queueLimit=$maxPending"

    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
    internal val pendingCount: Int get() = writer.pendingCount
    internal val droppedCount: Long get() = writer.droppedCount

    override fun close() {
        synchronized(admission) {
            closed = true
            owners.values.forEach { it.accepting = false }
            owners.clear()
            writer.close()
        }
    }
}
