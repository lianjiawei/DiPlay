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
import com.shilapi.xcertplay.host.R

/** Decorative app chrome only; never placed on a media surface or an instrument output. */
internal class SceneBackdrop(context: Context, private val night: Boolean, private val quiet: Boolean,
    private val readingShare: Float = .50f) :
    ColorDrawable(DiPlayPalette.of(night).background) {
    init { FidelityMaterials.install(context) }
    private val photo = if (VehicleBranding.preset(context) == VehicleBrandPreset.LINCOLN) image(context, quiet) else null
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
        if (quiet || bounds.height() > bounds.width()) {
            val readingVeil = if (bounds.height() > bounds.width()) {
                if (night) (if(quiet)0xA0 else 0xD0) else 0xF0
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
        @Volatile private var cached: Bitmap? = null
        @Volatile private var cachedSoft: Bitmap? = null
        @Synchronized private fun image(context: Context, quiet: Boolean): Bitmap? {
            (if (quiet) cachedSoft else cached)?.takeUnless { it.isRecycled }?.let { return it }
            return runCatching { BitmapFactory.decodeResource(context.resources,
                if (quiet) R.drawable.corsair_scene_soft else R.drawable.corsair_scene) }
                .getOrNull()?.also { if (quiet) cachedSoft = it else cached = it }
        }
    }
}
