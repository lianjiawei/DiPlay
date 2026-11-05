package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.widget.FrameLayout
import android.widget.ImageView
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 27, 29, 33], qualifiers = "zh-rCN-w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MusicWindowArtworkTest {
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun artworkSurvivesMetadataCorrectionAndLyricsButClearsForNewReferenceOrExplicitClear() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val controller = mock(CarPlayController::class.java)
        val window = CarPlayMusicWindow(activity.get())
        val root = FrameLayout(activity.get())
        val first = CarPlayNowPlaying(title = "第一句歌词", artist = "歌曲", album = "专辑",
            sourceApp = "music.app", artworkTransferId = 7, durationMillis = 180_000,
            elapsedMillis = 6_000, playing = true)
        val firstCover = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val delayedCover = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val nextCover = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        var lastSnapshot = ClusterMusicState.Snapshot()
        val stop = ClusterMusicState.observe { lastSnapshot = it }
        try {
            root.addView(window, FrameLayout.LayoutParams(400, 600))
            activity.get().setContentView(root)
            `when`(controller.nowPlayingSnapshot()).thenReturn(first)
            CarPlayMediaKeys.attach(activity.get(), controller)
            idle()
            deliverArtwork(7, firstCover)
            idle()
            assertSame(firstCover, shownCover(window))

            // The phone can correct a duration after delivering the cover while reusing its ID.
            val inFlightOwner = ReflectionHelpers.getField<Any>(CarPlayMediaKeys, "artworkOwner")
            val corrected = first.copy(title = "第二句歌词", durationMillis = 181_000, elapsedMillis = 8_000)
            update(controller, corrected)
            assertSame(firstCover, lastSnapshot.artwork)
            assertSame(firstCover, shownCover(window))

            // A cover already decoding before the metadata correction must not be discarded.
            deliverArtwork(7, delayedCover, inFlightOwner)
            idle()
            assertFalse(delayedCover.isRecycled)
            assertSame(delayedCover, shownCover(window))

            // Other incoming images can evict this reference from the bounded cache. A later lyric
            // update still has to keep the already displayed cover in the independent window.
            repeat(4) { deliverArtwork(40 + it, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) }
            idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
            update(controller, corrected.copy(title = "第三句歌词", elapsedMillis = 14_000))
            assertNull(lastSnapshot.artwork)
            assertSame(delayedCover, shownCover(window))
            root.removeView(window)
            root.addView(window, FrameLayout.LayoutParams(400, 600))
            idle()
            assertSame(delayedCover, shownCover(window))

            val next = corrected.copy(title = "下一首", artworkTransferId = 8, elapsedMillis = 0)
            update(controller, next)
            assertNull(shownCover(window))
            deliverArtwork(8, nextCover)
            idle()
            assertSame(nextCover, shownCover(window))

            // Reused transfer IDs across apps must not carry an old app's cover forward.
            val oldOwner = ReflectionHelpers.getField<Any>(CarPlayMediaKeys, "artworkOwner")
            update(controller, next.copy(title = "其他应用", sourceApp = "other.music.app"))
            assertNull(shownCover(window))
            val staleCover = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            deliverArtwork(8, staleCover, oldOwner)
            idle()
            assertTrue(staleCover.isRecycled)
            assertNull(shownCover(window))
            val currentCover = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            deliverArtwork(8, currentCover)
            idle()
            assertSame(currentCover, shownCover(window))

            // A genuine explicit empty image must still clear the cover.
            deliverArtwork(8, null)
            idle()
            assertNull(shownCover(window))
            deliverArtwork(8, currentCover)
            idle()
            assertSame(currentCover, shownCover(window))
            CarPlayMediaKeys.detach(controller)
            idle()
            assertNull(shownCover(window))
        } finally {
            stop()
            CarPlayMediaKeys.detach(controller)
            activity.pause().stop().destroy()
            idle()
        }
    }

    private fun shownCover(window: CarPlayMusicWindow): Bitmap? =
        (ReflectionHelpers.getField<ImageView>(window, "artwork").drawable as? BitmapDrawable)?.bitmap

    private fun update(controller: CarPlayController, info: CarPlayNowPlaying) {
        `when`(controller.nowPlayingSnapshot()).thenReturn(info)
        CarPlayMediaKeys::class.java.getDeclaredMethod("onNowPlayingChanged", CarPlayController::class.java)
            .apply { isAccessible = true }.invoke(CarPlayMediaKeys, controller)
        idle()
    }

    private fun deliverArtwork(id: Int, bitmap: Bitmap?, expectedOwner: Any? = null) {
        val type = Class.forName("com.shilapi.xcertplay.CarPlayMediaKeys\$AmbientArtwork")
        val result = bitmap?.let {
            type.getDeclaredConstructor(Bitmap::class.java, Integer::class.java)
                .apply { isAccessible = true }.newInstance(it, null)
        }
        val owner = expectedOwner ?: ReflectionHelpers.getField<Any>(CarPlayMediaKeys, "artworkOwner")
        CarPlayMediaKeys::class.java.getDeclaredMethod("onArtworkDecoded", Any::class.java, Int::class.javaPrimitiveType, type)
            .apply { isAccessible = true }.invoke(CarPlayMediaKeys, owner, id, result)
    }
}
