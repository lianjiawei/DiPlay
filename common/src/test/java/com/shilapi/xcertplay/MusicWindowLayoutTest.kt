package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.os.Looper
import android.transition.Transition
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.host.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

/**
 * Lays the music window out at the sizes the strip really takes on the head unit.
 *
 * The first build set its text sizes as pixels through the sp scale, so the title came out about three
 * times too large, ran past the strip and squeezed the cover down to a thumbnail. The second build gave the
 * keys a row from a weight and a size from the block's height, so the play key came out taller than its row
 * and the card cut its bottom off. These numbers keep the lines and the keys whole at every screen
 * resolution the window meets — the strip is a third of a 1024-wide screen on one head unit and a half of a
 * 2560-wide one on another, the density runs from 1 to 3, and a portrait screen puts the strip on its side.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicWindowLayoutTest {
    private val controllers = mutableListOf<ActivityController<Activity>>()

    @After
    fun clearSong() {
        // Destroy the activity, not just drop it: a resumed window is counted by AppScreens, and these
        // tests would otherwise leave four of them in front and fail that suite.
        // Pause and stop before destroying: a resumed screen that is destroyed directly never reports
        // its stop, and AppScreens counts it as in front for the rest of the JVM.
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        controllers.clear()
        putNowPlaying(CarPlayNowPlaying())
        MusicWindowSettings.setTextScale(RuntimeEnvironment.getApplication(), 1f)
        RuntimeEnvironment.setQualifiers("+mdpi")
    }

    private fun putNowPlaying(value: CarPlayNowPlaying, elapsedAt: Long = SystemClock.elapsedRealtime()) {
        CarPlayMediaKeys::class.java.getDeclaredField("nowPlaying")
            .apply { isAccessible = true }.set(CarPlayMediaKeys, value)
        CarPlayMediaKeys::class.java.getDeclaredField("elapsedUpdatedAt")
            .apply { isAccessible = true }.set(CarPlayMediaKeys, elapsedAt)
    }

    private fun song(playing: Boolean = true) = CarPlayNowPlaying(
        title = "DON JULIO LEMONADE",
        artist = "DaBaby",
        album = "BE MORE G...",
        durationMillis = 197_000,
        elapsedMillis = 42_000,
        playing = playing,
    )

    private fun window(
        width: Int,
        height: Int,
        withSong: Boolean = true,
        cover: Bitmap? = null,
    ): CarPlayMusicWindow {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        controllers += controller
        val activity = controller.get()
        val window = CarPlayMusicWindow(activity)
        window.applyPalette(DiPlayPalette.DARK)
        activity.setContentView(window, ViewGroup.LayoutParams(width, height))
        ClusterMusicState.publish(if (withSong) song() else CarPlayNowPlaying(), cover)
        putNowPlaying(if (withSong) song() else CarPlayNowPlaying())
        // Twice: the first pass tells the window how wide the strip is, the second lays the cover out.
        repeat(3) {
            window.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            window.layout(0, 0, width, height)
            ShadowLooper.idleMainLooper()
        }
        return window
    }

    private fun awaitBackdrop(window: CarPlayMusicWindow): Bitmap? {
        repeat(200) {
            ShadowLooper.idleMainLooper()
            window.backdropView?.let { return it }
            Thread.sleep(5)
        }
        return window.backdropView
    }

    /** Every key inside its row, and the row inside the card: the cut-off play key was this, failing. */
    private fun assertKeysWhole(window: CarPlayMusicWindow, label: String, height: Int) {
        val row = window.transportView
        assertTrue("$label: keys row ${row.width}x${row.height}", row.width > 0 && row.height > 0)
        assertTrue("$label: keys row ends at ${row.bottom}", row.bottom <= height)
        for (button in window.transportButtons) {
            assertTrue(
                "$label: key ${button.width}x${button.height} in a ${row.height}px row",
                button.height <= row.height && button.width <= row.width,
            )
            assertTrue("$label: key ends at ${button.bottom}", button.bottom <= row.bottom)
        }
        // The play key is the row, so a row that was too short for it shows up here as well.
        assertEquals("$label: play key against its row", row.height, window.transportButtons[1].height)
    }

    @Test
    fun noKeyIsTallerThanItsRow() {
        // The strip the head unit left in the screenshot: 2/3 CarPlay on a 2376x1080 screen.
        for ((width, height) in listOf(
            792 to 1080,
            660 to 2376,
            1280 to 1080,
            682 to 600,
            1080 to 640,
            320 to 480,
        )) {
            val window = window(width, height)
            assertKeysWhole(window, "${width}x$height", height)
        }
    }

    @Test
    fun tallStripGivesTheCoverTheWidth() {
        val width = 660
        val height = 2376
        val window = window(width, height)
        val cover = window.coverView
        assertTrue("cover ${cover.width}x${cover.height} is a square", cover.width == cover.height)
        assertTrue("cover ${cover.width} fills the ${width}px strip", cover.width >= width * 0.75f)
        // A 660px strip wants a title around 35px: much above that is the sp-scale mistake coming back.
        assertTrue("title size ${window.titleTextView.textSize}", window.titleTextView.textSize in 28f..60f)
    }

    @Test
    fun flatStripStandsTheCardSideways() {
        val width = 2376
        val height = 792
        val window = window(width, height)
        assertEquals(height, window.measuredHeight)
        assertEquals(LinearLayout.HORIZONTAL, window.contentOrientation)
        val cover = window.coverView
        val info = window.textBlockView
        assertTrue("cover ${cover.width} keeps most of the ${height}px height", cover.width >= height * 0.6f)
        assertTrue("cover ends at ${cover.right}, lines start at ${info.left}", info.left >= cover.right)
        assertKeysWhole(window, "2376x792", height)
    }

    @Test
    fun progressLineSitsUnderTheAlbumAndShowsThePosition() {
        val window = window(660, 2376)
        val line = window.progressView
        assertEquals(View.VISIBLE, line.visibility)
        assertTrue("line is ${line.width}x${line.height}", line.width > 0 && line.height > 0)
        assertTrue("line ends at ${line.bottom}", line.bottom <= 2376)
        assertTrue("line is narrower than the card", line.width <= window.coverView.width + 1)
    }

    @Test
    fun progressLineDisappearsWithoutALength() {
        val window = window(660, 1200, withSong = false)
        putNowPlaying(CarPlayNowPlaying(title = "No length", playing = true, elapsedMillis = 1_000))
        window.refreshPlayState()
        ShadowLooper.idleMainLooper()
        // Invisible, not gone: the block keeps its height so the lines above do not jump.
        assertEquals(View.INVISIBLE, window.progressView.visibility)
    }

    /**
     * The strip on every screen the head unit ships: the three shares CarPlay can take (two thirds, half,
     * one third) of the usual resolutions, portrait included, at the density each one runs at.
     */
    @Test
    fun everyStripKeepsTheCardInside() {
        val screens = listOf(
            Triple(1024, 600, "mdpi"),
            Triple(1280, 720, "hdpi"),
            Triple(1920, 720, "xhdpi"),
            Triple(1920, 1080, "xhdpi"),
            Triple(2560, 1440, "xxhdpi"),
            Triple(1080, 1920, "xhdpi"),
        )
        var checked = 0
        for ((screenWidth, screenHeight, density) in screens) {
            RuntimeEnvironment.setQualifiers("+$density")
            for (sixths in listOf(4, 3, 2)) {
                val portrait = screenHeight > screenWidth
                val width = if (portrait) screenWidth else screenWidth * sixths / 6
                val height = if (portrait) screenHeight * sixths / 6 else screenHeight
                val window = window(width, height)
                val label = "${screenWidth}x$screenHeight@$density sixths=$sixths strip=${width}x$height"
                for (index in 0 until window.childCount) {
                    val child = window.getChildAt(index)
                    if (child.visibility == View.GONE) continue
                    assertTrue("$label: child $index left ${child.left}", child.left >= 0)
                    assertTrue("$label: child $index right ${child.right}", child.right <= width)
                    assertTrue("$label: child $index bottom ${child.bottom}", child.bottom <= height)
                }
                val cover = window.coverView
                assertTrue("$label: cover ${cover.width}", cover.width == cover.height && cover.width > 0)
                assertTrue("$label: cover ${cover.width} on $width wide", cover.width <= width)
                assertTrue("$label: title ${window.titleTextView.textSize}", window.titleTextView.textSize >= 18f)
                assertKeysWhole(window, label, height)
                assertEquals("$label: line", View.VISIBLE, window.progressView.visibility)
                assertTrue("$label: line ${window.progressView.height}", window.progressView.height > 0)
                checked++
            }
        }
        assertEquals(18, checked)
    }

    /** The card never overflows a strip too short for its natural size, and the type gives way first. */
    @Test
    fun shortStripShrinksTheCard() {
        // A 1920x720 head unit with CarPlay taking two thirds leaves a 640x720 strip; a 1/3 strip of a
        // 1080-wide portrait screen is 1080x640, whose natural card is taller than the strip.
        for ((width, height) in listOf(640 to 720, 1080 to 640, 1080 to 480, 320 to 480)) {
            val window = window(width, height)
            val keys = window.transportView
            assertTrue("${width}x$height: keys end at ${keys.bottom}", keys.bottom <= height)
            assertTrue("${width}x$height: cover ${window.coverView.width}", window.coverView.width > 0)
            assertTrue("${width}x$height: title ${window.titleTextView.textSize}", window.titleTextView.textSize >= 12f)
            assertKeysWhole(window, "${width}x$height", height)
        }
    }

    /**
     * The block of lines and keys has the height its own parts measure, and the cover takes what is left:
     * the strip is filled edge to edge, not by a cover that only happens to fit.
     */
    @Test
    fun theCoverTakesWhateverTheTextBlockLeaves() {
        val height = 1080
        val width = 1280
        val window = window(width, height)
        val block = window.textBlockView
        val cover = window.coverView
        val pad = (minOf(width, height) * 0.05f).toInt()
        assertTrue("block ${block.height}", block.height in (height * 0.18f).toInt()..(height * 0.45f).toInt())
        assertTrue(
            "cover ${cover.height} + block ${block.height} should nearly fill ${height - pad * 2}",
            cover.height + block.height >= (height - pad * 2) * 0.9f,
        )
        assertTrue("cover ${cover.width} is a square", cover.width == cover.height)
        // The block is the sum of its parts: nothing inside it was squeezed to make it fit.
        val parts = window.titleTextView.height + window.metaTextView.height +
            window.progressView.height + window.transportView.height
        assertTrue("parts $parts of block ${block.height}", parts <= block.height)
    }

    /** Picking a step in the settings moves the text and the block together; the card carries no key. */
    @Test
    fun theChosenStepGrowsTheTextAndTheBlockTogether() {
        val window = window(1280, 1080)
        val beforeText = window.titleTextView.textSize
        val beforeBlock = window.textBlockView.height
        MusicWindowSettings.setTextScaleStep(RuntimeEnvironment.getApplication(), 3)
        window.reloadTextScale()
        relayout(window, 1280, 1080)
        assertEquals(1.22f, window.textScaleInUse, 0.001f)
        assertTrue("text ${beforeText} -> ${window.titleTextView.textSize}", window.titleTextView.textSize > beforeText)
        assertTrue("block ${beforeBlock} -> ${window.textBlockView.height}", window.textBlockView.height > beforeBlock)
        assertKeysWhole(window, "1280x1080 step 3", 1080)
    }

    /**
     * The fifth step is for reading from the far seat: from the fourth, the lines and the bar both grow
     * again, and the keys still end inside the strip.
     */
    @Test
    fun theFifthStepGoesBeyondTheFourth() {
        // The strip the head unit leaves beside a 2/3 CarPlay picture: tall and narrow, not a band.
        val window = window(792, 1080)
        MusicWindowSettings.setTextScaleStep(RuntimeEnvironment.getApplication(), 4)
        window.reloadTextScale()
        relayout(window, 792, 1080)
        assertEquals(1.45f, window.textScaleInUse, 0.001f)
        val fourthText = window.titleTextView.textSize
        val fourthBar = window.progressView.layoutParams.height
        MusicWindowSettings.setTextScaleStep(RuntimeEnvironment.getApplication(), 5)
        window.reloadTextScale()
        relayout(window, 792, 1080)
        assertEquals(1.7f, window.textScaleInUse, 0.001f)
        assertTrue(
            "text $fourthText -> ${window.titleTextView.textSize}",
            window.titleTextView.textSize > fourthText,
        )
        assertTrue(
            "bar $fourthBar -> ${window.progressView.layoutParams.height}",
            window.progressView.layoutParams.height > fourthBar,
        )
        assertKeysWhole(window, "792x1080 step 5", 1080)
    }

    private fun relayout(window: CarPlayMusicWindow, width: Int, height: Int) {
        repeat(3) {
            window.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            window.layout(0, 0, width, height)
            ShadowLooper.idleMainLooper()
        }
    }

    @Test fun twoLineLyricsReflowWithoutOverlappingAndReturnToTheShortLineLayout() {
        for ((width, height) in listOf(640 to 1080, 480 to 1080, 320 to 1080, 320 to 480, 1080 to 480, 1080 to 240)) {
            for (step in listOf(2, 5)) {
                MusicWindowSettings.setTextScaleStep(RuntimeEnvironment.getApplication(), step)
                val window = window(width, height)
                fun publish(title: String) {
                    ClusterMusicState.publish(song().copy(title = title), null)
                    ShadowLooper.idleMainLooper()
                    relayout(window, width, height)
                }
                fun bounds(view: View): Rect = Rect(0, 0, view.width, view.height).also {
                    window.offsetDescendantRectToMyCoords(view, it)
                }
                publish("星光")
                val coverBefore = bounds(window.coverView)
                val keysBefore = bounds(window.transportView)
                publish("等风吹过长街\n再一起回家")
                val title = window.titleTextView
                val layout = requireNotNull(title.layout)
                val label = "${width}x$height step=$step"
                assertEquals(label, 2, layout.lineCount)
                assertEquals("$label first line clipped", 0, layout.getEllipsisCount(0))
                assertEquals("$label second line clipped", 0, layout.getEllipsisCount(1))
                assertTrue("$label text clipped vertically", layout.height <= title.height)
                assertTrue("$label title overlaps its metadata", bounds(title).bottom <= bounds(window.metaTextView).top)
                assertTrue("$label metadata overlaps progress", bounds(window.metaTextView).bottom <= bounds(window.progressView).top)
                assertTrue("$label progress overlaps controls", bounds(window.progressView).bottom <= bounds(window.transportView).top)
                val cover = bounds(window.coverView)
                val text = bounds(window.textBlockView)
                if (window.contentOrientation == LinearLayout.VERTICAL) {
                    assertTrue("$label cover overlaps text", cover.bottom <= text.top)
                } else {
                    assertTrue("$label cover overlaps text", cover.right <= text.left)
                }
                assertTrue("$label controls outside card", bounds(window.transportView).bottom <= height)
                publish("星光")
                assertEquals("$label cover did not return after the lyric changed", coverBefore, bounds(window.coverView))
                assertEquals("$label controls did not return after the lyric changed", keysBefore, bounds(window.transportView))
            }
        }
    }

    @Test fun tallNarrowWindowsUseTheAvailableHeightWithoutClipping() {
        RuntimeEnvironment.setQualifiers("+xhdpi")
        for (width in listOf(240, 320, 384, 480, 640)) {
            for (step in 1..5) {
                MusicWindowSettings.setTextScaleStep(RuntimeEnvironment.getApplication(), step)
                val window = window(width, 1080)
                fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                    window.offsetDescendantRectToMyCoords(view, it)
                }
                val cover = bounds(window.coverView)
                val text = bounds(window.titleTextView)
                val progress = bounds(window.progressView)
                val keys = bounds(window.transportView)
                val label = "width=$width step=$step"
                assertTrue("$label cover should use the upper part", cover.top < 1080 * 0.30)
                assertTrue("$label controls should use the lower part", keys.bottom > 1080 * 0.70)
                assertTrue("$label cover/title overlap", cover.bottom <= text.top)
                assertTrue("$label text/progress overlap", text.bottom <= progress.top)
                assertTrue("$label progress/controls overlap", progress.bottom <= keys.top)
                assertTrue("$label controls cut off", keys.bottom <= 1080)
                assertKeysWhole(window, label, 1080)
            }
        }
    }

    @Test @Config(sdk = [27, 34], qualifiers = "en")
    fun lyricWrapAnimatesOnceWhileOrdinaryUpdatesDoNot() {
        val window = window(640, 1080)
        ReflectionHelpers.callInstanceMethod<Unit>(window, "onWindowVisibilityChanged",
            ReflectionHelpers.ClassParameter(Int::class.javaPrimitiveType, View.VISIBLE))
        fun publishAndLayout(title: String) {
            ClusterMusicState.publish(song().copy(title = title), null)
            ShadowLooper.idleMainLooper()
            relayout(window, 640, 1080)
            window.viewTreeObserver.dispatchOnPreDraw()
            shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        }
        publishAndLayout("星光")
        val transition = ReflectionHelpers.getField<Transition>(window, "reflowTransition")
        var starts = 0
        transition.addListener(object : Transition.TransitionListener {
            override fun onTransitionStart(transition: Transition) { starts++ }
            override fun onTransitionEnd(transition: Transition) = Unit
            override fun onTransitionCancel(transition: Transition) = Unit
            override fun onTransitionPause(transition: Transition) = Unit
            override fun onTransitionResume(transition: Transition) = Unit
        })
        publishAndLayout("星光明亮")
        assertEquals("A one-line lyric update should not animate", 0, starts)
        publishAndLayout("等风吹过长街\n再一起回家")
        assertEquals("A lyric wrap should animate the layout", 1, starts)
        val play = window.transportButtons[1]
        play.isPressed = true
        publishAndLayout("星光")
        assertEquals("A pressed control must not start a moving transition", 1, starts)
        play.isPressed = false
        publishAndLayout("等风吹过长街\n再一起回家")
        assertEquals("A later wrap should animate normally", 2, starts)
        window.visibility = View.GONE
        ClusterMusicState.publish(song().copy(title = "短歌词"), null)
        ShadowLooper.idleMainLooper()
        assertEquals("A hidden window must not start animations", 2, starts)
    }

    @Test fun lyricsLongerThanTwoLinesAreEllipsizedWithoutOverlappingTheControls() {
        val window = window(640, 1080)
        val long = "第一行看见远方的星光\n第二行我们一起走过长街\n第三行还有更远的路"
        ClusterMusicState.publish(song().copy(title = long), null)
        ShadowLooper.idleMainLooper()
        relayout(window, 640, 1080)
        val layout = requireNotNull(window.titleTextView.layout)
        assertEquals(2, layout.lineCount)
        assertTrue("the hidden remainder must be marked", layout.getEllipsisCount(1) > 0)
        assertTrue(layout.height <= window.titleTextView.height)
        assertTrue(window.transportView.bottom <= window.textBlockView.height)
    }

    @Test fun aLongUnbrokenLyricCanWrapNaturallyIntoTwoReadableLines() {
        val window = window(640, 1080)
        val lyric = "我们沿着星光走过长街然后一起回到温暖的家"
        ClusterMusicState.publish(song().copy(title = lyric), null)
        ShadowLooper.idleMainLooper()
        relayout(window, 640, 1080)
        val layout = requireNotNull(window.titleTextView.layout)
        assertEquals(2, layout.lineCount)
        assertEquals(0, layout.getEllipsisCount(0))
        assertEquals(0, layout.getEllipsisCount(1))
        assertTrue(layout.height <= window.titleTextView.height)
    }

    /** The performer and the album share one line, so the keys have room below them. */
    @Test
    fun thePerformerAndTheAlbumShareOneLine() {
        val window = window(1280, 1080)
        val meta = window.metaTextView
        assertEquals(View.VISIBLE, meta.visibility)
        assertEquals(1, meta.maxLines)
        assertEquals("DaBaby · BE MORE G...", meta.text.toString())
        // One line of text is shorter than the title block above it.
        assertTrue("meta ${meta.height} against title ${window.titleTextView.height}", meta.height < window.titleTextView.height)
    }

    /** The card takes its corner from the CarPlay setting, so the two edges cannot disagree. */
    @Test
    fun theCardTakesItsCornerFromTheSetting() {
        val window = window(1280, 1080, withSong = false)
        window.setCornerRadiusDp(30)
        assertEquals(30f, window.cornerRadius, 0.01f)
        window.setCornerRadiusDp(0)
        assertEquals(0f, window.cornerRadius, 0.01f)
    }

    /** The backdrop is the album, blurred once: it survives a repaint and changes when the album does. */
    @Test
    fun theBackdropIsTheBlurredAlbum() {
        val cover = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(200, 60, 40))
        }
        val window = window(660, 1200, cover = cover)
        val backdrop = awaitBackdrop(window)
        assertNotNull("backdrop was not built", backdrop)
        // A quarter of the card, not a 96-pixel stamp: stretched from that, the blur read as a mosaic.
        assertTrue("backdrop is ${backdrop!!.width} wide", backdrop.width in 120..320)
        assertTrue("backdrop is ${backdrop.width}x${backdrop.height}", backdrop.height in 40..400)
        assertTrue("bitmap was recycled", !backdrop.isRecycled)
    }

    @Test fun brightArtworkKeepsTheSongAndPerformerReadableInTheGlass() {
        RuntimeEnvironment.setQualifiers("+xhdpi")
        for ((width, height) in listOf(240 to 1080, 320 to 1080, 384 to 1080, 480 to 1080, 640 to 1080, 1080 to 480, 1080 to 240)) {
            for (step in listOf(2, 5)) {
                MusicWindowSettings.setTextScaleStep(RuntimeEnvironment.getApplication(), step)
                val cover = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                val window = window(width, height, cover = cover)
                assertNotNull(awaitBackdrop(window))
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    window.draw(Canvas(bitmap))
                    for ((label, view, minimum) in listOf(
                        Triple("title", window.titleTextView, 4.5),
                        Triple("performer", window.metaTextView, 3.0),
                    )) {
                        val bounds = Rect(0, 0, view.width, view.height).also {
                            window.offsetDescendantRectToMyCoords(view, it)
                        }
                        // Outside the cover and text glyphs, on the same row; the white input is uniform.
                        val sampleX = if (window.contentOrientation == LinearLayout.HORIZONTAL) width - 8 else 8
                        val background = bitmap.getPixel(sampleX, bounds.centerY())
                        val contrast = ColorUtils.calculateContrast(view.currentTextColor, background)
                        assertTrue("${width}x$height step=$step $label contrast=$contrast", contrast >= minimum)
                    }
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun lateBackdropWorkCannotRestoreAnOldCoverOrDetachedWindow() {
        val red = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val window = window(660, 1200, cover = red)
        ClusterMusicState.publish(song(), blue)
        ShadowLooper.idleMainLooper()
        val worker = CarPlayMusicWindow::class.java.getDeclaredField("BACKDROP_EXECUTOR")
            .apply { isAccessible = true }.get(null) as ExecutorService
        worker.submit {}.get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
        val latest = requireNotNull(window.backdropView)
        val pixel = latest.getPixel(latest.width / 2, latest.height / 2)
        assertTrue("the old red cover must not replace the new blue cover", Color.blue(pixel) > Color.red(pixel))
        (window.parent as ViewGroup).removeView(window)
        worker.submit {}.get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
        assertNull(window.backdropView)
        assertTrue("the shared cover is never recycled", !red.isRecycled && !blue.isRecycled)
    }

    /** A same-size cover must not be blurred in place: createScaledBitmap hands back the source. */
    @Test
    fun aCoverAlreadyThumbnailSizedIsNotPaintedOver() {
        val cover = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(30, 120, 200))
        }
        val before = cover.getPixel(48, 48)
        val window = window(660, 1200, cover = cover)
        awaitBackdrop(window)
        assertEquals(before, cover.getPixel(48, 48))
    }

    /**
     * The blur reaches the card. [StackBlur.blur] works on a copy and returns it, and the first builds
     * wrote the array that went in — the sharp thumbnail — so the glass was never blurred at all: a white
     * spot on a black cover stayed white at 255. Blurred, it cannot: the same energy is spread over the
     * radius.
     */
    @Test
    fun theBackdropIsBlurredNotJustShrunk() {
        val cover = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.BLACK)
            canvas.drawRect(98f, 98f, 102f, 102f, Paint().apply { color = Color.WHITE })
        }
        val window = window(660, 1200, cover = cover)
        val backdrop = awaitBackdrop(window)!!
        var brightest = 0
        for (y in 0 until backdrop.height) for (x in 0 until backdrop.width) {
            brightest = maxOf(brightest, backdrop.getPixel(x, y) and 0xFF)
        }
        assertTrue("brightest pixel of the backdrop is $brightest: the array was not blurred", brightest < 200)
    }

    /**
     * The card fills the strip. A gap towards CarPlay left a black band down the card's left edge and
     * along its bottom, under a blurred album that stops at the card's edge and never reaches the screen's.
     */
    @Test
    fun theCardReachesTheStripsEdges() {
        val cover = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(200, 60, 40))
        }
        val width = 792
        val height = 1080
        val window = window(width, height, cover = cover)
        awaitBackdrop(window)
        val screen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        canvas.drawColor(Color.BLACK)
        window.draw(canvas)
        for ((label, x, y) in listOf(
            Triple("left", 2, height / 2),
            Triple("right", width - 2, height / 2),
            Triple("top", width / 2, 2),
            Triple("bottom", width / 2, height - 2),
        )) {
            val pixel = screen.getPixel(x, y)
            assertTrue("$label edge of the card is ${Integer.toHexString(pixel)}", Color.red(pixel) > 12)
        }
    }

    /** The line and the keys sit in their own bands: close up, the block read as one grey slab. */
    @Test
    fun theLineAndTheKeysHaveRoomAroundThem() {
        val window = window(792, 1080)
        val title = window.titleTextView.textSize
        val above = window.progressView.top - window.metaTextView.bottom
        val below = window.transportView.top - window.progressView.bottom
        assertTrue("line starts $above below the album line", above >= title * 0.25f)
        assertTrue("keys start $below below the line", below >= title * 0.28f)
    }

    /**
     * A rounded corner is a cut, and what shows through the cut is the screen's black. The window used to
     * leave those four slivers to the page behind it — white in the day theme — and every corner of the card
     * came out as a small white notch bitten out of the glass.
     */
    @Test
    fun theCornersOutsideTheCardAreTheScreensBlack() {
        val cover = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(Color.rgb(30, 60, 120))
        }
        val width = 792
        val height = 1080
        val window = window(width, height, cover = cover)
        window.setCornerRadiusDp(28)
        awaitBackdrop(window)
        val screen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        canvas.drawColor(Color.WHITE)
        window.draw(canvas)
        for ((label, x, y) in listOf(
            Triple("top left", 1, 1),
            Triple("top right", width - 2, 1),
            Triple("bottom left", 1, height - 2),
            Triple("bottom right", width - 2, height - 2),
        )) {
            val pixel = screen.getPixel(x, y)
            assertTrue("$label corner is ${Integer.toHexString(pixel)}", Color.red(pixel) < 24)
        }
        // The card itself is untouched: only the cut corners are black.
        val centre = screen.getPixel(width / 2, height / 2)
        assertTrue("card centre became ${Integer.toHexString(centre)}", Color.blue(centre) > 24)
    }

    /**
     * The keys are discs again: a round plate behind every glyph, the two skip keys dim white and the
     * play key solid white with a dark glyph, so the middle key is the one that is lit.
     *
     * The plate is checked by drawing the key and reading its pixels: a disc leaves the four corners of
     * the box clear, which a square plate could not, and the play key's plate is the only opaque one.
     */
    @Test
    fun theKeysAreDiscsAgain() {
        val width = 792
        val height = 1080
        val window = window(width, height)
        val keys = window.transportButtons
        val row = window.transportView.height
        val bitmaps = keys.map { key ->
            val bitmap = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
            key.draw(Canvas(bitmap))
            bitmap
        }
        for ((i, key) in keys.withIndex()) {
            assertEquals("key $i is not square", key.width, key.height)
            val bitmap = bitmaps[i]
            for ((x, y) in listOf(
                0 to 0,
                key.width - 1 to 0,
                0 to key.height - 1,
                key.width - 1 to key.height - 1,
            )) {
                assertEquals("key $i fills its corner at $x,$y", 0, Color.alpha(bitmap.getPixel(x, y)))
            }
        }
        // The play key fills the row; the two skip keys are a little smaller and exactly each other's size.
        assertEquals("the play key is the row", row, keys[1].height)
        assertEquals("the skip keys are one size", keys[0].width, keys[2].width)
        assertTrue("play ${keys[1].width} against skip ${keys[0].width}", keys[1].width > keys[0].width)

        /** The darkest pixel the glyph itself can have: pixels under an opaque plate or glyph. */
        fun darkest(bitmap: Bitmap): Int {
            var value = 255
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 200) {
                    value = minOf(value, (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3)
                }
            }
            return value
        }
        // A white plate carries a dark glyph; the dim plates carry white ones, so nothing there is dark.
        assertTrue("the play glyph is dark on its plate: ${darkest(bitmaps[1])}", darkest(bitmaps[1]) < 70)
        assertTrue("a skip glyph is white on its plate: ${darkest(bitmaps[0])}", darkest(bitmaps[0]) > 200)

        // The plate around the glyph: white behind play, white at a fifth strength behind the skip keys.
        fun plate(i: Int): Int = bitmaps[i].getPixel((keys[i].width * 0.1f).toInt(), keys[i].height / 2)
        assertEquals("the play plate is solid", 255, Color.alpha(plate(1)))
        assertTrue("the play plate is white", Color.red(plate(1)) > 240 && Color.blue(plate(1)) > 240)
        val skipPlate = plate(0)
        assertTrue("the skip plate is translucent: ${Color.alpha(skipPlate)}", Color.alpha(skipPlate) in 1..120)
        assertTrue("the skip plate is white", Color.red(skipPlate) > 240 && Color.blue(skipPlate) > 240)
    }

    /**
     * The discs are spaced by a margin, the same on both sides of the play key, and each disc keeps a
     * fifth of itself as padding, so the glyphs end up further apart than the plates do.
     */
    @Test
    fun theGapsBetweenTheKeysAreEven() {
        for ((width, height) in listOf(
            792 to 1080,
            640 to 1080,
            1280 to 1080,
            1080 to 640,
            660 to 2376,
            320 to 480,
        )) {
            val window = window(width, height)
            val keys = window.transportButtons
            val row = window.transportView.height
            for (key in keys) {
                assertNotNull("a key with no plate behind it", key.background)
                assertTrue("key ${key.height} against a ${row}px row", key.height <= row)
            }
            assertEquals("the play key is the row", row, keys[1].height)
            val first = keys[1].left - keys[0].right
            val second = keys[2].left - keys[1].right
            assertEquals("${width}x$height: gaps $first and $second", first.toFloat(), second.toFloat(), 1f)
            assertTrue("${width}x$height: gap $first in a ${row}px row", first >= row * 0.25f - 1f)
            // The glyphs sit a fifth of a plate inside it, so the visible gap is wider than the margin.
            val glyphGap = first + keys[0].paddingLeft + keys[1].paddingLeft
            assertTrue("${width}x$height: glyph gap $glyphGap in a ${row}px row", glyphGap >= row * 0.35f)
            assertTrue(
                "${width}x$height: keys end at ${keys[2].right} in a ${window.transportView.width}px row",
                keys[2].right <= window.transportView.right,
            )
        }
    }

    @Test fun spacedControlsStayInsideNarrowAndWideStripsAtEveryTextSize() {
        RuntimeEnvironment.setQualifiers("+xhdpi")
        for ((width, height) in listOf(240 to 1080, 320 to 480, 320 to 1080, 384 to 1080, 480 to 1080, 640 to 1080, 1080 to 480, 1080 to 240)) {
            for (step in 1..5) {
                MusicWindowSettings.setTextScaleStep(RuntimeEnvironment.getApplication(), step)
                val window = window(width, height)
                val keys = window.transportButtons
                val row = window.transportView
                val label = "${width}x$height step=$step"
                val gapBefore = keys[1].left - keys[0].right
                val gapAfter = keys[2].left - keys[1].right
                assertTrue("$label first key starts outside row", keys[0].left >= 0)
                assertTrue("$label last key ends outside row", keys[2].right <= row.width)
                assertEquals("$label uneven spacing", gapBefore.toFloat(), gapAfter.toFloat(), 1f)
                assertTrue("$label keys are crowded", gapBefore >= keys[1].width * 0.25f - 1f)
                assertKeysWhole(window, label, height)
            }
        }
    }

    /** A failed press does not lie, and a real state change does not shift the row. */
    @Test
    fun thePlayKeyKeepsItsBoxWhenItsGlyphChanges() {
        val width = 792
        val height = 1080
        val window = window(width, height)
        val keys = window.transportButtons
        val play = keys[1]
        val before = keys.map { listOf(it.left, it.right) }
        val wasPlaying = play.contentDescription
        keys[1].performClick()
        ShadowLooper.idleMainLooper()
        assertEquals("A missing controller cannot change playback", wasPlaying, play.contentDescription)
        putNowPlaying(song(playing = wasPlaying == window.context.getString(R.string.music_window_play)))
        window.refreshPlayState()
        repeat(2) {
            window.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            window.layout(0, 0, width, height)
            ShadowLooper.idleMainLooper()
        }
        assertNotNull("the key has no description", play.contentDescription)
        assertNotEquals("the key did not follow playback state", wasPlaying, play.contentDescription)
        assertEquals(before, keys.map { listOf(it.left, it.right) })
    }
}
