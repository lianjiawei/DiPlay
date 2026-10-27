package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.provider.Settings
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsbManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 30, 33], manifest = Config.NONE)
class BackgroundBootRoutingTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val usb: UsbManager get() = app.getSystemService(UsbManager::class.java)
    private val launches = mutableListOf<Intent>()
    private val context get() = object : ContextWrapper(app) {
        override fun startActivity(intent: Intent) { launches += intent }
    }

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        ShadowUsbManager.reset()
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_startup_diagnostics", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        ShadowUsbManager.reset()
        AirPlayPersistence.saveBackgroundStartOnBoot(app, false)
    }

    @Test fun existingInstallStillLaunchesExactlyTheLegacyActivity() {
        assertFalse(AirPlayPersistence.loadBackgroundStartOnBoot(app))
        boot()
        assertEquals(1, launches.size)
        assertEquals(DiPlayActivity::class.java.name, launches.single().component?.className)
        val expected = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(expected, launches.single().flags)
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("startActivity-returned"))
    }

    @Test fun disabledAutoStartNeverLaunchesRegardlessOfBackgroundChoiceOrPhone() {
        attachApple()
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        boot()
        assertTrue(launches.isEmpty())
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchResult=disabled"))
    }

    @Test fun backgroundBootWithoutPhoneDoesNotOpenTheHomeScreen() {
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        boot()
        assertTrue(launches.isEmpty())
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("launchResult=background-wait"))
    }

    @Test fun backgroundBootWithPreviouslyAttachedPhoneReusesNormalStartup() {
        attachApple()
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        boot()
        assertEquals(DiPlayActivity::class.java.name, launches.single().component?.className)
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("startActivity-returned"))
    }

    @Test fun backgroundBootDoesNotAutoConnectPreattachedPhoneWhenAutoConnectIsDisabled() {
        attachApple()
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, false)
        boot()
        assertTrue(launches.isEmpty())
    }

    @Test fun unrelatedUsbDeviceDoesNotOpenTheHomeScreen() {
        val device = mock(UsbDevice::class.java)
        `when`(device.deviceName).thenReturn("/dev/bus/usb/001/002")
        `when`(device.vendorId).thenReturn(0x1234)
        shadowOf(usb).addOrUpdateUsbDevice(device, false)
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        boot()
        assertTrue(launches.isEmpty())
    }

    @Test fun wirelessBackgroundWaitArmsOnlyForTheCurrentBoot() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 5)
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        DiPlayPreferences.savePhone(app, "12:34:56:78:9A:BC", "Test phone")
        boot()
        assertTrue(launches.isEmpty())
        assertTrue(BackgroundWirelessWake.isArmed(app))
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 6)
        assertFalse(BackgroundWirelessWake.isArmed(app))
    }

    @Test fun repeatedBootDoesNotStartAnotherWirelessConnectionOverAnExistingSession() {
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        DiPlayPreferences.savePhone(app, "12:34:56:78:9A:BC", "Phone")
        val controller = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, 0, false, false, 800, 480)) { }
        boot()
        assertTrue(launches.isEmpty())
        assertFalse(BackgroundWirelessWake.isArmed(app))
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertTrue(StartupDiagnosticSnapshot.report(app).contains("session-already-running"))
    }

    private fun boot() = BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

    private fun attachApple() {
        val device = mock(UsbDevice::class.java)
        `when`(device.deviceName).thenReturn("/dev/bus/usb/001/001")
        `when`(device.vendorId).thenReturn(IphoneUsbMatcher.APPLE_VENDOR_ID)
        shadowOf(usb).addOrUpdateUsbDevice(device, false)
    }
}
