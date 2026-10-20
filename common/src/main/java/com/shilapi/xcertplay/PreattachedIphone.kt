package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbManager
import com.shilapi.xcertplay.transport.IphoneUsbMatcher

/** Android need not replay a USB attach broadcast for a phone connected before boot. */
internal object PreattachedIphone {
    fun isAttached(context: Context): Boolean = runCatching {
        context.getSystemService(UsbManager::class.java)?.deviceList?.values
            ?.any { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID } == true
    }.getOrDefault(false)
}
