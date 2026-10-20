package com.shilapi.xcertplay.update

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Owns updater files only. Every filesystem/provider operation runs off the UI thread. */
internal object UpdateStorage {
    internal const val MAX_VERSIONS = 2
    internal const val STALE_MILLIS = 24L * 60 * 60 * 1000
    internal const val APK_MIME = "application/vnd.android.package-archive"
    internal val DOWNLOADS_PATH = "${Environment.DIRECTORY_DOWNLOADS}/Lincoln/"
    private const val PREFS = "diplay_update_storage"
    private const val SAVED = "saved:"
    private const val INSTALL = "install:"
    private const val TEMP = "temporary:"
    private val attempts = mutableSetOf<String>()
    private val sweepQueued = AtomicBoolean(false)
    private val lastSweep = AtomicLong(0)
    private const val SWEEP_INTERVAL_MILLIS = 60L * 60 * 1000
    private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "diplay-update-storage").apply { isDaemon = true } }

    data class Usage(val apkCount: Int, val apkBytes: Long, val cacheBytes: Long,
        val protectedCount: Int, val removableCount: Int, val problems: Int) {
        val bytes: Long get() = apkBytes + cacheBytes
    }
    data class Cleaned(val files: Int, val bytes: Long, val usage: Usage)
    private data class Entry(val key: String, val name: String, val bytes: Long, val modified: Long,
        val cache: Boolean, val protected: Boolean, val identity: String = name, val remove: () -> Boolean)
    private data class Scan(val entries: List<Entry>, val problems: Int)

    fun execute(task: Runnable) = io.execute(task)

    fun start(context: Context) {
        val now = System.currentTimeMillis()
        val previous = lastSweep.get()
        if (previous != 0L && now >= previous && now - previous < SWEEP_INTERVAL_MILLIS) return
        if (!sweepQueued.compareAndSet(false, true)) return
        val app = context.applicationContext
        execute(Runnable {
            try { runCatching { clean(app, manual = false) } }
            finally { lastSweep.set(System.currentTimeMillis()); sweepQueued.set(false) }
        })
    }

    @Synchronized fun createAttempt(context: Context, history: Boolean): File {
        val root = File(context.cacheDir, if (history) "history" else "update")
        val directory = File(root, UUID.randomUUID().toString())
        directory.mkdirs() // UpdateClient reports a storage failure through the normal download UI.
        attempts += directory.canonicalPath
        return directory
    }

    /** The activity no longer needs this attempt; an external installer may still hold it. */
    fun release(context: Context, directory: File) {
        val app = context.applicationContext
        execute(Runnable { discard(app, directory) })
    }

    @Synchronized fun discard(context: Context, directory: File) {
        val path = runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
        attempts -= path
        runCatching {
            if (!isAttempt(context, directory)) return@runCatching
            if (!installHeld(context, path, System.currentTimeMillis())) deleteTree(directory)
            clean(context, manual = false)
        }
    }

    /** Persist before launching another process, so a recreated activity cannot delete its APK. */
    @Synchronized fun protectInstaller(context: Context, apk: File, now: Long = System.currentTimeMillis()) {
        val directory = apk.parentFile ?: return
        if (isAttempt(context, directory)) check(preferences(context).edit()
            .putLong(INSTALL + directory.canonicalPath, now + STALE_MILLIS).commit()) {
            "Installation file could not be protected"
        }
    }

    @Synchronized fun remember(context: Context, key: String, name: String, bytes: Long,
        modified: Long = System.currentTimeMillis(), checksum: String? = null, version: String = name) {
        val savedAt = maxOf(System.currentTimeMillis(), modified, preferences(context).getLong("last_saved_at", 0) + 1)
        preferences(context).edit().putLong("last_saved_at", savedAt).putString(SAVED + key, JSONObject().apply {
            put("name", name); put("bytes", bytes); put("modified", modified); put("saved_at", savedAt); put("sha256", checksum); put("version", version)
        }.toString()).apply()
    }

    @Synchronized fun forget(context: Context, key: String) {
        preferences(context).edit().remove(SAVED + key).remove(TEMP + key).apply()
    }

    @Synchronized fun rememberTemporary(context: Context, file: File) {
        check(preferences(context).edit().putLong(TEMP + file.canonicalPath, System.currentTimeMillis()).commit()) {
            "Update temporary file could not be registered"
        }
    }

    @Synchronized fun usage(context: Context): Usage = scan(context, System.currentTimeMillis()).usage()

    /** Keep two recent APK versions (one archive each); explicit cleanup removes all idle copies. */
    @Synchronized fun clean(context: Context, manual: Boolean, now: Long = System.currentTimeMillis()): Cleaned {
        val scan = scan(context, now)
        val keep = scan.entries.filter { !it.cache }.groupBy { it.identity }
            .entries.sortedWith(compareByDescending<Map.Entry<String, List<Entry>>> { it.value.maxOf { e -> e.modified } }
                .thenBy { it.key }).take(MAX_VERSIONS).map { it.value.maxBy { e -> e.modified }.key }.toSet()
        var files = 0
        var bytes = 0L
        var problems = scan.problems
        for (entry in scan.entries) {
            if (entry.protected) continue
            val remove = if (manual) true else if (entry.cache) entry.modified <= now - STALE_MILLIS else entry.key !in keep
            if (!remove) continue
            if (runCatching(entry.remove).getOrDefault(false)) {
                files++; bytes += entry.bytes
                forget(context, entry.key)
            } else problems++
        }
        val remaining = scan(context, now)
        // Remove orphaned bookkeeping only after a complete scan, never after a provider failure.
        if (remaining.problems == 0) {
            val keys = remaining.entries.flatMap { listOf(SAVED + it.key, TEMP + it.key) }.toSet()
            val edit = preferences(context).edit()
            preferences(context).all.keys.filter { (it.startsWith(SAVED) || it.startsWith(TEMP)) && it !in keys }.forEach(edit::remove)
            edit.apply()
        }
        return Cleaned(files, bytes, remaining.usage().copy(problems = problems + remaining.problems))
    }

    internal fun versionKey(context: Context, apk: File): String = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return@runCatching apk.name
        "${info.packageName}:${PackageInfoCompat.getLongVersionCode(info)}:${info.versionName.orEmpty()}"
    }.getOrDefault(apk.name)

    private fun Scan.usage(): Usage = Usage(entries.count { !it.cache },
        entries.filter { !it.cache }.sumOf { it.bytes }, entries.filter { it.cache }.sumOf { it.bytes },
        entries.count { it.protected }, entries.count { !it.protected }, problems)

    private fun scan(context: Context, now: Long): Scan {
        val entries = mutableListOf<Entry>()
        val protectedNames = mutableSetOf<String>()
        var problems = 0
        for (kind in listOf("update", "history")) {
            val root = File(context.cacheDir, kind)
            if (!root.exists()) continue
            val children = root.listFiles()
            if (children == null) { problems++; continue }
            for (directory in children.filter { isAttempt(context, it) }) {
                val held = directory.canonicalPath in attempts || installHeld(context, directory.canonicalPath, now)
                val apks = directory.listFiles()?.filter { it.name.endsWith(".apk", true) }.orEmpty()
                if (held) protectedNames += apks.map { it.name }
                entries += Entry(directory.canonicalPath, directory.name, treeBytes(directory), directory.lastModified(),
                    true, held) { deleteTree(directory) }
            }
        }
        for (key in preferences(context).all.keys.filter { it.startsWith(INSTALL) }) {
            if (preferences(context).getLong(key, 0) <= now) preferences(context).edit().remove(key).apply()
        }
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        val roots = buildList {
            if (external != null) add(File(external, "update") to false)
            if (Build.VERSION.SDK_INT < 29) add(File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "Lincoln") to true)
        }
        for ((root, public) in roots) {
            if (public && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                problems++; continue
            }
            if (!root.exists()) continue
            val files = root.listFiles()
            if (files == null) { problems++; continue }
            for (file in files.filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".part") &&
                it.canonicalFile.parentFile == root.canonicalFile }) {
                if (public && !preferences(context).contains(TEMP + file.canonicalPath)) continue
                entries += Entry(file.canonicalPath, file.name, file.length(), file.lastModified(), true, false) { file.delete() }
            }
            for (file in files.filter { it.isFile && it.name.endsWith(".apk", true) && it.canonicalFile.parentFile == root.canonicalFile }) {
                if (public && !ownedLegacy(context, file)) continue
                val record = record(context, file.canonicalPath)
                val name = record?.optString("name")?.takeIf { it.isNotBlank() } ?: file.name
                val savedAt = record?.optLong("saved_at", file.lastModified()) ?: file.lastModified()
                entries += Entry(file.canonicalPath, name, file.length(), savedAt, false,
                    name in protectedNames, record?.optString("version")?.takeIf { it.isNotBlank() } ?: versionKey(context, file)) { file.delete() }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val media = media(context, protectedNames)
            entries += media.entries; problems += media.problems
        }
        return Scan(entries, problems)
    }

    private fun media(context: Context, protectedNames: Set<String>): Scan {
        if (Build.VERSION.SDK_INT < 29) return Scan(emptyList(), 0)
        val resolver = context.contentResolver
        val base = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_MODIFIED, MediaStore.Downloads.RELATIVE_PATH,
            MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.IS_PENDING, MediaStore.Downloads.OWNER_PACKAGE_NAME)
        fun query(withOwner: Boolean): Scan {
            val entries = mutableListOf<Entry>()
            val projection = if (withOwner) columns else columns.dropLast(1).toTypedArray()
            val cursor = resolver.query(base, projection,
                "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.MIME_TYPE}=?",
                arrayOf(DOWNLOADS_PATH, APK_MIME), null) ?: return Scan(emptyList(), 1)
            cursor.use {
                while (it.moveToNext()) {
                    fun string(column: String) = it.getString(it.getColumnIndexOrThrow(column)).orEmpty()
                    val name = string(MediaStore.Downloads.DISPLAY_NAME)
                    if (string(MediaStore.Downloads.RELATIVE_PATH) != DOWNLOADS_PATH ||
                        string(MediaStore.Downloads.MIME_TYPE) != APK_MIME || !name.endsWith(".apk", true)) continue
                    val uri = ContentUris.withAppendedId(base, it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads._ID)))
                    val key = uri.toString()
                    val record = record(context, key)
                    val owner = if (withOwner) string(MediaStore.Downloads.OWNER_PACKAGE_NAME) else ""
                    if (owner != context.packageName && (owner.isNotBlank() || record == null)) continue
                    val size = it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)).coerceAtLeast(0)
                    val modified = record?.optLong("saved_at", record.optLong("modified")) ?: (it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)) * 1000)
                    val originalName = record?.optString("name")?.takeIf { it.isNotBlank() } ?: name
                    val identity = record?.optString("version")?.takeIf { it.isNotBlank() } ?: originalName
                    val pending = it.getInt(it.getColumnIndexOrThrow(MediaStore.Downloads.IS_PENDING)) != 0
                    entries += Entry(key, originalName, size, modified, pending, originalName in protectedNames, identity) {
                        resolver.delete(uri, null, null) == 1
                    }
                }
            }
            return Scan(entries, 0)
        }
        return runCatching { query(true) }.getOrElse {
            // Some OEM providers omit owner metadata. Only our own recorded URIs may be removed.
            runCatching { query(false).let { it.copy(problems = it.problems + 1) } }.getOrElse { Scan(emptyList(), 1) }
        }
    }

    @Suppress("DEPRECATION")
    private fun ownedLegacy(context: Context, file: File): Boolean = runCatching {
        val known = record(context, file.canonicalPath)
        val checksum = known?.optString("sha256")?.takeIf { it.isNotBlank() }
        if (checksum != null && known.optLong("bytes") == file.length() && known.optLong("modified") == file.lastModified() &&
            UpdateChecksums.sha256Hex(file) == checksum) return@runCatching true
        val archived = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES) ?: return@runCatching false
        if (archived.packageName != context.packageName) return@runCatching false
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        archived.signatures.orEmpty().any { it in installed }
    }.getOrDefault(false)

    private fun record(context: Context, key: String): JSONObject? = runCatching {
        preferences(context).getString(SAVED + key, null)?.let(::JSONObject)
    }.getOrNull()

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun installHeld(context: Context, path: String, now: Long) = preferences(context).getLong(INSTALL + path, 0) > now
    private fun isAttempt(context: Context, directory: File): Boolean = runCatching {
        if (!UUID.fromString(directory.name).toString().equals(directory.name, ignoreCase = true)) return@runCatching false
        val parent = directory.canonicalFile.parentFile
        directory.isDirectory && !symlink(directory) && parent in listOf(File(context.cacheDir, "update").canonicalFile, File(context.cacheDir, "history").canonicalFile)
    }.getOrDefault(false)

    /** Never follow a symlink while walking a tree selected for deletion. */
    private fun symlink(file: File): Boolean = file.parentFile?.let {
        File(it.canonicalFile, file.name) != file.canonicalFile
    } ?: false
    private fun children(file: File): List<File> = if (file.isDirectory && !symlink(file))
        file.listFiles()?.toList().orEmpty() else emptyList()
    private fun treeBytes(file: File): Long = if (symlink(file)) 0 else
        if (file.isFile) file.length() else children(file).sumOf(::treeBytes)
    private fun deleteTree(file: File): Boolean {
        val children = children(file)
        var success = true
        for (child in children) if (!deleteTree(child)) success = false
        return file.delete() && success
    }
}
