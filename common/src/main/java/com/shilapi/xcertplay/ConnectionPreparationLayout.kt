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
    const val BOTTOM_DP = ReferenceHomeMetrics.BOTTOM_DP
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
    private val wordmark = HomeBrandView(context)
    private var footerHeight = 0
    private var footerStackHeight = 0
    private var landscape = false
    private var contentWidth = 0
    private var headerReserve = 0
    private var brandTopInset = 0
    val usesFullWidthContent get() = contentWidth >= width-paddingLeft-paddingRight-dp(ConnectionScreenMetrics.SIDE_DP*2)

    init { isClickable = true; addView(contentArea) }

    fun bind(content: View, brand: View, brandLabel: TextView, actions: View) {
        this.brand = brand
        this.brandLabel = brandLabel
        this.actions = actions
        contentArea.addView(content, FrameLayout.LayoutParams(-1, -1))
        addView(brand)
        addView(actions)
        addView(wordmark)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val compactHeight = height - paddingTop - paddingBottom < dp(300)
        landscape = availableWidth > (height-paddingTop-paddingBottom)*1.3f
        brandTopInset = dp(if (compactHeight) 0 else ReferenceHomeMetrics.TOP_DP)
        brandLabel.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val brandHeight = maxOf(brandLabel.measuredHeight, dp(if (compactHeight) 32 else ReferenceHomeMetrics.HEADER_DP))
        headerReserve = brandTopInset + brandHeight + dp(ReferenceHomeMetrics.HEADER_GAP_DP)
        val innerWidth = (availableWidth-dp(ConnectionScreenMetrics.SIDE_DP*2)).coerceAtLeast(1)
        val actionWidth = ConnectionPageLayout.actionWidth(innerWidth, landscape, resources.displayMetrics.density)
        footerHeight = ConnectionPageLayout.actionHeight(context, actionWidth)
        val row = actions as android.widget.LinearLayout
        for (i in 0 until row.childCount) {
            row.getChildAt(i).layoutParams = (row.getChildAt(i).layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                this.width = actionWidth; this.height = footerHeight; weight = 0f
                marginEnd = if (i == 0) innerWidth-actionWidth*2 else 0
            }
        }
        actions.measure(exact(innerWidth), exact(footerHeight))
        val wordmarkWidth = if (landscape) (innerWidth-actionWidth*2-dp(ReferenceHomeMetrics.BRAND_ACTION_GAP_DP*2)).coerceAtLeast(0) else innerWidth
        wordmark.measure(exact(wordmarkWidth), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        footerStackHeight = if (landscape) maxOf(footerHeight,wordmark.measuredHeight)
            else footerHeight+wordmark.measuredHeight+dp(ReferenceHomeMetrics.HERO_FOOTER_GAP_DP)
        brand.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.AT_MOST), exact(brandHeight))
        val bodyHeight = (height-paddingTop-paddingBottom-headerReserve-footerStackHeight-
            dp(ConnectionScreenMetrics.BOTTOM_DP + ReferenceHomeMetrics.HERO_FOOTER_GAP_DP)).coerceAtLeast(1)
        contentWidth = ConnectionPageLayout.contentWidth(availableWidth, bodyHeight, landscape, resources.displayMetrics.density)
        (contentArea.getChildAt(0) as ConnectionContentLayout).actionHeight = footerHeight
        contentArea.measure(exact(contentWidth), exact(bodyHeight))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val bodyTop = paddingTop + headerReserve
        val start = paddingLeft+dp(ConnectionScreenMetrics.SIDE_DP)
        contentArea.layout(start, bodyTop, start+contentWidth, bodyTop+contentArea.measuredHeight)
        val brandTop = paddingTop+brandTopInset
        brand.layout(start, brandTop, start+brand.measuredWidth, brandTop+brand.measuredHeight)
        val footerBottom = height-paddingBottom-dp(ConnectionScreenMetrics.BOTTOM_DP)
        val actionTop = footerBottom-footerStackHeight
        actions.layout(start, actionTop, start+actions.measuredWidth, actionTop+actions.measuredHeight)
        val wordmarkTop = if (landscape) actionTop+(footerStackHeight-wordmark.measuredHeight)/2 else footerBottom-wordmark.measuredHeight
        val wordmarkLeft = paddingLeft+(width-paddingLeft-paddingRight-wordmark.measuredWidth)/2
        wordmark.layout(wordmarkLeft, wordmarkTop, wordmarkLeft+wordmark.measuredWidth, wordmarkTop+wordmark.measuredHeight)
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
    var actionHeight = 0
    private val hintMeasure = StableConnectionTextView(context, ConnectionScreenCopy.hints).apply { includeFontPadding = false }

    init {
        listOf(headline, phone, stage).forEach { it.includeFontPadding = false }
        for (index in 0 until hint.childCount) (hint.getChildAt(index) as TextView).includeFontPadding = false
        listOf(headline, ring, phone, stage, hint).forEach { addView(it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        side = 0
        gap = dp(if (height < dp(140)) 4 else ConnectionPageLayout.CONTENT_GAP_DP)
        val innerWidth = width.coerceAtLeast(1)
        columns = height < dp(ConnectionPageLayout.SHORT_BODY_DP) && innerWidth >= dp(320)
        visualWidth = if (columns) (innerWidth*.42f).toInt() else innerWidth
        detailsWidth = if (columns) innerWidth-visualWidth-gap else innerWidth
        val compact = height < dp(140)
        headline.textSize = if (compact) 18f else if (columns) 20f else UiTypography.HERO_SP.toFloat()
        phone.textSize = if (compact) 14f else UiTypography.ROW_SP.toFloat()
        stage.textSize = if (compact) 12f else UiTypography.BODY_SP.toFloat()
        hintMeasure.textSize = if (compact) 12f else UiTypography.CAPTION_SP.toFloat()
        var primaryAction = false
        for (index in 0 until hint.childCount) {
            val child = hint.getChildAt(index)
            if (child is android.widget.Button) primaryAction = true
            else (child as? TextView)?.textSize = hintMeasure.textSize / resources.displayMetrics.scaledDensity
        }
        headline.measure(exact(visualWidth), unspecified())
        phone.measure(MeasureSpec.makeMeasureSpec(detailsWidth, MeasureSpec.AT_MOST), unspecified())
        phone.measure(exact(phone.measuredWidth), exact(maxOf(phone.measuredHeight, dp(if (compact) 20 else ConnectionPageLayout.PHONE_DP))))
        stage.measure(exact(detailsWidth), unspecified())
        hintMeasure.measure(exact(detailsWidth), unspecified())
        val detailHeight = maxOf(hintMeasure.measuredHeight, if (!compact || primaryAction) actionHeight else 0)
        hint.measure(exact(detailsWidth), exact(detailHeight))
        detailsHeight = phone.measuredHeight+stage.measuredHeight+hint.measuredHeight+gap*2
        val ringRoom = height-headline.measuredHeight-gap-(if (columns) 0 else detailsHeight+gap)
        val ringSize = minOf(dp(ConnectionPageLayout.RING_DP), ringRoom).coerceAtLeast(dp(ReferenceHomeMetrics.MIN_RING_DP))
        ring.measure(exact(ringSize), exact(ringSize))
        visualHeight = headline.measuredHeight+gap+ringSize
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        headline.layout(side, 0, side+visualWidth, headline.measuredHeight)
        val ringLeft = side+(visualWidth-ring.measuredWidth)/2
        val ringTop = headline.bottom+gap
        ring.layout(ringLeft, ringTop, ringLeft+ring.measuredWidth, ringTop+ring.measuredHeight)
        val detailsLeft = if (columns) side+visualWidth+gap else side
        var y = if (columns) 0 else ring.bottom+gap
        for (view in listOf(phone, stage, hint)) {
            val x = detailsLeft+(detailsWidth-view.measuredWidth)/2
            view.layout(x, y, x+view.measuredWidth, y+view.measuredHeight)
            y += view.measuredHeight+gap
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
    private fun unspecified() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
}
