package com.shilapi.xcertplay

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.provider.Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 30, 33], manifest = Config.NONE)
class BackgroundWirelessWakeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val selected = "12:34:56:78:9A:BC"
    private val launched = mutableListOf<Intent>()
    private val context get() = object : ContextWrapper(app) {
        override fun startActivity(intent: Intent) { launched += intent }
    }

    @Before fun setUp() {
        AppLocale.save(app, AppLocale.SIMPLIFIED_CHINESE)
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay_background_wireless_wake", Context.MODE_PRIVATE).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 5)
        if (Build.VERSION.SDK_INT >= 31) shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, false)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        DiPlayPreferences.savePhone(app, selected, "Test phone")
    }

    @After fun tearDown() {
        AirPlayPersistence.saveBackgroundStartOnBoot(app, false)
        BackgroundWirelessWake.cancel(app)
        CarPlayBackgroundSession.clear()
    }

    @Test fun selectedPhoneAclCanWakeOnlyAnArmedBackgroundWirelessBoot() {
        BackgroundWirelessWake.armForCurrentBoot(app)
        event("AA:BB:CC:DD:EE:FF")
        assertTrue(launched.isEmpty())
        event(selected)
        event(selected)
        assertEquals(1, launched.size)
        assertEquals(DiPlayActivity::class.java.name, launched.single().component?.className)
        assertTrue(launched.single().getBooleanExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, false))
        assertTrue(launched.single().getBooleanExtra(PhoneBluetoothReceiver.EXTRA_BACKGROUND_BOOT_WIRELESS, false))
        assertTrue(BackgroundWirelessWake.matches(app, launched.single()))
    }

    @Test fun staleOrCancelledWaitCannotWake() {
        BackgroundWirelessWake.armForCurrentBoot(app)
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 6)
        event(selected)
        assertTrue(launched.isEmpty())
        BackgroundWirelessWake.armForCurrentBoot(app)
        BackgroundWirelessWake.cancel(app)
        event(selected)
        assertTrue(launched.isEmpty())
    }

    @Test fun changingAnyRequiredPreferenceCancelsEligibility() {
        BackgroundWirelessWake.armForCurrentBoot(app)
        AirPlayPersistence.saveBackgroundStartOnBoot(app, false)
        assertFalse(BackgroundWirelessWake.isArmed(app))
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        assertFalse(BackgroundWirelessWake.isArmed(app))
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        DiPlayPreferences.saveAutoConnect(app, false)
        assertFalse(BackgroundWirelessWake.isArmed(app))
        DiPlayPreferences.saveAutoConnect(app, true)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        assertFalse(BackgroundWirelessWake.isArmed(app))
    }

    @Test fun disablingAndReenablingDoesNotReviveAnOldWait() {
        BackgroundWirelessWake.armForCurrentBoot(app)
        AirPlayPersistence.saveBackgroundStartOnBoot(app, false)
        AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
        assertFalse(BackgroundWirelessWake.isArmed(app))
        BackgroundWirelessWake.armForCurrentBoot(app)
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        AirPlayPersistence.saveAutoStartOnBoot(app, true)
        assertFalse(BackgroundWirelessWake.isArmed(app))
        BackgroundWirelessWake.armForCurrentBoot(app)
        DiPlayPreferences.saveAutoConnect(app, false)
        DiPlayPreferences.saveAutoConnect(app, true)
        assertFalse(BackgroundWirelessWake.isArmed(app))
        BackgroundWirelessWake.armForCurrentBoot(app)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.WIRELESS)
        assertFalse(BackgroundWirelessWake.isArmed(app))
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.LAST_USED)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        BackgroundWirelessWake.armForCurrentBoot(app)
        assertTrue(BackgroundWirelessWake.isArmed(app))
        AirPlayPersistence.saveWirelessEnabled(app, false)
        AirPlayPersistence.saveWirelessEnabled(app, true)
        assertFalse(BackgroundWirelessWake.isArmed(app))
    }

    @Test fun missingBootIdentityFailsClosed() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
        BackgroundWirelessWake.armForCurrentBoot(app)
        event(selected)
        assertTrue(launched.isEmpty())
    }

    @Test fun changingSelectedPhoneInvalidatesQueuedBackgroundWake() {
        val lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = lifecycle.get()
            ReflectionHelpers.setField(activity, "setupError", null)
            BackgroundWirelessWake.armForCurrentBoot(app)
            event(selected)
            val queued = launched.single()
            DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:FF", "Other phone")
            assertFalse(BackgroundWirelessWake.isArmed(app))
            assertFalse(ReflectionHelpers.callInstanceMethod<Boolean>(activity,
                "consumeBluetoothAutoConnectIntent", ReflectionHelpers.ClassParameter.from(Intent::class.java, queued)))
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun updatingSamePhoneNamePreservesWait() {
        BackgroundWirelessWake.armForCurrentBoot(app)
        DiPlayPreferences.savePhone(app, selected.lowercase(), "Renamed phone")
        assertTrue(BackgroundWirelessWake.isArmed(app))
    }

    @Test fun existingBluetoothOptInStillWorksWithoutBackgroundMode() {
        AirPlayPersistence.saveBackgroundStartOnBoot(app, false)
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        event(selected)
        assertEquals(1, launched.size)
        assertFalse(launched.single().getBooleanExtra(PhoneBluetoothReceiver.EXTRA_BACKGROUND_BOOT_WIRELESS, false))
    }

    @Test fun independentBluetoothWakeStillWorksWhenBootAndOpenAutoConnectAreDisabled() {
        AirPlayPersistence.saveAutoStartOnBoot(app, false)
        DiPlayPreferences.saveAutoConnect(app, false)
        DiPlayPreferences.saveDefaultConnectionMode(app, DefaultConnectionMode.USB)
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        event(selected)
        assertEquals(1, launched.size)
        assertFalse(launched.single().getBooleanExtra(PhoneBluetoothReceiver.EXTRA_BACKGROUND_BOOT_WIRELESS, false))
        val lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            ReflectionHelpers.setField(lifecycle.get(), "setupError", null)
            assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(lifecycle.get(), "consumeBluetoothAutoConnectIntent",
                ReflectionHelpers.ClassParameter.from(Intent::class.java, launched.single())))
        } finally { lifecycle.destroy() }
    }

    @Test fun activityAcceptsOnlyCurrentBackgroundWakeAndConsumesItOnce() {
        val lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = lifecycle.get()
            ReflectionHelpers.setField(activity, "setupError", null)
            BackgroundWirelessWake.armForCurrentBoot(app)
            val intent = BackgroundWirelessWake.launchIntent(app, requireNotNull(BackgroundWirelessWake.token(app)))
            fun consume() = ReflectionHelpers.callInstanceMethod<Boolean>(activity,
                "consumeBluetoothAutoConnectIntent", ReflectionHelpers.ClassParameter.from(Intent::class.java, intent))
            assertTrue(consume())
            assertFalse(BackgroundWirelessWake.isArmed(app))
            assertFalse(consume())
            BackgroundWirelessWake.armForCurrentBoot(app)
            AirPlayPersistence.saveBackgroundStartOnBoot(app, false)
            intent.putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, true)
                .putExtra(PhoneBluetoothReceiver.EXTRA_BACKGROUND_BOOT_WIRELESS, true)
            assertFalse(consume())
        } finally {
            lifecycle.destroy()
        }
    }

    @Test fun lateWakeAfterCancellationDoesNotShowHome() {
        BackgroundWirelessWake.armForCurrentBoot(app)
        val late = BackgroundWirelessWake.launchIntent(app, requireNotNull(BackgroundWirelessWake.token(app)))
        BackgroundWirelessWake.cancel(app)
        val lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java, late).create().start().resume()
        try {
            assertTrue(lifecycle.get().isFinishing)
        } finally { lifecycle.destroy() }
    }

    @Test fun oldWakeCannotConsumeOrCancelANewWaitInTheSameBoot() {
        BackgroundWirelessWake.armForCurrentBoot(app)
        val oldToken = requireNotNull(BackgroundWirelessWake.token(app))
        val oldIntent = BackgroundWirelessWake.launchIntent(app, oldToken)
        BackgroundWirelessWake.cancel(app)
        BackgroundWirelessWake.armForCurrentBoot(app)
        val nextToken = requireNotNull(BackgroundWirelessWake.token(app))
        assertNotEquals(oldToken, nextToken)
        assertFalse(BackgroundWirelessWake.consume(app, oldToken))
        BackgroundWirelessWake.cancelIfCurrent(app, oldToken)
        assertTrue(BackgroundWirelessWake.matches(app, nextToken))
        assertFalse(BackgroundWirelessWake.matches(app, oldIntent))
        assertNotEquals(oldIntent.data, BackgroundWirelessWake.launchIntent(app, nextToken).data)
        val lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java, oldIntent).create()
        try {
            assertTrue(lifecycle.get().isFinishing)
            assertTrue(BackgroundWirelessWake.matches(app, nextToken))
        } finally { lifecycle.destroy() }
    }

    @Test fun cancellingWaitInvalidatesItsNotificationAction() {
        BackgroundWirelessWake.armForCurrentBoot(app)
        val oldIntent = BackgroundWirelessWake.launchIntent(app, requireNotNull(BackgroundWirelessWake.token(app)))
        val oldAction = PendingIntent.getActivity(app, BackgroundWirelessWake.PENDING_INTENT_REQUEST,
            oldIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        BackgroundWirelessWake.cancel(app)
        BackgroundWirelessWake.armForCurrentBoot(app)
        try {
            oldAction.send()
            fail("An old notification must not bring the app back to the foreground")
        } catch (_: PendingIntent.CanceledException) { }
        assertTrue(BackgroundWirelessWake.isArmed(app))
    }

    @Test @Config(sdk = [30]) @LooperMode(LooperMode.Mode.PAUSED)
    fun queuedBackgroundConnectRechecksUserChoicesButLegacyWakeStillConnects() {
        val lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val activity = lifecycle.get()
            ReflectionHelpers.setField(activity, "setupError", null)
            AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
            var connections = 0
            activity.projectionStarter = { connections++; true }
            fun queue(background: Boolean) = ReflectionHelpers.callInstanceMethod<Unit>(activity,
                "scheduleBluetoothAutoConnect", ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, background))
            queue(true)
            AirPlayPersistence.saveBackgroundStartOnBoot(app, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, connections)
            AirPlayPersistence.saveBackgroundStartOnBoot(app, true)
            queue(true)
            DiPlayPreferences.savePhone(app, "AA:BB:CC:DD:EE:FF", "Other phone")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, connections)
            AirPlayPersistence.saveBackgroundStartOnBoot(app, false)
            queue(false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, connections)
        } finally { lifecycle.destroy() }
    }

    private fun event(address: String) {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        PhoneBluetoothReceiver().onReceive(context,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
    }
}
