package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.TouchDelegate
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Positions native controls using the measured composition of the selected 800×480 design. */
internal class ReferenceHomeScene(
    context: Context, private val hero: LinearLayout, private val ring: View,
    private val phone: View, private val usb: View, private val settings: View,
    private val brand: View, private val viewportHeight: () -> Int,
    private val reservedHeight: Int,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val landscape get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private fun dp(v: Int) = (v*density).roundToInt()
    private var wantedHeight = 0
    init {
        clipChildren = false; clipToPadding = false
        addView(hero); addView(usb); addView(settings); addView(brand)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val fullWidth = resources.configuration.screenWidthDp * density
        val scale = minOf(fullWidth / (800*density), resources.configuration.screenHeightDp / 480f).coerceIn(.7f, 1.4f)
        val heroWidth = if (landscape) (fullWidth * .305f).toInt().coerceAtMost(w) else w
        val naturalRing = dp((156*scale).roundToInt())
        ring.layoutParams = (ring.layoutParams as LinearLayout.LayoutParams).apply { width=naturalRing; height=naturalRing }
        hero.measure(MeasureSpec.makeMeasureSpec(heroWidth,MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        val footerWidth = if (landscape) (w * .287f).toInt() else (w-dp(12))/2
        for (view in listOf(usb,settings)) view.measure(MeasureSpec.makeMeasureSpec(footerWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        val footerHeight = maxOf(usb.measuredHeight,settings.measuredHeight)
        for (view in listOf(usb,settings)) view.measure(MeasureSpec.makeMeasureSpec(footerWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(footerHeight,MeasureSpec.EXACTLY))
        brand.measure(MeasureSpec.makeMeasureSpec(if (landscape) (w*.25f).toInt() else w,MeasureSpec.AT_MOST),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        wantedHeight = (viewportHeight() - reservedHeight).coerceAtLeast(dp(240))
        if (landscape) {
            val budget = wantedHeight - footerHeight - dp(ReferenceHomeMetrics.FOOTER_INSET_DP + ReferenceHomeMetrics.HERO_FOOTER_GAP_DP)
            val reduction = (hero.measuredHeight-budget).coerceAtLeast(0)
            val ringSize = (naturalRing-reduction).coerceAtLeast(dp(ReferenceHomeMetrics.MIN_RING_DP))
            ring.layoutParams = (ring.layoutParams as LinearLayout.LayoutParams).apply { width=ringSize; height=ringSize }
            hero.forceLayout()
            hero.measure(MeasureSpec.makeMeasureSpec(heroWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(w,maxOf(wantedHeight,hero.measuredHeight+footerHeight+dp(ReferenceHomeMetrics.HERO_FOOTER_GAP_DP+ReferenceHomeMetrics.FOOTER_INSET_DP)))
        } else setMeasuredDimension(w,maxOf(wantedHeight,hero.measuredHeight+footerHeight+brand.measuredHeight+dp(ReferenceHomeMetrics.PORTRAIT_FOOTER_GAP_DP)))
    }
    override fun onLayout(changed: Boolean,l: Int,t: Int,r: Int,b: Int) {
        val centre = if (landscape) resources.configuration.screenWidthDp*density*.30f - dp(ReferenceHomeMetrics.SIDE_DP) else width/2f
        val hx=(centre-hero.measuredWidth/2).toInt().coerceAtLeast(0)
        hero.layout(hx,0,hx+hero.measuredWidth,hero.measuredHeight)
        val footerTop = if (landscape) height-dp(ReferenceHomeMetrics.FOOTER_INSET_DP)-usb.measuredHeight else hero.bottom+dp(ReferenceHomeMetrics.HERO_FOOTER_GAP_DP)
        usb.layout(0,footerTop,usb.measuredWidth,footerTop+usb.measuredHeight)
        settings.layout(width-settings.measuredWidth,footerTop,width,footerTop+settings.measuredHeight)
        val brandY=if (landscape) footerTop+(usb.measuredHeight-brand.measuredHeight)/2 else usb.bottom+dp(ReferenceHomeMetrics.HERO_FOOTER_GAP_DP)
        brand.layout((width-brand.measuredWidth)/2,brandY,(width+brand.measuredWidth)/2,brandY+brand.measuredHeight)
        // A quiet 40dp picker label keeps the target at least 48dp without changing the drawing.
        hero.touchDelegate=TouchDelegate(Rect(phone.left,phone.top-dp(4),phone.right,phone.bottom+dp(4)),phone)
        if (wantedHeight != (viewportHeight()-reservedHeight).coerceAtLeast(dp(240))) requestLayout()
    }
}
internal object ReferenceHomeMetrics {
    const val SIDE_DP=24
    const val TOP_DP=16
    const val BOTTOM_DP=24
    const val HEADER_DP=44
    const val HEADER_GAP_DP=4
    const val HERO_FOOTER_GAP_DP=12
    const val FOOTER_INSET_DP=12
    const val PORTRAIT_FOOTER_GAP_DP=40
    const val MIN_RING_DP=40
}
