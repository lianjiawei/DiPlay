package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TouchDispatchQueueTest {
    private class PausedExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        var reject = false
        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException()
            tasks.addLast(command)
        }
        fun runNext() = tasks.removeFirst().run()
        fun drain() { while (tasks.isNotEmpty()) runNext() }
    }

    private fun finger(id: Int, x: Double, down: Boolean = true) = AirPlayContact(id, x, 0.5, down)

    @Test fun stalledMovesCollapseToLatestButPressAndReleaseRemainOrdered() {
        val executor = PausedExecutor()
        val queue = TouchDispatchQueue { executor }
        val session = Any()
        val sent = mutableListOf<List<AirPlayContact>>()
        queue.touch(session, listOf(finger(1, 0.0)), send = sent::add)
        repeat(500) { queue.touch(session, listOf(finger(1, (it + 1) / 500.0)), send = sent::add) }
        queue.touch(session, listOf(finger(1, 1.0, false)), send = sent::add)
        assertEquals(3, executor.tasks.size)
        executor.drain()
        assertEquals(listOf(listOf(finger(1, 0.0)), listOf(finger(1, 1.0)),
            listOf(finger(1, 1.0, false))), sent)
    }

    @Test fun movingAfterEverySendKeepsEveryPosition() {
        val executor = PausedExecutor()
        val queue = TouchDispatchQueue { executor }
        val session = Any()
        val sent = mutableListOf<List<AirPlayContact>>()
        repeat(32) {
            queue.touch(session, listOf(finger(1, it.toDouble())), send = sent::add)
            executor.runNext()
        }
        assertEquals((0 until 32).map { listOf(finger(1, it.toDouble())) }, sent)
    }

    @Test fun multipleFingersAndCancelRemainInOrder() {
        val executor = PausedExecutor()
        val queue = TouchDispatchQueue { executor }
        val session = Any()
        val sent = mutableListOf<List<AirPlayContact>>()
        fun touch(vararg contacts: AirPlayContact) = queue.touch(session, contacts.toList(), send = sent::add)
        touch(finger(1, 0.0))
        touch(finger(1, 0.1))
        touch(finger(1, 0.2))
        touch(finger(1, 0.2), finger(2, 0.3))
        touch(finger(1, 0.4), finger(2, 0.5))
        touch(finger(1, 0.6), finger(2, 0.7))
        touch(finger(1, 0.6), finger(2, 0.7, false))
        touch(finger(1, 0.8))
        touch()
        executor.drain()
        assertEquals(listOf(
            listOf(finger(1, 0.0)), listOf(finger(1, 0.2)),
            listOf(finger(1, 0.2), finger(2, 0.3)),
            listOf(finger(1, 0.6), finger(2, 0.7)),
            listOf(finger(1, 0.6), finger(2, 0.7, false)),
            listOf(finger(1, 0.8)), emptyList(),
        ), sent)
    }

    @Test fun controlCommandsSeparateMovesAndKeepTheirRelativeOrder() {
        val executor = PausedExecutor()
        val queue = TouchDispatchQueue { executor }
        val session = Any()
        val sent = mutableListOf<String>()
        fun touch(x: Double) = queue.touch(session, listOf(finger(1, x))) { sent.add("touch:${it[0].x}") }
        touch(0.0)
        touch(0.1)
        queue.control { sent.add("media") }
        touch(0.2)
        touch(0.3)
        executor.drain()
        assertEquals(listOf("touch:0.0", "touch:0.1", "media", "touch:0.3"), sent)
    }

    @Test fun explicitPressIsNeverMergedEvenIfPreviousReleaseWasMissing() {
        val executor = PausedExecutor()
        val queue = TouchDispatchQueue { executor }
        val session = Any()
        val sent = mutableListOf<List<AirPlayContact>>()
        queue.touch(session, listOf(finger(1, 0.0)), isMoveEvent = false, send = sent::add)
        queue.touch(session, listOf(finger(1, 0.1)), send = sent::add)
        queue.touch(session, listOf(finger(1, 0.2)), isMoveEvent = false, send = sent::add)
        queue.touch(session, listOf(finger(1, 0.3)), send = sent::add)
        executor.drain()
        assertEquals((0..3).map { listOf(finger(1, it / 10.0)) }, sent)
    }

    @Test fun newSessionNeverMergesWithPreviousSession() {
        val executor = PausedExecutor()
        val queue = TouchDispatchQueue { executor }
        val sent = mutableListOf<String>()
        val first = Any()
        val second = Any()
        fun touch(session: Any, name: String, x: Double) =
            queue.touch(session, listOf(finger(1, x))) { sent.add("$name:${it[0].x}") }
        touch(first, "old", 0.0)
        touch(first, "old", 0.1)
        touch(second, "new", 0.2)
        touch(second, "new", 0.3)
        executor.drain()
        assertEquals(listOf("old:0.0", "old:0.1", "new:0.2", "new:0.3"), sent)
    }

    @Test fun rejectedTaskCannotBecomePhantomPendingMove() {
        val executor = PausedExecutor()
        val queue = TouchDispatchQueue { executor }
        val session = Any()
        val sent = mutableListOf<List<AirPlayContact>>()
        queue.touch(session, listOf(finger(1, 0.0)), send = sent::add)
        executor.reject = true
        assertThrows(RejectedExecutionException::class.java) {
            queue.touch(session, listOf(finger(1, 0.1)), send = sent::add)
        }
        assertThrows(RejectedExecutionException::class.java) {
            queue.touch(session, listOf(finger(1, 0.2)), send = sent::add)
        }
        executor.reject = false
        queue.touch(session, listOf(finger(1, 0.3)), send = sent::add)
        executor.drain()
        assertEquals(listOf(listOf(finger(1, 0.0)), listOf(finger(1, 0.3))), sent)
    }

    @Test fun synchronousExecutorDoesNotLeaveAnAlreadySentMovePending() {
        val queue = TouchDispatchQueue { Executor { it.run() } }
        val sent = mutableListOf<List<AirPlayContact>>()
        val session = Any()
        repeat(3) { queue.touch(session, listOf(finger(1, it.toDouble())), send = sent::add) }
        assertEquals(3, sent.size)
    }

    @Test fun shutdownCannotMergeIntoAQueuedMoveThatWasDiscarded() {
        val executor = PausedExecutor()
        val queue = TouchDispatchQueue { executor }
        val session = Any()
        val sent = mutableListOf<List<AirPlayContact>>()
        queue.touch(session, listOf(finger(1, 0.0)), send = sent::add)
        queue.touch(session, listOf(finger(1, 0.1)), send = sent::add)
        queue.stop { executor.tasks.clear() }
        assertThrows(RejectedExecutionException::class.java) {
            queue.touch(session, listOf(finger(1, 0.2)), send = sent::add)
        }
        assertThrows(RejectedExecutionException::class.java) { queue.control {} }
        assertEquals(0, executor.tasks.size)
    }

    @Test fun consecutiveTapsNeverLosePressOrRelease() {
        val executor = PausedExecutor()
        val queue = TouchDispatchQueue { executor }
        val session = Any()
        val sent = mutableListOf<List<AirPlayContact>>()
        repeat(40) {
            queue.touch(session, listOf(finger(1, 0.1)), send = sent::add)
            queue.touch(session, listOf(finger(1, 0.1, false)), send = sent::add)
        }
        executor.drain()
        assertEquals(80, sent.size)
        sent.forEachIndexed { index, event -> assertEquals(index % 2 == 0, event.single().down) }
    }
}
