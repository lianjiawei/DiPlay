package com.shilapi.xcertplay

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w900dp-h600dp-land")
class UpdateHomeAvailabilityTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @After fun clear() = UpdateAvailability.clearAllForTest(application)

    @Test fun cachedAndNewBackgroundUpdatesNeverAddHomeCardsOrPrompts() = verifyHome()

    @Config(sdk = [29], qualifiers = "en-w400dp-h900dp-port")
    @Test fun compactHomeAlsoKeepsUpdatesInsideSettings() = verifyHome()

    private fun verifyHome() {
        UpdateAvailability.save(application, release())
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            controller.windowFocusChanged(true)
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            UpdateAvailability.save(application, release().copy(tagName = "v100.0.0"))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(1100))
            assertTrue(root === activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().none {
                it.text == activity.getString(R.string.update_available_home)
            })
            assertEquals(null, ReflectionHelpers.getField<UpdatePromptDialog?>(activity, "updatePrompt"))
            ReflectionHelpers.setField(activity, "page", "settings")
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ABOUT)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
                it.text == activity.getString(R.string.update_download)
            })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun everyUpdaterStageKeepsHomeToOneSceneWithoutExtraCards() {
        UpdateAvailability.save(application, release())
        val c = Robolectric.buildActivity(DiPlayActivity::class.java).setup().visible()
        try {
            val a = c.get()
            val initialStage = ReflectionHelpers.getField<Any>(a, "updateStage")
            for (stage in initialStage.javaClass.enumConstants) {
                ReflectionHelpers.setField(a, "updateStage", stage)
                ReflectionHelpers.callInstanceMethod<Unit>(a, "render")
                val root = a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val scene = descendants(root).filterIsInstance<ReferenceHomeScene>().single()
                assertEquals("$stage must not append a home card", 1, (scene.parent as ViewGroup).childCount)
                ReflectionHelpers.setField(a, "updatePromptForDownload", true)
                ReflectionHelpers.callInstanceMethod<Unit>(a, "refreshUpdatePrompt")
                ReflectionHelpers.callInstanceMethod<Unit>(a, "refreshUpdateAvailability")
                assertTrue("$stage must not replace home", root === a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
                assertEquals(null, ReflectionHelpers.getField<UpdatePromptDialog?>(a, "updatePrompt"))
            }
        } finally { c.pause().stop().destroy() }
    }

    private fun release() = UpdateRelease(
        tagName = "v99.0.0",
        apkName = "DiPlay.apk",
        apkUrl = "https://example.com/DiPlay.apk",
        checksumsUrl = "https://example.com/SHA256SUMS.txt",
    )

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
