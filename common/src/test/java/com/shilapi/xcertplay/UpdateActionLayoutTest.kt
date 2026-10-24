package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.update.UpdateAvailability
import java.io.File
import java.net.SocketTimeoutException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29, 33], qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateActionLayoutTest {
    private var controller: ActivityController<DiPlayActivity>? = null

    @After fun cleanup() {
        controller?.pause()?.stop()?.destroy()
        RuntimeEnvironment.setFontScale(1f)
        CarPlayBackgroundSession.clear()
    }

    @Test fun updateActionsRetainTheirFinalHeightAcrossStatusChangesAndTraversals() = exercise(null)

    @Test @Config(sdk = [29], qualifiers = "zh-rCN-w1280dp-h720dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fullChineseLargeFont() = exercise("full-zh")

    @Test @Config(sdk = [29], qualifiers = "zh-rCN-w320dp-h600dp-port-xhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactChineseLargeFont() = exercise("compact-zh")

    @Test @Config(sdk = [29], qualifiers = "ar-ldrtl-w1280dp-h480dp-land-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun arabicLargeFont() = exercise("full-ar-rtl")

    private fun exercise(screenshot: String?) {
        if (screenshot != null) RuntimeEnvironment.setFontScale(1.3f)
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        UpdateAvailability.saveBackgroundChecksEnabled(app, false)
        val connectivity = shadowOf(app.getSystemService(ConnectivityManager::class.java))
        connectivity.clearAllNetworks()
        connectivity.setActiveNetworkInfo(null)
        val network = ShadowNetwork.newInstance(99)
        connectivity.addNetwork(network, mock(NetworkInfo::class.java))
        val capabilities = mock(NetworkCapabilities::class.java)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        connectivity.setNetworkCapabilities(network, capabilities)
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup().visible()
        val screen = controller!!.get()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ABOUT)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        val root = screen.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        root.layoutDirection = screen.resources.configuration.layoutDirection
        val density = screen.resources.displayMetrics.density
        val width = (screen.resources.configuration.screenWidthDp * density).toInt()
        val height = (screen.resources.configuration.screenHeightDp * density).toInt()
        fun traverse() {
            root.requestLayout()
            root.measure(exact(width), exact(height))
            root.layout(0, 0, width, height)
            root.viewTreeObserver.dispatchOnPreDraw()
        }
        val workers = mutableListOf<Runnable>()
        screen.updateCheckWorker = workers::add
        screen.updateLookup = { throw SocketTimeoutException("test timeout") }
        val check = ReflectionHelpers.getField<Button>(screen, "updateCheckButton")
        val history = ReflectionHelpers.getField<Button>(screen, "historyActionButton")
        for (stage in listOf("idle", "checking", "failed")) {
            if (stage == "checking") {
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "checkForUpdates")
                assertEquals("Check stage: ${ReflectionHelpers.getField<Any>(screen, "updateStage")}; " +
                    "networks: ${app.getSystemService(ConnectivityManager::class.java).allNetworks.size}", 1, workers.size)
            }
            if (stage == "failed") workers.removeAt(0).run()
            repeat(5) {
                traverse()
                for (button in listOf(check, history)) {
                    val visible = Rect()
                    assertTrue("${button.text} is visible", button.getGlobalVisibleRect(visible))
                    assertEquals("${button.text} is not clipped at $stage: " + generateSequence(button as View) { it.parent as? View }
                        .joinToString { "${it.javaClass.simpleName}=${it.top},${it.height}/${it.measuredHeight},pad=${it.paddingTop},${it.paddingBottom}" },
                        button.height, visible.height())
                    assertEquals("${button.text} measured height after $stage traversal $it",
                        button.height, button.measuredHeight)
                    assertTrue("${button.text} has room for its complete text",
                        button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                }
                if (check.parent === history.parent && (check.parent as? LinearLayout)?.orientation == LinearLayout.HORIZONTAL)
                    assertEquals(check.height, history.height)
            }
            if (screenshot != null) {
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(image))
                    val file = File("build/reports/update-actions/$screenshot-$stage.png")
                    file.parentFile.mkdirs()
                    file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally { image.recycle() }
            }
        }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
