package com.shilapi.xcertplay.lincoln

import java.text.BreakIterator
import java.util.Locale
import java.util.UUID

/**
 * Lincoln/Ford instrument-cluster forwarding, reverse-engineered from the field build
 * `0.2.20-lincoln-preview19` (`com.lincolnpay.android81`).
 *
 * The cluster app draws its own dashboard card and receives plain broadcasts; DiPlay only has to
 * translate CarPlay's iAP2 guidance (0x5201/0x5202) and now-playing (0x5001) into their extras.
 * Everything here is pure so the mapping can be unit-tested without a head unit.
 */
object LincolnClusterProtocol {
    /** The cluster app that draws the dashboard card on the tested Lincoln/Ford firmware. */
    const val DEFAULT_CLUSTER_PACKAGE = "com.baidu.xiaoduos.instrumentcluster"

    // Broadcasts the cluster listens for.
    const val ACTION_NAV_STATE = "com.baidu.car.navi.STATE"
    const val ACTION_NAV_INFO = "com.baidu.car.navi.INFO"
    /**
     * The cluster's own channel for total remaining distance/time. Confirmed on a MEK-MX8Q
     * (Android 8.1) head unit: the cluster reads `remainDistance` (metres) and `remainTime`
     * (seconds). The field build never sent this, which is why its remaining readout was missing.
     */
    const val ACTION_NAV_REMAIN = "com.baidu.car.navi.REMAIN"
    const val ACTION_MEDIA_INFO = "com.baidu.car.radio.notify.PLAY_INFO"

    // Extras the tested build sends.
    const val EXTRA_STATE = "state"
    const val EXTRA_NAME = "name"
    const val EXTRA_DISTANCE = "distance"
    const val EXTRA_ROAD = "nextRoadName"
    // The verified broadcast receiver uses these doubles ONLY to identify a maneuver. It computes
    // BargraphSteps itself; that downstream vendor-Bundle key is not a broadcast input.
    const val EXTRA_MANEUVER_LATITUDE = "latitude"
    const val EXTRA_MANEUVER_LONGITUDE = "longitude"
    const val EXTRA_REMAIN_DISTANCE = "remainDistance"
    const val EXTRA_REMAIN_TIME = "remainTime"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ARTIST = "artist"
    const val EXTRA_ALBUM = "album_name"
    const val EXTRA_SOURCE_NAME = "source_name"
    const val EXTRA_SOURCE_TYPE = "source_type"
    const val EXTRA_PLAYING = "is_playing"

    const val STATE_ON = "TRUE"
    const val STATE_OFF = "FALSE"

    const val SOURCE_NAME = "CarPlay"
    const val SOURCE_TYPE = 1

    /** The factory cluster truncates its primary street-name slot at 19 UTF-16 units. */
    const val MAX_ROAD_NAME_CHARS = 19

    /** The factory cluster applies the same 19-unit limit to its short media-text slots. */
    const val MAX_MEDIA_TEXT_CHARS = 19

    /** Beyond this the cluster is not asked to show a distance (matches the field build). */
    const val MAX_DISTANCE_METERS = 2_000_000

    /**
     * Apple's RouteGuidanceManeuverType -> the cluster's own turn code. The grouping follows the
     * field build, which maps a U-turn to a single `turn_back` regardless of driving side.
     */
    fun turnCode(maneuverType: Int?, drivingSide: Int): String? = when (maneuverType) {
        null -> null
        1, 20 -> "turn_left"
        2, 21 -> "turn_right"
        47 -> "turn_left_back"
        48 -> "turn_right_back"
        13, 22, 49, 52 -> "turn_left_front"
        14, 23, 50, 53 -> "turn_right_front"
        4, 18, 19, 26 -> "turn_back"
        6, 7, in 28..46 -> "turn_ring"
        10, 12, 24, 25, 27 -> "turn_dest"
        3, 5, 8, 9, 11, 15, 16, 17, 51 -> "turn_front"
        else -> "turn_front"
    }

    /**
     * Match Baidu's factory HUD packet distance: exact below 1 km, floor to 100 m below 100 km,
     * then floor to 1 km. Negative and implausibly large values are rejected before formatting.
     */
    fun roundDistance(meters: Int): Int? {
        if (meters < 0 || meters > MAX_DISTANCE_METERS) return null
        return when {
            meters < 1_000 -> meters
            meters < 100_000 -> meters / 100 * 100
            else -> meters / 1_000 * 1_000
        }
    }

    fun sanitizeRoadName(text: String?): String? = sanitizeText(text, MAX_ROAD_NAME_CHARS)

    fun sanitizeMediaText(text: String?): String? = sanitizeText(text, MAX_MEDIA_TEXT_CHARS)

