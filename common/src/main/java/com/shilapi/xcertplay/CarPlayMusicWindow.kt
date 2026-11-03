package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.TextUtils
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The music window beside CarPlay: the strip CarPlay leaves next to its picture, filled with the song the
 * iPhone plays instead of the clock.
 *
 * The card is CarPlay's own: the album, blurred and darkened into frosted glass, with the cover on top,
 * the title and one line of performer and album under it, then the progress line and the transport keys.
 * The keys reach the iPhone through [CarPlayMediaKeys] like the wheel keys do.
 *
 * The strip is the side panel's, so this view only ever replaces that panel's content
 * ([MusicWindowSettings]) and is laid out by the panel's own placement. The glass fills that rectangle
 * edge to edge: the panel used to leave a ten-pixel gap towards CarPlay to stand its drag grip in, and the
 * gap showed as a black band down the card's left side and along its bottom.
 *
 * Three rules drive the layout:
 *  - the lines and the keys are laid out first and at their own size: the fonts are a share of the card's
 *    width, the keys are as tall as the row they sit in, and the block's height is the sum of what those
 *    parts really measure. The cover then takes the rest. The keys used to be given a row from a weight
 *    and a size from the block's height, so the play key came out taller than its row and the card cut its
 *    bottom off; a row is now never shorter than the key inside it.
 *  - a strip too small for this shrinks the type until it fits, rather than clipping anything: the card
 *    gets smaller and stays centred, in both directions.
 *  - the text size is bound to the block: pressing the size button makes the fonts grow, and the block
 *    grows with them because it is the sum of those fonts. Every size is in pixels: the strip is a few
 *    hundred pixels wide on the head unit, and a pixel value read as sp was multiplied by the car's font
 *    scale, which once made the title three times too large and pushed the cover out of the strip.
 */
internal class CarPlayMusicWindow(context: Context) : FrameLayout(context) {
    private val main = Handler(Looper.getMainLooper())

    /** Everything the card lays out: the cover and the block of lines and keys, side by side or stacked. */
    private val card = LinearLayout(context)
    private val artworkFrame = FrameLayout(context)
    private val artwork = ImageView(context)
    private val titleView = TextView(context)
    /** The performer and the album on one line: two lines of small type said the same thing twice. */
    private val metaView = TextView(context)
    private val progressLine = MusicProgressLine(context)
    private val infoColumn = LinearLayout(context)
    private val controls = LinearLayout(context)
    private val reflowTransition = ChangeBounds().apply { duration = REFLOW_MILLIS }
    // Round keys, the row the window started with: two dim white discs with white glyphs and a white
    // disc in the middle carrying the accent-dark glyph, so the play key is the one the eye lands on.
    private val previousButton = transportButton(R.drawable.ic_music_previous) {
        CarPlayMediaKeys.onHomeCardPrevious()
    }
    private val playButton = transportButton(R.drawable.ic_music_play) {
        // Show the result at once; [optimisticUntil] keeps the next stale poll from undoing it, and
        // the iPhone's own state settles it after that.
        if (CarPlayMediaKeys.onHomeCardToggle()) {
            optimisticUntil = SystemClock.elapsedRealtime() + OPTIMISTIC_MILLIS
            applyPlaying(!shownPlaying, force = true)
        }
    }
    private val nextButton = transportButton(R.drawable.ic_music_next) {
        CarPlayMediaKeys.onHomeCardNext()
    }

    private var palette = DiPlayPalette.DARK
    private var snapshot = ClusterMusicState.Snapshot()
    private var stopState: (() -> Unit)? = null
    private var windowShown = true
    private var shownPlaying = false
    /** The cover in [artwork], so a metadata repeat does not hand the ImageView a new drawable. */
    private var shownCover: Bitmap? = null
    /** After a press the key shows the state the press asked for, until the iPhone's own state arrives. */
    private var optimisticUntil = 0L
    private var metricsWidth = 0
    private var metricsHeight = 0
    private var metricsScale = -1f
    /** Bumped when a line appears or disappears, which changes the block's height at the same strip size. */
    private var contentGeneration = 0
    private var metricsContent = -1
    private var textScale = MusicWindowSettings.textScale(context)

