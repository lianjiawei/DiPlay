package com.shilapi.xcertplay

import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.os.Build
import android.provider.Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 30, 33], manifest = Config.NONE)
class BackgroundWirelessProbeServiceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controller = Robolectric.buildService(BackgroundWirelessProbeService::class.java)
    private val service get() = controller.get()

    @Before fun setup() {
        AppLocale.save(app, AppLocale.SIMPLIFIED_CHINESE)
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 5)
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        DiPlayPreferences.savePhone(app, "12:34:56:78:9A:BC", "Phone")
        BackgroundWirelessWake.armForCurrentBoot(app)
        if (Build.VERSION.SDK_INT >= 31) shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        controller.create()
        ReflectionHelpers.setField(service, "waitToken", BackgroundWirelessWake.token(app))
    }

    @After fun cleanup() {
        controller.destroy()
        CarPlayBackgroundSession.clear()
        BackgroundSocketHandoff.clear()
    }

    @Test fun cancellationClosesOnlyTheCurrentAttemptAndLateTimeoutIsHarmless() {
        val socket = mock(BluetoothSocket::class.java)
        assertTrue(install(socket))
        ReflectionHelpers.callInstanceMethod<Unit>(service, "stopNow")
        ReflectionHelpers.callInstanceMethod<Unit>(service, "closeAttempt",
            ClassParameter.from(BluetoothSocket::class.java, socket))
        verify(socket, times(1)).close()
        val late = mock(BluetoothSocket::class.java)
        assertFalse(install(late))
    }

    @Test fun matchingAclIsDeferredDuringAttemptAndOtherPhoneIsIgnored() {
        val socket = mock(BluetoothSocket::class.java)
        assertTrue(install(socket))
        fun event(address: String): Boolean = ReflectionHelpers.callInstanceMethod(service, "recordAcl",
            ClassParameter.from(String::class.java, address))
        assertFalse(event("AA:BB:CC:DD:EE:FF"))
        assertFalse(ReflectionHelpers.getField(service, "aclPending"))
        assertTrue(event("12:34:56:78:9A:BC"))
        assertTrue(ReflectionHelpers.getField(service, "aclPending"))
        verify(socket, never()).close()
    }

    @Test fun repeatedAclAfterSocketHandoffDoesNotQueueAnotherWake() {
        ReflectionHelpers.setField(service, "handoffOffered", true)
        val handled = ReflectionHelpers.callInstanceMethod<Boolean>(service, "recordAcl",
            ClassParameter.from(String::class.java, "12:34:56:78:9A:BC"))
        assertTrue(handled)
        assertFalse(ReflectionHelpers.getField(service, "aclPending"))
    }

    @Test @Config(sdk = [30])
    fun proactiveConnectionTransfersTheOriginalSocket() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 5)
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        BackgroundWirelessWake.armForCurrentBoot(app)
        val manager = mock(BluetoothManager::class.java)
        val adapter = mock(BluetoothAdapter::class.java)
        val phone = mock(BluetoothDevice::class.java)
        val socket = mock(BluetoothSocket::class.java)
        `when`(manager.adapter).thenReturn(adapter)
        `when`(adapter.isEnabled).thenReturn(true)
        `when`(adapter.bondedDevices).thenReturn(setOf(phone))
        `when`(phone.address).thenReturn("12:34:56:78:9A:BC")
        `when`(phone.createRfcommSocketToServiceRecord(UUID.fromString("00000000-deca-fade-deca-deafdecacafe"))).thenReturn(socket)
        shadowOf(app).setSystemService(Context.BLUETOOTH_SERVICE, manager)
        Shadow.extract<ShadowContextImpl>(service.baseContext).setSystemService(Context.BLUETOOTH_SERVICE, manager)
        val connected = CountDownLatch(1)
        doAnswer { connected.countDown(); null }.`when`(socket).connect()
        service.onStartCommand(Intent(), 0, 1)
        assertTrue("fake Bluetooth connection should run immediately", connected.await(3, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var transferred: com.shilapi.xcertplay.orchestration.PreconnectedBluetoothSocket? = null
        while (transferred == null && System.nanoTime() < deadline) {
            transferred = BackgroundSocketHandoff.take("12:34:56:78:9A:BC")
            if (transferred == null) Thread.yield()
        }
        assertSame(socket, transferred?.socket)
        verify(socket, never()).close()
        transferred?.close()
        verify(socket, times(1)).close()
    }

    @Test @Config(sdk = [30])
    fun fastRestartReplacesWorkerAndOldStopCannotCancelTheNewWait() {
        val firstToken = requireNotNull(BackgroundWirelessWake.token(app))
        service.onStartCommand(Intent(), 0, 1)
        val oldWorker = ReflectionHelpers.getField<Thread>(service, "worker")
        BackgroundWirelessWake.cancel(app)
        BackgroundWirelessWake.armForCurrentBoot(app)
        val nextToken = requireNotNull(BackgroundWirelessWake.token(app))
        service.onStartCommand(Intent(), 0, 2)
        assertNotSame(oldWorker, ReflectionHelpers.getField<Thread>(service, "worker"))
        ReflectionHelpers.callInstanceMethod<Unit>(service, "stopForToken", ClassParameter.from(String::class.java, firstToken))
        assertEquals(nextToken, ReflectionHelpers.getField<String>(service, "waitToken"))
        assertFalse(ReflectionHelpers.getField(service, "stopped"))
    }

    private fun install(socket: BluetoothSocket): Boolean = ReflectionHelpers.callInstanceMethod(service, "installAttempt",
        ClassParameter.from(BluetoothSocket::class.java, socket),
        ClassParameter.from(String::class.java, requireNotNull(BackgroundWirelessWake.token(app))))

    @Test fun sessionStartingDuringWaitDisarmsItWithoutStoppingTheSession() {
        val token = requireNotNull(BackgroundWirelessWake.token(app))
        CarPlayBackgroundSession.store(mock(CarPlayController::class.java), mock(AndroidMediaSink::class.java),
            800, 480, Any(), CarPlaySessionDisplay(800, 480, 0, false, false, 800, 480)) { }
        ReflectionHelpers.callInstanceMethod<Unit>(service, "probeLoop", ClassParameter.from(String::class.java, token))
        assertFalse(BackgroundWirelessWake.isArmed(app))
        assertTrue(CarPlayBackgroundSession.hasSession())
    }
}
