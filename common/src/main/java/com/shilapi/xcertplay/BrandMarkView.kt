package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.widget.ImageView
import androidx.core.content.ContextCompat

/** Uses the existing vehicle artwork, with a transparent mark for the supplied Lincoln tile. */
internal class BrandMarkView(context: Context, private var night: Boolean=context.resolveAppNightNow(),
    private val homeBrand: Boolean = false) : ImageView(context) {
    private var lincoln=false
    init { refreshLogo() }
    fun appearance(night: Boolean) { this.night=night;refreshLogo() }
    fun refreshLogo() {
        if (homeBrand) {
            val brand = AirPlayPersistence.loadHomeVehicleBrand(context)
            lincoln = brand == HomeVehicleBrand.LINCOLN
            setImageDrawable(if (brand == HomeVehicleBrand.FORD) FordHomeMarkDrawable(context, DiPlayPalette.of(night).primaryText)
                else ContextCompat.getDrawable(context, brand.logo))
            contentDescription = brand.displayName(context)
        } else {
            lincoln=VehicleBranding.preset(context)==VehicleBrandPreset.LINCOLN
            VehicleBranding.setLogo(this)
            contentDescription=VehicleBranding.displayName(context)
        }
        if(lincoln) {
            scaleType=ScaleType.MATRIX
            val tint=DiPlayPalette.of(night).primaryText
            colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                0f,0f,0f,0f,Color.red(tint).toFloat(),
                0f,0f,0f,0f,Color.green(tint).toFloat(),
                0f,0f,0f,0f,Color.blue(tint).toFloat(),
                .34f,1.14f,.12f,0f,-90f,
            )))
            updateMatrix(width,height)
        } else { clearColorFilter();scaleType=ScaleType.FIT_CENTER }
    }
    private fun updateMatrix(w: Int,h: Int) {
        if(!lincoln || h<=0)return
        val d=drawable ?: return
        val scale=h/(d.intrinsicHeight*.68f)
        imageMatrix=Matrix().apply {
            setScale(scale,scale)
            postTranslate((w-d.intrinsicWidth*scale)/2,(h-d.intrinsicHeight*scale)/2)
        }
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh);updateMatrix(w,h)
    }
}
