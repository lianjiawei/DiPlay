package com.shilapi.xcertplay.update

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Copies a verified APK where a file manager can reach it, for head units that cannot use the system installer. */
internal object UpdateApkExport {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val DOWNLOADS_DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/Lincoln"

    /** [path] is shown to the driver. [uri] is set only for a MediaStore copy that file managers can open. */
    class Saved(val path: String, val uri: Uri?)

    /** Returns null when no public copy could be made. */
    fun copy(context: Context, apk: File): Saved? {
        synchronized(UpdateStorage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    return copyToDownloads(context, apk)
                } catch (_: Exception) {
                    // Fall through: some head units ship without a working Downloads provider.
                }
            }
            return try {
                val directory = File(context.getExternalFilesDir(null) ?: return null, "update")
                val destination = copyToDirectory(context, directory, apk)
                rememberFile(context, destination, apk)
                Saved(destination.absolutePath, null)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Saves a historical APK in visible Downloads, then applies the managed retention policy.
     * Android 9 and older need WRITE_EXTERNAL_STORAGE; Android 10+ uses MediaStore.
     */
    fun copyToSystemDownloads(context: Context, apk: File): Saved {
        synchronized(UpdateStorage) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return copyToDownloads(context, apk)
            }
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Lincoln",
            )
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Downloads/Lincoln is unavailable")
            }
            val destination = copyToDirectory(context, directory, apk)
            rememberFile(context, destination, apk)
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(destination.absolutePath),
                arrayOf(APK_MIME),
                null,
            )
            return Saved(destination.absolutePath, null)
        }
    }

    private fun rememberFile(context: Context, file: File, apk: File) {
        UpdateStorage.remember(context, file.canonicalPath, apk.name, file.length(), file.lastModified(),
            UpdateChecksums.sha256Hex(file), UpdateStorage.versionKey(context, apk))
        runCatching { UpdateStorage.clean(context, manual = false) }
    }

    private fun copyToDirectory(context: Context, directory: File, apk: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Update storage is unavailable")
        // Publish atomically and leave an existing copy intact until retention can safely prune it.
        val destination = if (File(directory, apk.name).exists())
            File(directory, "${apk.nameWithoutExtension}-${java.util.UUID.randomUUID()}.apk")
        else File(directory, apk.name)
        val temporary = File(directory, ".${java.util.UUID.randomUUID()}.part")
        UpdateStorage.rememberTemporary(context, temporary)
        try {
            apk.copyTo(temporary)
            if (temporary.length() != apk.length() || !temporary.renameTo(destination)) {
                throw IOException("Update storage could not publish the APK")
            }
            return destination
        } finally {
            if (!temporary.exists() || temporary.delete()) UpdateStorage.forget(context, temporary.canonicalPath)
        }
    }

    private fun copyToDownloads(context: Context, apk: File): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOADS_DIRECTORY)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the update")
        try {
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Update destination is unavailable")
            apk.inputStream().use { input -> output.use { input.copyTo(it) } }
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the update")
            UpdateStorage.remember(context, uri.toString(), apk.name, apk.length(), version = UpdateStorage.versionKey(context, apk))
            runCatching {
                UpdateStorage.clean(context, manual = false)
            }
            // MediaStore renames on a name clash, so report the name it actually stored.
            val name = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: apk.name
            return Saved("$DOWNLOADS_DIRECTORY/$name", uri)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}
