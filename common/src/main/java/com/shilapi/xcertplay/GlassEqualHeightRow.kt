package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.LinearLayout

internal class GlassEqualHeightRow(context: Context) : LinearLayout(context) {
    init { orientation = HORIZONTAL; isBaselineAligned = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // A hidden status label can retain a taller measurement from a previous traversal.
        // LinearLayout excludes it from this row's height, so it must not stretch the actions
        // beyond that height and make CENTER_VERTICAL place their top outside the row.
        val visible = (0 until childCount).map(::getChildAt).filter { it.visibility != View.GONE }
        val height = visible.maxOfOrNull { it.measuredHeight } ?: return
        for (child in visible) {
            if (child.measuredHeight != height) {
                child.measure(MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
            }
        }
    }
}
