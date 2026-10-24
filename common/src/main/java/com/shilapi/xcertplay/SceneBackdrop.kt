package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable

/** Decorative app chrome only; never placed on a media surface or an instrument output. */
internal class SceneBackdrop(context: Context, private val night: Boolean, private val quiet: Boolean,
    private val readingShare: Float = .50f, private val fullWidthReading: () -> Boolean = { false },
    private val strongReadingVeil: Boolean = false) :
    ColorDrawable(DiPlayPalette.of(night).background) {
    init { FidelityMaterials.install(context) }
    // CarPlay icon/brand customization must not discard the driver's chosen background.
    private val photo = if (VehicleBranding.preset(context) == VehicleBrandPreset.LINCOLN ||
        AirPlayPersistence.hasVehicleModelSelection(context)) {
        val model = AirPlayPersistence.loadVehicleModel(context)
        val custom = if (model == VehicleModel.CUSTOM) CustomWallpaper.file(context, quiet) else null
        custom?.let { cachedImage("custom:${it.path}") { BitmapFactory.decodeFile(it.path) } }
            ?: image(context, if (quiet) model.softScene else model.scene)
    } else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val bitmap = photo ?: return
        val scale = maxOf(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        destination.set(bounds.exactCenterX() - width / 2, bounds.exactCenterY() - height / 2,
            bounds.exactCenterX() + width / 2, bounds.exactCenterY() + height / 2)
        paint.shader = null
        canvas.drawBitmap(bitmap, null, destination, paint)
        if (quiet || bounds.height() > bounds.width() || fullWidthReading()) {
            val readingVeil = if (bounds.height() > bounds.width() || fullWidthReading()) {
                if (night) (if(quiet && !strongReadingVeil)0xA0 else 0xD0) else 0xF0
            } else if (night) 0x68 else 0xE8
            canvas.drawColor((color and 0x00FFFFFF) or (readingVeil shl 24))
        } else {
            if (!night) canvas.drawColor(Color.argb(180, 231, 239, 246))
            val readingColor = if (night) Color.argb(195, 7, 20, 36) else Color.argb(215, 231, 239, 246)
            paint.shader = LinearGradient(bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
                intArrayOf(readingColor, readingColor, Color.TRANSPARENT),
                floatArrayOf(0f, readingShare.coerceIn(0f, .8f), 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = null
        }
    }

    companion object {
        // Do not retain six full-size photos on old head units. Eviction leaves bitmaps held
        // by an existing drawable valid until that screen is released.
        private val images = object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
        private fun image(context: Context, resource: Int): Bitmap? =
            cachedImage("resource:$resource") { BitmapFactory.decodeResource(context.resources, resource) }

        @Synchronized private fun cachedImage(key: String, decode: () -> Bitmap?): Bitmap? {
            images.get(key)?.takeUnless { it.isRecycled }?.let { return it }
            return runCatching(decode).getOrNull()?.also { images.put(key, it) }
        }
    }
}
