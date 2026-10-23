package com.shilapi.xcertplay

import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PreparationLayoutTest {
    @Test fun footerStaysVisibleWhileShortViewportsLetTheContentScroll() {
        // Attach without onCreate: avoid starting sensors, networking or CarPlay sessions.
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val method = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
        method.isAccessible = true
        val root = method.invoke(activity) as android.widget.FrameLayout
        val viewport = root.getChildAt(2) as ConnectionPreparationLayout
        val scroll = viewport.contentScroll
        val panel = scroll.getChildAt(0) as LinearLayout
        val actions = viewport.getChildAt(2)
        val density = activity.resources.displayMetrics.density
        fun layout(heightDp: Int) {
            val width = (640 * density).toInt()
            val height = (heightDp * density).toInt()
            repeat(3) {
                // This detached view has no ViewRoot to schedule the next traversal.
                fun invalidateMeasurement(view: View) {
                    view.forceLayout()
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) invalidateMeasurement(view.getChildAt(index))
                    }
                }
                invalidateMeasurement(root)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            assertEquals(0, viewport.scrollY)
            assertEquals(1f, panel.scaleX, 0f)
            assertEquals(1f, panel.scaleY, 0f)
            assertEquals(viewport.height - viewport.paddingBottom - (ConnectionScreenMetrics.BOTTOM_DP * density).toInt(), actions.bottom)
            assertTrue(actions.height >= 48 * density)
            assertTrue(scroll.bottom + ConnectionScreenMetrics.ACTION_GAP_DP * density <= actions.top + 1)
            if (panel.height > scroll.height) {
                scroll.scrollTo(0, panel.height)
                assertTrue("Overflow must remain reachable at height=$heightDp", scroll.scrollY > 0)
                scroll.scrollTo(0, 0)
            }
        }
        for (height in listOf(200, 240, 320, 400, 479, 480, 720)) layout(height)
        viewport.setPadding(12, 20, 12, 32)
        layout(720)
        assertEquals(22f, org.robolectric.util.ReflectionHelpers.getField<TextView>(activity, "connectionTitleView").textSize / activity.resources.displayMetrics.scaledDensity, 0.01f)
    }
}
