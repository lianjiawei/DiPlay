package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

internal object ConnectionScreenMetrics {
    const val CONTENT_SHARE = .60f
    const val ACTION_WIDTH_DP = 360
    const val HEADER_RESERVE_DP = 64
    const val SIDE_DP = 24
    const val BOTTOM_DP = 24
    const val ACTION_GAP_DP = 16
    const val BRAND_TOP_DP = 16
    const val BRAND_HEIGHT_DP = 44
}

/** Fixed, non-scrolling regions for the brand, connection content and bottom actions. */
internal class ConnectionPreparationLayout(context: Context) : FrameLayout(context) {
    val contentArea = FrameLayout(context)
    private lateinit var brand: View
    private lateinit var brandLabel: TextView
    private lateinit var actions: View
    private var contentWidth = 0
    private var headerReserve = 0
    private var brandTopInset = 0
    val usesFullWidthContent get() = contentWidth >= width - paddingLeft - paddingRight

    init { isClickable = true; addView(contentArea) }

    fun bind(content: View, brand: View, brandLabel: TextView, actions: View) {
        this.brand = brand
        this.brandLabel = brandLabel
        this.actions = actions
        contentArea.addView(content, FrameLayout.LayoutParams(-1, -1))
        addView(brand)
        addView(actions)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val compactHeight = height - paddingTop - paddingBottom < dp(300)
        headerReserve = dp(if (compactHeight) 32 else ConnectionScreenMetrics.HEADER_RESERVE_DP)
        brandTopInset = dp(if (compactHeight) 0 else ConnectionScreenMetrics.BRAND_TOP_DP)
        contentWidth = (availableWidth * if (width > height * 1.3f)
            ConnectionScreenMetrics.CONTENT_SHARE else 1f).toInt().coerceAtLeast(1)
        val actionWidth = minOf(dp(ConnectionScreenMetrics.ACTION_WIDTH_DP),
            (contentWidth - dp(ConnectionScreenMetrics.SIDE_DP * 2)).coerceAtLeast(1))
        actions.measure(exact(actionWidth), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val brandWidth = MeasureSpec.makeMeasureSpec(
            (availableWidth - dp(ConnectionScreenMetrics.SIDE_DP * 2)).coerceAtLeast(1), MeasureSpec.AT_MOST)
        brandLabel.measure(brandWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val brandHeight = maxOf(brandLabel.measuredHeight, dp(if (compactHeight) 28 else ConnectionScreenMetrics.BRAND_HEIGHT_DP))
        brand.measure(brandWidth, exact(brandHeight))
        headerReserve = maxOf(headerReserve, brandTopInset + brandHeight + dp(4))
        val bodyHeight = (height - paddingTop - paddingBottom - headerReserve -
            actions.measuredHeight - dp(ConnectionScreenMetrics.BOTTOM_DP + if (compactHeight) 4 else ConnectionScreenMetrics.ACTION_GAP_DP))
            .coerceAtLeast(1)
        // Very short landscape windows use the available width for a two-column composition.
        if (bodyHeight < dp(200) && width > height * 1.3f) contentWidth = availableWidth
        contentArea.measure(exact(contentWidth), exact(bodyHeight))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val bodyTop = paddingTop + headerReserve
        contentArea.layout(paddingLeft, bodyTop, paddingLeft + contentWidth, bodyTop + contentArea.measuredHeight)
        val brandLeft = paddingLeft + dp(ConnectionScreenMetrics.SIDE_DP)
        val brandTop = paddingTop + brandTopInset
        brand.layout(brandLeft, brandTop, brandLeft + brand.measuredWidth, brandTop + brand.measuredHeight)
        val actionLeft = paddingLeft + (contentWidth - actions.measuredWidth) / 2
        val actionBottom = height - paddingBottom - dp(ConnectionScreenMetrics.BOTTOM_DP)
        actions.layout(actionLeft, actionBottom - actions.measuredHeight,
            actionLeft + actions.measuredWidth, actionBottom)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}

/** Measure all localized text first, then give the decorative ring the remaining space. */
internal class ConnectionContentLayout(
    context: Context, private val headline: TextView, private val ring: View,
    private val phone: TextView, private val stage: TextView, private val hint: FrameLayout,
) : FrameLayout(context) {
    private var columns = false
    private var visualWidth = 0
    private var detailsWidth = 0
    private var gap = 0
    private var side = 0
    private var visualHeight = 0
    private var detailsHeight = 0

    init {
        listOf(headline, phone, stage).forEach { it.includeFontPadding = false }
        for (index in 0 until hint.childCount) (hint.getChildAt(index) as TextView).includeFontPadding = false
        listOf(headline, ring, phone, stage, hint).forEach { addView(it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        side = dp(ConnectionScreenMetrics.SIDE_DP)
        gap = dp(if (height < dp(140)) 4 else if (height < dp(240)) 6 else 10)
        val innerWidth = (width - side * 2).coerceAtLeast(1)
        columns = height < dp(200) && innerWidth >= dp(480)
        visualWidth = if (columns) (innerWidth * .42f).toInt() else innerWidth
        detailsWidth = if (columns) innerWidth - visualWidth - gap else innerWidth
        val compact = height < dp(140)
        headline.textSize = if (compact) 18f else if (height < dp(200)) 20f else UiTypography.COMPACT_PAGE_SP.toFloat()
        phone.textSize = if (compact) 14f else UiTypography.ROW_SP.toFloat()
        stage.textSize = if (compact) 12f else UiTypography.BODY_SP.toFloat()
        for (index in 0 until hint.childCount) (hint.getChildAt(index) as TextView).textSize = if (compact) 12f else UiTypography.CAPTION_SP.toFloat()
        fun measureText() {
            headline.measure(exact(visualWidth), unspecified())
            listOf(phone, stage, hint).forEach { it.measure(exact(detailsWidth), unspecified()) }
            detailsHeight = phone.measuredHeight + stage.measuredHeight + hint.measuredHeight + gap * 2
        }
        measureText()
        val normalRing = dp(if (columns) 72 else 120)
        val ringRoom = height - headline.measuredHeight - gap -
            if (columns) 0 else detailsHeight + gap
        val ringSize = minOf(normalRing, ringRoom).coerceAtLeast(dp(32))
        ring.measure(exact(ringSize), exact(ringSize))
        visualHeight = headline.measuredHeight + gap + ringSize
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val groupHeight = if (columns) maxOf(visualHeight, detailsHeight) else visualHeight + gap + detailsHeight
        val groupTop = ((height - groupHeight) / 2).coerceAtLeast(0)
        val visualTop = if (columns) ((height - visualHeight) / 2).coerceAtLeast(0) else groupTop
        headline.layout(side, visualTop, side + visualWidth, visualTop + headline.measuredHeight)
        val ringLeft = side + (visualWidth - ring.measuredWidth) / 2
        val ringTop = headline.bottom + gap
        ring.layout(ringLeft, ringTop, ringLeft + ring.measuredWidth, ringTop + ring.measuredHeight)
        val detailsLeft = if (columns) side + visualWidth + gap else side
        var y = if (columns) ((height - detailsHeight) / 2).coerceAtLeast(0) else ring.bottom + gap
        for (view in listOf(phone, stage, hint)) {
            view.layout(detailsLeft, y, detailsLeft + detailsWidth, y + view.measuredHeight)
            y += view.measuredHeight + gap
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    private fun unspecified() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
}