    /** The blurred album, and the cover it was made from, so a repaint does not rebuild it. */
    private var backdrop: Bitmap? = null
    private var backdropSource: Bitmap? = null
    private var backdropTarget = 0
    @Volatile private var backdropGeneration = 0
    // The card is drawn over a thumbnail of the cover, a third of its own width: filtering is what turns
    // that back into a smooth wash. Without it the shader samples nearest neighbour and every blurred
    // pixel becomes a visible square, which is the mosaic the frosted card used to look like.
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isFilterBitmap = true
        isDither = true
    }
    private var backdropShader: BitmapShader? = null
    private var backdropShaderSource: Bitmap? = null
    private val backdropMatrix = Matrix()
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }
    /**
     * What shows in the four corners the card's arc cuts away: the black of the screen's border. Left to
     * the page behind, those slivers took the host's light background and read as white notches bitten out
     * of the card, which is exactly what a rounded corner must not look like.
     */
    private val surroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }
    private var scrimHeight = 0
    private var scrimWide = false
    /** The card's corner, from the same setting the CarPlay picture uses, so the two edges match. */
    private var cornerRadiusPx = 0f
    /** Which edge of the card the drag grip sits on: the one facing CarPlay. */
    private var gripEdge = Gravity.START
    private var gripAlpha = 0
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = GRIP_FILL
    }

    // Read the existing media state only while visible; this never takes ownership of audio focus.
    private val poll = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isShown || !windowShown) return
            refreshPlayState()
            main.postDelayed(this, PLAY_STATE_POLL_MILLIS)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false

        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        artwork.scaleType = ImageView.ScaleType.CENTER_CROP
        artwork.setImageResource(R.drawable.art_now_playing_placeholder)
        artwork.clipToOutline = true
        artwork.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = minOf(view.width, view.height) * ART_CORNER_FRACTION
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        // The frame is the cover's square, so the image simply fills it: no second layout pass to wait for.
        artworkFrame.addView(artwork, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        card.addView(artworkFrame, LinearLayout.LayoutParams(0, 0))

        titleView.apply {
            maxLines = TITLE_MAX_LINES
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            ellipsize = TextUtils.TruncateAt.END
        }
        metaView.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        infoColumn.orientation = LinearLayout.VERTICAL
        for (view in listOf<TextView>(titleView, metaView)) {
            view.includeFontPadding = false
            view.gravity = Gravity.CENTER
            // A blurred album can carry a bright patch under a line; the shadow is what keeps the text
            // readable there without another scrim over the cover.
            infoColumn.addView(view, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0))
        }
        infoColumn.addView(progressLine, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(3)))
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        controls.addView(previousButton)
        controls.addView(playButton)
        controls.addView(nextButton)
        infoColumn.addView(controls, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0))
        card.addView(infoColumn, LinearLayout.LayoutParams(0, 0))

        paint()
        render()
    }

    /** The parts the layout tests measure; the class is internal to the music window. */
    internal val coverView: View get() = artworkFrame
    internal val titleTextView: TextView get() = titleView
    internal val metaTextView: TextView get() = metaView
    internal val progressView: View get() = progressLine
    internal val transportView: View get() = controls
    internal val transportButtons: List<View> get() = listOf(previousButton, playButton, nextButton)
    internal val textBlockView: View get() = infoColumn
    internal val textScaleInUse: Float get() = textScale
    internal val contentOrientation: Int get() = card.orientation
    internal val cornerRadius: Float get() = cornerRadiusPx
    /** The blurred backdrop, for the layout tests: null until the worker has finished the first blur. */
    internal val backdropView: Bitmap? get() = backdrop

    /** The corner the CarPlay picture and the side panel card use, so the three read as one card. */
    fun setCornerRadiusDp(radiusDp: Int) {
        val next = dp(radiusDp).toFloat()
        if (next == cornerRadiusPx) return
        cornerRadiusPx = next
        invalidate()
    }

    /**
     * The edge the drag grip belongs on: the one facing CarPlay. The card fills the strip now, so the grip
     * is drawn over the glass rather than standing in a gap, and it is this view that knows where the edge is.
     */
    fun setGripEdge(edge: Int) {
        if (edge == gripEdge) return
        gripEdge = edge
        invalidate()
    }

    /** The panel fades the grip out after a touch; the card follows it, since it draws the grip now. */
    fun setGripAlpha(alpha: Int) {
        if (alpha == gripAlpha) return
        gripAlpha = alpha
        invalidate()
    }

    fun applyPalette(palette: DiPlayPalette) {
        this.palette = palette
        paint()
        invalidate()
    }

    /** The iPhone's play state and position, without waiting for the next callback. */
    fun refreshPlayState() {
        applyPlaying(CarPlayMediaKeys.isPlaying())
        applyProgress(CarPlayMediaKeys.musicProgress())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopState = ClusterMusicState.observe { state ->
            snapshot = state
            render()
        }
        updatePolling()
    }

    override fun onDetachedFromWindow() {
        stopState?.invoke()
        stopState = null
        main.removeCallbacks(poll)
        TransitionManager.endTransitions(card)
        clearBackdrop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updatePolling()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowShown = visibility == VISIBLE
        updatePolling()
    }

    private fun updatePolling() {
        main.removeCallbacks(poll)
        if (isAttachedToWindow && isShown && windowShown) {
            refreshPlayState()
            render()
            main.postDelayed(poll, PLAY_STATE_POLL_MILLIS)
        } else {
            if (isAttachedToWindow) TransitionManager.endTransitions(card)
            clearBackdrop()
        }
    }

    private fun clearBackdrop() {
        backdropGeneration++
        backdropSource = null
        backdropTarget = 0
        backdropShader = null
        backdropShaderSource = null
        // A displayed bitmap may still be in the hardware renderer; release our references and let
        // Android retire it instead of recycling it while a previous frame is in flight.
        backdropPaint.shader = null
        backdrop = null
    }

    /**
     * The card is sized from the strip and the chosen text scale, and from whether a line is showing: when
     * any of those changes it re-measures, because the block's height is what the lines and keys measure.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (width > 0 && height > 0 &&
            (width != metricsWidth || height != metricsHeight ||
                textScale != metricsScale || contentGeneration != metricsContent)
        ) {
            metricsWidth = width
            metricsHeight = height
            metricsScale = textScale
            metricsContent = contentGeneration
            applyMetrics(width, height)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (oldWidth > 0 && oldHeight > 0 && (width != oldWidth || height != oldHeight)) {
            // A dragged or resized side panel owns its own geometry, not the lyric animation.
            TransitionManager.endTransitions(card)
        }
    }

    /**
     * Lays the card out. The lines and the keys decide the block's height first, the cover takes what is
     * left, and a strip too small for that shrinks the type rather than cutting a row short.
     */
    private fun applyMetrics(width: Int, height: Int) {
        // The card is the strip: a gap towards CarPlay left a black band along the card's edge and let the
        // CarPlay picture's corner show beside it, so the glass now reaches the strip's own edges.
        val pad = paddingFor(width, height)
        card.setPadding(pad, pad, pad, pad)
        val innerWidth = (width - pad * 2).coerceAtLeast(dp(24))
        val innerHeight = (height - pad * 2).coerceAtLeast(dp(24))
        val wide = isWide(width, height)
        val gap = (minOf(innerWidth, innerHeight) * GAP_FRACTION).toInt().coerceAtLeast(dp(4))
        val minCover = (minOf(innerWidth, innerHeight) * MIN_COVER_SHARE).toInt().coerceAtLeast(dp(20))

        card.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        // Both axes: a card smaller than the strip (a small text size, or a strip that forced a shrink)
        // must sit in its middle.
        card.gravity = Gravity.CENTER

        var scale = textScale
        var plan = planCycle(innerWidth, innerHeight, wide, scale, gap, minCover)
        var pass = 0
        while (plan.overflow && pass++ < FIT_PASSES) {
            // Everything in the block scales with the type, so one division lands close; a second pass
            // only corrects how the lines wrap at the smaller size.
            val room = if (wide) {
                innerHeight.toFloat()
            } else {
                (innerHeight - gap - minCover).coerceAtLeast(dp(40)).toFloat()
            }
            scale *= (room / plan.blockHeight).coerceIn(MIN_FIT_STEP, MAX_FIT_STEP)
            plan = planCycle(innerWidth, innerHeight, wide, scale, gap, minCover)
        }

        val cover = if (wide) {
            plan.cover.coerceIn(dp(16).coerceAtMost(innerHeight / 2), innerHeight)
        } else {
            plan.cover.coerceIn(dp(16).coerceAtMost(innerWidth / 2), innerWidth)
        }
        val sideInset = (plan.blockWidth * TEXT_SIDE_INSET).toInt().coerceAtLeast(dp(2))

        artworkFrame.layoutParams = LinearLayout.LayoutParams(cover, cover).apply {
            marginEnd = if (wide) gap else 0
        }
        artwork.layoutParams = FrameLayout.LayoutParams(cover, cover, Gravity.CENTER)
        infoColumn.layoutParams = LinearLayout.LayoutParams(plan.blockWidth, plan.blockHeight).apply {
            gravity = Gravity.CENTER
            topMargin = if (wide) 0 else gap
        }
        infoColumn.setPadding(sideInset, 0, sideInset, 0)

        titleView.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, plan.titleHeight)
        // Margins, not slack: the block's height is the sum of its parts, and a gap that was only counted
        // in that sum never appeared between two lines — the extra height collected at the block's ends.
        metaView.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, plan.metaHeight).apply {
            topMargin = if (plan.metaHeight > 0) plan.lineGap else 0
        }
        progressLine.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, plan.progressHeight).apply {
            topMargin = plan.progressGap
        }
        // The keys row is exactly as tall as the largest key: a row shorter than its key clipped the key.
        controls.layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, plan.keyRow).apply {
            topMargin = plan.keysGap
        }

        // Separate the buttons visibly while keeping all three inside even a narrow side strip.
        val keyGap = (plan.keyRow * KEY_MARGIN_SHARE).toInt()
        val sideKey = (plan.keyRow * SIDE_KEY_SHARE).toInt().coerceAtLeast(1)
        previousButton.layoutParams = LinearLayout.LayoutParams(sideKey, sideKey).apply { marginEnd = keyGap }
        playButton.layoutParams = LinearLayout.LayoutParams(plan.keyRow, plan.keyRow)
        nextButton.layoutParams = LinearLayout.LayoutParams(sideKey, sideKey).apply { marginStart = keyGap }
        for (button in listOf(previousButton, nextButton)) {
            button.setPadding(sideKey / 5, sideKey / 5, sideKey / 5, sideKey / 5)
        }
        playButton.setPadding(plan.keyRow / 5, plan.keyRow / 5, plan.keyRow / 5, plan.keyRow / 5)

        // The thumbnail the card is drawn over follows the card's own width; a resized strip rebuilds it.
        if (backdropTargetWidth() != backdropTarget) updateBackdrop(backdropSource, force = true)
    }

    /**
     * A block width and the cover that goes with it, settled: the lines are as wide as the cover in a
     * stacked card, and the cover's width depends on the height the lines take, so the two are chased
     * for a couple of rounds until they agree.
     */
    private fun planCycle(
        innerWidth: Int,
        innerHeight: Int,
        wide: Boolean,
        scale: Float,
        gap: Int,
        minCover: Int,
    ): CardPlan {
        var plan = planCard(innerWidth, innerHeight, wide, scale, innerWidth, gap, minCover)
        if (wide) return plan
        repeat(SETTLE_PASSES - 1) {
            val next = planCard(innerWidth, innerHeight, wide, scale, plan.cover, gap, minCover)
            val settled = next.cover == plan.cover && next.blockWidth == plan.blockWidth
            plan = next
            if (settled) return plan
        }
        return plan
    }

    /** One pass: the type from the block's width, the block from what those lines and keys measure. */
    private fun planCard(
        innerWidth: Int,
        innerHeight: Int,
        wide: Boolean,
        scale: Float,
        coverGuess: Int,
        gap: Int,
        minCover: Int,
    ): CardPlan {
        val blockWidth = if (wide) {
            (innerWidth * WIDE_BLOCK_SHARE * scale).toInt()
                .coerceIn((innerWidth * WIDE_BLOCK_MIN).toInt(), (innerWidth * WIDE_BLOCK_MAX).toInt())
        } else {
            minOf(innerWidth, coverGuess).coerceAtLeast((innerWidth * MIN_BLOCK_SHARE).toInt())
        }
        val sideInset = (blockWidth * TEXT_SIDE_INSET).toInt().coerceAtLeast(dp(2))
        val textWidth = (blockWidth - sideInset * 2).coerceAtLeast(1)

        // A physical-size floor must also fit the actual strip on a narrow, high-density screen.
        val minimumTitle = minOf(dp(MIN_TITLE_DP).toFloat(), (textWidth * 0.10f).coerceAtLeast(1f))
        val titleSize = (textWidth * TITLE_WIDTH_SHARE * scale).coerceAtLeast(minimumTitle)
        val minimumMeta = minOf(dp(MIN_META_DP).toFloat(), titleSize * 0.85f)
        val metaSize = (titleSize * META_RATIO).coerceAtLeast(minimumMeta)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, titleSize)
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_PX, metaSize)
        titleView.setShadowLayer(
            (titleSize * SHADOW_SHARE).coerceAtLeast(1f), 0f, titleSize * SHADOW_DROP, TEXT_SHADOW,
        )
        metaView.setShadowLayer(
            (metaSize * SHADOW_SHARE).coerceAtLeast(1f), 0f, metaSize * SHADOW_DROP, TEXT_SHADOW,
        )

        val titleHeight = if (titleView.visibility == View.GONE) 0 else measuredHeight(titleView, textWidth)
        val metaHeight = if (metaView.visibility == View.GONE) 0 else measuredHeight(metaView, textWidth)
        val lineGap = (titleSize * LINE_GAP_SHARE).toInt().coerceAtLeast(dp(2))
        // The bar thickens with the type, like the keys: the old 6 dp ceiling was reached at the largest
        // step on a wide strip, so the bar stopped growing while the lines kept going. Rounded, not
        // truncated: truncation held two neighbouring steps at the same odd pixel height.
        val progressHeight = (titleSize * PROGRESS_SHARE).roundToInt().coerceIn(dp(2), dp(10))
        // The line and the keys breathe: tight against the album's line above and the keys below, the card
        // read as one grey slab instead of a player.
        val progressGap = (titleSize * PROGRESS_GAP_SHARE).toInt().coerceAtLeast(dp(4))
        val keysGap = (titleSize * KEYS_GAP_SHARE).toInt().coerceAtLeast(dp(4))

        // The row is cut to the tallest glyph, which is the pause bars: the skip pairs stand a little over
        // half of it, exactly as CarPlay draws them. [KEYS_ACROSS] is what three glyphs and their two gaps
        // need across, so a narrow block shrinks the row instead of letting the keys run off its edge.
        val keyLimit = (textWidth / KEYS_ACROSS).toInt().coerceAtLeast(1)
        val keyRow = (textWidth * KEY_ROW_SHARE * scale).toInt().coerceIn(dp(18).coerceAtMost(keyLimit), keyLimit)

        val blockHeight = titleHeight +
            (if (metaHeight > 0) lineGap + metaHeight else 0) +
            progressGap + progressHeight + keysGap + keyRow

        val cover = if (wide) {
            minOf(innerHeight, (innerWidth - blockWidth - gap).coerceAtLeast(0))
        } else {
            minOf(innerWidth, (innerHeight - blockHeight - gap).coerceAtLeast(0))
        }
        val overflow = if (wide) blockHeight > innerHeight || cover < minCover else cover < minCover
        return CardPlan(
            blockWidth = blockWidth,
            blockHeight = blockHeight,
            cover = cover,
            titleHeight = titleHeight,
            metaHeight = metaHeight,
            lineGap = lineGap,
            progressHeight = progressHeight,
            progressGap = progressGap,
            keyRow = keyRow,
            keysGap = keysGap,
            overflow = overflow,
        )
    }

    private fun measuredHeight(view: View, width: Int): Int {
        view.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight.coerceAtLeast(1)
    }

    /**
     * Re-reads the step chosen on the settings page. The card used to carry its own size key; the choice
     * lives in the settings now, so the card only has to pick it up when the menu closes.
     */
    internal fun reloadTextScale() {
        val next = MusicWindowSettings.textScale(context)
        if (next == textScale) return
        textScale = next
        metricsScale = -1f
        requestLayout()
        invalidate()
    }

    /** Draws the frosted album, then the dark scrim that keeps white text readable over any cover. */
    override fun dispatchDraw(canvas: Canvas) {
        val right = width.toFloat()
        val bottom = height.toFloat()
        if (right > 0f && bottom > 0f) {
            canvas.save()
            // The corner is a cut, not a notch: everything outside the arc is filled first, so the strip's
            // own black shows through the cut instead of the host page's colour behind the window.
            canvas.drawRect(0f, 0f, right, bottom, surroundPaint)
            // The same corner as the CarPlay picture: three radii stacked on one rectangle left the wider
            // one peeking out of the narrower one, a ragged rim at each corner.
            val radius = cornerRadiusPx.coerceIn(0f, minOf(right, bottom) / 2f)
            val image = backdrop
            if (image != null && !image.isRecycled && image.width > 0 && image.height > 0) {
                val shader = backdropShader?.takeIf { backdropShaderSource === image }
                    ?: BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        .also { backdropShader = it; backdropShaderSource = image }
                val scale = maxOf(right / image.width, bottom / image.height)
                backdropMatrix.setScale(scale, scale)
                backdropMatrix.postTranslate(
                    (right - image.width * scale) / 2f,
                    (bottom - image.height * scale) / 2f,
                )
                shader.setLocalMatrix(backdropMatrix)
                backdropPaint.shader = shader
            } else {
                backdropPaint.shader = null
                backdropPaint.color = palette.overlayBackground
            }
            canvas.drawRoundRect(0f, 0f, right, bottom, radius, radius, backdropPaint)
            canvas.drawRoundRect(0f, 0f, right, bottom, radius, radius, scrimFor(bottom.toInt()))
            drawGrip(canvas, right.toInt(), bottom.toInt())
            canvas.restore()
        }
        super.dispatchDraw(canvas)
    }

    /**
     * The handle that says the strip's edge drags, drawn over the glass at the edge facing CarPlay. It used
     * to stand in the ten-pixel gap the panel kept for it, which is now part of the card.
     */
    private fun drawGrip(canvas: Canvas, width: Int, height: Int) {
        if (gripAlpha <= 0 || width <= 0 || height <= 0) return
        val thickness = (minOf(width, height) * GRIP_THICKNESS_SHARE).toInt().coerceIn(dp(3), dp(6)).toFloat()
        val length = (height * GRIP_LENGTH_SHARE).toInt().coerceIn(dp(32), dp(110)).toFloat()
        // A little in from the edge: hard against it the pill reads as a scroll bar rather than a handle.
        val inset = thickness * 1.5f
        val left: Float
        val top: Float
        when (gripEdge) {
            Gravity.TOP -> {
                left = (width - length) / 2f
                top = inset
            }
            Gravity.END -> {
                left = width - inset - thickness
                top = (height - length) / 2f
            }
            else -> {
                left = inset
                top = (height - length) / 2f
            }
        }
        val right = if (gripEdge == Gravity.TOP) left + length else left + thickness
        val bottom = if (gripEdge == Gravity.TOP) top + thickness else top + length
        gripPaint.alpha = gripAlpha
        canvas.drawRoundRect(left, top, right, bottom, thickness / 2f, thickness / 2f, gripPaint)
    }

    /**
     * The scrim is rebuilt when the card's shape changes, not on every frame. A stacked card darkens
     * from the cover down, so the lines sit on the darkest part; a sideways one darkens evenly, because
     * its lines run the full height beside the cover.
     */
    private fun scrimFor(height: Int): Paint {
        val wide = card.orientation == LinearLayout.HORIZONTAL
        if (height != scrimHeight || wide != scrimWide) {
            scrimHeight = height
            scrimWide = wide
            val span = height.toFloat().coerceAtLeast(1f)
            scrimPaint.shader = if (wide) {
                LinearGradient(0f, 0f, 0f, span, intArrayOf(WIDE_SCRIM, WIDE_SCRIM), null, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(
                    0f,
                    0f,
                    0f,
                    span,
                    intArrayOf(SCRIM_TOP, SCRIM_MIDDLE, SCRIM_BOTTOM),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
        }
        return scrimPaint
    }

    /**
     * A quarter of the card's width: the blur is worked out at this size and then stretched back over the
     * card, so a smaller thumbnail is a stronger blur for the same radius. 96 pixels was a tenth of the
     * card, and stretched with nearest-neighbour sampling it read as a mosaic of squares; a third of the
     * card, blurred at a tenth of its width, still showed the album's outlines, so this is smaller and
     * blurred harder.
     */
    private fun backdropTargetWidth(): Int {
        val cardWidth = width.coerceAtLeast(1)
        return (cardWidth / BACKDROP_DIVISOR).coerceIn(BACKDROP_MIN_WIDTH, BACKDROP_MAX_WIDTH)
    }

    /**
     * Rebuilds the frosted backdrop when the cover changes, and only then: the blur is a one-off cost on
     * a worker thread, not something every frame pays for. A cover that is already being drawn is reused.
     */
    private fun updateBackdrop(source: Bitmap?, force: Boolean = false) {
        val target = backdropTargetWidth()
        if (!force && source === backdropSource && target == backdropTarget) return
        backdropSource = source
        backdropTarget = target
        val generation = ++backdropGeneration
        if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) {
            clearBackdrop()
            invalidate()
            return
        }
        val targetHeight = (source.height.toLong() * target / source.width).coerceIn(1L, 512L).toInt()
        val scaled = Bitmap.createScaledBitmap(source, target, targetHeight, true)
        // createScaledBitmap returns the same bitmap when the size already matches; blurring it would
        // paint over the cover the rest of the app is still using.
        val thumb = if (scaled === source) source.copy(Bitmap.Config.ARGB_8888, true) else scaled
        val radius = (target * BACKDROP_RADIUS_SHARE).toInt().coerceIn(4, BACKDROP_MAX_RADIUS)
        BACKDROP_EXECUTOR.execute {
            if (generation != backdropGeneration) {
                if (thumb !== source) thumb.recycle()
                return@execute
            }
            val output = runCatching {
                val pixels = IntArray(target * targetHeight)
                thumb.getPixels(pixels, 0, target, 0, 0, target, targetHeight)
                // [StackBlur.blur] works on a copy and hands it back; writing the array that went in put
                // the sharp thumbnail on the card, which is why the glass never looked blurred at all.
                val blurred = StackBlur.blur(pixels, target, targetHeight, radius)
                Bitmap.createBitmap(target, targetHeight, Bitmap.Config.ARGB_8888).apply {
                    setPixels(blurred, 0, target, 0, 0, target, targetHeight)
                }
            }.getOrNull()
            if (thumb !== source) thumb.recycle()
            main.post {
                if (generation != backdropGeneration || !isAttachedToWindow || !isShown || !windowShown) {
                    output?.recycle()
                    return@post
                }
                backdrop = output
                backdropShader = null
                backdropShaderSource = null
                backdropPaint.shader = null
                invalidate()
            }
        }
    }

    private fun paint() {
        // The card carries its own dark glass in either theme, so its lines stay white like CarPlay's.
        titleView.setTextColor(TEXT_PRIMARY)
        metaView.setTextColor(TEXT_SECONDARY)
        progressLine.setColors(
            trackColor = ColorUtils.setAlphaComponent(TEXT_SECONDARY, PROGRESS_TRACK_ALPHA),
            playedColor = TEXT_PRIMARY,
        )
        previousButton.setColorFilter(TEXT_PRIMARY)
        nextButton.setColorFilter(TEXT_PRIMARY)
        previousButton.background = keyBackground(KEY_FILL)
        nextButton.background = keyBackground(KEY_FILL)
        paintPlayButton()
    }

    private fun applyPlaying(playing: Boolean, force: Boolean = false) {
        // A press already showed its own result; the iPhone's reply settles it once that has had time to land.
        if (!force && SystemClock.elapsedRealtime() < optimisticUntil) return
        if (playing == shownPlaying) return
        shownPlaying = playing
        paintPlayButton()
    }

    private fun paintPlayButton() {
        playButton.setImageResource(if (shownPlaying) R.drawable.ic_music_pause else R.drawable.ic_music_play)
        playButton.contentDescription = context.getString(
            if (shownPlaying) R.string.music_window_pause else R.string.music_window_play,
        )
        // White disc, dark glyph: the one key in the row that is lit, whatever the theme is.
        playButton.setColorFilter(PLAY_GLYPH)
        playButton.background = keyBackground(Color.WHITE)
    }

    private fun applyProgress(progress: MusicProgress?) {
        progressLine.setProgress(progress)
    }

    private fun render() {
        val content = musicWindowContent(
            snapshot.info,
            unknownArtist = context.getString(R.string.settings_cluster_music_unknown),
            nothingPlaying = context.getString(R.string.music_window_nothing_playing),
        )
        val meta = content.meta.orEmpty()
        val showedMeta = metaView.visibility != View.GONE
        val titleChanged = titleView.text.toString() != content.title
        val changed = titleChanged ||
            metaView.text.toString() != meta ||
            showedMeta != meta.isNotEmpty()
        if (changed) {
            // The iPhone republishes the same title and artist about twice a second while a song plays,
            // and setText lays the line out again even when the string is identical. Writing only on a
            // real change keeps the card from re-measuring over the CarPlay picture on every frame.
            if (isAttachedToWindow && isShown && windowShown && card.isLaidOut && !card.isLayoutRequested &&
                transportButtons.none { it.isPressed }) {
                val oldLayout = titleView.layout
                val lineCountChanges = titleChanged && oldLayout != null && run {
                    val nextIsSingleLine = !content.title.contains('\n') && !content.title.contains('\r') &&
                        Layout.getDesiredWidth(content.title, titleView.paint) <= oldLayout.width
                    (oldLayout.lineCount == 1) != nextIsSingleLine
                }
                if (lineCountChanges || showedMeta != meta.isNotEmpty()) {
                    // Only actual reflow gets a short transition; ordinary lyric updates do not keep
                    // animating over the CarPlay video, and a pressed transport control stays responsive.
                    TransitionManager.beginDelayedTransition(card, reflowTransition)
                }
            }
            titleView.text = content.title
            metaView.text = meta
            // No line at all rather than an empty one: the block gives the height back to the cover.
            metaView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
            // The block's height is the sum of its lines, so a line appearing changes the layout at the
            // same strip size: the measure cache has to notice.
            contentGeneration++
            if (isLaidOut) requestLayout()
        }
        val cover = snapshot.artwork?.takeUnless { it.isRecycled }
        if (cover !== shownCover) {
            // setImageBitmap builds a fresh drawable and asks for a layout each time, so it is only
            // worth doing for a cover that actually changed.
            shownCover = cover
            if (cover != null) artwork.setImageBitmap(cover) else artwork.setImageResource(R.drawable.art_now_playing_placeholder)
        }
        if (isAttachedToWindow && isShown && windowShown) updateBackdrop(cover)
        progressLine.setProgress(CarPlayMediaKeys.musicProgress())
    }

    private fun keyBackground(fill: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(palette.ripple),
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
        },
        null,
    )

    private fun transportButton(icon: Int, onClick: () -> Unit): ImageButton = ImageButton(context).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = null
        isClickable = true
        contentDescription = context.getString(
            when (icon) {
                R.drawable.ic_music_previous -> R.string.music_window_previous
                R.drawable.ic_music_next -> R.string.music_window_next
                else -> R.string.music_window_play
            },
        )
        setOnClickListener { onClick() }
    }

    private fun paddingFor(width: Int, height: Int): Int =
        (minOf(width, height) * PADDING_FRACTION).toInt().coerceAtLeast(dp(8))

    /** A strip along the bottom of a portrait screen: the card stands horizontally to use the width. */
    private fun isWide(width: Int, height: Int): Boolean {
        val pad = paddingFor(width, height)
        return width - pad * 2 > (height - pad * 2) * WIDE_STRIP_RATIO
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    /** One measured shape of the card: the widths and heights, and whether they fit the strip. */
    private class CardPlan(
        val blockWidth: Int,
        val blockHeight: Int,
        val cover: Int,
        val titleHeight: Int,
        val metaHeight: Int,
        val lineGap: Int,
        val progressHeight: Int,
        val progressGap: Int,
        val keyRow: Int,
        val keysGap: Int,
        val overflow: Boolean,
    )

    companion object {
        private val BACKDROP_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "music-window-backdrop").apply { isDaemon = true }
        }
        private const val PLAY_STATE_POLL_MILLIS = 500L
        private const val REFLOW_MILLIS = 180L
        private const val OPTIMISTIC_MILLIS = 1_500L
        private const val ART_CORNER_FRACTION = 0.06f
        private const val PADDING_FRACTION = 0.05f
        private const val GAP_FRACTION = 0.05f

        /** The title's size as a share of the width the lines get, and the line under it against that. */
        private const val TITLE_WIDTH_SHARE = 0.062f
        private const val TITLE_MAX_LINES = 2
        private const val META_RATIO = 0.62f
        private const val MIN_TITLE_DP = 12
        private const val MIN_META_DP = 9

        /** How much room the block gives the gaps, against the title's size. */
        private const val LINE_GAP_SHARE = 0.16f
        private const val PROGRESS_SHARE = 0.14f
        /** The line sits in its own band, not against the album's line: about a third of a title above. */
        private const val PROGRESS_GAP_SHARE = 0.45f
        private const val KEYS_GAP_SHARE = 0.50f

        /**
         * The keys row against the width the lines get, what three discs and their two gaps need across,
         * how much smaller the skip discs are than the play key, and the margin between two discs.
         */
        private const val KEY_ROW_SHARE = 0.15f
        private const val KEYS_ACROSS = 3.4f
        /** The skip discs against the play key: the middle one reads as the main key without towering. */
        private const val SIDE_KEY_SHARE = 0.84f
        private const val KEY_MARGIN_SHARE = 0.32f

        /** A cover never shrinks below this share of the strip's shorter side. */
        private const val MIN_COVER_SHARE = 0.24f
        private const val MIN_BLOCK_SHARE = 0.5f

        /** In a band, the block takes this share of the width, before the size choice. */
        private const val WIDE_BLOCK_SHARE = 0.42f
        private const val WIDE_BLOCK_MIN = 0.26f
        // Raised with the fifth step: at 0.56 the fourth and fifth both clamped to the same width on a
        // band, so the two largest steps drew the same type.
        private const val WIDE_BLOCK_MAX = 0.66f
        private const val WIDE_STRIP_RATIO = 1.4f

        private const val TEXT_SIDE_INSET = 0.02f
        private const val SHADOW_SHARE = 0.10f
        private const val SHADOW_DROP = 0.03f

        /** Rounds of shrinking before a strip is simply too small, and of settling the cover's width. */
        private const val FIT_PASSES = 6
        private const val SETTLE_PASSES = 3
        private const val MIN_FIT_STEP = 0.80f
        private const val MAX_FIT_STEP = 0.97f

        /**
         * The backdrop: a quarter of the card, blurred at about a fifth of that. The blur is stretched back
         * over the card, so the radius that shows is roughly a quarter of the card's width — the album's
         * colours come through, its picture does not.
         */
        private const val BACKDROP_DIVISOR = 4
        private const val BACKDROP_MIN_WIDTH = 120
        private const val BACKDROP_MAX_WIDTH = 320
        private const val BACKDROP_RADIUS_SHARE = 0.22f
        private const val BACKDROP_MAX_RADIUS = 48

        /** The backdrop is always dark: the card is CarPlay's, whatever the settings theme is. */
        private const val SCRIM_TOP = 0x33000000
        private const val SCRIM_MIDDLE = 0x59000000
        private const val SCRIM_BOTTOM = 0xB3000000.toInt()
        private const val WIDE_SCRIM = 0xA6000000.toInt()
        private const val TEXT_PRIMARY = Color.WHITE
        private const val TEXT_SECONDARY = 0xFFB4BFCC.toInt()
        private const val PROGRESS_TRACK_ALPHA = 90
        /** The skip discs: white at a fifth strength, so they read as glass over the card. */
        private const val KEY_FILL = 0x2EFFFFFF
        /** The glyph on the play key's white disc. Dark whatever the theme, or it would vanish. */
        private const val PLAY_GLYPH = 0xFF121A15.toInt()
        private const val TEXT_SHADOW = 0xB3000000.toInt()
        private const val GRIP_FILL = 0xFFAAB4BE.toInt()
        private const val GRIP_THICKNESS_SHARE = 0.006f
        private const val GRIP_LENGTH_SHARE = 0.10f

    }
}

