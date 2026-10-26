package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Owns only measurement of the original framework action buttons. */
internal class GlassDialogActions(context: Context) : LinearLayout(context) {
    private val gap = (FidelitySettingsMetrics.DIALOG_ACTION_GAP_DP * resources.displayMetrics.density).roundToInt()
    private val minActionWidth = (FidelitySettingsMetrics.DIALOG_ACTION_MIN_WIDTH_DP * resources.displayMetrics.density).roundToInt()
    private val maxActionWidth = (FidelitySettingsMetrics.DIALOG_ACTION_MAX_WIDTH_DP * resources.displayMetrics.density).roundToInt()
    init { isBaselineAligned = false; gravity = Gravity.CENTER_HORIZONTAL }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val actions = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }
        val available = (MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight).coerceAtLeast(1)
        actions.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        val widest = minOf(actions.maxOfOrNull { it.measuredWidth } ?: 0, maxActionWidth, available)
        val totalGap = gap * (actions.size - 1).coerceAtLeast(0)
        val equalWidth = ((available - totalGap) / actions.size.coerceAtLeast(1)).coerceAtLeast(1)
        orientation = if (actions.size > 1 && equalWidth < minActionWidth) VERTICAL else HORIZONTAL
        actions.forEachIndexed { index, action ->
            val params = action.layoutParams as LayoutParams
            // Multi-action dialogs divide the complete row evenly. This keeps mixed short/long
            // labels visually balanced instead of anchoring a compact group to one edge. Narrow
            // windows still stack the actions, while a single action remains compact and centred.
            params.width = when {
                orientation == VERTICAL -> available
                actions.size == 1 -> widest.coerceAtLeast(minActionWidth).coerceAtMost(available)
                else -> equalWidth
            }
            params.height = LayoutParams.WRAP_CONTENT
            params.weight = 0f
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
