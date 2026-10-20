package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.host.R

/** A decorative status indicator. It observes UI state and never starts or retries a connection. */
internal class ConnectionRingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
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
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); syncAnimation() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); syncAnimation() }

    private fun syncAnimation() {
        val run = busy && isAttachedToWindow && isShown && windowVisibility == VISIBLE && motionEnabled(context)
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
        val radius = minOf(width, height) / 2f - 5f * density
        if (radius <= 0) return
        arc.set(width / 2f - radius, height / 2f - radius, width / 2f + radius, height / 2f + radius)
        paint.strokeWidth = 2f * density
        paint.color = ColorUtils.setAlphaComponent(palette.accent, if (complete) 255 else 155)
        canvas.drawOval(arc, paint)
        paint.color = palette.accent
        paint.strokeWidth = 3f * density
        if (!complete) canvas.drawArc(arc, angle, 55f, false, paint)
        val icon = if (complete) check else phone
        val half = (radius * if (complete) .48f else .43f).toInt()
        icon.setTint(palette.primaryText)
        icon.setBounds(width / 2 - half, height / 2 - half, width / 2 + half, height / 2 + half)
        icon.draw(canvas)
    }

    companion object {
        fun motionEnabled(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
            else runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(false)
    }
}
