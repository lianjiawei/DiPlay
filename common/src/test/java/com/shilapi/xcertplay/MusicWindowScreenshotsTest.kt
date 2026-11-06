package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-xhdpi")
    fun lincolnQuarterWidth() = capture("lincoln-quarter-width", 480, 1080)

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-xhdpi")
    fun lincolnNarrowTwoLineLyric() = capture("lincoln-narrow-two-line-lyric", 320, 1080,
        title = "等风吹过长街\n再一起回家", scaleStep = 5, expectedLines = 2)

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-xhdpi")
    fun lincolnFineWidth() = capture("lincoln-fine-width", 384, 1080)

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-xhdpi")
    fun lincolnNarrowestTwoLineLyric() = capture("lincoln-narrowest-two-line-lyric", 240, 1080,
        title = "等风吹过\n再一起回家", scaleStep = 5, expectedLines = 2)

    @Test @Config(qualifiers = "zh-rCN-w600dp-h1200dp-port-xhdpi")
    fun portraitNarrowestTwoLineLyric() = capture("portrait-narrowest-two-line-lyric", 1080, 240,
        title = "等风吹过\n再一起回家", scaleStep = 5, expectedLines = 2)

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-xhdpi")
    fun lincolnGlassOnBrightCover() = capture("lincoln-glass-bright-cover", 480, 1080, brightCover = true)

    @Test @Config(qualifiers = "zh-rCN-w1920dp-h1080dp-land-xhdpi")
    fun lincolnGlassWithoutCover() = capture("lincoln-glass-no-cover", 320, 1080, hasCover = false)

    @Test @Config(qualifiers = "zh-rCN-w320dp-h600dp-port-xhdpi")
    fun compactTwoLineLyricWithLargestText() = capture("compact-two-line-lyric-large", 320, 480,
        title = "等风吹过长街\n再一起回家", scaleStep = 5, expectedLines = 2)

    private fun capture(name: String, width: Int, height: Int, title: String = "夜空中最亮的星",
        scaleStep: Int = 2, expectedLines: Int? = null, brightCover: Boolean = false, hasCover: Boolean = true) {
        RuntimeEnvironment.setFontScale(1.3f)
        MusicWindowSettings.setTextScaleStep(RuntimeEnvironment.getApplication(), scaleStep)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val window = CarPlayMusicWindow(controller.get())
        val cover = albumCover(brightCover)
        val song = CarPlayNowPlaying(title = title, artist = "逃跑计划", album = "世界",
            durationMillis = 240_000, elapsedMillis = 45_000, playing = true)
        try {
            controller.get().setContentView(window, ViewGroup.LayoutParams(width, height))
            CarPlayMediaKeys::class.java.getDeclaredField("nowPlaying").apply { isAccessible = true }.set(CarPlayMediaKeys, song)
            CarPlayMediaKeys::class.java.getDeclaredField("elapsedUpdatedAt").apply { isAccessible = true }
                .set(CarPlayMediaKeys, SystemClock.elapsedRealtime())
            ClusterMusicState.publish(song, cover.takeIf { hasCover })
            ShadowLooper.idleMainLooper()
            window.refreshPlayState()
            repeat(3) {
                window.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                window.layout(0, 0, width, height)
                ShadowLooper.idleMainLooper()
            }
            if (hasCover) {
                repeat(100) {
                    if (window.backdropView != null) return@repeat
                    Thread.sleep(5)
                    ShadowLooper.idleMainLooper()
                }
                assertTrue("album backdrop should be ready", window.backdropView != null)
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

    /** A drawn mountain cover exercises the glass with light and dark album colours without external assets. */
    private fun albumCover(bright: Boolean): Bitmap {
        val cover = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cover)
        // Robolectric's software Canvas does not apply the ImageView's hardware outline clip.
        // Give the synthetic cover the same 6% rounded corners the real view uses.
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(0f, 0f, 240f, 240f, 14.4f, 14.4f, Path.Direction.CW) })
        val sky = if (bright) Color.WHITE else Color.rgb(198, 231, 230)
        val horizon = if (bright) Color.rgb(236, 244, 241) else Color.rgb(91, 160, 172)
        canvas.drawRect(0f, 0f, 240f, 240f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, 240f, sky, horizon, Shader.TileMode.CLAMP)
        })
        fun mountain(color: Int, points: List<Pair<Float, Float>>) {
            val path = Path().apply {
                moveTo(0f, 240f)
                points.forEach { (x, y) -> lineTo(x, y) }
                lineTo(240f, 240f)
                close()
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        mountain(if (bright) Color.rgb(222, 234, 232) else Color.rgb(112, 170, 176),
            listOf(0f to 177f, 72f to 97f, 125f to 145f, 173f to 81f, 240f to 164f))
        mountain(if (bright) Color.rgb(201, 216, 215) else Color.rgb(49, 109, 119),
            listOf(0f to 194f, 47f to 149f, 97f to 179f, 155f to 115f, 240f to 190f))
        mountain(if (bright) Color.rgb(181, 201, 199) else Color.rgb(22, 61, 70),
            listOf(0f to 218f, 55f to 188f, 105f to 211f, 176f to 171f, 240f to 212f))
        canvas.restore()
        return cover
    }
}
