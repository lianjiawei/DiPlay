package com.shilapi.xcertplay.lincoln

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LincolnClusterProtocolTest {
    @Test
    fun mapsAppleManeuversToClusterTurnCodes() {
        assertEquals("turn_left", LincolnClusterProtocol.turnCode(1, 0))
        assertEquals("turn_right", LincolnClusterProtocol.turnCode(2, 0))
        assertEquals("turn_left_back", LincolnClusterProtocol.turnCode(47, 0))
        assertEquals("turn_right_back", LincolnClusterProtocol.turnCode(48, 0))
        assertEquals("turn_left_front", LincolnClusterProtocol.turnCode(13, 0))
        assertEquals("turn_right_front", LincolnClusterProtocol.turnCode(23, 0))
        assertEquals("turn_back", LincolnClusterProtocol.turnCode(4, 1))
        assertEquals("turn_ring", LincolnClusterProtocol.turnCode(6, 0))
        assertEquals("turn_dest", LincolnClusterProtocol.turnCode(10, 0))
        assertEquals("turn_front", LincolnClusterProtocol.turnCode(3, 0))
        assertNull(LincolnClusterProtocol.turnCode(null, 0))
    }

    @Test
    fun formatsDistanceLikeTheFactoryBaiduHudAndRejectsInvalidInput() {
        assertEquals(0, LincolnClusterProtocol.roundDistance(0))
        assertEquals(2, LincolnClusterProtocol.roundDistance(2))
        assertEquals(3, LincolnClusterProtocol.roundDistance(3))
        assertEquals(348, LincolnClusterProtocol.roundDistance(348))
        assertEquals(997, LincolnClusterProtocol.roundDistance(997))
        assertEquals(998, LincolnClusterProtocol.roundDistance(998))
        assertEquals(999, LincolnClusterProtocol.roundDistance(999))
        assertEquals(1_000, LincolnClusterProtocol.roundDistance(1_000))
        assertEquals(1_000, LincolnClusterProtocol.roundDistance(1_024))
        assertEquals(1_000, LincolnClusterProtocol.roundDistance(1_099))
        assertEquals(1_100, LincolnClusterProtocol.roundDistance(1_100))
        assertEquals(1_400, LincolnClusterProtocol.roundDistance(1_499))
        assertEquals(1_500, LincolnClusterProtocol.roundDistance(1_500))
        assertEquals(9_900, LincolnClusterProtocol.roundDistance(9_999))
        assertEquals(10_000, LincolnClusterProtocol.roundDistance(10_000))
        assertEquals(10_200, LincolnClusterProtocol.roundDistance(10_249))
        assertEquals(99_900, LincolnClusterProtocol.roundDistance(99_999))
        assertEquals(100_000, LincolnClusterProtocol.roundDistance(100_000))
        assertEquals(100_000, LincolnClusterProtocol.roundDistance(100_999))
        assertEquals(101_000, LincolnClusterProtocol.roundDistance(101_000))
        assertEquals(2_000_000, LincolnClusterProtocol.roundDistance(2_000_000))
        assertNull(LincolnClusterProtocol.roundDistance(-1))
        assertNull(LincolnClusterProtocol.roundDistance(Int.MIN_VALUE))
        assertNull(LincolnClusterProtocol.roundDistance(2_000_001))
        assertNull(LincolnClusterProtocol.roundDistance(Int.MAX_VALUE))
    }

    @Test
    fun calculatesFactoryBargraphStepsFromThisManeuversStartDistance() {
        assertEquals(256, LincolnClusterProtocol.bargraphSteps(400, 400))
        assertEquals(192, LincolnClusterProtocol.bargraphSteps(300, 400))
        assertEquals(128, LincolnClusterProtocol.bargraphSteps(200, 400))
        assertEquals(0, LincolnClusterProtocol.bargraphSteps(0, 400))
        assertEquals(256, LincolnClusterProtocol.bargraphSteps(500, 400))
        assertEquals(1, LincolnClusterProtocol.bargraphSteps(0, 0))
        assertEquals(1, LincolnClusterProtocol.bargraphSteps(300, 0))
        assertEquals(0, LincolnClusterProtocol.bargraphSteps(-1, 0))
    }

    @Test
    fun resetsProgressForANewManeuverAndForARerouteThatGetsFartherAway() {
        val progress = LincolnManeuverProgress()
        assertEquals(256, progress.update(7, "turn_right", "Main Street", 400))
        assertEquals(128, progress.update(7, "turn_right", "Main Street", 200))
        assertEquals(256, progress.update(8, "turn_left", "Second Street", 600))
        assertEquals(128, progress.update(8, "turn_left", "Second Street", 300))

        // A reroute may reuse the same iAP2 index and remain below the old start distance.
        assertEquals(256, progress.update(8, "turn_left", "Second Street", 400))
        assertEquals(128, progress.update(8, "turn_left", "Second Street", 200))

        // Small GPS corrections must not repeatedly restart the progress bar.
        assertEquals(131, progress.update(8, "turn_left", "Second Street", 205))

        // Future callers cannot overflow the reroute delta even with malformed extreme input.
        progress.reset()
        assertEquals(1, progress.update(9, "turn_front", "", 0))
        assertEquals(256, progress.update(9, "turn_front", "", Int.MAX_VALUE))
    }

    @Test
    fun sanitizesRoadNamesToTheReceiversNineteenUnitLimit() {
        assertEquals("Main Street", LincolnClusterProtocol.sanitizeRoadName("Main Street"))
        assertNull(LincolnClusterProtocol.sanitizeRoadName("   "))
        assertNull(LincolnClusterProtocol.sanitizeRoadName(null))
        assertNull(LincolnClusterProtocol.sanitizeRoadName("\n\t\u0000"))
        assertEquals("Main Street", LincolnClusterProtocol.sanitizeRoadName("Main\n\t\u0000 Street"))
        assertEquals("x".repeat(18), LincolnClusterProtocol.sanitizeRoadName("x".repeat(18)))
        assertEquals("x".repeat(19), LincolnClusterProtocol.sanitizeRoadName("x".repeat(19)))
        assertEquals("x".repeat(19), LincolnClusterProtocol.sanitizeRoadName("x".repeat(20)))
    }

    @Test
    fun sanitizesMediaTextIndependentlyToTheReceiversNineteenUnitLimit() {
        assertEquals("Song title", LincolnClusterProtocol.sanitizeMediaText("Song title"))
        assertEquals("x".repeat(19), LincolnClusterProtocol.sanitizeMediaText("x".repeat(20)))
        assertEquals("中".repeat(17) + "🎵",
            LincolnClusterProtocol.sanitizeMediaText("中".repeat(17) + "🎵歌词"))
        assertNull(LincolnClusterProtocol.sanitizeMediaText("\n\t\u0000"))
    }

    @Test
    fun countsOrdinaryChineseCharactersRatherThanUtf8Bytes() {
        val chinese = "导航路名".repeat(4) + "导航路"
        assertEquals(chinese, LincolnClusterProtocol.sanitizeRoadName(chinese))
        assertEquals(chinese, LincolnClusterProtocol.sanitizeRoadName(chinese + "继续行驶"))
        assertEquals(57, chinese.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun keepsSupplementaryChineseAndEmojiWholeAtTheLimit() {
        for (character in listOf("𠀀", "🚗")) {
            val fits = "中".repeat(17) + character
            assertEquals(fits, LincolnClusterProtocol.sanitizeRoadName(fits + "终点"))
            assertEquals("中".repeat(18),
                LincolnClusterProtocol.sanitizeRoadName("中".repeat(18) + character + "终点"))
        }
    }

    @Test
    fun neverSplitsCombiningCharactersOrEmojiSequences() {
        val clusters = listOf(
            "e\u0301", "葛\uDB40\uDD00", "👍🏽", "🇨🇳", "👩‍👩‍👧‍👦", "#️⃣", "🏳️‍🌈",
            "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
        )
        for (cluster in clusters) {
            val fits = "中".repeat(19 - cluster.length) + cluster
            assertEquals(cluster, fits, LincolnClusterProtocol.sanitizeRoadName(fits + "终点"))
            // Exercise every possible cut inside the sequence, not just the surrogate boundary.
            for (available in 1 until cluster.length) {
                val prefix = "中".repeat(19 - available)
                assertEquals("$cluster at $available", prefix,
                    LincolnClusterProtocol.sanitizeRoadName(prefix + cluster + "终点"))
            }
        }
    }

    @Test
    fun keepsCompleteFlagPairsWhenSeveralFlagsAreAdjacent() {
        assertEquals("中".repeat(15) + "🇨🇳",
            LincolnClusterProtocol.sanitizeRoadName("中".repeat(15) + "🇨🇳🇺🇸终点"))
    }

    @Test
    fun removesMalformedSurrogatesBeforeApplyingTheLimit() {
        assertEquals("道路终点", LincolnClusterProtocol.sanitizeRoadName("道\uD800路\uDC00终点"))
        assertNull(LincolnClusterProtocol.sanitizeRoadName("\uD800\uDC00".take(1)))
        assertEquals("中".repeat(19),
            LincolnClusterProtocol.sanitizeRoadName("中".repeat(18) + "\uD800\n\t\u0000中终点"))
    }

    @Test
    fun doesNotSendBlankPrefixesOrHalfAnOversizedCombiningSequence() {
        assertNull(LincolnClusterProtocol.sanitizeRoadName(" ".repeat(19) + "终点"))
        assertNull(LincolnClusterProtocol.sanitizeRoadName("a" + "\u0301".repeat(40) + "终点"))
    }
}
