package com.shilapi.xcertplay

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.transport.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Exercise the real wired control loop, including identification and its normal subscriptions. */
class WiredStartAgainIntegrationTest {
    @Test fun aLateLinkGetsOneExtraStartWithoutRepeatingIdentificationOrSubscriptions() {
        var decisions = 0
        val replay = replay {
            decisions++
            if (decisions < 3) Iap2WiredControlClient.StartAgain.WAIT else Iap2WiredControlClient.StartAgain.SEND
        }
        assertEquals(3, decisions)
        assertEquals(2, replay.result.carPlayStartSessionsSent)
        assertEquals(2, replay.sent.count { it.messageId == 0x4301 })
        assertEquals(1, replay.sent.count { it.messageId == 0x1d01 })
        for (frame in Iap2WiredControlClient.subscriptions()) {
            assertEquals(1, replay.sent.count { it.messageId == frame.messageId })
        }
        assertEquals(1, replay.progress.count { it == Iap2WiredControlClient.START_SESSION_AGAIN_PROGRESS })
        assertEquals(2, replay.waits.count { it == 500L })
    }

    @Test fun aConnectedPhoneIsNotAskedAgainEvenIfTheLoopKeepsReceivingFrames() {
        var decisions = 0
        val replay = replay { decisions++; Iap2WiredControlClient.StartAgain.DONE }
        assertEquals(1, decisions)
        assertEquals(1, replay.result.carPlayStartSessionsSent)
        assertEquals(1, replay.sent.count { it.messageId == 0x4301 })
    }

    @Test fun closingTheChannelWhileTheLinkIsNotReadyDoesNotSendAnotherRequest() {
        val replay = replay { Iap2WiredControlClient.StartAgain.WAIT }
        assertEquals(Iap2WiredControlTerminal.CHANNEL_CLOSED, replay.result.terminal)
        assertEquals(1, replay.result.carPlayStartSessionsSent)
        assertEquals(1, replay.sent.count { it.messageId == 0x4301 })
    }

    private data class Replay(
        val result: Iap2WiredControlResult,
        val sent: List<Iap2Frame>,
        val progress: List<String>,
        val waits: List<Long>,
    )

    private fun replay(decide: () -> Iap2WiredControlClient.StartAgain): Replay {
        val session = mock(Iap2Session::class.java)
        val mfi = mock(Iap2MfiAuthenticationClient::class.java)
        val frames = listOf(
            Iap2Frame(0x1d00, byteArrayOf()), Iap2Frame(0x1d02, byteArrayOf()),
            Iap2Frame(0x4300, byteArrayOf()), null, null, Iap2Frame(0x5000, byteArrayOf()),
        ).iterator()
        var closed = false
        val waits = mutableListOf<Long>()
        `when`(session.awaitReady(anyLong())).thenReturn(true)
        `when`(session.isClosed).thenAnswer { closed }
        `when`(session.recv(anyLong())).thenAnswer {
            waits += it.getArgument<Long>(0)
            if (frames.hasNext()) frames.next() else { closed = true; null }
        }
        val progress = mutableListOf<String>()
        val result = Iap2WiredControlClient(session, mfi).run(
            identification = Iap2IdentificationConfig("Test", "Test", "Test", "Test", "1", "1", 2),
            endpoint = Iap2WiredCarPlayEndpoint(listOf("fe80::2"), 7000, "abcd", "1.0", "02:00:00:00:00:02"),
            availableCurrentMilliAmps = 1500,
            onProgress = progress::add,
            startSessionAgain = decide,
        )
        val sent = mockingDetails(session).invocations.filter { it.method.name == "send" }
            .map { it.arguments[0] as Iap2Frame }
        return Replay(result, sent, progress, waits)
    }
}
