package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Coalesces only consecutive, not-yet-running moves for the same session and fingers. */
internal class TouchDispatchQueue(private val executor: () -> Executor) {
    private class PendingMove(val session: Any, var contacts: List<AirPlayContact>)

    private val lock = Any()
    private var lastSession: Any? = null
    private var downIds: Set<Int> = emptySet()
    private var pendingMove: PendingMove? = null
    private var stopped = false

    fun touch(session: Any, contacts: List<AirPlayContact>, isMoveEvent: Boolean = true,
              send: (List<AirPlayContact>) -> Unit) {
        val snapshot = contacts.toList()
        synchronized(lock) {
            if (stopped) throw RejectedExecutionException("touch queue is stopped")
            val ids = snapshot.filter { it.down }.map { it.id }.toSet()
            val isMove = isMoveEvent && lastSession === session && snapshot.isNotEmpty() &&
                snapshot.all { it.down } && ids.isNotEmpty() && ids == downIds
            val pending = pendingMove
            if (isMove && pending != null && pending.session === session) {
                pending.contacts = snapshot
            } else if (isMove) {
                val move = PendingMove(session, snapshot)
                // Set before execute: a synchronous executor can run immediately. Restore on
                // rejection so no subsequent call can merge into work that was never accepted.
                pendingMove = move
                try {
                    executor().execute {
                        val latest = synchronized(lock) {
                            if (pendingMove === move) pendingMove = null
                            move.contacts
                        }
                        send(latest)
                    }
                } catch (error: Exception) {
                    if (pendingMove === move) pendingMove = pending
                    throw error
                }
            } else {
                executor().execute { send(snapshot) }
                pendingMove = null
            }
            lastSession = session
            downIds = ids
        }
    }

    /** Other commands form an ordering boundary even though they share the same executor. */
    fun control(task: () -> Unit) = synchronized(lock) {
        if (stopped) throw RejectedExecutionException("touch queue is stopped")
        executor().execute(task)
        pendingMove = null
    }

    /** Mark closed before discarding executor tasks, so no caller can merge into discarded work. */
    fun stop(shutdown: () -> Unit) = synchronized(lock) {
        stopped = true
        pendingMove = null
        lastSession = null
        downIds = emptySet()
        shutdown()
    }
}
