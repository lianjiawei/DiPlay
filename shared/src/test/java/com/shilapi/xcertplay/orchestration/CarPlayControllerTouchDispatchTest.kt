package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayControllerTouchDispatchTest {
    private class Wire(block: Boolean) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        val started = CountDownLatch(1)
        val release = CountDownLatch(if (block) 1 else 0)
        override fun write(b: Int) = error("unexpected single-byte write")
        override fun write(b: ByteArray, off: Int, len: Int) {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release blocked wire" }
            synchronized(bytes) { bytes.write(b, off, len) }
        }
        fun reports(): List<String> {
            val encrypted = synchronized(bytes) { bytes.toByteArray() }
            if (encrypted.isEmpty()) return emptyList()
            val plain = ControlCipher(ByteArray(32) { 2 }, ByteArray(32) { 1 }).decrypt(encrypted).data
            return RtspMessage.parseMessages(plain).messages.map { message ->
                val report = (BplistCodec.decode(message.body) as Map<*, *>)["hidReport"] as ByteArray
                val x = (report[2].toInt() and 0xff) or ((report[3].toInt() and 0xff) shl 8)
                "${report[1]}@$x"
            }
        }
    }

    private class Rig {
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
        val controller = CarPlayController(
            RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
                identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                    serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
            ), config, AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {},
        )
        private val sessions = mutableListOf<AirPlaySession>()
        fun session(wire: Wire): AirPlaySession {
            val session = AirPlaySession(Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
            set(session, "eventSocket", object : Socket() { override fun getOutputStream() = wire })
            set(session, "eventCipher", ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
            sessions += session
            return session
        }
        fun active(session: AirPlaySession?) = set(controller, "activeSession", session)
        fun drain() {
            val executor = field(controller, "touchExecutor") as ExecutorService
            executor.submit {}.get(5, TimeUnit.SECONDS)
        }
        fun close() {
            sessions.forEach { it.close() }
            controller.close()
            controller.awaitClosed(2_000)
        }
        private fun set(target: Any, name: String, value: Any?) =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        private fun field(target: Any, name: String): Any? =
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }

    @Test fun blockedWireKeepsPressLatestMoveAndRelease() {
        val rig = Rig()
        val wire = Wire(block = true)
        try {
            rig.active(rig.session(wire))
            assertTrue(rig.controller.sendTouch(listOf(AirPlayContact(1, 0.0, 0.5, true))))
            assertTrue(wire.started.await(5, TimeUnit.SECONDS))
            repeat(500) { assertTrue(rig.controller.sendTouch(listOf(AirPlayContact(1, (it + 1) / 500.0, 0.5, true)), true)) }
            assertTrue(rig.controller.sendTouch(listOf(AirPlayContact(1, 1.0, 0.5, false))))
            wire.release.countDown()
            rig.drain()
            assertEquals(listOf("1@0", "1@800", "0@800"), wire.reports())
        } finally {
            wire.release.countDown()
            rig.close()
        }
    }

    @Test fun queuedOldSessionTouchIsNotSentAfterSessionSwitch() {
        val rig = Rig()
        val oldWire = Wire(block = true)
        val newWire = Wire(block = false)
        try {
            rig.active(rig.session(oldWire))
            assertTrue(rig.controller.sendTouch(listOf(AirPlayContact(1, 0.0, 0.5, true))))
            assertTrue(oldWire.started.await(5, TimeUnit.SECONDS))
            assertTrue(rig.controller.sendTouch(listOf(AirPlayContact(1, 0.5, 0.5, true)), true))
            rig.active(rig.session(newWire))
            assertTrue(rig.controller.sendTouch(listOf(AirPlayContact(1, 0.8, 0.5, true))))
            oldWire.release.countDown()
            rig.drain()
            assertEquals(listOf("1@0"), oldWire.reports())
            assertEquals(listOf("1@640"), newWire.reports())
        } finally {
            oldWire.release.countDown()
            rig.close()
        }
    }
}
