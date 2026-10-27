package com.shilapi.xcertplay

import android.os.SystemClock
import com.shilapi.xcertplay.orchestration.PreconnectedBluetoothSocket
import java.io.Closeable

/** Single-owner transfer: an unclaimed, replaced, or expired resource is always closed. */
internal class ExpiringHandoff<T : Closeable>(private val now: () -> Long) {
    private data class Entry<T>(val key: String, val value: T, val expiresAt: Long, val owner: String?)
    private var entry: Entry<T>? = null

    @Synchronized fun offer(key: String, value: T, lifetimeMs: Long, owner: String? = null) {
        clear()
        entry = Entry(key, value, now() + lifetimeMs.coerceAtLeast(0), owner)
    }

    @Synchronized fun take(key: String): T? {
        val current = entry ?: return null
        if (now() >= current.expiresAt || !current.key.equals(key, ignoreCase = true)) {
            clear()
            return null
        }
        entry = null
        return current.value
    }

    @Synchronized fun clear() {
        val previous = entry
        entry = null
        if (previous != null) runCatching { previous.value.close() }
    }

    @Synchronized fun clearIf(value: T) {
        if (entry?.value === value) clear()
    }

    @Synchronized fun clearOwner(owner: String) {
        if (entry?.owner == owner) clear()
    }
}

internal object BackgroundSocketHandoff {
    private val pending = ExpiringHandoff<PreconnectedBluetoothSocket>(SystemClock::elapsedRealtime)
    fun offer(address: String, socket: PreconnectedBluetoothSocket, owner: String) = pending.offer(address, socket, 60_000, owner)
    fun take(address: String): PreconnectedBluetoothSocket? = pending.take(address)
    fun clearIf(socket: PreconnectedBluetoothSocket) = pending.clearIf(socket)
    fun clearOwner(owner: String) = pending.clearOwner(owner)
    fun clear() = pending.clear()
}
