package com.shilapi.xcertplay.lincoln

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LincolnRoadRotationTest {
    private val token = LincolnManeuverToken(1.1, 1.2)
    private val road = "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸"

    @Test fun splitsTheOriginalRoadSafelyAndBoundsTheTail() {
        assertEquals(listOf("短路名"), LincolnClusterProtocol.roadSegments("短路名"))
        assertEquals(emptyList<String>(), LincolnClusterProtocol.roadSegments(" (null) "))
        assertEquals(listOf("一二三四五六七...", "八九十甲乙丙丁...", "戊己庚辛壬癸"),
            LincolnClusterProtocol.roadSegments(road))
        assertEquals(listOf("中".repeat(7) + "...", "中".repeat(7) + "...", "中".repeat(7) + "..."),
            LincolnClusterProtocol.roadSegments("中".repeat(40)))
        val emoji = LincolnClusterProtocol.roadSegments("中".repeat(6) + "🎵" + "道路".repeat(5))
        assertEquals("中".repeat(6) + "...", emoji.first())
        assertTrue(emoji[1].startsWith("🎵"))
        assertTrue(emoji.all { it.length <= 10 })
    }

    @Test fun rotationKeepsTheHeadForFiveSecondsAndNeverBurstsAfterAStall() {
        val rotation = LincolnRoadRotation()
        fun at(seconds: Long) = rotation.select(road, "old", true, token, seconds * 1_000_000_000L)
        assertEquals("一二三四五六七...", at(0))
        assertEquals("一二三四五六七...", at(1))
        assertEquals("八九十甲乙丙丁...", at(2))
        assertEquals("戊己庚辛壬癸", at(4))
        assertEquals("一二三四五六七...", at(6))
        assertEquals("一二三四五六七...", at(10))
        assertEquals("八九十甲乙丙丁...", at(11))
        assertEquals("戊己庚辛壬癸", at(100))
        assertEquals("戊己庚辛壬癸", at(100))
    }

    @Test fun switchingOffAndChangingManeuversRestartFromTheHead() {
        val rotation = LincolnRoadRotation()
        rotation.select(road, "legacy", true, token, 0)
        assertEquals("八九十甲乙丙丁...", rotation.select(road, "legacy", true, token, 2_000_000_000L))
        assertEquals("legacy", rotation.select(road, "legacy", false, token, 3_000_000_000L))
        assertEquals("一二三四五六七...", rotation.select(road, "legacy", true, token, 4_000_000_000L))
        val next = LincolnManeuverToken(1.3, 1.4)
        assertEquals("一二三四五六七...", rotation.select(road, "legacy", true, next, 6_000_000_000L))
    }
}
