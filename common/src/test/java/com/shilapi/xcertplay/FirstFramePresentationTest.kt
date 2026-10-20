package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class FirstFramePresentationTest {
    @Test fun videoIsRevealedImmediatelyWhenMotionIsDisabledWithoutAnotherTap() = withViews { waiting, badge, presentation ->
        presentation.firstFrame(animate = false)
        assertEquals(View.GONE, waiting.visibility)
        assertEquals(View.GONE, badge.visibility)
        assertFalse(badge.isClickable)
        assertFalse(badge.isFocusable)
    }

    @Test fun aNewConnectionDuringTheFadeKeepsItsPreparationVisible() = withViews { waiting, badge, presentation ->
        presentation.firstFrame(animate = true)
        assertEquals(View.GONE, waiting.visibility)
        presentation.waiting()
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        assertEquals(View.VISIBLE, waiting.visibility)
        assertEquals(View.GONE, badge.visibility)
    }

    @Test fun finishingTheWindowCancelsTransientFeedbackAndDuplicateFramesDoNotReplayIt() = withViews { waiting, badge, presentation ->
        presentation.firstFrame(animate = true)
        presentation.finish()
        presentation.firstFrame(animate = true)
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        assertEquals(View.GONE, waiting.visibility)
        assertEquals(View.GONE, badge.visibility)
    }

    private fun withViews(test: (View, View, FirstFramePresentation) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val waiting = View(activity)
        val badge = View(activity).apply { visibility = View.GONE }
        root.addView(waiting); root.addView(badge)
        activity.setContentView(root)
        try { test(waiting, badge, FirstFramePresentation(waiting, badge)) }
        finally { controller.pause().stop().destroy() }
    }
}
