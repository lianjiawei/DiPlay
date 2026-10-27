package com.shilapi.xcertplay.lincoln

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.File

/** Test-only reconstruction of whitelisted numeric fields, never a claim of a full original frame. */
internal object LincolnNavigationNumericReplay {
    data class Event(val sequence: Long, val timeMs: Long, val session: Long, val connected: Boolean, val frame: Iap2Frame?)
    data class Trace(val events: List<Event>, val warnings: List<String>)

    /** Stream just the bounded frame section; ordinary reports can be much larger than this section. */
    fun decodeFile(file: File, allowIncomplete: Boolean = false): Trace {
        require(file.length() <= 64L * 1024 * 1024) { "report exceeds 64 MiB" }
        val section = StringBuilder()
        var started = false
        file.useLines { lines ->
            for (line in lines) {
                if (!started) {
                    if (!line.startsWith("Lincoln navigation frames v1:")) continue
                    started = true
                    section.appendLine(line)
                } else {
                    if (!line.startsWith("frame ")) break
                    require(line.length <= 1024 && section.length + line.length < 1_000_000) { "frame section too large" }
                    section.appendLine(line)
                }
            }
        }
        return decode(section.toString(), allowIncomplete)
    }

    fun decode(report: String, allowIncomplete: Boolean = false): Trace {
        require(report.length <= 1_000_000) { "report too large" }
        val header = report.lines().firstOrNull { it.startsWith("Lincoln navigation frames v1:") }
            ?: error("frame trace v1 is missing")
        fun counter(name: String): Long = Regex("\\b$name=(\\d+)").find(header)?.groupValues?.get(1)?.toLong()
            ?: error("missing $name")
        val warnings = mutableListOf("Numeric replay omits road names, text, audio, unknown fields and original scheduler timing.")
        if (counter("rateDropped") != 0L || counter("evicted") != 0L) warnings += "The capture has missing frames."
        val lines = report.lineSequence().filter { it.startsWith("frame ") }.toList()
        require(lines.size <= 256) { "too many frames" }
        val events = mutableListOf<Event>()
        var previous = 0L
        var previousTime: Long? = null
        for (line in lines) {
            require(line.length <= 1024) { "frame line too long" }
            val fields = line.split(' ').drop(1).associate { token ->
                val pos = token.indexOf('=')
                require(pos > 0) { "bad frame field" }
                token.substring(0, pos) to token.substring(pos + 1)
            }
            fun value(key: String): String = fields[key] ?: error("missing $key")
            fun number(key: String): Long? = value(key).takeUnless { it == "-" }?.toLong()
            val seq = value("seq").toLong()
            require(seq > previous) { "unordered or duplicate sequence" }
            if (seq != previous + 1) warnings += "Sequence gap before $seq."
            previous = seq
            val time = value("ms").toLong()
            require(previousTime == null || time >= previousTime!!) { "non-monotonic frame time" }
            previousTime = time
            val id = value("id")
            val invalid = value("invalid").toInt(16)
            val duplicate = value("duplicate").toInt(16)
            val valid = value("valid").toBooleanStrict()
            val limited = value("limited").toBooleanStrict()
            val count = number("listCount")
            if (!valid || limited || invalid != 0 || duplicate != 0 || (count != null && count > 8)) {
                warnings += "Frame $seq cannot be faithfully reconstructed."
                if (!allowIncomplete) error(warnings.last())
                continue
            }
            val frame = when (id) {
                "connection" -> null
                "5201" -> Iap2Messages.buildRaw(0x5201) {
                    number("state")?.let { u8(1, it.toInt()) }
                    number("maneuverState")?.let { u8(2, it.toInt()) }
                    number("remaining")?.let { u32(7, it) }
                    number("distance")?.let { u32(10, it) }
                    if (count != null) {
                        val raw = value("indices")
                        val indices = if (raw == "empty") emptyList() else raw.split(',').map { it.toInt() }
                        require(indices.size.toLong() == count) { "index count mismatch" }
                        u16List(13, indices)
                    }
                }
                "5202" -> Iap2Messages.buildRaw(0x5202) {
                    number("maneuverIndex")?.let { u16(1, it.toInt()) }
                    number("maneuverType")?.let { u8(3, it.toInt()) }
                    number("between")?.let { u32(5, it) }
                }
                else -> error("unsupported event id")
            }
            events += Event(seq, time, value("session").toLong(),
                value("connected").toBooleanStrict(), frame)
        }
        if (events.firstOrNull()?.frame != null) warnings += "Capture does not begin with a connection event."
        if (!allowIncomplete && warnings.size > 1) error(warnings.drop(1).joinToString(" "))
        return Trace(events, warnings)
    }
}
