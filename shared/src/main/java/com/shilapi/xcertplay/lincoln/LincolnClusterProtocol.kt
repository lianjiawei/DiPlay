package com.shilapi.xcertplay.lincoln

import com.shilapi.xcertplay.hud.BydAppleManeuver
import com.shilapi.xcertplay.hud.BydClusterFrame
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

    // Additional factory Baidu channels used by head units that do not consume INFO alone.
    const val ACTION_NAV_ACTIVE_OPEN = "ACTION_PHONE_NAV_ACTIVE_OPEN"
    const val ACTION_NAV_ACTIVE_CLOSE = "ACTION_PHONE_NAV_ACTIVE_CLOSE"
    const val ACTION_STANDARD_BROADCAST_SEND = "com.baidu.naviauto.STANDARD_BROADCAST_SEND"
    const val ACTION_START_NAVIGATION = "com.baidu.naviauto.start_navigation"
    const val ACTION_STOP_NAVIGATION = "com.baidu.naviauto.stop_navigation"
    const val ACTION_TURN_BY_TURN = "com.baidu.naviauto.action.send"
    const val EXTRA_KEY_TYPE = "KEY_TYPE"
    const val EXTRA_MODULE_TYPE = "MODULE_TYPE"
    const val VALUE_MODULE_NAVI = "NAVI"
    const val VALUE_KEY_TYPE_NAV_START = "10101"
    const val VALUE_KEY_TYPE_NAV_STOP = "10100"
    const val FLAG_INCLUDE_STOPPED_PACKAGES = 32
    const val NO_TURN_INDEX = -1

    private const val EXTRA_TURN_TYPE = "KEY_TURNTYPE"
    private const val EXTRA_TURN_TYPE_ALIAS = "turnType"
    private const val EXTRA_MANEUVER_ID = "maneuverId"
    private const val EXTRA_RES_ID = "resid"
    private const val EXTRA_MANEUVER_NAME = "maneuverName"
    private const val EXTRA_ICON_NAME = "icon_name"
    private const val EXTRA_REMAIN_DIST = "remainDist"
    private const val EXTRA_REMAIN_DIST_UNDERSCORE = "remain_dist"
    private const val EXTRA_DISTANCE_TO_NEXT = "DistanceToNextManeuver"
    private const val EXTRA_MANEUVER_TIPS = "maneuverTips"
    private const val EXTRA_STREET_NAME = "StreetName"
    private const val EXTRA_STREET_NAME_2 = "StreetName2"
    private const val EXTRA_CURRENT_STREET_NAME = "CurentStreetName"
    private const val EXTRA_CURRENT_STREET_NAME_2 = "CurentStreetName2"
    private const val EXTRA_ROAD_NAME = "road_name"

    // Extras the tested build sends.
    const val EXTRA_STATE = "state"
    const val EXTRA_NAME = "name"
    const val EXTRA_DISTANCE = "distance"
    const val EXTRA_ROAD = "nextRoadName"
    // The verified broadcast receiver uses these doubles ONLY to identify a maneuver. It computes
    // BargraphSteps itself; that downstream vendor-Bundle key is not a broadcast input.
    const val EXTRA_MANEUVER_LATITUDE = "latitude"
    const val EXTRA_MANEUVER_LONGITUDE = "longitude"
    const val EXTRA_INFO_LATITUDE = EXTRA_MANEUVER_LATITUDE
    const val EXTRA_INFO_LONGITUDE = EXTRA_MANEUVER_LONGITUDE
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

    /** The secondary navigation channel has a separate 21-unit street field. */
    private const val MAX_TURN_STREET_CHARS = 21

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

    /** Factory Baidu turn index used by the secondary broadcast and HUD socket. */
    fun turnIndex(maneuverType: Int?, drivingSide: Int): Int {
        val type = maneuverType ?: return NO_TURN_INDEX
        val icon = BydClusterFrame.from(
            BydAppleManeuver(distanceMeters = 0, type = type, drivingSide = drivingSide),
        ).icon
        return when (icon) {
            2, 6 -> 7
            3, 7 -> 3
            4 -> 8
            5 -> 2
            8, 19 -> 0
            9 -> 1
            11, 17 -> 9
            12, 18 -> 10
            15 -> 24
            else -> NO_TURN_INDEX
        }
    }

    fun hasTurnIcon(index: Int): Boolean = index != NO_TURN_INDEX && turnName(index).isNotEmpty()

    fun turnName(index: Int): String = when (index) {
        0 -> "turn_back"
        1 -> "turn_front"
        2 -> "turn_right_front"
        3 -> "turn_right"
        7 -> "turn_left"
        8 -> "turn_left_front"
        9 -> "turn_ring"
        10 -> "turn_ring_out"
        24 -> "turn_dest"
        else -> ""
    }

    fun turnByTurnExtras(index: Int, metres: Int, street: String): Map<String, Any> {
        val name = turnName(index)
        val icon = if (name.isEmpty()) "" else "$name.png"
        return linkedMapOf(
            EXTRA_TURN_TYPE to index,
            EXTRA_TURN_TYPE_ALIAS to index,
            EXTRA_MANEUVER_ID to index,
            EXTRA_RES_ID to index,
            EXTRA_MANEUVER_NAME to icon,
            EXTRA_ICON_NAME to icon,
            EXTRA_DISTANCE to metres.toString(),
            EXTRA_REMAIN_DIST to metres,
            EXTRA_REMAIN_DISTANCE to metres,
            EXTRA_REMAIN_DIST_UNDERSCORE to metres,
            EXTRA_DISTANCE_TO_NEXT to metres,
            EXTRA_MANEUVER_TIPS to "$metres 米",
            EXTRA_STREET_NAME to street,
            EXTRA_STREET_NAME_2 to "",
            EXTRA_CURRENT_STREET_NAME to street,
            EXTRA_CURRENT_STREET_NAME_2 to "",
            EXTRA_ROAD_NAME to street,
        )
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

    fun sanitizeTurnStreet(text: String?): String = sanitizeText(text, MAX_TURN_STREET_CHARS).orEmpty()

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
    private var minimumRawMeters = 0
    private var initialRawMeters = 0
    private var lastRevision: Long? = null
    private var smallRiseSinceNs: Long? = null
    private var smallRiseSamples = 0
    private var previousSmallRiseRaw: Int? = null
    private var largeRiseSamples = 0
    private var largeRiseSinceNs: Long? = null
    private var token: LincolnManeuverToken? = null
    var distanceMeters: Int = 0
        private set
    val pendingRiseSamples: Int get() = maxOf(smallRiseSamples, largeRiseSamples)
    var resetReason: String = "first-guidance"
        private set

    /** Filter raw metres before applying the factory display rounding. */
    fun update(
        maneuverIndex: Int, turn: String, road: String, rawMeters: Int,
        routeGeneration: Long, distanceRevision: Long, nowNs: Long,
        stabilize: Boolean = true,
    ): LincolnManeuverToken {
        require(rawMeters in 0..LincolnClusterProtocol.MAX_DISTANCE_METERS)
        val nextIdentity = if (maneuverIndex >= 0) {
            // The indexed maneuver survives road-name completion and instruction refinements.
            // A genuine route end resets this tracker separately from the held display packet.
            "$routeGeneration:index:$maneuverIndex"
        } else {
            // Current iAP2 guidance always has an index. For older callers without one, the
            // instruction is only a heuristic identity; do not confuse it with an indexed id.
            "$routeGeneration:instruction:$turn|$road"
        }
        val freshDistance = distanceRevision != lastRevision
        lastRevision = distanceRevision
        val formatted = checkNotNull(LincolnClusterProtocol.roundDistance(rawMeters))
        if (nextIdentity != identity) {
            if (token != null) resetReason = "maneuver-or-route-changed"
            identity = nextIdentity
            start(rawMeters)
        } else if (!stabilize) {
            if (formatted > distanceMeters) {
                resetReason = "distance-increased"
                start(rawMeters)
            } else distanceMeters = formatted
            // The receiver setting can change during a session. Keep the raw baseline aligned
            // so returning to the factory receiver cannot hold a custom receiver's value forever.
            minimumRawMeters = rawMeters
            clearRise()
        } else if (rawMeters <= minimumRawMeters) {
            minimumRawMeters = rawMeters
            distanceMeters = minOf(distanceMeters, formatted)
            clearRise()
        } else if (initialRawMeters == 0 && freshDistance) {
            // The first distance can be a placeholder zero. Do not pin a whole new leg at zero.
            resetReason = "zero-baseline-recovered"
            start(rawMeters)
        } else if (freshDistance) {
            // Duplicate published snapshots, keepalives and song updates cannot confirm a rise.
            val riseMeters = rawMeters - minimumRawMeters
            when {
                riseMeters < MIN_PERSISTENT_RISE_METERS -> {
                    // One-to-four-metre noise must not prime either later confirmation path.
                    clearRise()
                }
                riseMeters < LARGE_RISE_METERS -> {
                    // Crossing between the small and large bands starts a new confirmation.
                    clearLargeRise()
                    if (smallRiseSinceNs == null) smallRiseSinceNs = nowNs
                    val falling = previousSmallRiseRaw?.let { rawMeters < it } == true
                    previousSmallRiseRaw = rawMeters
                    smallRiseSamples++
                    if (smallRiseSamples >= MIN_PERSISTENT_RISE_SAMPLES &&
                        nowNs - checkNotNull(smallRiseSinceNs) >= MAX_SMALL_RISE_HOLD_NS &&
                        (!falling || nowNs - checkNotNull(smallRiseSinceNs) >= MAX_FALLING_RISE_HOLD_NS)
                    ) {
                        resetReason = "persistent-distance-increase"
                        start(rawMeters)
                    }
                }
                else -> {
                    clearSmallRise()
                    if (largeRiseSinceNs == null) largeRiseSinceNs = nowNs
                    largeRiseSamples++
                    if (largeRiseSamples >= MIN_LARGE_RISE_SAMPLES &&
                        nowNs - checkNotNull(largeRiseSinceNs) >= LARGE_RISE_CONFIRM_NS
                    ) {
                        resetReason = "confirmed-distance-increase"
                        start(rawMeters)
                    }
                }
            }
        }
        // Every transmitted distance under one token remains non-increasing. Therefore a receiver
        // restarting or missing any prefix still cannot compute >256. Any accepted rise starts
        // a new token; merely ignoring token changes while transmitting larger distances is unsafe.
        return checkNotNull(token)
    }

    private fun start(rawMeters: Int) {
        token = LincolnManeuverToken.fresh()
        minimumRawMeters = rawMeters
        initialRawMeters = rawMeters
        distanceMeters = checkNotNull(LincolnClusterProtocol.roundDistance(rawMeters))
        clearRise()
    }

    private fun clearRise() {
        clearSmallRise()
        clearLargeRise()
    }

    private fun clearSmallRise() {
        smallRiseSinceNs = null
        smallRiseSamples = 0
        previousSmallRiseRaw = null
    }

    private fun clearLargeRise() {
        largeRiseSamples = 0
        largeRiseSinceNs = null
    }

    fun reset(reason: String = "reset") {
        resetReason = reason
        identity = null
        minimumRawMeters = 0
        initialRawMeters = 0
        distanceMeters = 0
        lastRevision = null
        clearRise()
        token = null
    }

    private companion object {
        const val LARGE_RISE_METERS = 50
        const val MIN_PERSISTENT_RISE_METERS = 5
        const val MIN_PERSISTENT_RISE_SAMPLES = 3
        const val MIN_LARGE_RISE_SAMPLES = 2
        const val LARGE_RISE_CONFIRM_NS = 1_000_000_000L
        const val MAX_SMALL_RISE_HOLD_NS = 5_000_000_000L
        // A falling correction may return to the old minimum; bound the additional wait so a
        // real unannounced reroute cannot pin the displayed distance indefinitely.
        const val MAX_FALLING_RISE_HOLD_NS = 7_000_000_000L
    }
}
