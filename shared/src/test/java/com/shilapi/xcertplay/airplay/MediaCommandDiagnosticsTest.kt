package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class MediaCommandDiagnosticsTest {
    private var now = 100L
    private val lines = mutableListOf<String>()
    private val diagnostics = MediaCommandDiagnostics({ now }, lines::add)

    @Test fun outOfOrderRepliesMatchPressAndReleaseWithoutClaimingPlayback() {
        diagnostics.sent(42, CarPlayMediaButton.PLAY)
        diagnostics.sent(43, 0)
        now = 125L
        diagnostics.response(response(99, 200)) // A touch reply is not a media acknowledgment.
        diagnostics.response(response(43, 200))
        diagnostics.response(response(42, 200))
        diagnostics.response(response(42, 200)) // Duplicates must not create a second result.
        val replies = lines.filter { it.contains("rtspStatus=") }
        assertEquals(2, replies.size)
        assertTrue(replies[0].contains("cseq=43 index=0"))
        assertTrue(replies[1].contains("cseq=42 index=1"))
        assertTrue(replies.all { it.contains("elapsedMs=25 playbackConfirmed=false") })
    }

    @Test fun transportAndNumericCommandFailuresAreKeptWithoutResponseContents() {
        diagnostics.sent(1, CarPlayMediaButton.PLAY)
        diagnostics.response(response(1, 400, BplistCodec.encode(mapOf(
            "status" to -123, "errorCode" to 456, "privateText" to "do-not-export",
        ))))
        assertTrue(lines.last().contains("rtspStatus=400 commandStatus=-123 errorCode=456"))
        assertFalse(lines.any { it.contains("do-not-export") })
        diagnostics.sent(2, 0)
        diagnostics.response(response(2, 200, BplistCodec.encode(mapOf("params" to mapOf("status" to -4)))))
        assertTrue(lines.last().contains("rtspStatus=200 commandStatus=-4"))
    }

    @Test fun invalidOrLargeBodiesDoNotBreakTheReaderOrDumpPayloads() {
        for (data in listOf(byteArrayOf(1, 2, 3), ByteArray(4097) { 65 })) {
            diagnostics.sent(1, CarPlayMediaButton.PLAY)
            diagnostics.response(response(1, 200, data))
            assertTrue(lines.last().contains("rtspStatus=200 commandStatus=absent"))
        }
    }

    @Test fun silentPhoneCannotAccumulateUnboundedPendingCommands() {
        repeat(20) { diagnostics.sent(it, CarPlayMediaButton.PLAY) }
        assertEquals(4, lines.count { it.contains("result=unanswered-evicted") })
        diagnostics.response(response(0, 200))
        assertFalse(lines.any { it.contains("cseq=0 index=1 rtspStatus=") })
        diagnostics.clear()
        assertEquals(16, lines.count { it.contains("result=closed-without-response") })
        val count = lines.size
        diagnostics.clear()
        diagnostics.response(response(19, 200))
        assertEquals(count, lines.size)
    }

    @Test fun failedWriteIsNotReportedLaterAsAnAcknowledgment() {
        diagnostics.sent(5, CarPlayMediaButton.PLAY)
        diagnostics.writeFailed(5)
        diagnostics.response(response(5, 200))
        assertTrue(lines.last().contains("result=write-failed"))
        assertFalse(lines.any { it.contains("rtspStatus=") })
    }

    @Test fun modeDiagnosticsKeepOnlyBoundedNumericOwnershipFields() {
        val log = audioModeDiagnostic(mapOf(
            "appStates" to listOf(mapOf("appStateID" to 1, "entity" to 2, "speechMode" to 0,
                "state" to false, "title" to "private-song")),
            "resources" to List(20) { mapOf("resourceID" to 2, "entity" to 1,
                "owner" to "private-device", "url" to "private-url") },
        ))
        assertTrue(log.contains("appStateID=1, entity=2, speechMode=0, state=false"))
        assertEquals(8, Regex("resourceID=2").findAll(log).count())
        assertFalse(log.contains("private"))
        assertEquals("airplay audio modes appStates=absent resources=absent", audioModeDiagnostic(emptyMap()))
    }

    private fun response(cseq: Int, status: Int, body: ByteArray = byteArrayOf()): RtspMessage.Request {
        val head = "RTSP/1.0 $status Result\r\nCSeq: $cseq\r\nContent-Length: ${body.size}\r\n\r\n"
        return RtspMessage.parseMessages(head.toByteArray(Charsets.US_ASCII) + body).messages.single()
    }
}
