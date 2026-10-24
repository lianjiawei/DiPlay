package com.shilapi.xcertplay

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.view.ContextThemeWrapper
import com.shilapi.xcertplay.host.R

internal fun Context.appDialogTheme(): Int = R.style.Theme_Xcertplay_Dialog_Dark

internal fun Context.appDialogContext(): Context = ContextThemeWrapper(this, appDialogTheme())

internal fun Context.appDialogBuilder(): AlertDialog.Builder = GlassDialogBuilder(this)

internal fun Context.appDialog(): Dialog = Dialog(this, appDialogTheme())
