package com.shilapi.xcertplay.lincoln

import com.shilapi.xcertplay.glance.CarPlayGlance
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LincolnNavigationDiagnosticsTest {
    @Test fun zeroEventsAndSendCountersRemainSanitized() {
        val snapshot = CarPlayGlance.Snapshot(connected = true, routeActive = true,
            maneuverIndex = 27, selectedManeuverIndex = 27, maneuverType = 2,
            distanceMeters = 0, distanceRevision = 81, routeProgressGeneration = 19,
            road = "private-road", song = "private-song")
        val previous = LincolnNavigationDiagnostics.nanoTime
        try {
            var now = 1_000_000_000L
            LincolnNavigationDiagnostics.nanoTime = { now }
            LincolnNavigationDiagnostics.zeroDecision("started", snapshot)
            now += 1_000_000_000L
            LincolnNavigationDiagnostics.zeroCancelled("distance-recovered", 27, 19)
            LincolnNavigationDiagnostics.navigationSent(snapshot, "turn_right", 800, false, "test", true)
            LincolnNavigationDiagnostics.navigationRepeated()
            val report = LincolnNavigationDiagnostics.report()
            assertTrue(report.contains("zero-candidate started index=27"))
            assertTrue(report.contains("cancelled=distance-recovered index=27"))
            assertTrue(report.contains("sends changed="))
            assertTrue(report.contains("keepalive="))
            assertFalse(report.contains("private-road"))
            assertFalse(report.contains("private-song"))
        } finally {
            LincolnNavigationDiagnostics.nanoTime = previous
        }
    }

    @Test fun boundedEvidenceContainsNoRoadSongOrCoordinates() {
        val snapshot = CarPlayGlance.Snapshot(
            connected = true, routeActive = true, maneuverType = 1,
            maneuverIndex = 7,
            road = "private-street-name", song = "private-song-title",
            distanceMeters = 200,
        )
        val previous = LincolnNavigationDiagnostics.nanoTime
        var now = 1_000_000_000L
        try {
            LincolnNavigationDiagnostics.nanoTime = { now }
            repeat(160) {
                now += 1_000_000_000L
                LincolnNavigationDiagnostics.observe(snapshot.copy(maneuverIndex = it), true, true)
            }
            val report = LincolnNavigationDiagnostics.report()
            assertFalse(report.contains("private-street-name"))
            assertFalse(report.contains("private-song-title"))
            assertFalse(report.contains("latitude"))
            assertTrue(report.contains("omitted="))
            assertTrue(report.lineSequence().count() <= 387)
            assertTrue(report.contains("selected=159"))
        } finally {
            LincolnNavigationDiagnostics.nanoTime = previous
        }
    }
}
