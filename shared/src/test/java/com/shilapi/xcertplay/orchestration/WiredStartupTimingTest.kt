package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.orchestration.WiredStartupTiming.Stage.*
import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test

class WiredStartupTimingTest {
    @Test fun reportsMonotonicIntervalsAndTotalIncludingUserWaits() {
        var now = 10_000_000L
        val lines = mutableListOf<String>()
        val timing = WiredStartupTiming(lines::add) { now }
        timing.mark(HOST_REQUESTED)
        now += 32_000_000L
        timing.mark(DISPLAY_READY)
        now += 30_000_000_000L
        timing.mark(USB_PERMISSION_READY)
        now += 400_000_000L
        timing.mark(MAIN_FRAME_SUBMITTED)
        assertTrue(lines[1].contains("totalMs=32 deltaMs=32"))
        assertTrue(lines[2].contains("totalMs=30032 deltaMs=30000"))
        assertTrue(lines[3].contains("totalMs=30432 deltaMs=400"))
        assertEquals(4, lines.size)
    }

    @Test fun duplicateAndPostCompletionEventsDoNotEvenReadTheClock() {
        var clockReads = 0
        val lines = mutableListOf<String>()
        val timing = WiredStartupTiming(lines::add) { clockReads++; 0L }
        timing.mark(USB_PERMISSION_WAIT)
        repeat(100_000) { timing.mark(USB_PERMISSION_WAIT) }
        assertEquals(2, clockReads)
        timing.mark(MAIN_FRAME_SUBMITTED)
        repeat(100_000) {
            timing.mark(MAIN_FRAME_SUBMITTED)
            timing.mark(START_SESSION_SENT)
        }
        assertEquals(3, clockReads)
        assertEquals(2, lines.size)
    }

    @Test fun usbCycleesStaySeparateButRemainBoundedEvenOnBadCallbacks() {
        val lines = mutableListOf<String>()
        val timing = WiredStartupTiming(lines::add) { 0 }
        for (pass in -1..10_000) repeat(2) { timing.mark(USB_PERMISSION_READY, pass) }
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("usbCycle=0"))
        assertTrue(lines[2].contains("usbCycle=2"))
    }

    @Test fun cancellationFailureAndRestartStopOldCallbacksWithoutAffectingNewAttempts() {
        for (terminal in listOf(CANCELLED, FAILED, RESTARTED)) {
            val old = mutableListOf<String>()
            val next = mutableListOf<String>()
            val a = WiredStartupTiming(old::add) { 0 }
            val b = WiredStartupTiming(next::add) { 0 }
            a.mark(terminal)
            b.mark(CONTROLLER_START)
            a.mark(AIRPLAY_ACTIVE)
            a.mark(MAIN_FRAME_SUBMITTED)
            b.mark(MAIN_FRAME_SUBMITTED)
            assertEquals(1, old.size)
            assertEquals(2, next.size)
            assertNotEquals(old.single().substringBefore(" seq="), next.first().substringBefore(" seq="))
        }
    }

    @Test fun failedLoggerCannotInterruptStartupAndIsNotRetried() {
        var calls = 0
        val timing = WiredStartupTiming({ calls++; error("storage failure") }) { 0 }
        timing.mark(CONTROLLER_START)
        timing.mark(CONTROLLER_START)
        timing.mark(MAIN_FRAME_SUBMITTED)
        assertEquals(2, calls)
    }

    @Test fun racingCallbacksProduceOneOrderedRecordPerStage() {
        val lines = mutableListOf<String>()
        val timing = WiredStartupTiming(lines::add) { 0 }
        val gate = CountDownLatch(1)
        val workers = List(8) { Thread { gate.await(); repeat(1_000) { timing.mark(AIRPLAY_ACTIVE) } } }
        workers.forEach(Thread::start)
        gate.countDown()
        workers.forEach { it.join(2_000); assertFalse(it.isAlive) }
        timing.mark(MAIN_FRAME_SUBMITTED)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("seq=1"))
        assertTrue(lines[1].contains("seq=2"))
    }

    @Test fun negativeClockMovementCannotProduceNegativeDurations() {
        var now = 100_000_000L
        val lines = mutableListOf<String>()
        val timing = WiredStartupTiming(lines::add) { now }
        now = 90_000_000L
        timing.mark(CONTROLLER_START)
        now = 110_000_000L
        timing.mark(MAIN_FRAME_SUBMITTED)
        assertTrue(lines[0].endsWith("totalMs=0 deltaMs=0"))
        assertTrue(lines[1].endsWith("totalMs=10 deltaMs=10"))
        assertTrue(lines.all { it.length < 180 })
    }
}
