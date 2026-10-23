package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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

/** The waiting content can scroll; its brand and bottom actions never move with status text. */
internal class ConnectionPreparationLayout(context: Context) : FrameLayout(context) {
    val contentScroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false }
    private lateinit var brand: View
    private lateinit var actions: View
    private var contentWidth = 0

    init { isClickable = true; addView(contentScroll) }

    fun bind(content: LinearLayout, brand: View, actions: View) {
        this.brand = brand
        this.actions = actions
        contentScroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        addView(brand)
        addView(actions)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        contentWidth = (availableWidth * if (width > height * 1.3f)
            ConnectionScreenMetrics.CONTENT_SHARE else 1f).toInt().coerceAtLeast(1)
        val actionWidth = minOf(dp(ConnectionScreenMetrics.ACTION_WIDTH_DP),
            (contentWidth - dp(ConnectionScreenMetrics.SIDE_DP * 2)).coerceAtLeast(1))
        actions.measure(exact(actionWidth), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        brand.measure(MeasureSpec.makeMeasureSpec(
            (availableWidth - dp(ConnectionScreenMetrics.SIDE_DP * 2)).coerceAtLeast(1), MeasureSpec.AT_MOST),
            exact(dp(ConnectionScreenMetrics.BRAND_HEIGHT_DP)))
        val bodyHeight = (height - paddingTop - paddingBottom - dp(ConnectionScreenMetrics.HEADER_RESERVE_DP) -
            actions.measuredHeight - dp(ConnectionScreenMetrics.BOTTOM_DP + ConnectionScreenMetrics.ACTION_GAP_DP))
            .coerceAtLeast(1)
        contentScroll.measure(exact(contentWidth), exact(bodyHeight))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val bodyTop = paddingTop + dp(ConnectionScreenMetrics.HEADER_RESERVE_DP)
        contentScroll.layout(paddingLeft, bodyTop, paddingLeft + contentWidth, bodyTop + contentScroll.measuredHeight)
        val brandLeft = paddingLeft + dp(ConnectionScreenMetrics.SIDE_DP)
        val brandTop = paddingTop + dp(ConnectionScreenMetrics.BRAND_TOP_DP)
        brand.layout(brandLeft, brandTop, brandLeft + brand.measuredWidth, brandTop + brand.measuredHeight)
        val actionLeft = paddingLeft + (contentWidth - actions.measuredWidth) / 2
        val actionBottom = height - paddingBottom - dp(ConnectionScreenMetrics.BOTTOM_DP)
        actions.layout(actionLeft, actionBottom - actions.measuredHeight,
            actionLeft + actions.measuredWidth, actionBottom)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun exact(value: Int) = MeasureSpec.makeMeasureSpec(value, MeasureSpec.EXACTLY)
}
