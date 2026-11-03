package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The progress line's clock. The iPhone sends a position a few times a second at best, so the window
 * has to carry it forward itself; these pin that arithmetic and the two edges it must not cross.
 */
class MusicProgressTest {
    private fun progress(
        position: Long = 30_000,
        duration: Long = 200_000,
        playing: Boolean = true,
        receivedAt: Long = 1_000,
    ) = MusicProgress(position, duration, playing, receivedAt)

    @Test
    fun aPlayingSongMovesWithTheClock() {
        val song = progress()
        assertEquals(30_000, song.positionAt(1_000))
        assertEquals(31_500, song.positionAt(2_500))
    }

    @Test
    fun aPausedSongStaysWhereItWas() {
        val song = progress(playing = false)
        assertEquals(30_000, song.positionAt(1_000))
        assertEquals(30_000, song.positionAt(60_000))
    }

    @Test
    fun thePositionNeverRunsPastTheEnd() {
        val song = progress(position = 199_000, duration = 200_000)
        assertEquals(200_000, song.positionAt(1_000_000))
        assertEquals(1f, song.fractionAt(1_000_000), 0f)
    }

    @Test
    fun aSeekResetsTheAnchorImmediately() {
        val before = progress(position = 30_000, receivedAt = 1_000)
        val after = progress(position = 150_000, receivedAt = 10_000)
        assertEquals(150_000, after.positionAt(10_000))
        assertEquals(0.75f, after.fractionAt(10_000), 0.001f)
        assertEquals(0.15f, before.fractionAt(1_000), 0.001f)
    }

    @Test
    fun anEmptyLengthIsZeroRatherThanADivision() {
        assertEquals(0f, progress(duration = 0).fractionAt(1_000), 0f)
    }
}
