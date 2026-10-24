package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R

/** The packaged Ford script, rendered as a transparent white oval to match the Lincoln mark. */
internal class FordHomeMarkDrawable(context: Context, tint: Int) : Drawable() {
    private val bitmap = (requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_ford)) as BitmapDrawable).bitmap
    // Normalized artwork coordinates remove the square tile without changing the original script.
    private val source = Rect((bitmap.width * .06f).toInt(), (bitmap.height * .335f).toInt(),
        (bitmap.width * .94f).toInt(), (bitmap.height * .665f).toInt())
    private val monochrome = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
        0f, 0f, 0f, 0f, Color.red(tint).toFloat(),
        0f, 0f, 0f, 0f, Color.green(tint).toFloat(),
        0f, 0f, 0f, 0f, Color.blue(tint).toFloat(),
        -1.2f, 0f, 0f, 0f, 255f,
    )))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = monochrome }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.STROKE }
    private val oval = RectF()
    private val clip = Path()

    override fun draw(canvas: Canvas) {
        val width = minOf(bounds.width().toFloat(), bounds.height() * source.width().toFloat() / source.height())
        val height = width * source.height() / source.width()
        outline.strokeWidth = width * .018f
        oval.set(bounds.exactCenterX() - width / 2, bounds.exactCenterY() - height / 2,
            bounds.exactCenterX() + width / 2, bounds.exactCenterY() + height / 2)
        oval.inset(outline.strokeWidth / 2, outline.strokeWidth / 2)
        clip.reset(); clip.addOval(oval, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, source, oval, paint)
        canvas.restoreToCount(save)
        canvas.drawOval(oval, outline)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; outline.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter ?: monochrome
        outline.colorFilter = colorFilter
        invalidateSelf()
    }
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
