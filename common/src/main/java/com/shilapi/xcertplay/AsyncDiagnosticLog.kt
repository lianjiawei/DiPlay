package com.shilapi.xcertplay

import java.io.File

/** Queued entries retain only their exact file target and a redacted, bounded metadata string. */
internal object AsyncDiagnosticLog {
    const val FLUSH_TIMEOUT_MILLIS = 1_000L
    private val writer = SessionDiagnosticLogWriter()

    fun open(file: File, header: String): SessionDiagnosticLog = writer.open(file, header)

    fun append(target: SessionDiagnosticLog?, message: String, nowMillis: Long = System.currentTimeMillis()) {
        target?.append(message, nowMillis)
    }

    fun flush(timeoutMillis: Long): Boolean = writer.flush(timeoutMillis)
    fun status(): String = writer.status()
    internal fun awaitIdle(timeoutMillis: Long): Boolean = writer.awaitIdle(timeoutMillis)
}