    /**
     * Bound factory-cluster text without sending half a character. The receiver measures Java
     * UTF-16 units and otherwise uses `substring(0, 19)`, which can split a supplementary or
     * combined character. A sequence that crosses the budget is omitted in full instead.
     */
    private fun sanitizeText(text: String?, maxChars: Int): String? {
        if (text == null) return null
        val printable = buildString(text.length) {
            var index = 0
            while (index < text.length) {
                val point = Character.codePointAt(text, index)
                // Keep the existing C0-control filtering and also discard malformed surrogates.
                if (point >= 32 && point !in 0xD800..0xDFFF) appendCodePoint(point)
                index += Character.charCount(point)
            }
        }
        if (printable.length <= maxChars) return printable.takeIf { it.isNotBlank() }

        val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(printable) }
        var end = if (characters.isBoundary(maxChars)) maxChars else characters.preceding(maxChars)
        // Older Android character iterators may split newer emoji sequences. Retreat over the
        // entire sequence instead of relying on the host JVM's newer Unicode boundary rules.
        while (end > 0 && !safeTextBoundary(printable, end)) end = characters.preceding(end)
        return printable.substring(0, end.coerceAtLeast(0)).takeIf { it.isNotBlank() }
    }

    private fun safeTextBoundary(text: String, end: Int): Boolean {
        if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) return false
        val before = Character.codePointBefore(text, end)
        val after = Character.codePointAt(text, end)
        if (before == 0x200D || after == 0x200D || after == 0x200C) return false
        if (Character.getType(after) in intArrayOf(Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt()) ||
            after in 0xFE00..0xFE0F || after in 0xE0100..0xE01EF ||
            after in 0x1F3FB..0x1F3FF || after in 0xE0020..0xE007F) return false

        // Regional indicators form flag pairs; never keep only the first half of a flag.
        if (before in 0x1F1E6..0x1F1FF && after in 0x1F1E6..0x1F1FF) {
            var count = 0
            var index = end
            while (index > 0) {
                val point = Character.codePointBefore(text, index)
                if (point !in 0x1F1E6..0x1F1FF) break
                count++
                index -= Character.charCount(point)
            }
            if (count % 2 != 0) return false
        }
        return true
    }

    /** Beyond this the cluster drops the remaining readout. */
    const val MAX_REMAIN_DISTANCE_METERS = 10_000_000
}

/**
 * Opaque identity for the verified instrument receiver, NOT a geographic position. Its INFO
 * adapter passes latitude/longitude only to NavPresenterImpl's equality check; neither coordinate
 * reaches the vehicle service. Our current iAP2 model has maneuver ids, not maneuver coordinates.
 * Never substitute the vehicle's moving GPS position: that would restart the bar at every fix.
 */
internal data class LincolnManeuverToken(val latitude: Double, val longitude: Double) {
    companion object {
        fun fresh(): LincolnManeuverToken {
            val uuid = UUID.randomUUID()
            // Nonzero, finite doubles in [1, 2), with enough identity bits to avoid reusing the
            // receiver's cached token even if DiPlay restarts while the cluster stays alive.
            fun component(bits: Long) = Double.fromBits(0x3ff0000000000000L or (bits and 0x000fffffffffffffL))
            return LincolnManeuverToken(component(uuid.mostSignificantBits), component(uuid.leastSignificantBits))
        }
    }
}

/** Keeps each identity's transmitted distances non-increasing, even without receiver acknowledgements. */
internal class LincolnManeuverTracker {
    private var identity: String? = null
    private var lastDistanceMeters = 0
    private var token: LincolnManeuverToken? = null
    var resetReason: String = "first-guidance"
        private set

    /** Use the distance actually transmitted: that is what the receiver uses as its baseline. */
    fun update(
        maneuverIndex: Int, turn: String, road: String, distanceMeters: Int,
        routeGeneration: Long = 0,
    ): LincolnManeuverToken {
        require(distanceMeters in 0..LincolnClusterProtocol.MAX_DISTANCE_METERS)
        val nextIdentity = if (maneuverIndex >= 0) {
            // The indexed maneuver survives road-name completion and instruction refinements.
            // A genuine route end resets this tracker separately from the held display packet.
            "$routeGeneration:index:$maneuverIndex"
        } else {
            // Current iAP2 guidance always has an index. For older callers without one, the
            // instruction is only a heuristic identity; do not confuse it with an indexed id.
            "$routeGeneration:instruction:$turn|$road"
        }
        // sendBroadcast succeeding does not acknowledge delivery. The receiver may miss the
        // first sample or restart, establishing a SMALLER baseline than the sender remembers.
        // New identity on EVERY transmitted increase ensures that any ordered subsequence of
        // one identity is non-increasing, so its first received distance bounds the whole run.
        // This deliberately restarts the bar on upward GPS corrections (including rounding
        // boundaries), instead of falsifying the displayed distance or allowing a ratio > 256.
        if (nextIdentity != identity || distanceMeters > lastDistanceMeters) {
            resetReason = when {
                token == null -> resetReason
                nextIdentity != identity -> "maneuver-or-route-changed"
                else -> "distance-increased"
            }
            identity = nextIdentity
            token = LincolnManeuverToken.fresh()
        }
        lastDistanceMeters = distanceMeters
        return checkNotNull(token)
    }

    fun reset(reason: String = "reset") {
        resetReason = reason
        identity = null
        lastDistanceMeters = 0
        token = null
    }
}
