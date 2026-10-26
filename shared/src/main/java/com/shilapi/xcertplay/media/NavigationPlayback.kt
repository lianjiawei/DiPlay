package com.shilapi.xcertplay.media

/** A null stream means usage routing, fallback, or conflicting active legacy routes. */
data class NavigationPlaybackSnapshot(val active: Boolean, val legacyStreamType: Int?, val priorityVoiceActive: Boolean = false)

/** Consumers can poll this snapshot; it never requests focus or changes volume itself. */
object NavigationPlayback {
    private val tracker = NavigationPlaybackTracker()
    fun snapshot(): NavigationPlaybackSnapshot = tracker.snapshot()
    /** Focus callbacks may follow track teardown; volume keys must not use this short tail. */
    fun focusSnapshot(): NavigationPlaybackSnapshot = tracker.snapshot(includeFocusTail = true)
    internal fun open(owner: NavigationPlaybackTracker.Owner? = null) = tracker.open(owner)
    internal fun played(token: NavigationPlaybackTracker.Token, streamType: Int?, bufferedMillis: Long, priorityVoice: Boolean = false) =
        tracker.played(token, streamType, bufferedMillis, priorityVoice)
    internal fun close(token: NavigationPlaybackTracker.Token, retainFocusTail: Boolean = false) =
        tracker.close(token, retainFocusTail)
    internal fun closeOwner(owner: NavigationPlaybackTracker.Owner) = tracker.closeOwner(owner)
}

/** Per-renderer ownership prevents one navigation stream from clearing another stream. */
internal class NavigationPlaybackTracker(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val quietTailMillis: Long = 900L,
) {
    class Owner { internal var closed = false }
    class Token internal constructor(internal val owner: Owner?) { internal var closed = false }
    private data class Entry(val streamType: Int?, val untilMillis: Long, val priorityVoice: Boolean)
    private val entries = LinkedHashMap<Token, Entry>()
    private val focusTails = LinkedHashMap<Token, Entry>()

    fun open(owner: Owner? = null) = Token(owner)

    @Synchronized
    fun played(token: Token, streamType: Int?, bufferedMillis: Long, priorityVoice: Boolean = false) {
        if (token.closed || token.owner?.closed == true) return
        entries[token] = Entry(streamType, nowMillis() + bufferedMillis.coerceAtLeast(0L) + quietTailMillis, priorityVoice)
    }

    @Synchronized
    fun close(token: Token, retainFocusTail: Boolean = false) {
        if (token.closed) return // close() and the worker's finally must not extend the tail
        token.closed = true
        val entry = entries.remove(token) ?: return
        val now = nowMillis()
        focusTails.entries.removeAll { it.value.untilMillis <= now }
        if (retainFocusTail && token.owner?.closed != true && !entry.priorityVoice && entry.untilMillis > now) {
            focusTails[token] = entry.copy(untilMillis = minOf(entry.untilMillis, now + quietTailMillis))
        }
    }

    @Synchronized
    fun closeOwner(owner: Owner) {
        owner.closed = true
        entries.keys.removeAll { it.owner === owner }
        focusTails.keys.removeAll { it.owner === owner }
    }

    @Synchronized
    fun snapshot(includeFocusTail: Boolean = false): NavigationPlaybackSnapshot {
        val now = nowMillis()
        entries.entries.removeAll { it.value.untilMillis <= now }
        focusTails.entries.removeAll { it.value.untilMillis <= now }
        val navigation = entries.values.filter { !it.priorityVoice } +
            if (includeFocusTail) focusTails.values else emptyList()
        val routes = navigation.map { it.streamType }.distinct()
        return NavigationPlaybackSnapshot(navigation.isNotEmpty(), routes.singleOrNull(), entries.values.any { it.priorityVoice })
    }
}
