package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The progress line used to ask for a new frame sixty times a second while a song played, on a card that
 * sits over the CarPlay picture. A three-minute song moves the end of that line about two pixels a
 * second, so almost every one of those frames drew the same picture. These numbers pin the replacement:
 * the frame rate follows the pixels the line still has to cover, bounded at both ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicProgressLineThrottleTest {
    private lateinit var context: Context
    private lateinit var line: MusicProgressLine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        line = MusicProgressLine(context)
    }

    private fun progress(positionMillis: Long, durationMillis: Long, playing: Boolean = true, receivedAt: Long = 1_000L) =
        MusicProgress(
            positionMillis = positionMillis,
            durationMillis = durationMillis,
            playing = playing,
            receivedAt = receivedAt,
        )

    /** A three-minute song on a card-wide line: no more than two frames a second, and never fewer. */
    @Test
    fun longTrackAsksForAFrameEveryHalfSecondAtMost() {
        val song = progress(positionMillis = 0L, durationMillis = 180_000L)
        val millis = line.nextFrameMillis(song, fraction = 0.05f, span = 300f, now = 1_500L)
        assertEquals(500L, millis)
    }

    /** A short track covers those pixels quickly, so the line follows it at the panel's own pace. */
    @Test
    fun shortTrackAsksForAFrameNoFasterThanFiftyMilliseconds() {
        val song = progress(positionMillis = 0L, durationMillis = 8_000L)
        val millis = line.nextFrameMillis(song, fraction = 0f, span = 300f, now = 1_500L)
        assertEquals(50L, millis)
    }

    /** In between, the wait is the time the line needs for one more pixel, not a fixed frame step. */
    @Test
    fun middleOfATrackWaitsForTheNextPixel() {
        // 60 s left over 150 px is 400 ms per pixel: inside both bounds, so it is used as it is.
        val song = progress(positionMillis = 120_000L, durationMillis = 180_000L, receivedAt = 120_000L)
        val millis = line.nextFrameMillis(song, fraction = 0.5f, span = 300f, now = 120_000L)
        assertEquals(400L, millis)
    }

    /** A finished line has no pixel left to reach: it settles instead of spinning on an empty span. */
    @Test
    fun lineAtTheEndSettlesToHalfASecond() {
        val song = progress(positionMillis = 180_000L, durationMillis = 180_000L, receivedAt = 0L)
        assertEquals(500L, line.nextFrameMillis(song, fraction = 1f, span = 300f, now = 180_000L))
    }
}
