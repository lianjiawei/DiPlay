package com.shilapi.xcertplay

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/** Wrap long labels naturally, then give sibling actions the tallest button's height. */
internal class GlassActionRow(context: Context) : LinearLayout(context) {
    init { orientation = HORIZONTAL; isBaselineAligned = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val buttons = (0 until childCount).map(::getChildAt).filterIsInstance<Button>()
            .filter { it.visibility != View.GONE }
        val height = buttons.maxOfOrNull { it.measuredHeight } ?: return
        buttons.filter { it.measuredHeight != height }.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(it.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }
}
