package com.shilapi.xcertplay.orchestration

import android.hardware.usb.UsbDevice
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration

/** One mode-switch attempt, owned by the controller's main handler. */
internal class WiredUsbReenumeration(
    previous: UsbDevice,
    initiallyConnected: List<UsbDevice>,
    val deadlineMillis: Long,
) {
    private val previousKey = key(previous)
    private val previousDescriptors = descriptors(previous)
    private val otherPhones = initiallyConnected.map(::key).filter { it != previousKey }.toSet()
    private var previousDisappeared = false
    private var announced: String? = null
    var transitionReady = false
    var poll: Runnable? = null
    var scanFailureReported = false

    fun attached(device: UsbDevice) {
        val key = key(device)
        if (key !in otherPhones) announced = key
    }

    fun candidate(current: List<UsbDevice>): UsbDevice? {
        if (current.none { key(it) == previousKey }) previousDisappeared = true
        val eligible = current.filter { key(it) !in otherPhones }
        // An explicit attach can represent a node reused between polls. Confirm it is still
        // listed, but preserve this working broadcast path even when descriptors stayed equal.
        eligible.firstOrNull { key(it) == announced }?.let {
            if (transitionReady || key(it) != previousKey || previousDisappeared ||
                descriptors(it) != previousDescriptors) return it
        }
        return eligible.firstOrNull {
            key(it) != previousKey || previousDisappeared || descriptors(it) != previousDescriptors
        }
    }

    private fun key(device: UsbDevice): String =
        "${device.deviceName}:${device.deviceId}:${device.vendorId}:${device.productId}"

    private fun descriptors(device: UsbDevice): String =
        (0 until device.configurationCount).joinToString(";") { index ->
            val configuration = device.getConfiguration(index)
            "${configuration.id}:${IphoneCarPlayConfiguration.describe(configuration)}"
        }
}
