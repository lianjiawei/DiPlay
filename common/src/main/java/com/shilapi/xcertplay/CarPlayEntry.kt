package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent

/** Every current entry uses the launcher; the old host component only forwards legacy intents. */
internal object CarPlayEntry {
    fun intent(context: Context): Intent = Intent(context, DiPlayActivity::class.java)
        .setAction("com.shilapi.xcertplay.OPEN_PROJECTION")
        .putExtra(EXTRA_OPEN_PROJECTION, true)
}
