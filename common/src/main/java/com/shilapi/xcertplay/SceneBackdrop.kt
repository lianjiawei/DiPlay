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
internal class SceneBackdrop(context: Context, private val night: Boolean, private val quiet: Boolean) :
    ColorDrawable(DiPlayPalette.of(night).background) {
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
        if (quiet) {
            canvas.drawColor((color and 0x00FFFFFF) or (0xDA shl 24))
        } else {
            if (!night) canvas.drawColor(Color.argb(180, 231, 239, 246))
            paint.shader = LinearGradient(bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
                intArrayOf((color and 0x00FFFFFF) or (0x80 shl 24), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
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
