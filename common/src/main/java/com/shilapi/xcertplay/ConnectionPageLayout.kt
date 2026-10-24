package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** Dimensions shared by the full-page home and connecting surfaces, independent of connection state. */
internal object ConnectionPageLayout {
    const val RING_DP = 112
    const val CONTENT_GAP_DP = 8
    const val PHONE_DP = 48
    const val SHORT_BODY_DP = 200
    const val COMPACT_BODY_DP = 240
    const val COMPACT_GAP_DP = 4
    const val COMPACT_HEADLINE_SP = 20f
    const val MIN_READING_WIDTH_DP = 432

    fun contentWidth(safeWidth: Int, bodyHeight: Int, landscape: Boolean, density: Float): Int {
        val innerWidth = (safeWidth-ReferenceHomeMetrics.SIDE_DP*2*density).roundToInt().coerceAtLeast(1)
        if (!landscape || bodyHeight < SHORT_BODY_DP*density) return innerWidth
        return (safeWidth*.6f-ReferenceHomeMetrics.SIDE_DP*2*density).roundToInt()
            .coerceAtLeast((MIN_READING_WIDTH_DP*density).roundToInt()).coerceAtMost(innerWidth)
    }

    fun actionWidth(innerWidth: Int, landscape: Boolean, density: Float): Int =
        (if (landscape) innerWidth*.287f else (innerWidth-ReferenceHomeMetrics.HERO_FOOTER_GAP_DP*density)/2)
            .toInt().coerceAtLeast(1)

    fun actionHeight(context: Context, width: Int): Int {
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val measure = android.widget.Button(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            isAllCaps = false
            background = null
            includeFontPadding = false
            textSize = UiTypography.ACTION_SP.toFloat()
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minimumHeight = 0
            minHeight = (ReferenceHomeMetrics.ACTION_HEIGHT_DP*density).roundToInt()
            layoutDirection = context.resources.configuration.layoutDirection
        }
        val icons = setOf(R.string.connect_with_usb, R.string.disconnect, R.string.settings)
        val labels = icons + setOf(R.string.ui_glass_connect_carplay, R.string.open_carplay,
            R.string.ui_glass_back_home, R.string.ui_connection_help)
        return labels.maxOf { id ->
            measure.text = context.getString(id)
            measure.setPadding(((16 + if (id in icons) 28 else 0)*density).roundToInt(),
                (8*density).roundToInt(), (16*density).roundToInt(), (8*density).roundToInt())
            measure.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            measure.measuredHeight
        }
    }
}
