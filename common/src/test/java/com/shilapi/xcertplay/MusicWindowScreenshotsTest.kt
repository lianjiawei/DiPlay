package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicWindowScreenshotsTest {
    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-xhdpi")
    fun compactLargeText() = capture("compact-zh", 320, 480)

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-mdpi")
    fun lincolnLargeText() = capture("lincoln-1920x1080-zh", 640, 1080)

    @Test @Config(qualifiers = "en-w1920dp-h720dp-land-mdpi")
    fun ultraWideLargeText() = capture("ultrawide-1920x720-en", 640, 720)

    @Test @Config(qualifiers = "en-w600dp-h1200dp-port-xhdpi")
    fun portraitStripLargeText() = capture("portrait-en", 1080, 480)

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-xhdpi")
    fun lincolnTwoLineLyric() = capture("lincoln-two-line-lyric", 640, 1080,
        title = "等风吹过长街\n再一起回家", expectedLines = 2)

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-xhdpi")
    fun compactTwoLineLyricWithLargestText() = capture("compact-two-line-lyric-large", 320, 480,
        title = "等风吹过长街\n再一起回家", scaleStep = 5, expectedLines = 2)

    private fun capture(name: String, width: Int, height: Int, title: String = "夜空中最亮的星",
        scaleStep: Int = 2, expectedLines: Int? = null) {
        RuntimeEnvironment.setFontScale(1.3f)
        MusicWindowSettings.setTextScaleStep(RuntimeEnvironment.getApplication(), scaleStep)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val window = CarPlayMusicWindow(controller.get())
        val cover = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        Canvas(cover).drawRect(0f, 0f, 240f, 240f, Paint().apply {
            shader = LinearGradient(0f, 0f, 240f, 240f, Color.rgb(30, 92, 148),
                Color.rgb(160, 66, 90), Shader.TileMode.CLAMP)
        })
        val song = CarPlayNowPlaying(title = title, artist = "逃跑计划", album = "世界",
            durationMillis = 240_000, elapsedMillis = 45_000, playing = true)
        try {
            controller.get().setContentView(window, ViewGroup.LayoutParams(width, height))
            CarPlayMediaKeys::class.java.getDeclaredField("nowPlaying").apply { isAccessible = true }.set(CarPlayMediaKeys, song)
            CarPlayMediaKeys::class.java.getDeclaredField("elapsedUpdatedAt").apply { isAccessible = true }
                .set(CarPlayMediaKeys, SystemClock.elapsedRealtime())
            ClusterMusicState.publish(song, cover)
            ShadowLooper.idleMainLooper()
            window.refreshPlayState()
            repeat(3) {
                window.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                window.layout(0, 0, width, height)
                ShadowLooper.idleMainLooper()
            }
            repeat(100) {
                if (window.backdropView != null) return@repeat
                Thread.sleep(5)
                ShadowLooper.idleMainLooper()
            }
            assertTrue("cover should fit", window.coverView.width in 1..width && window.coverView.height <= height)
            assertTrue("transport controls should fit", window.transportView.bottom <= height)
            expectedLines?.let {
                val layout = requireNotNull(window.titleTextView.layout)
                assertEquals(it, layout.lineCount)
                assertTrue((0 until layout.lineCount).all { line -> layout.getEllipsisCount(line) == 0 })
                assertTrue(layout.height <= window.titleTextView.height)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                window.draw(Canvas(bitmap))
                val output = Paths.get("build/reports/music-window-ui/$name.png")
                Files.createDirectories(output.parent)
                Files.newOutputStream(output).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                if (name == "lincoln-two-line-lyric") {
                    fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                        window.offsetDescendantRectToMyCoords(view, it)
                    }
                    val top = (bounds(window.titleTextView).top - 20).coerceAtLeast(0)
                    val bottom = (bounds(window.transportView).bottom + 20).coerceAtMost(height)
                    val detail = Bitmap.createBitmap(bitmap, 0, top, width, bottom - top)
                    try {
                        val detailOutput = output.parent.resolve("lincoln-two-line-lyric-detail.png")
                        Files.newOutputStream(detailOutput).use {
                            assertTrue(detail.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { if (detail !== bitmap) detail.recycle() }
                }
            } finally { bitmap.recycle() }
        } finally {
            controller.pause().stop().destroy()
            ClusterMusicState.publish(CarPlayNowPlaying(), null)
            CarPlayMediaKeys::class.java.getDeclaredField("nowPlaying").apply { isAccessible = true }
                .set(CarPlayMediaKeys, CarPlayNowPlaying())
            ShadowLooper.idleMainLooper()
            cover.recycle()
            RuntimeEnvironment.setFontScale(1f)
            MusicWindowSettings.setTextScaleStep(RuntimeEnvironment.getApplication(), 2)
        }
    }
}
