package com.shilapi.xcertplay

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils

/** Shared app-owned material. Static highlights keep this inexpensive on older head units. */
internal object GlassChrome {
    const val BUTTON_HEIGHT_DP = 56
    const val MIN_TARGET_DP = 48
    const val PANEL_RADIUS_DP = 24
    const val DIALOG_RADIUS_DP = 28
    const val CONTROL_GAP_DP = 12
    const val GROUP_GAP_DP = 24

    fun radius(height: Int, density: Float): Float =
        (if (height > 0) height.toFloat() else BUTTON_HEIGHT_DP * density) / 2f

    fun surface(fill: Int, outline: Int, density: Float, radius: Float, primary: Boolean = false,
        opacity: Int = 236): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(fill, Color.WHITE, if (primary) .10f else .08f), if (primary) 255 else opacity),
            ColorUtils.setAlphaComponent(fill, if (primary) 255 else opacity),
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(fill, Color.BLACK, .10f), if (primary) 255 else opacity),
        )).apply {
            cornerRadius = radius
            setStroke(density.toInt().coerceAtLeast(1), outline)
        }

    fun textColors(text: Int, surface: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(ColorUtils.blendARGB(text, surface, .38f), text),
    )
}
