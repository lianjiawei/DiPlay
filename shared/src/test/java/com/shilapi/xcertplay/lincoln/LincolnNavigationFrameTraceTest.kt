package com.shilapi.xcertplay.lincoln

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class LincolnNavigationFrameTraceTest {
    @Test fun separateAndCombinedFramesRemainDistinguishableWithoutPrivateStrings() {
        var now = 1_000_000_000L
        val trace = LincolnNavigationFrameTrace({ now })
        trace.connection(1, true, 0, 0)
        trace.record(0x5202, Iap2Messages.buildRaw(0x5202) {
            u16(1, 8); string(2, "private direction"); u8(3, 2); string(4, "private street"); u32(5, 200)
        }.payload, 1, true, 0, 0)
        now += 1_000_000
        trace.record(0x5201, Iap2Messages.buildRaw(0x5201) { u32(0x0a, 150) }.payload, 1, true, 0, 1)
        now += 1_000_000
        trace.record(0x5201, Iap2Messages.buildRaw(0x5201) { u16List(0x0d, listOf(8, 9)) }.payload, 1, true, 0, 1)
        now += 1_000_000
        trace.record(0x5201, Iap2Messages.buildRaw(0x5201) {
            u8(1, 1); u8(2, 2); u16List(0x0d, listOf(8, 9)); u32(0x0a, 140); u32(7, 900)
        }.payload, 1, true, 0, 2)
        val report = trace.report()
        val lines = report.lines().filter { it.startsWith("frame ") }
        assertEquals(5, lines.size)
        assertTrue(lines[2], lines[2].contains("distance=150") && lines[2].contains("indices=-"))
        assertTrue(lines[3], lines[3].contains("distance=-") && lines[3].contains("indices=8,9"))
        assertTrue(lines[4], lines[4].contains("distance=140") && lines[4].contains("indices=8,9"))
        assertTrue(lines[1].contains("between=200"))
        assertFalse(report.contains("private direction"))
        assertFalse(report.contains("private street"))
        assertFalse(report.contains("latitude"))
        assertTrue(lines[0].contains("id=connection"))
    }

    @Test fun missingEmptyZeroMalformedAndOversizedListsHaveDistinctEvidence() {
        val trace = LincolnNavigationFrameTrace({ 1_000_000_000L })
        fun add(payload: ByteArray) = trace.record(0x5201, payload, 1, true, 0, 0)
        add(Iap2Messages.buildRaw(0x5201) { u8(1, 1) }.payload)
        add(Iap2Messages.buildRaw(0x5201) { u16List(0x0d, emptyList()); u32(0x0a, 0) }.payload)
        add(Iap2Messages.buildRaw(0x5201) { u16List(0x0d, (0..11).toList()) }.payload)
        add(Iap2Messages.buildRaw(0x5201) { raw(0x0d, byteArrayOf(0, 1, 2)) }.payload)
        add(byteArrayOf(0, 3, 0, 10))
        val lines = trace.report().lines().filter { it.startsWith("frame ") }
        assertTrue(lines[0].contains("distance=-") && lines[0].contains("indices=-"))
        assertTrue(lines[1].contains("distance=0") && lines[1].contains("listCount=0 indices=empty"))
        assertTrue(lines[2].contains("listCount=12 indices=0,1,2,3,4,5,6,7"))
        assertTrue(lines[3].contains("invalid=2000"))
        assertTrue(lines[4].contains("valid=false"))
    }

    @Test fun duplicateAndFieldLimitAreMarkedAsIncomplete() {
        val trace = LincolnNavigationFrameTrace({ 1_000_000_000L })
        val duplicate = Iap2Messages.buildRaw(0x5201) { u32(10, 4); u32(10, 5) }
        trace.record(0x5201, duplicate.payload, 1, true, 0, 0)
        val many = Iap2Messages.buildRaw(0x5201) { repeat(130) { void(0x1234) } }
        trace.record(0x5201, many.payload, 1, true, 0, 0)
        val lines = trace.report().lines().filter { it.startsWith("frame ") }
        assertTrue(lines[0].contains("duplicate=400") && lines[0].contains("distance=5"))
        assertTrue(lines[1].contains("limited=true"))
    }

    @Test fun samplingBoundsMemoryReportsGapsAndProtectsRouteEventsDuringManeuverBurst() {
        val trace = LincolnNavigationFrameTrace({ 1_000_000_000L }, capacity = 256)
        val distance = Iap2Messages.buildRaw(0x5201) { u32(10, 10) }.payload
        val maneuver = Iap2Messages.buildRaw(0x5202) { u16(1, 1); u8(3, 2) }.payload
        val route = Iap2Messages.buildRaw(0x5201) { u16List(13, listOf(1)) }.payload
        repeat(10) { trace.record(0x5201, distance, 1, true, 0, 0) }
        repeat(70) { trace.record(0x5202, maneuver, 1, true, 0, 0) }
        trace.record(0x5201, route, 1, true, 0, 0)
        val report = trace.report()
        assertTrue(report.contains("rateDropped=8"))
        assertTrue(report.lines().last().contains("indices=1"))
        val tiny = LincolnNavigationFrameTrace({ 1_000_000_000L }, capacity = 2)
        repeat(3) { tiny.connection(1, true, 0, 0) }
        assertTrue(tiny.report().contains("evicted=1"))
        assertEquals(2, tiny.report().lines().count { it.startsWith("frame ") })
    }

    @Test fun concurrentRecordingAndReportFinishWithoutLosingBounds() {
        val trace = LincolnNavigationFrameTrace(capacity = 32)
        val start = CountDownLatch(1)
        val done = CountDownLatch(3)
        repeat(2) { worker -> Thread {
            start.await()
            repeat(300) { trace.connection(worker.toLong(), it % 2 == 0, 0, 0) }
            done.countDown()
        }.start() }
        Thread { start.await(); repeat(100) { trace.report() }; done.countDown() }.start()
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(32, trace.report().lines().count { it.startsWith("frame ") })
        assertTrue(trace.report().contains("evicted=568"))
    }

    @Test fun arbitraryMalformedInputIsBoundedAndDoesNotEscape() {
        var now = 0L
        val trace = LincolnNavigationFrameTrace({ now }, capacity = 16)
        val random = Random(37)
        repeat(5_000) {
            now += 1_000_000_000L
            trace.record(if (it % 2 == 0) 0x5201 else 0x5202,
                random.nextBytes(random.nextInt(0, 200)), 1, true, 0, 0)
        }
        assertEquals(16, trace.report().lines().count { it.startsWith("frame ") })
        assertTrue(trace.report().contains("evicted=4984"))
    }
}
