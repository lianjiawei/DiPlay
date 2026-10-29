package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.graphics.SweepGradient
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R

/** Status indicator; the home screen may attach an accessible click action. */
internal class ConnectionRingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
    private var halo: SweepGradient? = null
    private var palette = DiPlayPalette.DARK
    private var angle = -90f
    private var animator: ValueAnimator? = null
    private var busy = false
    private var complete = false
    private val phone = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_connection_phone)).mutate()
    private val check = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_connection_done)).mutate()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; isClickable = false; isFocusable = false }

    fun appearance(night: Boolean) { palette = DiPlayPalette.of(night); invalidate() }

    fun state(connecting: Boolean, completed: Boolean = false) {
        if (busy == connecting && complete == completed) return
        busy = connecting; complete = completed
        syncAnimation(); invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncAnimation() }
    override fun getAccessibilityClassName(): CharSequence =
        if (isClickable) android.widget.Button::class.java.name else super.getAccessibilityClassName()
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); syncAnimation() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); syncAnimation() }

    private fun syncAnimation() {
        val run = busy && !complete && isAttachedToWindow && isShown && windowVisibility == VISIBLE && motionEnabled(context)
        if (!run) { animator?.cancel(); animator = null; return }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(-90f, 270f).apply {
            duration = 1200L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = minOf(width, height) / 2f - RING_INSET_DP * density
        if (radius <= 0) return
        arc.set(width / 2f - radius, height / 2f - radius, width / 2f + radius, height / 2f + radius)
        // The circular track is fixed. Only the short luminous arc moves around it.
        paint.shader = null
        paint.color = Color.rgb(74, 166, 255)
        paint.strokeWidth = TRACK_GLOW_DP * density
        paint.alpha = 45
        canvas.drawOval(arc, paint)
        paint.strokeWidth = TRACK_DP * density
        paint.alpha = 255
        canvas.drawOval(arc, paint)
        if (!complete) {
            val checkpoint = canvas.save()
            if (busy) canvas.rotate(angle, width / 2f, height / 2f)
            paint.shader = halo
            for ((stroke, opacity) in HALO_STROKES) {
                paint.strokeWidth = stroke * density
                paint.alpha = opacity
                canvas.drawArc(arc, 0f, HALO_SWEEP, false, paint)
            }
            canvas.restoreToCount(checkpoint)
            paint.shader = null
            paint.alpha = 255
        }
        val icon = if (complete) check else phone
        val half = (radius * if (complete) .48f else .52f).toInt()
        icon.setTint(palette.primaryText)
        icon.setBounds(width / 2 - half, height / 2 - half, width / 2 + half, height / 2 + half)
        icon.draw(canvas)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        halo = SweepGradient(w / 2f, h / 2f,
            intArrayOf(Color.TRANSPARENT, Color.rgb(55, 226, 255), Color.rgb(209, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, .12f, HALO_SWEEP / 360f, (HALO_SWEEP + 1f) / 360f))
    }

    companion object {
        private const val RING_INSET_DP = 6f
        private const val TRACK_DP = 1f
        private const val TRACK_GLOW_DP = 4f
        private const val HALO_SWEEP = 62f
        private val HALO_STROKES = listOf(8f to 30, 4f to 100, 1.8f to 255)
        fun motionEnabled(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
            else runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(false)
    }
}
