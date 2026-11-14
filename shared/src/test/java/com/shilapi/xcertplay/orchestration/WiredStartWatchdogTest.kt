package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2WiredControlClient.StartAgain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WiredStartWatchdogTest {
    private var delay: Long? = null
    private var timer: (() -> Unit)? = null
    private var cancelled = 0
    private var timeouts = 0

    private fun watchdog(lateLinkAwaitingResend: () -> Boolean = { false }) = WiredStartWatchdog(
        schedule = { delayMillis, action ->
            delay = delayMillis
            timer = action
            val cancel: () -> Unit = { cancelled++ }
            cancel
        },
        onTimeout = { timeouts++ },
        lateLinkAwaitingResend = lateLinkAwaitingResend,
    )

    @Test fun nothingIsTimedBeforeTheStartRequest() {
        watchdog()
        assertNull(timer)
        assertEquals(0, timeouts)
    }

    @Test fun anIphoneThatNeverOpensTheSessionEndsTheAttemptOnce() {
        val watch = watchdog()
        watch.startSessionSent()
        assertEquals(WiredStartWatchdog.TIMEOUT_MILLIS, delay)
        timer!!()
        timer!!()
        assertEquals(1, timeouts)
    }

    @Test fun aRepeatedStartRequestKeepsTheFirstDeadline() {
        val watch = watchdog()
        watch.startSessionSent()
        val first = timer
        watch.startSessionSent()
        assertEquals(first, timer)
        assertEquals(0, cancelled)
    }

    @Test fun linkOpeningAt29SecondsGetsItsQuietIntervalAndOneResend() {
        val second = 1_000_000_000L
        var now = 30 * second
        fun decision() = WiredStartAgain.decide(now, 0, 29 * second, false, false)
        val watch = watchdog { decision() != StartAgain.DONE }
        watch.startSessionSent()
        val first = timer!!
        first()
        val grace = timer!!
        assertEquals(WiredStartWatchdog.RESEND_GRACE_MILLIS, delay)
        now = 31 * second
        assertEquals(StartAgain.SEND, decision())
        watch.startSessionSentAgain()
        assertEquals(WiredStartWatchdog.TIMEOUT_MILLIS, delay)
        first()
        grace()
        assertEquals(0, timeouts)
        timer!!()
        assertEquals(1, timeouts)
    }

    @Test fun aStalledResendDoesNotExtendTheGraceTwice() {
        val watch = watchdog { true }
        watch.startSessionSent()
        timer!!()
        timer!!()
        assertEquals(1, timeouts)
    }

    @Test fun teardownOrSuccessfulConnectionDuringGraceIgnoresLateCallbacks() {
        for (end in listOf(WiredStartWatchdog::terminate, WiredStartWatchdog::sessionActive)) {
            val watch = watchdog { true }
            watch.startSessionSent()
            timer!!()
            val grace = timer!!
            end(watch)
            grace()
            watch.startSessionSentAgain()
            assertEquals(0, timeouts)
        }
    }

    @Test fun diPlaysOwnSecondRequestGetsAFullLimitOnce() {
        val watch = watchdog()
        watch.startSessionSent()
        val first = timer
        watch.startSessionSentAgain()
        assertEquals(1, cancelled)
        assertEquals(WiredStartWatchdog.TIMEOUT_MILLIS, delay)
        first!!()
        assertEquals(0, timeouts)
        // A further one keeps that limit.
        val second = timer
        watch.startSessionSentAgain()
        assertEquals(second, timer)
        assertEquals(1, cancelled)
        timer!!()
        assertEquals(1, timeouts)
    }

    @Test fun noSecondLimitWithoutAFirstRequestOrAfterTheSession() {
        val watch = watchdog()
        watch.startSessionSentAgain()
        assertNull(timer)
        watch.startSessionSent()
        watch.sessionActive()
        watch.startSessionSentAgain()
        assertEquals(1, cancelled)
        timer!!()
        assertEquals(0, timeouts)
    }

    @Test fun anActiveSessionCancelsTheLimit() {
        val watch = watchdog()
        watch.startSessionSent()
        watch.sessionActive()
        assertEquals(1, cancelled)
        timer!!()
        assertEquals(0, timeouts)
        watch.startSessionSent()
        assertEquals(1, cancelled)
    }

    @Test fun teardownCancelsTheLimit() {
        val watch = watchdog()
        watch.startSessionSent()
        watch.terminate()
        timer!!()
        assertEquals(0, timeouts)
    }
}
