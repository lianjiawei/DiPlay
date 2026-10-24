package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.setup.SetupGuide
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class PreattachedIphoneStartupTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val usb: UsbManager get() = app.getSystemService(UsbManager::class.java)
    private var lifecycle: ActivityController<DiPlayActivity>? = null
    private lateinit var activity: DiPlayActivity
    private var stops = 0

    @Before fun prepare() {
        ShadowUsbManager.reset()
        CarPlayBackgroundSession.clear()
        for (name in listOf("diplay", "xcertplay_airplay")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        DiPlayPreferences.savePhone(app, "00:11:22:33:44:55", "Test phone")
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
        AirPlayPersistence.saveWirelessEnabled(app, true)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.EXISTING_WIFI)
        // These startup routing tests use a fully configured LAN connection.
        AirPlayPersistence.saveExistingWifiCredentials(app, "Router", "router-password")
        activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        ReflectionHelpers.setField(activity, "page", "home")
    }

    @After fun cleanup() {
        lifecycle?.pause()?.stop()?.destroy()
        ReflectionHelpers.getField<android.os.Handler>(activity, "handler").removeCallbacksAndMessages(null)
        CarPlayBackgroundSession.clear()
        ShadowUsbManager.reset()
    }

    @Test fun enabledBootSelectsTheAlreadyConnectedIphoneThroughTheNormalActivityStartup() {
        attachApple()
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.USB_CH341)
        SetupGuide.markSeen(app)
        val launch = bootLaunch()!!
        assertEquals(DiPlayActivity::class.java.name, launch.component?.className)
        lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java, launch).create().start().resume()
        activity = lifecycle!!.get()
        idle()
        assertHostStarted(wireless = false)
        assertEquals(DefaultConnectionMode.WIRELESS, DiPlayPreferences.defaultConnectionMode(app))
    }

    @Test fun disabledBootDoesNotLaunchEvenWithAPhoneAndAutoConnectEnabled() {
        attachApple()
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        assertNull(bootLaunch())
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
    }

    @Test fun disabledAutoConnectDoesNotStartProjectionForAPreattachedPhone() {
        attachApple()
        DiPlayPreferences.saveAutoConnect(app, false)
        launchAutomatically()
        idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
    }

    @Test fun unrelatedUsbDevicesPreserveTheConfiguredWirelessDefault() {
        attach(0x1234)
        launchAutomatically()
        idle()
        assertHostStarted(wireless = true)
    }

    @Test fun noPhonePreservesTheConfiguredWiredDefault() {
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        launchAutomatically()
        idle()
        assertHostStarted(wireless = false)
    }

    @Test fun attachedIphoneUsesUsbEvenWhenLanCredentialsHaveNotBeenConfigured() {
        AirPlayPersistence.saveExistingWifiCredentials(app, "", "")
        attachApple()
        launchAutomatically()
        idle()
        assertHostStarted(wireless = false)
    }

    @Test fun permissionIsRequestedByTheExistingHostNotByTheStartupScan() {
        val phone = attachApple()
        assertFalse(usb.hasPermission(phone))
        launchAutomatically()
        idle()
        assertHostStarted(wireless = false)
        assertFalse(usb.hasPermission(phone))
    }

    @Test fun setupAndExplicitPageNavigationDoNotAutoConnect() {
        attachApple()
        ReflectionHelpers.setField(activity, "setupError", "Authentication unavailable")
        launchAutomatically()
        ReflectionHelpers.setField(activity, "setupError", null)
        ReflectionHelpers.setField(activity, "page", "setup")
        launchAutomatically()
        ReflectionHelpers.setField(activity, "page", "settings")
        launchAutomatically()
        ReflectionHelpers.setField(activity, "page", "home")
        activity.intent = Intent().putExtra("page", "settings")
        launchAutomatically()
        idle()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun anExistingOrConnectingSessionIsNeverStoppedToPreferUsb() {
        attachApple()
        registerSession()
        launchAutomatically()
        idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, stops)
    }

    @Test fun aSessionStartedWhileTheLaunchIsQueuedIsLeftAlone() {
        attachApple()
        launchAutomatically()
        registerSession()
        idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, stops)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
    }

    @Test fun aDisabledPreferenceOrSettingsNavigationCancelsAQueuedLaunch() {
        attachApple()
        launchAutomatically()
        DiPlayPreferences.saveAutoConnect(app, false)
        idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        DiPlayPreferences.saveAutoConnect(app, true)
        launchAutomatically()
        ReflectionHelpers.setField(activity, "page", "settings")
        idle()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aPhoneUnpluggedBeforeTheCallbackDoesNotForceWiredMode() {
        val phone = attachApple()
        launchAutomatically()
        shadowOf(usb).removeUsbDevice(phone)
        idle()
        assertHostStarted(wireless = true)
    }

    @Test fun finishingTheActivityCancelsAQueuedAutomaticConnection() {
        attachApple()
        launchAutomatically()
        activity.finish()
        idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
    }

    @Test fun repeatedSchedulingStartsOnlyOneProjectionActivity() {
        attachApple()
        repeat(2) { launchAutomatically() }
        idle()
        assertHostStarted(wireless = false)
    }

    @Test fun missingOrDeniedUsbServiceDoesNotCrashStartupDetection() {
        for (denied in listOf(false, true)) {
            val context = object : ContextWrapper(app) {
                override fun getSystemService(name: String): Any? {
                    if (name != USB_SERVICE) return super.getSystemService(name)
                    if (denied) throw SecurityException("USB temporarily unavailable")
                    return null
                }
            }
            assertFalse(PreattachedIphone.isAttached(context))
        }
    }

    private fun bootLaunch(): Intent? {
        var launch: Intent? = null
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launch = intent }
        }
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        return launch
    }

    private fun registerSession() {
        val stop: ((() -> Unit) -> Unit) = { done -> stops++; done() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
    }

    private fun attachApple() = attach(IphoneUsbMatcher.APPLE_VENDOR_ID)

    private fun attach(vendor: Int): UsbDevice = mock(UsbDevice::class.java).also {
        `when`(it.deviceName).thenReturn("/dev/bus/usb/001/002")
        `when`(it.vendorId).thenReturn(vendor)
        shadowOf(usb).addOrUpdateUsbDevice(it, false)
    }

    private fun launchAutomatically() =
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "connectAutomaticallyOnLaunch")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun assertHostStarted(wireless: Boolean) {
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity?.component?.className)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(wireless, AirPlayPersistence.loadWirelessEnabled(app))
        assertEquals(0, stops)
    }
}
