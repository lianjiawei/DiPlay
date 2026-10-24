package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/** Local, bounded copies: no URI grant, external file, or full-size camera image is needed later. */
internal object CustomWallpaper {
    private const val MAX_BYTES = 40L * 1024 * 1024
    internal const val MAX_EDGE = 2880
    internal const val SOFT_EDGE = 480
    internal const val MAX_PIXELS = 4_000_000L
    private val validId = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private fun root(context: Context) = File(context.noBackupFilesDir, "wallpapers")

    fun file(context: Context, quiet: Boolean = false): File? {
        val id = AirPlayPersistence.loadCustomWallpaperId(context)?.takeIf(validId::matches) ?: return null
        val dir = File(root(context), id)
        val original = File(dir, "image.jpg").takeIf { it.isFile && it.length() > 0 } ?: return null
        return if (quiet) File(dir, "soft.jpg").takeIf { it.isFile && it.length() > 0 } ?: original else original
    }

    fun available(context: Context) = file(context) != null
    fun appearanceKey(context: Context): String {
        val model = AirPlayPersistence.loadVehicleModel(context)
        return if (model == VehicleModel.CUSTOM) "custom:${AirPlayPersistence.loadCustomWallpaperId(context)}" else model.key
    }

    class Prepared internal constructor(val directory: File) {
        fun discard() { directory.deleteRecursively() }
    }

    /** Runs on the import worker. A failed/cancelled import never replaces the saved image. */
    fun prepare(context: Context, uri: Uri): Prepared {
        require(uri.scheme == "content" || uri.scheme == "file")
        val dir = File(root(context), UUID.randomUUID().toString())
        check(dir.mkdirs())
        val source = File(dir, "source")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var count = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
                        val size = input.read(buffer)
                        if (size < 0) break
                        count += size
                        if (count > MAX_BYTES) throw IOException("Image too large")
                        output.write(buffer, 0, size)
                    }
                }
            } ?: throw IOException("Image unavailable")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid image")
            var sample = 1
            fun sampled(size: Int) = (size.toLong() + sample - 1) / sample
            while (sampled(bounds.outWidth) > MAX_EDGE || sampled(bounds.outHeight) > MAX_EDGE ||
                sampled(bounds.outWidth) * sampled(bounds.outHeight) > MAX_PIXELS) sample *= 2
            val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: throw IOException("Invalid image")
            try {
                val orientation = runCatching { ExifInterface(source.path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
                val oriented = orient(decoded, orientation)
                try {
                    // Flatten transparency and remove source metadata before storing a bounded JPEG.
                    val image = Bitmap.createBitmap(oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(image).apply { drawColor(Color.rgb(16, 30, 49)); drawBitmap(oriented, 0f, 0f, null) }
                        write(image, File(dir, "image.jpg"))
                        val scale = minOf(1f, SOFT_EDGE.toFloat() / maxOf(image.width, image.height))
                        val soft = Bitmap.createScaledBitmap(image, maxOf(1, (image.width * scale).toInt()),
                            maxOf(1, (image.height * scale).toInt()), true)
                        try {
                            soften(soft)
                            write(soft, File(dir, "soft.jpg"))
                        } finally { if (soft !== image) soft.recycle() }
                    } finally { image.recycle() }
                } finally { if (oriented !== decoded) oriented.recycle() }
            } finally { decoded.recycle() }
            source.delete()
            return Prepared(dir)
        } catch (failure: Exception) {
            dir.deleteRecursively()
            throw failure
        } catch (failure: OutOfMemoryError) {
            dir.deleteRecursively()
            throw IOException("Image cannot be decoded within memory limits", failure)
        }
    }

    internal fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun write(bitmap: Bitmap, file: File) {
        file.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) throw IOException("Cannot save image") }
    }

    /** Two small box passes approximate a soft lens blur without newer graphics APIs or GPU work. */
    private fun soften(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        val scratch = IntArray(pixels.size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        fun pass(source: IntArray, output: IntArray, horizontal: Boolean) {
            val lines = if (horizontal) height else width
            val length = if (horizontal) width else height
            for (line in 0 until lines) {
                fun index(position: Int): Int = if (horizontal) line * width + position.coerceIn(0, length - 1)
                    else position.coerceIn(0, length - 1) * width + line
                var red = 0
                var green = 0
                var blue = 0
                for (offset in -2..2) {
                    val color = source[index(offset)]
                    red += Color.red(color); green += Color.green(color); blue += Color.blue(color)
                }
                for (position in 0 until length) {
                    output[index(position)] = Color.rgb(red / 5, green / 5, blue / 5)
                    val leaving = source[index(position - 2)]
                    val entering = source[index(position + 3)]
                    red += Color.red(entering) - Color.red(leaving)
                    green += Color.green(entering) - Color.green(leaving)
                    blue += Color.blue(entering) - Color.blue(leaving)
                }
            }
        }
        repeat(2) {
            pass(pixels, scratch, true)
            pass(scratch, pixels, false)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    fun activate(context: Context, prepared: Prepared) {
        check(File(prepared.directory, "image.jpg").isFile && File(prepared.directory, "soft.jpg").isFile)
        AirPlayPersistence.saveCustomWallpaper(context, prepared.directory.name)
    }

    /** Only remove old, inactive copies; never recycle bitmaps still used by a visible page. */
    fun clean(context: Context) {
        val active = AirPlayPersistence.loadCustomWallpaperId(context)
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        root(context).listFiles()?.filter { it.name != active && it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }
}

/** Retained across activity recreation; only a current, uncancelled job may change the wallpaper. */
internal class WallpaperImportModel(
    private val prepareImage: (Context, Uri) -> CustomWallpaper.Prepared = CustomWallpaper::prepare,
) : ViewModel() {
    enum class State { IDLE, LOADING, SAVED, FAILED }
    var state = State.IDLE
        private set
    var observer: ((State) -> Unit)? = null
    private var generation = 0
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun import(context: Context, uri: Uri) {
        val app = context.applicationContext
        val request = ++generation
        state = State.LOADING
        observer?.invoke(state)
        worker.execute {
            val prepared = runCatching { prepareImage(app, uri) }
            main.post {
                if (request != generation) {
                    workerCleanup(prepared.getOrNull())
                } else {
                    val saved = prepared.mapCatching { CustomWallpaper.activate(app, it) }.isSuccess
                    if (!saved) workerCleanup(prepared.getOrNull())
                    state = if (saved) State.SAVED else State.FAILED
                    if (!worker.isShutdown) worker.execute { runCatching { CustomWallpaper.clean(app) } }
                    observer?.invoke(state)
                }
            }
        }
    }

    private fun workerCleanup(prepared: CustomWallpaper.Prepared?) {
        if (prepared == null) return
        // A cleared ViewModel's worker can no longer accept work.
        Thread { prepared.discard() }.apply { name = "wallpaper-cleanup"; isDaemon = true; start() }
    }

    fun acknowledge() { state = State.IDLE }
    fun cancel() { generation++; state = State.IDLE; observer?.invoke(state) }
    override fun onCleared() { observer = null; generation++; worker.shutdownNow() }
}
