package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** A NAKed NCM bulk OUT must not hold a USB transfer for the full timeout before the session (#634). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [NcmWriteConnectionShadow::class, NcmWriteRequestShadow::class])
class NcmUsbBridgeWriteTimeoutTest {
    @Before fun reset() { NcmWriteReplay.reset() }

    @Test fun framesBeforeThePhoneTakesOneUseTheShortTimeoutThenTheCallers() {
        NcmWriteReplay.results.addAll(listOf(-1, -1, NcmWriteReplay.ALL, NcmWriteReplay.ALL))
        val diagnostics = withBridge { ncm -> repeat(4) { ncm.send(frame, 2_000) } }
        val short = NcmUsbBridge.NOT_READY_WRITE_TIMEOUT_MILLIS
        assertEquals(listOf(short, short, short, 2_000), NcmWriteReplay.timeouts)
        assertEquals(listOf("NCM bulk-out ready after 2 dropped frames"), diagnostics)
    }

    @Test fun theLinkOpensWithTheFirstFrameThePhoneTakes() {
        NcmWriteReplay.results.addAll(listOf(-1, -1, NcmWriteReplay.ALL))
        val opened = mutableListOf<Long?>()
        withBridge { ncm -> repeat(3) { ncm.send(frame, 2_000); opened += ncm.linkOpenedAtNanos } }
        assertNull(opened[0])
        assertNull(opened[1])
        assertNotNull(opened[2])
    }

    @Test fun aTimeoutAfterThePipeWasReadyKeepsTheCallersTimeout() {
        NcmWriteReplay.results.addAll(listOf(NcmWriteReplay.ALL, -1, NcmWriteReplay.ALL))
        withBridge { ncm -> repeat(3) { ncm.send(frame, 2_000) } }
        assertEquals(listOf(NcmUsbBridge.NOT_READY_WRITE_TIMEOUT_MILLIS, 2_000, 2_000), NcmWriteReplay.timeouts)
    }

    @Test fun aShorterCallerTimeoutIsKept() {
        NcmWriteReplay.results.add(-1)
        withBridge { ncm -> ncm.send(frame, 5) }
        assertEquals(listOf(5), NcmWriteReplay.timeouts)
    }

    @Test fun idleReceivePumpRetriesOnlyTheLatestBlockAndThenRestoresNormalTimeouts() {
        NcmWriteReplay.results.addAll(listOf(-1, -1, -1, NcmWriteReplay.ALL))
        NcmWriteReplay.readTimeoutsRemaining = 2
        val latest = byteArrayOf(1, 2, 3)
        val diagnostics = withBridge { ncm ->
            ncm.send(frame, 2_000)
            ncm.send(latest, 2_000)
            ncm.recv(1_000)
            assertEquals(200_000_000L, ncm.linkOpenedAtNanos)
            assertTrue(ncm.trafficSummary(null).endsWith("toIphone=1"))
            ncm.recv(1_000)
            ncm.send(frame, 2_000)
            assertEquals(200_000_000L, ncm.linkOpenedAtNanos)
        }
        assertEquals(listOf(20, 20, 20, 20, 2_000), NcmWriteReplay.timeouts)
        assertArrayEquals(Ntb16Codec.build(latest, 1), NcmWriteReplay.blocks[2])
        assertArrayEquals(NcmWriteReplay.blocks[2], NcmWriteReplay.blocks[3])
        assertEquals(listOf(100L, 100L), NcmWriteReplay.readWaits.take(2))
        assertEquals(listOf("NCM bulk-out ready after 2 dropped frames; startup retry"), diagnostics)
    }

    @Test fun aNewSuccessfulSendCancelsTheReplay() {
        NcmWriteReplay.results.add(-1)
        withBridge { ncm ->
            ncm.send(frame, 2_000)
            ncm.send(frame, 2_000)
            NcmWriteReplay.now = 100_000_000
            ncm.recv(1_000)
        }
        assertEquals(2, NcmWriteReplay.blocks.size)
    }

    @Test fun theRetryWindowExpiresEvenWhenNewOutboundFramesArrive() {
        NcmWriteReplay.results.addAll(listOf(-1, -1))
        withBridge { ncm ->
            ncm.send(frame, 2_000)
            NcmWriteReplay.now = 29_900_000_000
            ncm.send(frame, 2_000)
            NcmWriteReplay.now = 30_000_000_000
            ncm.recv(1_000)
            assertNull(ncm.linkOpenedAtNanos)
            ncm.send(frame, 2_000)
            assertNotNull(ncm.linkOpenedAtNanos)
        }
        assertEquals(3, NcmWriteReplay.blocks.size)
    }

    @Test fun shortWritesOnEitherPathFailWithoutAnnouncingAReadyLink() {
        for (retry in listOf(false, true)) {
            reset()
            if (retry) NcmWriteReplay.results.add(-1)
            NcmWriteReplay.results.add(1)
            val diagnostics = withBridge { ncm ->
                if (retry) {
                    ncm.send(frame, 2_000)
                    NcmWriteReplay.now = 100_000_000
                }
                assertThrows(IphoneUsbException.DeviceUnavailable::class.java) {
                    if (retry) ncm.recv(1_000) else ncm.send(frame, 2_000)
                }
                assertNull(ncm.linkOpenedAtNanos)
                assertTrue(ncm.trafficSummary(null).endsWith("toIphone=0"))
                assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { ncm.send(frame, 2_000) }
            }
            assertTrue(diagnostics.isEmpty())
        }
    }

    @Test fun closingABridgeWithAnInFlightRetryDoesNotOpenOrWriteIntoTheNextSession() {
        NcmWriteReplay.results.add(-1)
        val diagnostics = withBridge { ncm ->
            ncm.recv(1_000) // Set up a read request so cancel() signals when teardown has begun.
            ncm.send(frame, 2_000)
            NcmWriteReplay.now = 100_000_000
            val retryStarted = CountDownLatch(1)
            val finishRetry = CountDownLatch(1)
            NcmWriteReplay.onWrite = { retryStarted.countDown(); check(finishRetry.await(3, TimeUnit.SECONDS)) }
            val reader = FutureTask {
                assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { ncm.recv(1_000) }
            }
            Thread(reader).start()
            try {
                assertTrue(retryStarted.await(3, TimeUnit.SECONDS))
                val closeTask = FutureTask { ncm.close() }
                Thread(closeTask).start()
                assertTrue(NcmWriteReplay.readCancelled.await(3, TimeUnit.SECONDS))
                finishRetry.countDown()
                closeTask.get(3, TimeUnit.SECONDS)
                reader.get(3, TimeUnit.SECONDS)
            } finally { finishRetry.countDown() }
            assertNull(ncm.linkOpenedAtNanos)
            assertThrows(IphoneUsbException.DeviceUnavailable::class.java) { ncm.send(frame, 2_000) }
        }
        assertTrue(diagnostics.isEmpty())
        NcmWriteReplay.onWrite = {}
        withBridge { next -> next.recv(1_000); assertNull(next.linkOpenedAtNanos) }
        assertEquals(2, NcmWriteReplay.blocks.size)
    }

    private fun withBridge(block: (NcmUsbBridge) -> Unit): List<String> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val diagnostics = mutableListOf<String>()
        val ncm = NcmUsbBridge(connection, endpoint(0x06), endpoint(0x88), null, emptyList(), null,
            diagnostics::add, nanoTime = { NcmWriteReplay.now })
        try { block(ncm) } finally { ncm.close() }
        return diagnostics
    }

    private companion object {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
    }
}

