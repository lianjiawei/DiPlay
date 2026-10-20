package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import android.os.Looper
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.setup.SetupGuide
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class FirstLaunchHomeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val activities = mutableListOf<ActivityController<DiPlayActivity>>()

    @Before fun freshInstall() {
        for (name in listOf("diplay", "xcertplay_airplay")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        CarPlayBackgroundSession.clear()
        // A supported authentication configuration without private assets in the test tree.
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.USB_CH341)
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
    }

    @After fun cleanup() {
        activities.reversed().forEach { it.get().finish(); it.pause().stop().destroy() }
        CarPlayBackgroundSession.clear()
    }

    @Test fun aFreshInstallOpensHomeWithoutASavedPhoneOrCompletedGuide() {
        val screen = open().get()
        assertFalse(SetupGuide.seen(app))
        assertNull(DiPlayPreferences.phoneAddress(app))
        assertNull(ReflectionHelpers.getField<String?>(screen, "setupError"))
        assertEquals("home", page(screen))
    }

    @Test fun defaultUsbAutoConnectDoesNotRequireCompletingTheGuide() {
        val screen = open().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("home", page(screen))
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(screen).nextStartedActivity?.component?.className)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(app))
        assertFalse(SetupGuide.seen(app))
    }

    @Test fun disablingAutoConnectLeavesTheFreshInstallOnHome() {
        DiPlayPreferences.saveAutoConnect(app, false)
        val screen = open().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("home", page(screen))
        assertNull(shadowOf(screen).nextStartedActivity)
        assertFalse(SetupGuide.seen(app))
    }

    @Test fun manualSetupAndRestoredSettingsRemainAccessibleWithoutAutoConnecting() {
        val settings = open(Bundle().apply { putString("page", "settings") }).get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("settings", page(settings))
        assertNull(shadowOf(settings).nextStartedActivity)
        ReflectionHelpers.callInstanceMethod<Unit>(settings, "openSetupGuide",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true))
        assertEquals("setup", page(settings))
        assertTrue(ReflectionHelpers.getField(settings, "setupFromSettings"))
        assertFalse(SetupGuide.seen(app))
    }

    private fun open(saved: Bundle? = null): ActivityController<DiPlayActivity> =
        Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .create(saved).start().resume().also { activities += it }

    private fun page(screen: DiPlayActivity): String = ReflectionHelpers.getField(screen, "page")
}
