package com.shilapi.xcertplay.update

import android.Manifest
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateStorageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val public get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Lincoln")
    private val active = mutableListOf<File>()

    @Before fun permissions() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    @After fun cleanup() { active.forEach { UpdateStorage.discard(app, it) } }

    @Test fun exportedVersionsAreBoundedAcrossPrivateAndPublicCopies() {
        val unrelated = File(public.apply { mkdirs() }, "Other-app.apk").apply { writeText("not our APK") }
        for (version in 1..4) {
            val apk = File(app.cacheDir, "CorsairPlay-precision.$version.apk").apply { writeText("version $version") }
            UpdateApkExport.copy(app, apk)
            UpdateApkExport.copyToSystemDownloads(app, apk)
        }
        val usage = UpdateStorage.usage(app)
        assertEquals(2, usage.apkCount)
        assertEquals(0, usage.problems)
        assertTrue(File(public, "CorsairPlay-precision.4.apk").exists())
        assertTrue(File(public, "CorsairPlay-precision.3.apk").exists())
        assertFalse(File(public, "CorsairPlay-precision.2.apk").exists())
        assertEquals("not our APK", unrelated.readText())
    }

    @Test fun repeatedSameVersionDoesNotAccumulateOrLoseTheNewCopy() {
        val apk = File(app.cacheDir, "same.apk").apply { writeText("first") }
        val first = UpdateApkExport.copy(app, apk)!!
        apk.writeText("second")
        val second = UpdateApkExport.copy(app, apk)!!
        assertFalse(File(first.path).exists())
        assertEquals("second", File(second.path).readText())
        assertEquals(1, UpdateStorage.usage(app).apkCount)
    }

    @Test fun automaticCleanupExpiresOnlyIdleStaleAttempts() {
        val now = System.currentTimeMillis()
        val stale = orphan(now - UpdateStorage.STALE_MILLIS - 10)
        val fresh = orphan(now)
        val downloading = UpdateStorage.createAttempt(app, history = true).also(active::add)
        File(downloading, "download.apk").writeText("in flight")
        downloading.setLastModified(now - UpdateStorage.STALE_MILLIS - 10)
        val result = UpdateStorage.clean(app, manual = false, now = now)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(downloading.exists())
        assertEquals(1, result.usage.protectedCount)
    }

    @Test fun manualCleanupReclaimsIdleFilesButKeepsReadyDownloadsAndTheirCopies() {
        val ready = UpdateStorage.createAttempt(app, history = false).also(active::add)
        val apk = File(ready, "ready.apk").apply { writeText("ready") }
        val saved = UpdateApkExport.copy(app, apk)!!
        val unrelated = File(app.filesDir, "settings.json").apply { writeText("keep settings") }
        val idle = orphan(System.currentTimeMillis())
        val result = UpdateStorage.clean(app, manual = true)
        assertFalse(idle.exists())
        assertTrue(apk.exists())
        assertTrue(File(saved.path).exists())
        assertEquals("keep settings", unrelated.readText())
        assertEquals(2, result.usage.protectedCount)
        assertEquals(0, result.usage.removableCount)
        assertTrue(result.bytes > 0)
    }

    @Test fun installationProtectionSurvivesActivityReleaseAndExpires() {
        val now = System.currentTimeMillis()
        val attempt = UpdateStorage.createAttempt(app, history = false)
        val apk = File(attempt, "install.apk").apply { writeText("installing") }
        attempt.setLastModified(now - UpdateStorage.STALE_MILLIS - 100)
        UpdateStorage.protectInstaller(app, apk, now)
        UpdateStorage.discard(app, attempt) // No in-memory activity lease remains.
        assertTrue(apk.exists())
        assertEquals(1, UpdateStorage.clean(app, manual = true, now = now).usage.protectedCount)
        UpdateStorage.clean(app, manual = false, now = now + UpdateStorage.STALE_MILLIS + 1)
        assertFalse(attempt.exists())
    }

    @Test fun legacyPublicMigrationRequiresTheInstalledPackageAndSignature() {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.signatures = arrayOf(Signature("aabb"))
        shadowOf(app.packageManager).installPackage(info)
        val owned = legacy("our-old.apk", app.packageName, "aabb")
        val foreign = legacy("foreign.apk", app.packageName, "ccdd")
        val other = legacy("another.apk", "other.package", "aabb")
        UpdateStorage.clean(app, manual = true)
        assertFalse(owned.exists())
        assertTrue(foreign.exists())
        assertTrue(other.exists())
    }

    @Test fun cleanupDoesNotFollowSymlinksOrRecordedPathsOutsideItsRoots() {
        val keep = File(app.filesDir, "keep").apply { mkdirs() }
        val settings = File(keep, "settings.txt").apply { writeText("private data") }
        val attempt = orphan(System.currentTimeMillis())
        Files.createSymbolicLink(File(attempt, "link").toPath(), keep.toPath())
        UpdateStorage.remember(app, settings.canonicalPath, "pretend.apk", settings.length())
        UpdateStorage.clean(app, manual = true)
        assertFalse(attempt.exists())
        assertEquals("private data", settings.readText())
    }

    @Test fun legacyPermissionDenialIsReportedWithoutTouchingPublicFiles() {
        val saved = File(app.cacheDir, "history.apk").apply { writeText("download") }
        val exported = File(UpdateApkExport.copyToSystemDownloads(app, saved).path)
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val result = UpdateStorage.clean(app, manual = true)
        assertTrue(result.usage.problems > 0)
        assertTrue(exported.exists())
    }

    @Test fun replacedPublicFilesMustStillMatchTheSavedCopy() {
        val apk = File(app.cacheDir, "original.apk").apply { writeText("ours") }
        val saved = File(UpdateApkExport.copyToSystemDownloads(app, apk).path)
        val modified = saved.lastModified()
        saved.writeText("evil")
        saved.setLastModified(modified)
        UpdateStorage.clean(app, manual = true)
        assertEquals("evil", saved.readText())
    }

    @Test fun sameFilenameCanKeepTwoDifferentApkVersions() {
        val apk = File(app.cacheDir, "generic.apk").apply { writeText("APK") }
        val saved = (1..3).map { version ->
            shadowOf(app.packageManager).setPackageArchiveInfo(apk.absolutePath, PackageInfo().apply {
                packageName = app.packageName; versionCode = version; versionName = "v$version"
            })
            File(UpdateApkExport.copyToSystemDownloads(app, apk).path)
        }
        assertFalse(saved[0].exists())
        assertTrue(saved[1].exists())
        assertTrue(saved[2].exists())
        assertEquals(2, UpdateStorage.usage(app).apkCount)
    }

    @Test fun incompletePublicCopiesNeedACreationRecordBeforeCleanup() {
        val ours = File(public.apply { mkdirs() }, ".${UUID.randomUUID()}.part").apply { writeText("partial") }
        val other = File(public, ".unrelated.part").apply { writeText("keep") }
        UpdateStorage.rememberTemporary(app, ours)
        UpdateStorage.clean(app, manual = true)
        assertFalse(ours.exists())
        assertEquals("keep", other.readText())
    }

    private fun orphan(modified: Long): File = File(app.cacheDir, "update/${UUID.randomUUID()}").apply {
        mkdirs(); File(this, "orphan.apk").writeText("old cache"); setLastModified(modified)
    }
    private fun legacy(name: String, packageName: String, signature: String): File {
        val file = File(public.apply { mkdirs() }, name).apply { writeText("legacy") }
        shadowOf(app.packageManager).setPackageArchiveInfo(file.absolutePath, PackageInfo().apply {
            this.packageName = packageName; signatures = arrayOf(Signature(signature))
        })
        return file
    }
}