object NcmWriteReplay {
    const val ALL = Int.MAX_VALUE
    /** One result per bulkTransfer: a byte count, or [ALL] for the whole frame. */
    val results = ArrayDeque<Int>()
    val timeouts = mutableListOf<Int>()
    val blocks = mutableListOf<ByteArray>()
    val readWaits = mutableListOf<Long>()
    var readTimeoutsRemaining = 0
    var now = 0L
    var onWrite: () -> Unit = {}
    lateinit var request: UsbRequest
    lateinit var buffer: ByteBuffer
    var readCancelled = CountDownLatch(1)
    fun reset() {
        results.clear(); timeouts.clear(); blocks.clear(); readWaits.clear()
        readTimeoutsRemaining = 0; now = 0L; onWrite = {}
        readCancelled = CountDownLatch(1)
    }
}

@Implements(UsbDeviceConnection::class)
class NcmWriteConnectionShadow {
    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        NcmWriteReplay.timeouts += timeout
        NcmWriteReplay.blocks += buffer.copyOf(length)
        NcmWriteReplay.onWrite()
        return NcmWriteReplay.results.pollFirst()?.takeIf { it != NcmWriteReplay.ALL } ?: length
    }
    @Implementation fun requestWait(timeoutMillis: Long): UsbRequest {
        NcmWriteReplay.readWaits += timeoutMillis
        if (NcmWriteReplay.readTimeoutsRemaining > 0) {
            NcmWriteReplay.readTimeoutsRemaining--
            NcmWriteReplay.now += timeoutMillis * 1_000_000
            throw TimeoutException()
        }
        NcmWriteReplay.buffer.put(Ntb16Codec.build(byteArrayOf(1), 1))
        return NcmWriteReplay.request
    }
    @Implementation fun close() = Unit
}

@Implements(UsbRequest::class)
class NcmWriteRequestShadow {
    @RealObject lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint) = true
    @Implementation fun queue(buffer: ByteBuffer): Boolean {
        NcmWriteReplay.request = request
        NcmWriteReplay.buffer = buffer
        return true
    }
    @Implementation fun cancel(): Boolean { NcmWriteReplay.readCancelled.countDown(); return true }
    @Implementation fun close() = Unit
}
