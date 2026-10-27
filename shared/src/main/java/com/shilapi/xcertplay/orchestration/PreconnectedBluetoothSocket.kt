package com.shilapi.xcertplay.orchestration

import android.bluetooth.BluetoothSocket
import java.io.Closeable

/** Ownership moves from the background connector to the controller when claimed. */
class PreconnectedBluetoothSocket(val socket: BluetoothSocket, val connectMillis: Long) : Closeable {
    override fun close() = socket.close()
}

/** Retry once through normal startup only before a preconnected link sent StartSession. */
internal object PreconnectedRetryPolicy {
    fun shouldRetry(usedPreconnected: Boolean, sentStartSession: Boolean, active: Boolean): Boolean =
        usedPreconnected && !sentStartSession && !active
}
