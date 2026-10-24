package com.shilapi.xcertplay

import android.content.Context
import android.view.TouchDelegate
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Publish this traversal's viewport before ScrollView measures its unbounded content. */
internal class HomeViewportScrollView(context: Context) : ScrollView(context) {
    var availableHeight = 0
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val nextHeight = (MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(1)
        if (availableHeight != nextHeight) {
            availableHeight = nextHeight
            // ScrollView gives its child the same UNSPECIFIED height even when the window
            // changes. Invalidate cached child measurements before, not during, layout.
            for (index in 0 until childCount) invalidateMeasurements(getChildAt(index))
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun invalidateMeasurements(view: View) {
        view.forceLayout()
        if (view is ViewGroup) for (index in 0 until view.childCount) invalidateMeasurements(view.getChildAt(index))
    }
}

/** Home and preparation share their content slots, action dimensions and safe bottom edge. */
internal class ReferenceHomeScene(
    context: Context, private val hero: ConnectionContentLayout,
    private val phone: View, private val primary: TextView, private val usb: View, private val settings: View,
    private val brand: View, private val viewportHeight: () -> Int,
    private val reservedHeight: Int,
    private val prepareActions: (Int) -> Unit = {},
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v*density).roundToInt()
    private var wantedHeight = 0
    private var footerStackHeight = 0
    private var landscape = false
    init {
        clipChildren = false; clipToPadding = false
        addView(hero); addView(usb); addView(settings); addView(brand)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val fullWidth = w + dp(ReferenceHomeMetrics.SIDE_DP * 2)
        val fullHeight = viewportHeight()
        landscape = fullWidth > fullHeight * 1.3f
        val footerWidth = ConnectionPageLayout.actionWidth(w, landscape, density)
        prepareActions(footerWidth)
        val actionMetrics = ConnectionPageLayout.actionMetrics(context, footerWidth)
        val footerHeight = actionMetrics.height
        fun constrainAction(view: View) {
            if (view is TextView) {
                view.textSize = actionMetrics.textSp.toFloat()
                if (view.maxLines != ConnectionPageLayout.ACTION_MAX_LINES) view.maxLines = ConnectionPageLayout.ACTION_MAX_LINES
                view.ellipsize = android.text.TextUtils.TruncateAt.END
            } else if (view is ViewGroup) for (index in 0 until view.childCount) constrainAction(view.getChildAt(index))
        }
        listOf(primary, usb, settings).forEach(::constrainAction)
        for (view in listOf(usb, settings)) view.measure(exact(footerWidth), exact(footerHeight))
        primary.layoutParams = (primary.layoutParams as FrameLayout.LayoutParams).apply {
            width = footerWidth; height = footerHeight
        }
        val brandWidth = if (landscape) (w - footerWidth * 2 - dp(ReferenceHomeMetrics.BRAND_ACTION_GAP_DP * 2)).coerceAtLeast(0) else w
        brand.measure(exact(brandWidth), MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        wantedHeight = (fullHeight - reservedHeight).coerceAtLeast(1)
        footerStackHeight = if (landscape) maxOf(footerHeight, brand.measuredHeight)
            else footerHeight + brand.measuredHeight + dp(ReferenceHomeMetrics.HERO_FOOTER_GAP_DP)
        val bodyHeight = (wantedHeight - footerStackHeight - dp(ReferenceHomeMetrics.HERO_FOOTER_GAP_DP)).coerceAtLeast(1)
        val heroWidth = ConnectionPageLayout.contentWidth(fullWidth, bodyHeight, landscape, density)
        hero.actionHeight = footerHeight
        hero.measure(exact(heroWidth), exact(bodyHeight))
        setMeasuredDimension(w, wantedHeight)
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        hero.layout(0, 0, hero.measuredWidth, hero.measuredHeight)
        val footerTop = height-footerStackHeight
        usb.layout(0,footerTop,usb.measuredWidth,footerTop+usb.measuredHeight)
        settings.layout(width-settings.measuredWidth,footerTop,width,footerTop+settings.measuredHeight)
        val brandY=if (landscape) footerTop+(footerStackHeight-brand.measuredHeight)/2 else height-brand.measuredHeight
        brand.layout((width-brand.measuredWidth)/2,brandY,(width+brand.measuredWidth)/2,brandY+brand.measuredHeight)
        hero.touchDelegate=TouchDelegate(Rect(phone.left,phone.top-dp(4),phone.right,phone.bottom+dp(4)),phone)
    }
    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
internal object ReferenceHomeMetrics {
    const val SIDE_DP=24
    const val TOP_DP=16
    const val BOTTOM_DP=12
    const val ACTION_HEIGHT_DP=48
    const val HEADER_DP=44
    const val LINCOLN_MARK_WIDTH_DP=32
    const val FORD_MARK_WIDTH_DP=56
    const val BRAND_MARK_HEIGHT_DP=40
    const val HEADER_GAP_DP=4
    const val HERO_FOOTER_GAP_DP=12
    const val MIN_RING_DP=32
    const val BRAND_TEXT_SP=12f
    const val BRAND_ACTION_GAP_DP=12
    const val BRAND_RULE_GAP_DP=12
    const val BRAND_RULE_DP=1f
    const val BRAND_SHADOW_DP=2f
}
