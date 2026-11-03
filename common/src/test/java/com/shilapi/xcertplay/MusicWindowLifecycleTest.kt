package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 27, 28, 33], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class MusicWindowLifecycleTest {
    @Test fun hiddenAndDetachedWindowsStopPollingAndReattachToTheLatestSong() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val root = FrameLayout(controller.get())
        val window = CarPlayMusicWindow(controller.get())
        try {
            root.addView(window, FrameLayout.LayoutParams(400, 600))
            controller.get().setContentView(root)
            controller.visible()
            ReflectionHelpers.callInstanceMethod<Unit>(window, "onWindowVisibilityChanged",
                ReflectionHelpers.ClassParameter(Int::class.javaPrimitiveType, View.VISIBLE))
            ClusterMusicState.publish(CarPlayNowPlaying(title = "First"), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("First", window.titleTextView.text.toString())
            val handler = ReflectionHelpers.getField<Handler>(window, "main")
            val poll = ReflectionHelpers.getField<Runnable>(window, "poll")
            assertTrue(handler.hasCallbacks(poll))

            root.visibility = View.GONE
            assertFalse(handler.hasCallbacks(poll))
            root.visibility = View.VISIBLE
            assertTrue(handler.hasCallbacks(poll))
            root.removeView(window)
            assertFalse(handler.hasCallbacks(poll))
            ClusterMusicState.publish(CarPlayNowPlaying(title = "New session"), null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("First", window.titleTextView.text.toString())

            root.addView(window, FrameLayout.LayoutParams(400, 600))
            ReflectionHelpers.callInstanceMethod<Unit>(window, "onWindowVisibilityChanged",
                ReflectionHelpers.ClassParameter(Int::class.javaPrimitiveType, View.VISIBLE))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("New session", window.titleTextView.text.toString())
            assertTrue(handler.hasCallbacks(poll))
        } finally {
            controller.pause().stop().destroy()
            ClusterMusicState.publish(CarPlayNowPlaying(), null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
