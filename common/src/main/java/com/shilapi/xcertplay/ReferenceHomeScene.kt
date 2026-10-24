package com.shilapi.xcertplay

import android.content.Context
import android.view.TouchDelegate
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Home and preparation share their content slots, action dimensions and safe bottom edge. */
internal class ReferenceHomeScene(
    context: Context, private val hero: ConnectionContentLayout, private val ring: View,
    private val phone: View, private val primary: TextView, private val usb: View, private val settings: View,
    private val brand: View, private val viewportHeight: () -> Int,
    private val reservedHeight: Int,
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
        val footerHeight = ConnectionPageLayout.actionHeight(context, footerWidth)
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
        if (wantedHeight != (viewportHeight()-reservedHeight).coerceAtLeast(1)) requestLayout()
    }
    private fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
}
internal object ReferenceHomeMetrics {
    const val SIDE_DP=24
    const val TOP_DP=16
    const val BOTTOM_DP=12
    const val ACTION_HEIGHT_DP=48
    const val HEADER_DP=44
    const val HEADER_GAP_DP=4
    const val HERO_FOOTER_GAP_DP=12
    const val MIN_RING_DP=32
    const val BRAND_TEXT_SP=12f
    const val BRAND_ACTION_GAP_DP=12
    const val BRAND_RULE_GAP_DP=12
    const val BRAND_RULE_DP=1f
    const val BRAND_SHADOW_DP=2f
}