/** One song as the window shows it: the title, and the performer and album on one line under it. */
internal data class MusicWindowContent(val title: String, val meta: String?)

/**
 * Maps what the iPhone reports to the two lines, and to the empty state when it plays nothing. A title and
 * an artist are not the only fields: some apps report the artist alone, others put a lyric line in the title.
 * The performer and the album share one line, separated by a dot, the way CarPlay's own card does it: two
 * small lines under the title said the same thing twice and left the keys without room.
 * Pure so the mapping is unit-tested without a screen.
 */
internal fun musicWindowContent(
    info: CarPlayNowPlaying,
    unknownArtist: String,
    nothingPlaying: String,
): MusicWindowContent {
    val title = info.title?.takeIf { it.isNotBlank() }
    val artist = info.artist?.takeIf { it.isNotBlank() }
    if (title == null && artist == null) return MusicWindowContent(nothingPlaying, null)
    val album = info.album?.takeIf { it.isNotBlank() }
    // The artist alone is already the title line; repeating it under itself says nothing.
    val shownArtist = if (title == null) null else artist ?: unknownArtist
    val meta = listOfNotNull(shownArtist, album)
        .filter { it.isNotBlank() }
        .joinToString(META_SEPARATOR)
        .takeIf { it.isNotEmpty() }
    return MusicWindowContent(title = title ?: artist.orEmpty(), meta = meta)
}

