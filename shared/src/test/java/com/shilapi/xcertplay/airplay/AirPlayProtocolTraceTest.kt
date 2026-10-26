package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 33])
class AirPlayProtocolTraceTest {
    @Test fun disabledTracesNeverConstructTheirPayloadOrInvokeTheRecorder() {
        var built = 0
        val session = session(object : AirPlaySessionListener {
            override val protocolTraceEnabled = false
            override fun onDebugLog(message: String) { fail("disabled trace was delivered") }
        })
        try {
            repeat(1_000) { session.logTrace { built++; error("payload must not be formatted") } }
            assertEquals(0, built)
        } finally { session.close() }
    }

    @Test fun existingListenersKeepTheirTraceMessages() {
        val messages = mutableListOf<String>()
        val session = session(object : AirPlaySessionListener {
            override fun onDebugLog(message: String) { messages += message }
        })
        try {
            session.logTrace { "constructed" }
            session.logTrace("already constructed")
            assertEquals(listOf("TRACE constructed", "TRACE already constructed"), messages)
        } finally { session.close() }
    }

    @Test fun changingRecorderPolicyTakesEffectWithoutRestartingTheSession() {
        var enabled = false
        var built = 0
        val traces = mutableListOf<String>()
        val session = session(object : AirPlaySessionListener {
            override val protocolTraceEnabled get() = enabled
            override fun onDebugLog(message: String) { traces += message }
        })
        try {
            session.logTrace { built++; "first" }
            enabled = true
            session.logTrace { built++; "second" }
            enabled = false
            session.logTrace { built++; "third" }
            assertEquals(1, built)
            assertEquals(listOf("TRACE second"), traces)
        } finally { session.close() }
    }

    @Test fun tracingDoesNotChangeTheEncryptedTouchCommandsOrTheirOrder() {
        val enabled = touchWire(true)
        val disabled = touchWire(false)
        assertTrue(enabled.first.isNotEmpty())
        assertArrayEquals(enabled.first, disabled.first)
        assertEquals(3, enabled.second.size)
        assertTrue(enabled.second.all { it.startsWith("TRACE airplay event tx headers=") })
        assertTrue(disabled.second.isEmpty())
    }

    @Test fun aBrokenRecorderPolicyCannotStopACommandBeingSent() {
        val output = ByteArrayOutputStream()
        val session = session(object : AirPlaySessionListener {
            override val protocolTraceEnabled: Boolean get() = error("recorder unavailable")
        })
        try {
            eventChannel(session, output)
            assertTrue(session.sendTouch(emptyList()))
            assertTrue(output.size() > 0)
        } finally { session.close() }
    }

    @Test fun gestureDiagnosticsRecordStartsWithoutMovementCoordinatesOrUiGuesses() {
        val messages = mutableListOf<String>()
        val output = ByteArrayOutputStream()
        val session = session(object : AirPlaySessionListener {
            override val protocolTraceEnabled = false
            override fun onDebugLog(message: String) { messages += message }
        })
        try {
            eventChannel(session, output)
            session.sendTouch(listOf(AirPlayContact(0, .2, .3, true)))
            repeat(20) { session.sendTouch(listOf(AirPlayContact(0, .5, .6, true))) }
            session.sendTouch(emptyList())
            session.sendTouch(listOf(AirPlayContact(0, .1, .1, true)))
            assertEquals(listOf("airplay input touch-start written=true", "airplay input touch-start written=true"),
                messages.filter { it.startsWith("airplay input ") })
            assertFalse(messages.any { it.contains("TRACE ") })
        } finally { session.close() }
    }

    @Test fun mediaDiagnosticsDoNotChangePressReleasePayloads() {
        val messages = mutableListOf<String>()
        val output = ByteArrayOutputStream()
        val session = session(object : AirPlaySessionListener {
            override val protocolTraceEnabled = false
            override fun onDebugLog(message: String) { messages += message }
        })
        try {
            eventChannel(session, output)
            session.sendMedia(CarPlayMediaButton.PLAY)
            val plain = ControlCipher(ByteArray(32), ByteArray(32)).decrypt(output.toByteArray()).data
            val requests = RtspMessage.parseMessages(plain).messages
            assertEquals(2, requests.size)
            val reports = requests.map { BplistCodec.decode(it.body) as Map<*, *> }
            assertTrue(reports.all { it["type"] == "hidSendReport" &&
                it["uuid"] == AirPlayHid.MEDIA_HID_UID.toString(16) })
            assertArrayEquals(byteArrayOf(1), reports[0]["hidReport"] as ByteArray)
            assertArrayEquals(byteArrayOf(0), reports[1]["hidReport"] as ByteArray)
            assertTrue(messages.any { it.contains("cseq=1 index=1 awaitingResponse=true") })
            assertTrue(messages.any { it.contains("cseq=2 index=0 awaitingResponse=true") })
        } finally { session.close() }
    }

    private fun touchWire(trace: Boolean): Pair<ByteArray, List<String>> {
        val output = ByteArrayOutputStream()
        val traces = mutableListOf<String>()
        val session = session(object : AirPlaySessionListener {
            override val protocolTraceEnabled = trace
            override fun onDebugLog(message: String) {
                if (message.startsWith("TRACE ")) traces += message
            }
        })
        try {
            eventChannel(session, output)
            assertTrue(session.sendTouch(listOf(AirPlayContact(0, .2, .3, true))))
            assertTrue(session.sendTouch(listOf(AirPlayContact(0, .5, .6, true))))
            assertTrue(session.sendTouch(emptyList()))
            return output.toByteArray() to traces
        } finally { session.close() }
    }

    private fun eventChannel(session: AirPlaySession, output: ByteArrayOutputStream) {
        set(session, "eventSocket", object : Socket() { override fun getOutputStream() = output })
        set(session, "eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))
    }

    private fun set(session: AirPlaySession, name: String, value: Any) {
        AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    }

    private fun session(listener: AirPlaySessionListener) = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = listener, media = object : AirPlayMediaHandler {},
    )
}
