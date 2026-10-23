package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import com.shilapi.xcertplay.host.R

/** Raster material only; text, focus, hit targets and actions remain real native controls. */
internal object FidelityMaterials {
    private var appResources: Resources? = null
    private val images = mutableMapOf<Int, Bitmap>()
    fun install(context: Context) { appResources = context.applicationContext.resources }
    @Synchronized fun image(id: Int): Bitmap? = images[id] ?: appResources?.let {
        BitmapFactory.decodeResource(it, id)?.also { bitmap -> images[id] = bitmap }
    }
}

internal class GlassMaterialDrawable(
    colors: IntArray, private val fill: Int, private val primary: Boolean,
) : GradientDrawable(Orientation.TOP_BOTTOM, colors) {
    private var outlineWidth=0
    private var outlineColor=Color.TRANSPARENT
    override fun setStroke(width: Int,color: Int) {
        outlineWidth=width;outlineColor=color;super.setStroke(width,color)
    }
    override fun getConstantState(): android.graphics.drawable.Drawable.ConstantState {
        val savedColors=colors?.clone() ?: intArrayOf(fill,fill)
        val savedRadius=cornerRadius
        val savedWidth=outlineWidth
        val savedOutline=outlineColor
        val savedAlpha=alpha
        val configurations=changingConfigurations
        return object : android.graphics.drawable.Drawable.ConstantState() {
            override fun getChangingConfigurations()=configurations
            override fun newDrawable(): android.graphics.drawable.Drawable = GlassMaterialDrawable(savedColors,fill,primary).apply {
                cornerRadius=savedRadius;setStroke(savedWidth,savedOutline);alpha=savedAlpha
            }
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val target = RectF()
    private val silhouette = Path()

    override fun draw(canvas: Canvas) {
        val pill = bounds.height() <= cornerRadius * 2.2f && bounds.width() >= bounds.height() * 1.3f
        val eligible = Color.blue(fill) >= Color.red(fill) && (!primary || androidx.core.graphics.ColorUtils.calculateLuminance(fill) > .20)
        val bitmap = if (pill && eligible) FidelityMaterials.image(if (primary) R.drawable.glass_primary_material else R.drawable.glass_secondary_material) else null
        if (bitmap == null) { super.draw(canvas); return }
        // The raster includes blue light outside its pill. Keep every slice inside the actual
        // button silhouette, instead of exposing its rectangular image/shadow bounds at the ends.
        val checkpoint = canvas.save()
        target.set(bounds)
        silhouette.reset()
        silhouette.addRoundRect(target, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(silhouette)
        // Preserve the generated round ends while stretching just the straight centre.
        val body = if (primary) Rect(160, 255, 1773, 554) else Rect(117, 230, 1867, 563)
        val cap = body.height() / 2
        val h = bounds.height().toFloat()
        val capWidth = minOf(h / 2, bounds.width() / 2f)
        paint.alpha = if(primary)alpha else (alpha*.74f).toInt()
        fun slice(l: Int, rr: Int, x: Float, right: Float) {
            source.set(l, body.top, rr, body.bottom)
            target.set(x, bounds.top.toFloat(), right, bounds.bottom.toFloat())
            canvas.drawBitmap(bitmap, source, target, paint)
        }
        slice(body.left, body.left + cap, bounds.left.toFloat(), bounds.left + capWidth)
        slice(body.left + cap, body.right - cap, bounds.left + capWidth, bounds.right - capWidth)
        slice(body.right - cap, body.right, bounds.right - capWidth, bounds.right.toFloat())
        canvas.restoreToCount(checkpoint)
    }
}
