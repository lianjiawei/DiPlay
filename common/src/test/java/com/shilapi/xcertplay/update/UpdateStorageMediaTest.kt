package com.shilapi.xcertplay.update

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.pm.PackageInfo
import org.robolectric.Shadows.shadowOf
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33])
class UpdateStorageMediaTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: Downloads
    private val active = mutableListOf<File>()
    @Before fun setup() {
        provider = Downloads(app.cacheDir, app.packageName)
        provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
    }
    @After fun cleanup() { active.forEach { UpdateStorage.discard(app, it) } }

    @Test fun retentionAndManualCleaningNeverDeleteOtherOwnersOrFolders() {
        val other = provider.row("foreign.apk", "other.app")
        val anotherFolder = provider.row("outside.apk", app.packageName, "Download/Other/")
        for (version in 1..4) {
            val saved = UpdateApkExport.copyToSystemDownloads(app, apk("version-$version.apk"))
            assertNotNull(saved.uri)
        }
        assertEquals(2, UpdateStorage.usage(app).apkCount)
        val result = UpdateStorage.clean(app, manual = true)
        assertEquals(0, result.usage.apkCount)
        assertEquals(0, result.usage.problems)
        assertEquals(setOf(other, anotherFolder), provider.rows.keys)
    }

    @Test fun missingOwnerColumnOnlyAllowsPreviouslyRecordedUris() {
        val unknown = provider.row("not-recorded.apk", app.packageName)
        provider.ownerSupported = false
        val saved = UpdateApkExport.copyToSystemDownloads(app, apk("our.apk"))
        val owned = requireNotNull(saved.uri)
        assertEquals(1, UpdateStorage.usage(app).apkCount)
        UpdateStorage.clean(app, manual = true)
        assertFalse(provider.rows.containsKey(ContentUris.parseId(owned)))
        assertTrue(provider.rows.containsKey(unknown))
    }

    @Test fun failedPublicationKeepsTheExistingGoodCopy() {
        val saved = UpdateApkExport.copyToSystemDownloads(app, apk("same.apk"))
        val original = requireNotNull(saved.uri)
        provider.failPublish = true
        try {
            UpdateApkExport.copyToSystemDownloads(app, apk("same.apk"))
            fail("Failed publication must be reported")
        } catch (_: java.io.IOException) { }
        assertEquals(setOf(ContentUris.parseId(original)), provider.rows.keys)
    }

    @Test fun manualCleanupPreservesBothTheReadyCacheAndPublicInstallerUri() {
        val directory = UpdateStorage.createAttempt(app, history = false).also(active::add)
        val apk = File(directory, "ready.apk").apply { writeText("verified") }
        val saved = UpdateApkExport.copyToSystemDownloads(app, apk)
        val result = UpdateStorage.clean(app, manual = true)
        assertTrue(apk.exists())
        assertTrue(provider.rows.containsKey(ContentUris.parseId(saved.uri!!)))
        assertEquals(2, result.usage.protectedCount)
    }

    @Test fun anUnavailableProviderDoesNotReportSuccessfulCompleteCleanup() {
        provider.failQuery = true
        assertTrue(UpdateStorage.clean(app, manual = true).usage.problems > 0)
    }

    @Test fun stalePendingCopiesAreTreatedAsTemporaryFiles() {
        val id = provider.row("partial.apk", app.packageName)
        provider.rows[id]!!.put("is_pending", 1)
        provider.rows[id]!!.put("date_modified", 1L)
        UpdateStorage.clean(app, manual = false)
        assertFalse(provider.rows.containsKey(id))
    }

    @Test fun sameMediaFilenameDoesNotReplaceADifferentVersion() {
        val apk = apk("generic.apk")
        val uris = (1..3).map { version ->
            shadowOf(app.packageManager).setPackageArchiveInfo(apk.absolutePath, PackageInfo().apply {
                packageName = app.packageName; versionCode = version; versionName = "v$version"
            })
            requireNotNull(UpdateApkExport.copyToSystemDownloads(app, apk).uri)
        }
        assertFalse(provider.rows.containsKey(ContentUris.parseId(uris[0])))
        assertTrue(provider.rows.containsKey(ContentUris.parseId(uris[1])))
        assertTrue(provider.rows.containsKey(ContentUris.parseId(uris[2])))
        assertEquals(2, UpdateStorage.usage(app).apkCount)
    }

    private fun apk(name: String) = File(app.cacheDir, name).apply { writeText("APK test data") }

    private class Downloads(private val directory: File, private val owner: String) : ContentProvider() {
        val rows = linkedMapOf<Long, ContentValues>()
        private var nextId = 1L
        var ownerSupported = true
        var failPublish = false
        var failQuery = false
        private fun data(id: Long) = File(directory, "provider-$id.apk")
        override fun onCreate() = true
        fun row(name: String, packageName: String, path: String = UpdateStorage.DOWNLOADS_PATH): Long {
            val id = nextId++
            rows[id] = ContentValues().apply {
                put("_id", id); put("_display_name", name); put("owner_package_name", packageName)
                put("relative_path", path); put("mime_type", UpdateStorage.APK_MIME)
                put("is_pending", 0)
                put("_size", 10L); put("date_modified", System.currentTimeMillis() / 1000)
            }
            return id
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            if (failQuery) throw SecurityException("provider denied")
            val fields = requireNotNull(projection)
            if (!ownerSupported && "owner_package_name" in fields) throw IllegalArgumentException("owner column missing")
            return MatrixCursor(fields).apply {
                // Deliberately ignore selection: the caller must verify ownership, MIME and directory.
                for (values in rows.values) addRow(fields.map { values[it] }.toTypedArray())
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = row(requireNotNull(values).getAsString("_display_name"), owner)
            rows[id]!!.put("is_pending", 1)
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            if (failPublish) return 0
            val id = ContentUris.parseId(uri)
            rows[id]?.put("_size", data(id).length())
            rows[id]?.put("is_pending", 0)
            return if (id in rows) 1 else 0
        }
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = ContentUris.parseId(uri) // A broad collection delete would fail this test.
            data(id).delete()
            return if (rows.remove(id) != null) 1 else 0
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
            data(ContentUris.parseId(uri)), if (mode.contains('w')) ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = UpdateStorage.APK_MIME
    }
}
