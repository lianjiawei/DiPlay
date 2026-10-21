package com.shilapi.xcertplay
import android.content.Context
import android.widget.LinearLayout
internal class GlassEqualHeightRow(context:Context):LinearLayout(context) {
    init { orientation=HORIZONTAL;isBaselineAligned=false }
    override fun onMeasure(w:Int,h:Int) {
        super.onMeasure(w,h)
        val max=(0 until childCount).maxOfOrNull { getChildAt(it).measuredHeight }?:return
        for(i in 0 until childCount) getChildAt(i).let {
            it.measure(MeasureSpec.makeMeasureSpec(it.measuredWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(max,MeasureSpec.EXACTLY))
        }
    }
}
