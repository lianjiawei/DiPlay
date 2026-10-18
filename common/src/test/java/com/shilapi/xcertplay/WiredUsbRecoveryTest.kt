package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Answers
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WiredUsbRecoveryTest {
    private val old = device("old", 1)
    private val next = device("next", 2)
    private var devices = listOf(old)
    private var completeTransition = true
    private var scanFails = false
    private var scans = 0
    private var dataOpens = 0
    private val requested = mutableListOf<UsbDevice>()
    private val transitions = mutableListOf<(IphoneUsbHost.TransitionResult) -> Unit>()
    private val sessionCallbacks = mutableListOf<(IphoneUsbHost.Iap2SessionResult) -> Unit>()
    private val statuses = mutableListOf<CarPlayStatus>()
    private val controllers = mutableListOf<CarPlayController>()
    private val host = mock(IphoneUsbHost::class.java) { call ->
        when (call.method.name) {
            "discover" -> {
                scans++
                check(!scanFails) { "USB service temporarily unavailable" }
                devices
            }
            "requestPermission" -> {
                val device = call.getArgument<UsbDevice>(0)
                requested += device
                IphoneUsbHost.PermissionRequest.Requested(device)
            }
            "requestCarPlayReenumerationAsync" -> {
                val callback = call.getArgument<(IphoneUsbHost.TransitionResult) -> Unit>(2)
                transitions += callback
                if (completeTransition) callback(IphoneUsbHost.TransitionResult.ReenumerationRequested)
                null
            }
            "openIap2UsbSessionAsync" -> {
                dataOpens++
                sessionCallbacks += call.getArgument<(IphoneUsbHost.Iap2SessionResult) -> Unit>(2)
                null
            }
            else -> Answers.RETURNS_DEFAULTS.answer(call)
        }
    }

    @After fun cleanup() {
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2_000)) }
        BydNavigationOutputs.endNow()
    }

    @Test fun aPreconnectedPhoneNeedsNoAttachBroadcast() {
        startPhone(controller())
        idle()
        assertEquals(listOf(old), requested)
    }

    @Test fun initialDiscoveryStillPollsForAPhoneThatAppearsLater() {
        devices = emptyList()
        startPhone(controller())
        idle()
        assertTrue(statuses.contains(CarPlayStatus.WaitingForIphone))
        devices = listOf(next)
        advance(350)
        assertEquals(listOf(next), requested)
    }

    @Test fun aNewEnumerationIsFoundWithoutAnAttachBroadcast() {
        begin(controller())
        devices = listOf(next)
        advance(350)
        assertEquals(listOf(next), requested)
        assertEquals(1, transitions.size)
    }

    @Test fun theOldNodeIsNotReusedWhileItIsStillListed() {
        begin(controller())
        advance(1_000)
        assertTrue(requested.isEmpty())
        assertEquals(1, transitions.size)
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
    }

    @Test fun aReusedAddressIsAcceptedAfterTheOldDeviceDisappears() {
        begin(controller())
        devices = emptyList()
        advance(350)
        devices = listOf(old)
        advance(350)
        assertEquals(listOf(old), requested)
    }

    @Test fun changedDescriptorsAtTheSameAddressCountAsANewEnumeration() {
        begin(controller())
        val changed = device("old", 1)
        val configuration = mock(UsbConfiguration::class.java)
        `when`(configuration.id).thenReturn(6)
        `when`(changed.configurationCount).thenReturn(1)
        `when`(changed.getConfiguration(0)).thenReturn(configuration)
        devices = listOf(changed)
        advance(350)
        assertEquals(listOf(changed), requested)
    }

    @Test fun anAlreadyConnectedSecondPhoneIsNotMistakenForTheReenumeratedPhone() {
        val other = device("other", 3)
        devices = listOf(old, other)
        begin(controller())
        devices = listOf(other)
        advance(700)
        assertTrue(requested.isEmpty())
        devices = listOf(other, next)
        advance(350)
        assertEquals(listOf(next), requested)
    }

    @Test fun broadcastAndPollingCannotRequestUsbPermissionTwice() {
        val c = controller()
        begin(c)
        devices = listOf(next)
        attached(c, next)
        attached(c, next)
        advance(1_000)
        assertEquals(listOf(next), requested)
    }

    @Test fun aTrustedAttachEventStillHandlesAnAddressReusedBetweenPolls() {
        val c = controller()
        begin(c)
        idle()
        attached(c, old)
        idle()
        assertEquals(listOf(old), requested)
    }

    @Test fun missingReenumerationTimesOutOnceWithoutRepeatedModeSwitches() {
        begin(controller())
        advance(31_000)
        assertEquals(1, statuses.filterIsInstance<CarPlayStatus.Failed>().size)
        advance(31_000)
        assertEquals(1, statuses.filterIsInstance<CarPlayStatus.Failed>().size)
        assertEquals(1, transitions.size)
        assertTrue(requested.isEmpty())
    }

    @Test fun aTransitionCallbackThatNeverArrivesAlsoHasABoundedWait() {
        completeTransition = false
        begin(controller())
        advance(31_000)
        assertEquals(1, statuses.filterIsInstance<CarPlayStatus.Failed>().size)
    }

    @Test fun aDelayedOldTransitionCallbackCannotAdvanceOrFailItsReplacement() {
        completeTransition = false
        val c = controller()
        begin(c)
        begin(c)
        transitions[0](IphoneUsbHost.TransitionResult.ReenumerationRequested)
        transitions[0](IphoneUsbHost.TransitionResult.Failed(
            com.shilapi.xcertplay.transport.IphoneUsbException.Protocol("old request")))
        advance(350)
        assertTrue(requested.isEmpty())
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
        devices = listOf(next)
        transitions[1](IphoneUsbHost.TransitionResult.ReenumerationRequested)
        advance(350)
        assertEquals(listOf(next), requested)
    }

    @Test fun closingCancelsPendingScansTimeoutsAndTransitionCallbacks() {
        completeTransition = false
        val c = controller()
        begin(c)
        c.close()
        val before = scans
        devices = listOf(next)
        transitions.single()(IphoneUsbHost.TransitionResult.ReenumerationRequested)
        advance(31_000)
        assertEquals(before, scans)
        assertTrue(requested.isEmpty())
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
    }

    @Test fun waitingForUsbPermissionDoesNotTriggerTheReenumerationTimeout() {
        begin(controller())
        devices = listOf(next)
        advance(350)
        assertEquals(listOf(next), requested)
        advance(31_000)
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
    }

    @Test fun anOldPermissionGrantCannotReopenThePreviousNodeDuringTransition() {
        val c = controller()
        begin(c)
        c.javaClass.getDeclaredMethod("onIphonePermission", IphoneUsbHost.PermissionResult::class.java)
            .apply { isAccessible = true }
            .invoke(c, IphoneUsbHost.PermissionResult.Granted(old))
        idle()
        assertEquals(1, transitions.size)
        assertEquals(0, dataOpens)
    }

    @Test fun aTransientScanFailureDoesNotPretendTheOldNodeDisappeared() {
        begin(controller())
        idle()
        scanFails = true
        advance(350)
        scanFails = false
        advance(350)
        assertTrue(requested.isEmpty())
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
        devices = listOf(next)
        advance(350)
        assertEquals(listOf(next), requested)
    }

    @Test fun aFreshAttachCanAdvanceBeforeASlowModeReplyWithoutRegressingStatus() {
        completeTransition = false
        val c = controller()
        begin(c)
        devices = listOf(next)
        attached(c, next)
        advance(350)
        assertEquals(listOf(next), requested)
        assertEquals(CarPlayStatus.RequestingIphonePermission, statuses.last())
        transitions.single()(IphoneUsbHost.TransitionResult.ReenumerationRequested)
        idle()
        assertEquals(listOf(next), requested)
        assertEquals(CarPlayStatus.RequestingIphonePermission, statuses.last())
    }

    @Test fun rejectedModeRequestsKeepTheExistingOldIosDataPathFallback() {
        completeTransition = false
        begin(controller(), fallback = true)
        transitions.single()(IphoneUsbHost.TransitionResult.Failed(
            com.shilapi.xcertplay.transport.IphoneUsbException.Protocol("mode request rejected")))
        advance(31_000)
        assertEquals(1, dataOpens)
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
    }

    @Test fun anOldRejectedRequestCannotOpenDataPathsAfterItsReplacementStarts() {
        completeTransition = false
        val c = controller()
        begin(c, fallback = true)
        begin(c)
        transitions[0](IphoneUsbHost.TransitionResult.Failed(
            com.shilapi.xcertplay.transport.IphoneUsbException.Protocol("old rejected request")))
        idle()
        assertEquals(0, dataOpens)
        assertTrue(statuses.none { it is CarPlayStatus.Failed })
    }

    @Test fun aReplyAfterTimeoutCannotResumeTheExpiredAttempt() {
        completeTransition = false
        begin(controller())
        advance(31_000)
        devices = listOf(next)
        transitions.single()(IphoneUsbHost.TransitionResult.ReenumerationRequested)
        advance(350)
        assertEquals(1, statuses.filterIsInstance<CarPlayStatus.Failed>().size)
        assertTrue(requested.isEmpty())
    }

    @Test fun anAlreadyQueuedPermissionRequestCannotRunInTheNextTransition() {
        val c = controller()
        val field = c.javaClass.getDeclaredField("phase")
        ReflectionHelpers.setField(c, "phase", field.type.enumConstants.first { it.toString() == "IPHONE" })
        c.javaClass.getDeclaredMethod("requestIphonePermission", UsbDevice::class.java)
            .apply { isAccessible = true }.invoke(c, old)
        begin(c)
        devices = listOf(next)
        advance(350)
        assertEquals(listOf(next), requested)
    }

    @Test fun anInitialAttachQueuedBeforeTheModeSwitchCannotBeReplayedAsReenumeration() {
        val c = controller()
        val field = c.javaClass.getDeclaredField("phase")
        ReflectionHelpers.setField(c, "phase", field.type.enumConstants.first { it.toString() == "IPHONE" })
        attached(c, old)
        begin(c)
        advance(1_000)
        assertTrue(requested.isEmpty())
        assertEquals(1, transitions.size)
    }

    @Test fun aSessionOpenedAfterCloseIsReleasedWithoutTouchingNcm() {
        val c = controller()
        openDataPaths(c)
        c.close()
        assertTrue(c.awaitClosed(2_000))
        val session = mock(Iap2UsbSession::class.java)
        val before = statuses.toList()
        clearInvocations(old)
        sessionCallbacks.single()(IphoneUsbHost.Iap2SessionResult.Connected(session))
        idle()
        verify(session).close()
        verify(old, never()).configurationCount
        assertEquals(before, statuses)
    }

    @Test fun aSessionFromAnOldAttemptIsReleasedWithoutFailingItsReplacement() {
        val c = controller()
        openDataPaths(c)
        startPhone(c)
        idle()
        val session = mock(Iap2UsbSession::class.java)
        val before = statuses.toList()
        sessionCallbacks.single()(IphoneUsbHost.Iap2SessionResult.Connected(session))
        idle()
        verify(session).close()
        assertEquals(before, statuses)
    }

    @Test fun anOldSessionFailureCannotFailItsReplacement() {
        val c = controller()
        openDataPaths(c)
        startPhone(c)
        idle()
        val before = statuses.toList()
        sessionCallbacks.single()(IphoneUsbHost.Iap2SessionResult.Failed(
            com.shilapi.xcertplay.transport.IphoneUsbException.Protocol("old USB open")))
        idle()
        assertEquals(before, statuses)
    }

    @Test fun closingBetweenOpeningNcmAndStartingTheStackReleasesBothUsbConnections() {
        val c = controller()
        c.close()
        assertTrue(c.awaitClosed(2_000))
        val session = mock(Iap2UsbSession::class.java)
        val ncm = mock(NcmUsbBridge::class.java)
        c.javaClass.getDeclaredMethod("runStack", Iap2UsbSession::class.java, NcmUsbBridge::class.java)
            .apply { isAccessible = true }.invoke(c, session, ncm)
        verify(session).close()
        verify(ncm).close()
    }

    private fun device(name: String, id: Int): UsbDevice = mock(UsbDevice::class.java).also {
        `when`(it.deviceName).thenReturn(name)
        `when`(it.deviceId).thenReturn(id)
        `when`(it.vendorId).thenReturn(0x05ac)
        `when`(it.productId).thenReturn(0x12a8)
    }

    private fun controller(): CarPlayController {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int) = false
        }
        return CarPlayController(context,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test",
                    manufacturer = "test", serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1",
                    carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, statuses::add,
        ).also {
            ReflectionHelpers.setField(it, "iphoneHost\$delegate", lazyOf(host))
            controllers += it
        }
    }

    private fun startPhone(c: CarPlayController) = c.javaClass.getDeclaredMethod("startIphone")
        .apply { isAccessible = true }.invoke(c)
    private fun openDataPaths(c: CarPlayController) = c.javaClass.getDeclaredMethod("openDataPaths", UsbDevice::class.java)
        .apply { isAccessible = true }.invoke(c, old)
    private fun begin(c: CarPlayController, fallback: Boolean = false) = c.javaClass.getDeclaredMethod("beginReenumeration",
        UsbDevice::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(c, old, fallback)
    private fun attached(c: CarPlayController, device: UsbDevice) = c.javaClass.getDeclaredMethod("onIphoneAttached",
        UsbDevice::class.java).apply { isAccessible = true }.invoke(c, device)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
