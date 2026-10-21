package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Owns only measurement of the original framework action buttons. */
internal class GlassDialogActions(context: Context) : LinearLayout(context) {
    private val gap = (FidelitySettingsMetrics.DIALOG_ACTION_GAP_DP * resources.displayMetrics.density).roundToInt()
    init { isBaselineAligned = false }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val actions = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }
        val available = (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        actions.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        val widest = actions.maxOfOrNull { it.measuredWidth } ?: 0
        orientation = if (widest * actions.size + gap * (actions.size - 1).coerceAtLeast(0) <= available) HORIZONTAL else VERTICAL
        actions.forEachIndexed { index, action ->
            val params = action.layoutParams as LayoutParams
            params.width = if (orientation == HORIZONTAL) 0 else LayoutParams.MATCH_PARENT
            params.height = LayoutParams.WRAP_CONTENT
            params.weight = if (orientation == HORIZONTAL) 1f else 0f
            params.setMargins(0, if (orientation == VERTICAL && index > 0) gap else 0, 0, 0)
            params.marginStart = if (orientation == HORIZONTAL && index > 0) gap else 0
            params.marginEnd = 0
            params.resolveLayoutDirection(layoutDirection)
        }
        super.onMeasure(widthSpec, heightSpec)
        if (orientation == HORIZONTAL) {
            val height = actions.maxOfOrNull { it.measuredHeight } ?: 0
            actions.forEach {
                it.measure(MeasureSpec.makeMeasureSpec(it.measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }
        }
    }
}