private const val META_SEPARATOR = " · "

/**
 * The line under the album: the track dim, the played share bright, rounded at both ends so it reads as
 * progress rather than a rule.
 *
 * The line owns the clock. The iPhone sends a position a few times a second at best, and nothing at all
 * between two frames, so drawing only when one arrives left the line frozen on the position the song
 * started at. Here the anchor is kept and the line redraws itself between anchors while the song plays,
 * but only as often as its bright end can move: the frame rate follows the pixels, not the panel's
 * refresh. A new anchor from a seek or a pause replaces it immediately.
 */
internal class MusicProgressLine(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var song: MusicProgress? = null

    init {
        visibility = INVISIBLE
    }

    /** A new anchor, or null while the iPhone reports no length: the line hides and keeps its space. */
    fun setProgress(progress: MusicProgress?) {
        // The window asks again every half second and on every metadata frame; the anchor is a value,
        // so an unchanged one needs neither a layout nor a redraw.
        if (song == progress) return
        song = progress
        visibility = if (progress == null) INVISIBLE else VISIBLE
        invalidate()
    }

    fun setColors(trackColor: Int, playedColor: Int) {
        track.color = trackColor
        played.color = playedColor
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        track.strokeWidth = height.toFloat()
        played.strokeWidth = height.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val current = song ?: return
        val now = SystemClock.elapsedRealtime()
        val fraction = current.fractionAt(now)
        val inset = height / 2f
        val end = width - inset
        if (end > inset) {
            val middle = height / 2f
            canvas.drawLine(inset, middle, end, middle, track)
            val reached = inset + (end - inset) * fraction
            if (reached > inset) canvas.drawLine(inset, middle, reached, middle, played)
        }
        // One frame per pixel the bright end can move, not sixty a second: over a three-minute song
        // the end of a card-wide line advances about two pixels a second, so all but a handful of
        // those frames drew the identical picture on top of the CarPlay video.
        if (current.playing && fraction < 1f) postInvalidateDelayed(nextFrameMillis(current, fraction, end - inset, now))
    }

    /** How long until the bright end reaches another pixel, bounded by a frame's time and half a second. */
    internal fun nextFrameMillis(current: MusicProgress, fraction: Float, span: Float, now: Long): Long {
        val remainingMillis = (current.durationMillis - current.positionAt(now)).coerceAtLeast(0L)
        // A finished line has no pixel left to reach; asking again a frame later would only draw it again.
        if (fraction >= 1f || remainingMillis <= 0L) return MAX_FRAME_MILLIS
        val remainingPixels = ((1f - fraction) * span).coerceAtLeast(1f)
        return (remainingMillis / remainingPixels).toLong().coerceIn(MIN_FRAME_MILLIS, MAX_FRAME_MILLIS)
    }

    private companion object {
        /** A pixel this soon means the song is nearly over: keep drawing, the panel can keep up. */
        const val MIN_FRAME_MILLIS = 50L
        /** Never slower than this, so a long track still reads as a moving line. */
        const val MAX_FRAME_MILLIS = 500L
    }
}
