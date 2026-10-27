package com.shilapi.xcertplay.orchestration

import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded startup milestones, never packet/frame logging. The sink must only enqueue: no file I/O,
 * UI dispatch, flush or transport work. Times use one monotonic clock; deltaMs is the interval
 * since the previous admitted milestone (not exclusive CPU time). User/phone waits are included.
 */
class WiredStartupTiming(
    private val report: (String) -> Unit,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    enum class Stage {
        HOST_REQUESTED, DISPLAY_PENDING, DISPLAY_READY, CONTROLLER_SETUP, CONTROLLER_START,
        MFI_PREPARING, MFI_READY, USB_DISCOVERY, USB_FOUND, USB_ACCESS_CHECK,
        USB_PERMISSION_WAIT, USB_PERMISSION_READY, USB_TRANSITION_REQUESTED, USB_REENUMERATED,
        USB_DATA_OPEN, USB_DATA_READY, NCM_OPEN, NCM_READY, USBMUX_OPEN, USBMUX_READY,
        PAIRING_LOAD, PAIRING_REUSED, PAIRING_NEW, PAIRING_CREATED, PAIRING_READY, PAIRING_REJECTED,
        CARKIT_OPEN, CARKIT_READY, IAP2_LINK_OPEN, IAP2_LINK_READY,
        NETWORK_ATTACH, NETWORK_ATTACHED, NCM_LINK_ACCEPTING, IDENTIFICATION_READY, AUTHENTICATION_READY,
        START_SESSION_SENT, START_SESSION_RESENT, AIRPLAY_TCP_ACCEPTED, AIRPLAY_ACTIVE, MAIN_FRAME_SUBMITTED,
        FAILED, CANCELLED, RESTARTED,
    }

    private val attempt = attempts.incrementAndGet()
    private val startedNanos = clockNanos()
    private var lastNanos = startedNanos
    private var sequence = 0
    private val seen = Array(3) { BooleanArray(Stage.entries.size) }
    @Volatile private var finished = false

    /** USB cycles 0..2 distinguish the initial node and the two permitted re-enumerations. */
    @Synchronized
    fun mark(stage: Stage, usbCycle: Int = 0) {
        if (finished || usbCycle !in seen.indices || seen[usbCycle][stage.ordinal]) return
        seen[usbCycle][stage.ordinal] = true
        val now = maxOf(lastNanos, clockNanos())
        val previous = lastNanos
        lastNanos = now
        sequence++
        if (stage in terminalStages) finished = true
        try {
            report("$PREFIX attempt=$attempt seq=$sequence stage=$stage usbCycle=$usbCycle " +
                "totalMs=${(now - startedNanos) / 1_000_000L} deltaMs=${(now - previous) / 1_000_000L}")
        } catch (_: Exception) {
            // A broken optional diagnostic sink must never fail a connection or decoder.
        }
    }

    companion object {
        const val PREFIX = "WIRED_STARTUP"
        private val attempts = AtomicLong()
        private val terminalStages = setOf(
            Stage.MAIN_FRAME_SUBMITTED, Stage.FAILED, Stage.CANCELLED, Stage.RESTARTED,
        )
    }
}
