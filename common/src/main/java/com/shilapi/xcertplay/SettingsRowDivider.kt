package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import androidx.core.graphics.ColorUtils

/** One inset hairline for choice rows, switches and adjustment entries in either direction. */
internal class SettingsRowDivider(context: Context, color: Int) : Drawable() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint().apply {
        this.color = ColorUtils.setAlphaComponent(color, FidelitySettingsMetrics.ROW_DIVIDER_ALPHA)
        strokeWidth = density * FidelitySettingsMetrics.ROW_DIVIDER_DP
    }
    override fun draw(canvas: Canvas) {
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val start = FidelitySettingsMetrics.ROW_START_DP * density
        val end = FidelitySettingsMetrics.ROW_END_DP * density
        canvas.drawLine(bounds.left + if (rtl) end else start,
            bounds.bottom - paint.strokeWidth / 2,
            bounds.right - if (rtl) start else end,
            bounds.bottom - paint.strokeWidth / 2, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable compatibility")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Retains the switch row's original focus indicator when a divider is needed. */
internal class SettingsRowForeground(val original: Drawable, divider: Drawable) : LayerDrawable(arrayOf(original, divider))
