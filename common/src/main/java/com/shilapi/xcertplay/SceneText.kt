package com.shilapi.xcertplay

import android.graphics.Color
import android.widget.TextView

/** Photo-backed app text; never applied to a CarPlay video surface. */
internal object SceneText {
    private const val SHADOW_RADIUS_DP = 2f
    private const val SHADOW_OFFSET_DP = 1f

    fun apply(view: TextView, night: Boolean, secondary: Boolean = false) {
        val palette = DiPlayPalette.of(night)
        view.setTextColor(if (night && secondary) Color.rgb(232, 242, 252)
            else if (secondary) palette.secondaryText else palette.primaryText)
        if (night) {
            val density = view.resources.displayMetrics.density
            view.setShadowLayer(SHADOW_RADIUS_DP * density, 0f, SHADOW_OFFSET_DP * density,
                Color.argb(220, 0, 10, 24))
        } else view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
    }
}
