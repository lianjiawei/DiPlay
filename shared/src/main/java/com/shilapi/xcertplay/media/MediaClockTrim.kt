package com.shilapi.xcertplay.media

import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps the head unit's audio clock in step with the iPhone's during music (#610).
 *
 * The iPhone sends music at its own 48 kHz and the head unit plays it at its own. A Humax BHCN-T3103C played
 * 0.76 % fast, so the 300 ms start buffer ran dry about every 70 s and music paused while it refilled. After
 * each write the renderer reports how many frames are queued; a slow average of that level drives a PI
 * controller that, once a second, trims the track's playback rate so the level stays at the start threshold.
 * The trim is bounded by [MAX_TRIM] (26 cents at most) and settles at the head unit's own drift, which is a
 * hardware constant, so the next music stream starts from the trim this one learned.
 */
internal class MediaClockTrim(
    private val sampleRate: Int,
    private val targetFrames: Long,
    private var integral: Double = learnedTrim,
) {
    private var level: Double? = null
    private var lastSampleNs = 0L
    private var lastUpdateNs: Long? = null
    private var inputQueueFraction = 0.0

    /** The playback rate last asked of the track, in Hz. */
    var rate = sampleRate
        private set

    /** Records the queued level right after a write while music plays. */
    fun sample(queuedFrames: Long, nowNs: Long, inputQueueFraction: Double = 0.0) {
        if (targetFrames <= 0L) return
        this.inputQueueFraction = inputQueueFraction.coerceIn(0.0, 1.0)
        val error = (queuedFrames - targetFrames).toDouble() / targetFrames
        val previous = level
        // After a pause, a rebuffer or the first write the average restarts from what is queued now.
        level = if (previous == null || nowNs - lastSampleNs > STALE_NS) error
        else previous + (error - previous) * (1.0 - exp(-(nowNs - lastSampleNs).toDouble() / AVERAGE_NS))
        lastSampleNs = nowNs
    }

    /** The playback rate to set now, or null to keep the current one. */
    fun rateDue(nowNs: Long): Int? {
        val error = level ?: return null
        // Without new audio the level says nothing about the clocks: keep the trim as it is.
        if (nowNs - lastSampleNs > STALE_NS) return null
        val previousUpdate = lastUpdateNs
        if (previousUpdate != null && nowNs - previousUpdate < UPDATE_NS) return null
        // A pause between updates is not drift: integrate at most one normal step over it.
        val seconds = if (previousUpdate == null) 0.0 else ((nowNs - previousUpdate) / 1e9).coerceAtMost(MAX_STEP_SECONDS)
        lastUpdateNs = nowNs
        val congested = inputQueueFraction >= INPUT_QUEUE_HIGH_WATER
        // A low hardware watermark is not starvation when decoded/input work is backed up.
        // Forget negative wind-up and drain congestion within the existing pitch/rate bounds.
        if (congested) integral = integral.coerceAtLeast(0.0)
        val integralError = if (congested) error.coerceAtLeast(0.0) else error
        integral = (integral + KI * integralError * seconds).coerceIn(-MAX_TRIM, MAX_TRIM)
        learnedTrim = integral
        val minimumTrim = if (congested) {
            MAX_TRIM * (inputQueueFraction - INPUT_QUEUE_HIGH_WATER) / (1.0 - INPUT_QUEUE_HIGH_WATER)
        } else -MAX_TRIM
        val correction = (KP * error + integral).coerceIn(minimumTrim, MAX_TRIM)
        val raw = (sampleRate * (1.0 + correction)).roundToInt()
        // Keep the native rate unless a real drift needs correcting. Any off-rate track makes the
        // platform resample the whole stream, and a small periodic correction holds that resampler
        // on continuously, which some head units (e.g. the Lincoln/Ford i.MX8) render as a faint
        // periodic artifact under music. A genuine drift such as #610's 0.76 % is far above the
        // deadband, so the correction still engages there.
        val next = if (abs(raw - sampleRate) < (sampleRate * DEADBAND).roundToInt()) sampleRate else raw
        if (next == rate) return null
        rate = next
        return next
    }

    internal companion object {
        /** At most 1.5 % slower or faster: 26 cents, above the 0.76 % seen on #610. */
        const val MAX_TRIM = 0.015
        /** Below this fraction the native rate is kept, so no resampler is inserted for a small correction. */
        const val DEADBAND = 0.002
        const val INPUT_QUEUE_HIGH_WATER = 0.75
        const val KP = 0.03
        /** Per second, per unit of level error. */
        const val KI = 0.001
        const val AVERAGE_NS = 5_000_000_000.0
        const val UPDATE_NS = 1_000_000_000L
        const val STALE_NS = 250_000_000L
        const val MAX_STEP_SECONDS = 2.0

        /** The drift the last music stream settled on. Each process plays to one head unit. */
        @Volatile var learnedTrim = 0.0
    }
}
