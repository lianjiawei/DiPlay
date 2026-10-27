package com.shilapi.xcertplay.lincoln

/**
 * A bounded, numeric-only observation of individual iAP2 messages. No payload or string is kept.
 * This is diagnostic evidence, not a decoder for making navigation decisions.
 */
internal class LincolnNavigationFrameTrace(
    private val nanoTime: () -> Long = System::nanoTime,
    private val capacity: Int = 256,
) {
    internal data class Entry(
        val sequence: Long, val timeMs: Long, val session: Long, val connected: Boolean,
        val messageId: Int, val generation: Long, val revision: Long,
        val valid: Boolean, val limited: Boolean, val present: Int, val invalid: Int, val duplicate: Int,
        val state: Int?, val maneuverState: Int?, val distance: Long?, val remaining: Long?,
        val indices: List<Int>?, val indexCount: Int?, val maneuverIndex: Int?, val maneuverType: Int?,
        val betweenDistance: Long?,
    ) {
        fun line(): String = "frame seq=$sequence ms=$timeMs session=$session connected=$connected" +
            " id=${if (messageId == 0) "connection" else messageId.toString(16)} generation=$generation revision=$revision" +
            " valid=$valid limited=$limited present=${present.toString(16)}" +
            " invalid=${invalid.toString(16)} duplicate=${duplicate.toString(16)}" +
            " state=${state ?: "-"} maneuverState=${maneuverState ?: "-"}" +
            " distance=${distance ?: "-"} remaining=${remaining ?: "-"}" +
            " listCount=${indexCount ?: "-"} indices=${indices?.let { if (it.isEmpty()) "empty" else it.joinToString(",") } ?: "-"}" +
            " maneuverIndex=${maneuverIndex ?: "-"} maneuverType=${maneuverType ?: "-"}" +
            " between=${betweenDistance ?: "-"}"
    }

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()
    private var sequence = 0L
    private var evicted = 0L
    private var rateDropped = 0L
    private var windowNs: Long? = null
    private var regularCount = 0
    private var routeCount = 0
    private var maneuverCount = 0

    init { require(capacity > 0) }

    internal fun clearForTests() = synchronized(lock) {
        entries.clear()
        sequence = 0
        evicted = 0
        rateDropped = 0
        windowNs = null
        regularCount = 0
        routeCount = 0
        maneuverCount = 0
    }

    fun connection(session: Long, connected: Boolean, generation: Long, revision: Long) {
        val now = nanoTime()
        synchronized(lock) {
            val event = Entry(++sequence, now / 1_000_000, session, connected, 0, generation, revision,
                true, false, 0, 0, 0, null, null, null, null, null, null, null, null, null)
            addLocked(event)
        }
    }

    /** Called in the existing glance ordering lock; this function never performs I/O. */
    fun record(id: Int, payload: ByteArray, session: Long, connected: Boolean, generation: Long, revision: Long) {
        if (id != 0x5201 && id != 0x5202) return
        val parsed = parse(id, payload)
        val now = nanoTime()
        synchronized(lock) {
            val seq = ++sequence
            val start = windowNs
            if (start == null || now < start || now - start >= SECOND_NS) {
                windowNs = now
                regularCount = 0
                routeCount = 0
                maneuverCount = 0
            }
            // Distance-only messages are common; leave a separate budget for status/index/burst changes.
            val regular = id == 0x5201 && parsed.valid && !parsed.limited &&
                parsed.present == (1 shl 0x0a)
            val allowed = when {
                regular -> ++regularCount <= REGULAR_PER_SECOND
                id == 0x5201 -> ++routeCount <= ROUTE_PER_SECOND
                else -> ++maneuverCount <= MANEUVER_PER_SECOND
            }
            if (!allowed) { rateDropped++; return }
            val event = Entry(seq, now / 1_000_000, session, connected, id, generation, revision,
                parsed.valid, parsed.limited, parsed.present, parsed.invalid, parsed.duplicate,
                parsed.state, parsed.maneuverState, parsed.distance, parsed.remaining,
                parsed.indices, parsed.indexCount, parsed.maneuverIndex, parsed.maneuverType, parsed.between)
            addLocked(event)
        }
    }

    private fun addLocked(event: Entry) {
        if (entries.size == capacity) { entries.removeFirst(); evicted++ }
        entries.addLast(event)
    }

    fun report(): String {
        val (copy, dropped, overwritten) = synchronized(lock) { Triple(entries.toList(), rateDropped, evicted) }
        return buildString {
            appendLine("Lincoln navigation frames v1: numeric-only; partial replay; rateDropped=$dropped evicted=$overwritten")
            copy.forEach { appendLine(it.line()) }
        }.trimEnd()
    }

    private data class Parsed(
        var valid: Boolean = true, var limited: Boolean = false,
        var present: Int = 0, var invalid: Int = 0, var duplicate: Int = 0,
        var state: Int? = null, var maneuverState: Int? = null,
        var distance: Long? = null, var remaining: Long? = null,
        var indices: List<Int>? = null, var indexCount: Int? = null,
        var maneuverIndex: Int? = null, var maneuverType: Int? = null, var between: Long? = null,
    )

    private fun parse(id: Int, bytes: ByteArray): Parsed {
        val out = Parsed()
        var offset = 0
        var fields = 0
        while (offset < bytes.size) {
            if (fields++ >= MAX_FIELDS) { out.limited = true; break }
            if (bytes.size - offset < 4) { out.valid = false; break }
            val length = u16(bytes, offset)
            val field = u16(bytes, offset + 2)
            if (length < 4 || length > bytes.size - offset) { out.valid = false; break }
            val size = length - 4
            val value = offset + 4
            val relevant = if (id == 0x5201) {
                field == 1 || field == 2 || field == 3 || field == 4 || field == 7 || field == 10 || field == 13
            } else field == 1 || field == 2 || field == 3 || field == 4 || field == 5
            if (relevant) {
                val bit = 1 shl field
                if (out.present and bit != 0) out.duplicate = out.duplicate or bit
                out.present = out.present or bit
                val needed = when {
                    id == 0x5201 && field == 13 -> 0
                    id == 0x5201 && (field == 3 || field == 4) -> 0 // string presence only
                    id == 0x5202 && (field == 2 || field == 4) -> 0 // never retain text
                    id == 0x5202 && field == 1 -> 2
                    field == 7 || field == 10 || (id == 0x5202 && field == 5) -> 4
                    else -> 1
                }
                if (size < needed || (id == 0x5201 && field == 13 && size % 2 != 0)) {
                    out.invalid = out.invalid or bit
                } else if (id == 0x5201) {
                    when (field) {
                        1 -> out.state = bytes[value].toInt() and 0xff
                        2 -> out.maneuverState = bytes[value].toInt() and 0xff
                        7 -> out.remaining = u32(bytes, value)
                        10 -> out.distance = u32(bytes, value)
                        13 -> {
                            out.indexCount = size / 2
                            out.indices = (0 until minOf(size / 2, MAX_INDICES)).map { u16(bytes, value + it * 2) }
                        }
                    }
                } else {
                    when (field) {
                        1 -> out.maneuverIndex = u16(bytes, value)
                        3 -> out.maneuverType = bytes[value].toInt() and 0xff
                        5 -> out.between = u32(bytes, value)
                    }
                }
            }
            offset += length
        }
        return out
    }

    private fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
    private fun u32(b: ByteArray, p: Int): Long =
        ((u16(b, p).toLong() shl 16) or u16(b, p + 2).toLong())

    private companion object {
        const val SECOND_NS = 1_000_000_000L
        const val REGULAR_PER_SECOND = 8
        const val ROUTE_PER_SECOND = 32
        const val MANEUVER_PER_SECOND = 64
        const val MAX_FIELDS = 128
        const val MAX_INDICES = 8
    }
}
