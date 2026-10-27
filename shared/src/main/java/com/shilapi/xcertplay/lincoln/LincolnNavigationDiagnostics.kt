package com.shilapi.xcertplay.lincoln

import com.shilapi.xcertplay.glance.CarPlayGlance

/** Bounded in-memory evidence only: no names, coordinates, per-frame disk I/O or timers. */
object LincolnNavigationDiagnostics {
    private const val LIMIT = 128
    private const val PERIOD_NS = 1_000_000_000L
    private val lock = Any()
    private val entries = ArrayDeque<String>()
    private var lastShape: String? = null
    private var lastDecision: String? = null
    private val lastTransport = mutableMapOf<String, Boolean>()
    private var lastDistanceNs = 0L
    private var lastRevision = 0L
    private var latestInput = "none"
    private var windowNs = 0L
    private var windowCount = 0
    private var omitted = 0L
    private var changedSends = 0L
    private var keepaliveSends = 0L
    internal var nanoTime: () -> Long = System::nanoTime
    private val frameTrace = LincolnNavigationFrameTrace()
    @Volatile private var frameCaptureEnabled = false

    internal fun enableFrameCapture(enabled: Boolean) { frameCaptureEnabled = enabled }
    internal fun clearFramesForTests() = frameTrace.clearForTests()

    /** The caller already owns this payload copy; no raw frame is retained by diagnostics. */
    internal fun frame(id: Int, payload: ByteArray, session: Long, connected: Boolean, generation: Long, revision: Long) {
        if (frameCaptureEnabled) frameTrace.record(id, payload, session, connected, generation, revision)
    }

    internal fun connection(session: Long, connected: Boolean, generation: Long, revision: Long) {
        if (frameCaptureEnabled) frameTrace.connection(session, connected, generation, revision)
    }

    fun observe(s: CarPlayGlance.Snapshot, enabled: Boolean, enhanced: Boolean) {
        val now = nanoTime()
        val shape = "connected=${s.connected} enabled=$enabled enhanced=$enhanced" +
            " active=${s.routeActive} ended=${s.routeEnded} endPending=${s.routeEndPending}" +
            " selected=${s.maneuverIndex} type=${s.maneuverType}" +
            " routeIndex=${s.selectedManeuverIndex}" +
            " generation=${s.routeProgressGeneration} pending=${s.routeProgressPending}"
        val detail = "$shape distance=${if (s.maneuverType != null) s.distanceMeters else "missing"}" +
            " revision=${s.distanceRevision} remaining=${s.remainingMeters}"
        synchronized(lock) {
            latestInput = detail
            val changed = shape != lastShape
            if (!changed && (now - lastDistanceNs < PERIOD_NS || s.distanceRevision == lastRevision)) return
            lastShape = shape
            lastDistanceNs = now
            lastRevision = s.distanceRevision
            recordLocked(now, "input $detail")
        }
    }

    /** Identity change is logged as a boolean; coordinates and street names are never recorded. */
    internal fun navigationSent(s: CarPlayGlance.Snapshot, turn: String, distance: Int, identityChanged: Boolean, reason: String, changed: Boolean) {
        val key = "turn:$turn:${s.maneuverIndex}:$distance:$identityChanged:$reason"
        synchronized(lock) {
            if (changed) changedSends++ else keepaliveSends++
            if (key == lastDecision) return
            lastDecision = key
            recordLocked(nanoTime(), "output $key revision=${s.distanceRevision} generation=${s.routeProgressGeneration}")
        }
    }

    internal fun navigationRepeated() = synchronized(lock) { keepaliveSends++ }

    internal fun zeroDecision(decision: String, snapshot: CarPlayGlance.Snapshot) = synchronized(lock) {
        recordLocked(nanoTime(), "zero-candidate $decision index=${snapshot.maneuverIndex}" +
            " generation=${snapshot.routeProgressGeneration} revision=${snapshot.distanceRevision}")
    }

    internal fun zeroCancelled(reason: String, index: Int, generation: Long) = synchronized(lock) {
        recordLocked(nanoTime(), "zero-candidate cancelled=$reason index=$index generation=$generation")
    }

    internal fun staleDestination(previous: Int, selected: Int) {
        val key = "stale-destination:$previous:$selected"
        synchronized(lock) {
            if (key == lastDecision) return
            lastDecision = key
            recordLocked(nanoTime(), "decision $key awaiting-maneuver-details")
        }
    }

    /** Success means Android accepted the send, not that the receiver rendered it. */
    fun transport(channel: String, action: String, accepted: Boolean) {
        val key = "$channel:$action"
        synchronized(lock) {
            if (lastTransport[key] == accepted) return
            lastTransport[key] = accepted
            recordLocked(nanoTime(), "transport $key accepted=$accepted")
        }
    }

    private fun recordLocked(now: Long, line: String) {
        if (windowNs == 0L || now - windowNs >= PERIOD_NS || now < windowNs) {
            windowNs = now
            windowCount = 0
        }
        if (windowCount++ >= 10) { omitted++; return }
        if (entries.size == LIMIT) { entries.removeFirst(); omitted++ }
        entries.addLast("t=${now / 1_000_000}ms $line")
    }

    fun report(): String {
        val (copy, skipped, latest) = synchronized(lock) {
            Triple(entries.toList(), omitted, "$latestInput; sends changed=$changedSends keepalive=$keepaliveSends")
        }
        return buildString {
            appendLine("Lincoln navigation: in-memory, bounded; omitted=$skipped")
            appendLine("latest input $latest")
            copy.forEach { appendLine(it) }
            appendLine(frameTrace.report())
        }.trimEnd()
    }
}
