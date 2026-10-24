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
    fun roundsDistanceInFiveFiftyAndFiveHundredMeterSteps() {
        assertEquals(0, LincolnClusterProtocol.roundDistance(0))
        assertEquals(0, LincolnClusterProtocol.roundDistance(2))
        assertEquals(5, LincolnClusterProtocol.roundDistance(3))
        assertEquals(5, LincolnClusterProtocol.roundDistance(7))
        assertEquals(10, LincolnClusterProtocol.roundDistance(8))
        assertEquals(350, LincolnClusterProtocol.roundDistance(348))
        assertEquals(350, LincolnClusterProtocol.roundDistance(352))
        assertEquals(355, LincolnClusterProtocol.roundDistance(353))
        assertEquals(450, LincolnClusterProtocol.roundDistance(450))
        assertEquals(455, LincolnClusterProtocol.roundDistance(455))
        assertEquals(700, LincolnClusterProtocol.roundDistance(700))
        assertEquals(995, LincolnClusterProtocol.roundDistance(997))
        assertEquals(1_000, LincolnClusterProtocol.roundDistance(998))
        assertEquals(1_000, LincolnClusterProtocol.roundDistance(999))
        assertEquals(1_000, LincolnClusterProtocol.roundDistance(1_000))
        assertEquals(1_000, LincolnClusterProtocol.roundDistance(1_024))
        assertEquals(1_050, LincolnClusterProtocol.roundDistance(1_025))
        assertEquals(1_050, LincolnClusterProtocol.roundDistance(1_049))
        assertEquals(1_050, LincolnClusterProtocol.roundDistance(1_050))
        assertEquals(1_500, LincolnClusterProtocol.roundDistance(1_500))
        assertEquals(9_950, LincolnClusterProtocol.roundDistance(9_974))
        assertEquals(10_000, LincolnClusterProtocol.roundDistance(9_975))
        assertEquals(10_000, LincolnClusterProtocol.roundDistance(9_999))
        assertEquals(10_000, LincolnClusterProtocol.roundDistance(10_000))
        assertEquals(10_000, LincolnClusterProtocol.roundDistance(10_249))
        assertEquals(10_500, LincolnClusterProtocol.roundDistance(10_250))
        assertEquals(10_500, LincolnClusterProtocol.roundDistance(10_499))
        assertEquals(10_500, LincolnClusterProtocol.roundDistance(10_500))
        assertEquals(10_500, LincolnClusterProtocol.roundDistance(10_749))
        assertEquals(11_000, LincolnClusterProtocol.roundDistance(10_750))
        assertEquals(11_500, LincolnClusterProtocol.roundDistance(11_600))
        assertEquals(2_000_000, LincolnClusterProtocol.roundDistance(2_000_000))
        assertNull(LincolnClusterProtocol.roundDistance(-1))
        assertNull(LincolnClusterProtocol.roundDistance(2_000_001))
    }

    @Test
    fun sanitizesClusterText() {
        assertEquals("Main Street", LincolnClusterProtocol.sanitizeText("Main Street"))
        assertNull(LincolnClusterProtocol.sanitizeText("   "))
        assertNull(LincolnClusterProtocol.sanitizeText(null))
        assertNull(LincolnClusterProtocol.sanitizeText("\n\t\u0000"))
        assertEquals("Main Street", LincolnClusterProtocol.sanitizeText("Main\n\t\u0000 Street"))
        assertEquals("x".repeat(32), LincolnClusterProtocol.sanitizeText("x".repeat(40)))
    }

    @Test
    fun countsOrdinaryChineseCharactersRatherThanUtf8Bytes() {
        val chinese = "导航路名".repeat(8)
        assertEquals(chinese, LincolnClusterProtocol.sanitizeText(chinese))
        assertEquals(chinese, LincolnClusterProtocol.sanitizeText(chinese + "继续行驶"))
        assertEquals(96, chinese.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun keepsSupplementaryChineseAndEmojiWholeAtTheLimit() {
        for (character in listOf("𠀀", "🚗")) {
            val fits = "中".repeat(30) + character
            assertEquals(fits, LincolnClusterProtocol.sanitizeText(fits + "终点"))
            assertEquals("中".repeat(31),
                LincolnClusterProtocol.sanitizeText("中".repeat(31) + character + "终点"))
        }
    }

    @Test
    fun neverSplitsCombiningCharactersOrEmojiSequences() {
        val clusters = listOf(
            "e\u0301", "葛\uDB40\uDD00", "👍🏽", "🇨🇳", "👩‍👩‍👧‍👦", "#️⃣", "🏳️‍🌈",
            "🏴\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F",
        )
        for (cluster in clusters) {
            val fits = "中".repeat(32 - cluster.length) + cluster
            assertEquals(cluster, fits, LincolnClusterProtocol.sanitizeText(fits + "终点"))
            // Exercise every possible cut inside the sequence, not just the surrogate boundary.
            for (available in 1 until cluster.length) {
                val prefix = "中".repeat(32 - available)
                assertEquals("$cluster at $available", prefix,
                    LincolnClusterProtocol.sanitizeText(prefix + cluster + "终点"))
            }
        }
    }

    @Test
    fun keepsCompleteFlagPairsWhenSeveralFlagsAreAdjacent() {
        assertEquals("中".repeat(26) + "🇨🇳",
            LincolnClusterProtocol.sanitizeText("中".repeat(26) + "🇨🇳🇺🇸终点"))
    }

    @Test
    fun removesMalformedSurrogatesBeforeApplyingTheLimit() {
        assertEquals("道路终点", LincolnClusterProtocol.sanitizeText("道\uD800路\uDC00终点"))
        assertNull(LincolnClusterProtocol.sanitizeText("\uD800\uDC00".take(1)))
        assertEquals("中".repeat(32),
            LincolnClusterProtocol.sanitizeText("中".repeat(31) + "\uD800\n\t\u0000中终点"))
    }

    @Test
    fun doesNotSendBlankPrefixesOrHalfAnOversizedCombiningSequence() {
        assertNull(LincolnClusterProtocol.sanitizeText(" ".repeat(32) + "终点"))
        assertNull(LincolnClusterProtocol.sanitizeText("a" + "\u0301".repeat(40) + "终点"))
    }
}
