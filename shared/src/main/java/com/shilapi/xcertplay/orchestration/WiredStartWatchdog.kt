package com.shilapi.xcertplay.orchestration

/**
 * A time limit for the iPhone's AirPlay session after a wired `carplay-start-session`.
 *
 * The wired control loop has no limit of its own, so an iPhone that accepts the start request but
 * never opens the session can leave CarPlay on "connecting" until the cable is replugged. When the
 * limit passes, [onTimeout] ends the attempt so the normal retry starts a new one.
 */
internal class WiredStartWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
    private val lateLinkAwaitingResend: () -> Boolean = { false },
) {
    private var armed = false
    private var finished = false
    private var rearmed = false
    private var grantedResendGrace = false
    // The limit in force; a replaced timer that runs anyway finds a newer one and does nothing.
    private var generation = 0
    private var cancelTimer: (() -> Unit)? = null

    /** The first `carplay-start-session` starts the limit; a repeated one keeps it. */
    @Synchronized fun startSessionSent() {
        if (armed || finished) return
        armed = true
        arm()
    }

    /** DiPlay's own second request after a late USB network ([WiredStartAgain]) gets a full limit, once. */
    @Synchronized fun startSessionSentAgain() {
        if (!armed || finished || rearmed) return
        rearmed = true
        cancel()
        arm()
    }

    @Synchronized fun sessionActive() {
        finished = true
        cancel()
    }

    @Synchronized fun terminate() {
        finished = true
        cancel()
    }

    private fun arm(delayMillis: Long = timeoutMillis) {
        val armedGeneration = ++generation
        cancelTimer = schedule(delayMillis) { expire(armedGeneration) }
    }

    private fun expire(armedGeneration: Int) {
        synchronized(this) {
            if (finished || armedGeneration != generation) return
            if (!rearmed && !grantedResendGrace && lateLinkAwaitingResend()) {
                grantedResendGrace = true
                arm(RESEND_GRACE_MILLIS)
                return
            }
            finished = true
            cancelTimer = null
        }
        onTimeout()
    }

    private fun cancel() {
        cancelTimer?.invoke()
        cancelTimer = null
    }

    companion object {
        /** The same limit as the wireless first connection. */
        const val TIMEOUT_MILLIS = 30_000L
        // Allow the 2 s quiet interval plus time for the control loop's 500 ms poll and dispatch.
        const val RESEND_GRACE_MILLIS = WiredStartAgain.QUIET_MILLIS + 1_000L
    }
}
