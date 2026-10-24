package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** The approved photo-backed footer: a readable wordmark between two balanced rules. */
internal class HomeBrandView(context: Context) : LinearLayout(context) {
    private val density = resources.displayMetrics.density

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val gap = (ReferenceHomeMetrics.BRAND_RULE_GAP_DP * density).roundToInt()
        fun rule() = View(context).apply {
            setBackgroundColor(Color.rgb(220, 239, 255))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val lineHeight = (ReferenceHomeMetrics.BRAND_RULE_DP * density).roundToInt().coerceAtLeast(1)
        addView(rule(), LayoutParams(0, lineHeight, 1f).apply { marginEnd = gap })
        addView(TextView(context).apply {
            text = context.getString(R.string.app_name)
            textSize = ReferenceHomeMetrics.BRAND_TEXT_SP
            letterSpacing = .12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            // A decorative label must not acquire TextView's single-line horizontal scroll offset.
            maxLines = 1
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            // The photo remains a dusk scene in both themes; muted text loses contrast.
            setTextColor(Color.WHITE)
            setShadowLayer(ReferenceHomeMetrics.BRAND_SHADOW_DP * density, 0f, density, Color.rgb(8, 25, 44))
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(rule(), LayoutParams(0, lineHeight, 1f).apply { marginStart = gap })
    }
}
