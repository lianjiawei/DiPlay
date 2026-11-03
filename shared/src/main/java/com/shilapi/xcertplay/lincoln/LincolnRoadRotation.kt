package com.shilapi.xcertplay.lincoln

/** The cluster sees only the selected piece; maneuver identity and auxiliary output stay separate. */
internal class LincolnRoadRotation {
    private var source: String? = null
    private var maneuver: LincolnManeuverToken? = null
    private var pieces: List<String> = emptyList()
    private var index = 0
    private var sinceNs = 0L
    private var holdingHead = false

    fun select(rawRoad: String, fallback: String, enabled: Boolean, token: LincolnManeuverToken, nowNs: Long): String {
        if (!enabled) {
            clear()
            return fallback
        }
        if (source != rawRoad || maneuver != token) {
            source = rawRoad
            maneuver = token
            pieces = LincolnClusterProtocol.roadSegments(rawRoad)
            index = 0
            sinceNs = nowNs
            holdingHead = false
        }
        if (pieces.size > 1) {
            val hold = if (holdingHead) HEAD_HOLD_NS else PIECE_HOLD_NS
            if (nowNs - sinceNs >= hold) {
                if (holdingHead) {
                    index = 1
                    holdingHead = false
                } else if (index == pieces.lastIndex) {
                    index = 0
                    holdingHead = true
                } else {
                    index++
                }
                val nextHold = if (holdingHead) HEAD_HOLD_NS else PIECE_HOLD_NS
                // A delayed tick advances once, never bursts through several pieces at once.
                sinceNs = if (nowNs - sinceNs >= hold + nextHold) nowNs else sinceNs + hold
            }
        }
        return pieces.getOrNull(index).orEmpty()
    }

    fun clear() {
        source = null
        maneuver = null
        pieces = emptyList()
        index = 0
        sinceNs = 0L
        holdingHead = false
    }

    private companion object {
        const val PIECE_HOLD_NS = 2_000_000_000L
        const val HEAD_HOLD_NS = 5_000_000_000L
    }
}
