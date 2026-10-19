package com.shilapi.xcertplay.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test fun shortPersonalNamesCompareWithLegacyTagsWithoutOfferingTheInstalledBuildAgain() {
        assertTrue(UpdateVersion.isNewer("v0.2.26-precision.6", "0.2.26-precision.5"))
        assertTrue(UpdateVersion.isNewer("precision.6", "0.2.26-precision.5"))
        assertFalse(UpdateVersion.isNewer("v0.2.26-precision.6", "precision.6"))
        assertFalse(UpdateVersion.isNewer("v0.2.26-precision.5", "precision.6"))
    }

    @Test fun shortPersonalNamesOrderRevisionsNumerically() {
        assertTrue(UpdateVersion.isNewer("vprecision.10", "precision.6"))
        assertTrue(UpdateVersion.isNewer("precision.7", "precision.6-hud-test"))
        assertFalse(UpdateVersion.isNewer("precision.6", "precision.6"))
        assertFalse(UpdateVersion.isNewer("precision.5", "precision.6"))
        assertFalse(UpdateVersion.isNewer("v0.2.27", "precision.6"))
    }

    @Test fun invalidShortPersonalRevisionDoesNotOfferAnUpdate() {
        assertFalse(UpdateVersion.isNewer("precision.999999999999999999999", "precision.6"))
        assertFalse(UpdateVersion.isNewer("precision.invalid", "precision.6"))
        assertFalse(UpdateVersion.isNewer("precision.7invalid", "precision.6"))
    }

    @Test fun personalRevisionUpdatesWithinTheSameBaseVersion() {
        assertTrue(UpdateVersion.isNewer("v0.2.24-precision.2", "0.2.24-precision.1"))
        assertTrue(UpdateVersion.isNewer("v0.2.24-precision.10", "0.2.24-precision.2"))
        assertTrue(UpdateVersion.isNewer("v0.2.24-precision.1", "0.2.24"))
        assertFalse(UpdateVersion.isNewer("v0.2.24-precision.2", "0.2.24-precision.2"))
        assertFalse(UpdateVersion.isNewer("v0.2.24", "0.2.24-precision.2"))
        assertFalse(UpdateVersion.isNewer("v0.2.23-precision.99", "0.2.24-precision.2"))
    }

    @Test fun oversizedNumericVersionIsIgnoredWithoutCrashingTheCheck() {
        assertFalse(UpdateVersion.isNewer("v999999999999999999.2.0", "0.2.24"))
    }
    @Test
    fun newerTagIsAnUpdate() {
        assertTrue(UpdateVersion.isNewer("v0.2.15", "0.2.14"))
    }

    @Test
    fun sameVersionIsNotAnUpdate() {
        assertFalse(UpdateVersion.isNewer("0.2.14", "0.2.14"))
    }

    @Test
    fun olderTagIsIgnored() {
        assertFalse(UpdateVersion.isNewer("v0.2.13", "0.2.14"))
    }

    @Test
    fun numericFieldsCompareLeftToRight() {
        assertTrue(UpdateVersion.isNewer("0.3.0", "0.2.14"))
        assertFalse(UpdateVersion.isNewer("0.2.9", "0.2.14"))
    }

    @Test
    fun preReleaseSuffixStillComparesByNumbers() {
        assertTrue(UpdateVersion.isNewer("0.2.15-beta.1", "0.2.14"))
        assertFalse(UpdateVersion.isNewer("0.2.14-rc.1", "0.2.14"))
        assertTrue(UpdateVersion.isNewer("0.2.25-rc1", "0.2.24"))
        assertFalse(UpdateVersion.isNewer("0.2.24-rc1", "0.2.24"))
    }

    @Test
    fun unparseableRemoteVersionIsNeverAnUpdate() {
        assertFalse(UpdateVersion.isNewer("public-preview", "0.2.14"))
    }

    @Test
    fun unparseableInstalledVersionAcceptsAnyParseableRemote() {
        assertTrue(UpdateVersion.isNewer("0.2.15", "psa.2"))
        assertFalse(UpdateVersion.isNewer("public-preview", "psa.2"))
    }
}
