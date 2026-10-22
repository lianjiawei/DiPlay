package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.roundToInt

/** Styles the framework dialog after inflation; its adapters, listeners and dismiss rules stay native. */
internal class GlassDialogBuilder(private val owner: Context) : AlertDialog.Builder(owner,owner.appDialogTheme()) {
    var reconnectHint=false
    override fun create(): AlertDialog = super.create().also { dialog ->
        val decor=dialog.window?.decorView ?: return@also
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { view.post { style(dialog) } }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }
    private fun style(dialog: AlertDialog) {
        if(!dialog.isShowing)return
        FidelityMaterials.install(owner)
        val density=owner.resources.displayMetrics.density
        fun dp(value:Int)=(value*density).roundToInt()
        val palette=DiPlayPalette.of((owner as? AppAppearanceOwner)?.currentAppNight ?: owner.resolveAppNightNow())
        dialog.window?.setBackgroundDrawable(InsetDrawable(GlassChrome.surface(palette.surface,palette.outline,density,
            dp(GlassChrome.DIALOG_RADIUS_DP).toFloat(),opacity=224),dp(GlassChrome.CONTROL_GAP_DP)))
        fun descendants(view: View): List<View> = listOf(view) + if(view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }else emptyList()
        val decor=dialog.window?.decorView ?: return
        descendants(decor).filterIsInstance<TextView>().forEach { text ->
            val name=runCatching { owner.resources.getResourceEntryName(text.id) }.getOrNull()
            if(name=="alertTitle") { text.textSize=UiTypography.STATUS_SP.toFloat();text.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);text.setTextColor(palette.primaryText) }
            if(name=="message") { text.textSize=UiTypography.BODY_SP.toFloat();text.setTextColor(palette.secondaryText) }
        }
        for(id in listOf(AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(id)?.apply {
                val primary=id==AlertDialog.BUTTON_POSITIVE
                isAllCaps=false;textSize=UiTypography.ACTION_SP.toFloat();minHeight=dp(FidelitySettingsMetrics.DIALOG_ACTION_DP)
                setSingleLine(false)
                minWidth=dp(96);setPadding(dp(20),dp(8),dp(20),dp(8))
                setTextColor(GlassChrome.textColors(if(primary)palette.onAccent else palette.primaryText,palette.surface))
                val fill=if(primary)palette.accent else palette.button
                val radius=GlassChrome.radius(dp(FidelitySettingsMetrics.DIALOG_ACTION_DP),density)
                val normal=GlassChrome.surface(fill,palette.outline,density,radius,primary=primary)
                val disabled=GlassChrome.surface(fill,palette.outline,density,radius,primary=primary).apply { alpha=112 }
                background=android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(-android.R.attr.state_enabled),disabled)
                    addState(intArrayOf(),RippleDrawable(ColorStateList.valueOf(palette.ripple),normal,null))
                }
                addOnLayoutChangeListener { view,_,_,_,_,_,_,_,_ ->
                    normal.cornerRadius=view.height/2f;disabled.cornerRadius=view.height/2f
                }
            }
        }
        val actionParent=dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.parent as? android.widget.LinearLayout
        if(actionParent!=null) {
            val actions=(0 until actionParent.childCount).map(actionParent::getChildAt).filterIsInstance<android.widget.Button>()
            actionParent.removeAllViews()
            actionParent.addView(GlassDialogActions(owner).apply {
                actions.forEach { action -> addView(action,android.widget.LinearLayout.LayoutParams(-1,-2)) }
            },android.widget.LinearLayout.LayoutParams(-1,-2))
        }
        dialog.listView?.apply {
            val optionCount=adapter?.count ?: 0
            if(reconnectHint)addFooterView(TextView(context).apply {
                text=owner.getString(com.shilapi.xcertplay.host.R.string.ui_glass_reconnect_hint)
                textSize=UiTypography.CAPTION_SP.toFloat();setTextColor(palette.secondaryText);minHeight=dp(FidelitySettingsMetrics.DIALOG_HINT_DP)
                gravity=android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(FidelitySettingsMetrics.CONTENT_PAD_DP),dp(FidelitySettingsMetrics.ROW_GAP_DP),dp(FidelitySettingsMetrics.CONTENT_PAD_DP),dp(FidelitySettingsMetrics.ROW_GAP_DP))
            },null,false)
            val source=adapter ?: return@apply
            val checks=checkedItemPositions?.clone()
            adapter=object : android.widget.BaseAdapter() {
                override fun getCount()=source.count
                override fun getItem(position:Int)=source.getItem(position)
                override fun getItemId(position:Int)=source.getItemId(position)
                override fun hasStableIds()=source.hasStableIds()
                override fun getItemViewType(position:Int)=source.getItemViewType(position)
                override fun getViewTypeCount()=source.viewTypeCount
                override fun areAllItemsEnabled()=source.areAllItemsEnabled()
                override fun isEnabled(position:Int)=source.isEnabled(position)
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View = source.getView(position,convertView,parent).also { view ->
                    if(view is TextView && position<optionCount) {
                        view.textSize=UiTypography.ROW_SP.toFloat();view.setTextColor(palette.primaryText);view.minHeight=dp(FidelitySettingsMetrics.DIALOG_OPTION_DP)
                        view.setSingleLine(false);view.setPadding(dp(FidelitySettingsMetrics.CARD_PAD_DP),dp(FidelitySettingsMetrics.ROW_VERTICAL_DP),dp(FidelitySettingsMetrics.CARD_PAD_DP),dp(FidelitySettingsMetrics.ROW_VERTICAL_DP))
                        view.text=view.text.toString().replace(" · ","\n")
                        view.background=android.graphics.drawable.StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_checked),GlassChrome.surface(palette.railSelected,palette.railSelectedOutline,density,dp(16).toFloat(),opacity=200))
                            addState(intArrayOf(),android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                        }
                    }
                }
            }
            if(checks!=null)for(i in 0 until checks.size())setItemChecked(checks.keyAt(i),checks.valueAt(i))
        }
    }
}
