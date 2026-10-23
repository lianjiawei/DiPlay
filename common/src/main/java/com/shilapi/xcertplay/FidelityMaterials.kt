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
    // These rectangles are adjacent parts of one material. Edge antialiasing gives each
    // internal edge partial coverage, leaving a visible alpha seam when scaled/translated.
    // Filter the texture, but let only the outer rounded silhouette define the edge.
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val target = RectF()
    private val silhouette = Path()
    private data class MaterialSize(val width: Int, val height: Int, val primary: Boolean)
    companion object {
        // Shared by equal-size buttons and their enabled/disabled states; keep old head units bounded.
        private val materials = object : android.util.LruCache<MaterialSize, Bitmap>(4 * 1024 * 1024) {
            override fun sizeOf(key: MaterialSize, value: Bitmap) = value.allocationByteCount
        }
    }

    private fun stretchedMaterial(bitmap: Bitmap): Bitmap {
        val key = MaterialSize(bounds.width(), bounds.height(), primary)
        materials.get(key)?.let { return it }
        val body = if (primary) Rect(160, 255, 1773, 554) else Rect(117, 230, 1867, 563)
        val cap = body.height() / 2
        val width = bounds.width()
        val height = bounds.height()
        val capWidth = minOf(height / 2, width / 2)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        paint.alpha = 255
        fun slice(left: Int, right: Int, x: Int, end: Int) {
            source.set(left, body.top, right, body.bottom)
            target.set(x.toFloat(), 0f, end.toFloat(), height.toFloat())
            canvas.drawBitmap(bitmap, source, target, paint)
        }
        // Compose at integer pixel boundaries once. The screen then transforms one texture,
        // rather than separately filtering/alpha-blending three adjacent rectangles.
        slice(body.left, body.left + cap, 0, capWidth)
        slice(body.left + cap, body.right - cap, capWidth, width - capWidth)
        slice(body.right - cap, body.right, width - capWidth, width)
        materials.put(key, result)
        return result
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val pill = bounds.height() <= cornerRadius * 2.2f && bounds.width() >= bounds.height() * 1.3f
        val eligible = Color.blue(fill) >= Color.red(fill) && (!primary || androidx.core.graphics.ColorUtils.calculateLuminance(fill) > .20)
        val bitmap = if (pill && eligible) FidelityMaterials.image(if (primary) R.drawable.glass_primary_material else R.drawable.glass_secondary_material) else null
        if (bitmap == null) { super.draw(canvas); return }
        val texture = stretchedMaterial(bitmap)
        // The raster includes blue light outside its pill. Keep the material inside the actual
        // button silhouette, instead of exposing its rectangular image/shadow bounds at the ends.
        val checkpoint = canvas.save()
        target.set(bounds)
        silhouette.reset()
        silhouette.addRoundRect(target, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.clipPath(silhouette)
        paint.alpha = if(primary)alpha else (alpha*.74f).toInt()
        canvas.drawBitmap(texture, null, target, paint)
        canvas.restoreToCount(checkpoint)
    }
}
