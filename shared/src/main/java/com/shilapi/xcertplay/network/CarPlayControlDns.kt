package com.shilapi.xcertplay.network

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.util.Locale

/** Bounded DNS-SD codec for direct, on-link mDNS queries (RFC 6762 sections 5.5/6.7).
 * A gateway becomes a control endpoint only with PTR + SRV + matching TXT id + its own A record.
 * No port guesses, reverse lookups, or network scans are performed.
 */
internal object CarPlayControlDns {
    val service = listOf("_carplay-ctrl", "_tcp", "local")
    data class Question(val name: List<String>, val type: Int)
    data class Record(val name: List<String>, val type: Int, val value: Any)
    data class Srv(val port: Int, val target: List<String>)

    fun bluetoothId(value: String?): String? = value?.replace(":", "")?.replace("-", "")
        ?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[0-9a-f]{12}")) }

    fun query(id: Int, questions: List<Question>): ByteArray {
        require(id in 1..65535 && questions.size in 1..16)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(id)
            out.writeShort(0)
            out.writeShort(questions.size)
            repeat(3) { out.writeShort(0) }
            questions.forEach { q ->
                require(q.name.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 } < 255)
                q.name.forEach { label ->
                    val encoded = label.toByteArray(Charsets.UTF_8)
                    require(encoded.size in 1..63)
                    out.writeByte(encoded.size)
                    out.write(encoded)
                }
                out.writeByte(0)
                out.writeShort(q.type)
                out.writeShort(1) // Legacy unicast response to our ephemeral source port.
            }
        }
        return bytes.toByteArray()
    }

    fun response(bytes: ByteArray, id: Int): List<Record> = try {
        val reader = Reader(bytes)
        require(reader.u16() == id)
        val flags = reader.u16()
        require(flags and 0xfa0f == 0x8000) // Response, standard opcode, not truncated, no DNS error.
        val questions = reader.u16()
        val count = reader.u16() + reader.u16() + reader.u16()
        require(questions <= 16 && count <= 128)
        repeat(questions) { reader.name(); reader.skip(4) }
        buildList {
            repeat(count) {
                val name = reader.name()
                val type = reader.u16()
                val klass = reader.u16() and 0x7fff
                val ttl = reader.u32()
                val length = reader.u16()
                val end = reader.position + length
                require(end <= bytes.size)
                val value: Any? = if (klass != 1 || ttl == 0L) null else when (type) {
                    12 -> reader.name().also { require(reader.position == end) }
                    33 -> {
                        require(length >= 7)
                        reader.skip(4)
                        val port = reader.u16()
                        Srv(port, reader.name()).also { require(reader.position == end) }
                    }
                    16 -> buildMap<String, String> {
                        while (reader.position < end) {
                            val size = reader.u8()
                            require(reader.position + size <= end)
                            val text = reader.text(size)
                            val key = text.substringBefore('=').lowercase(Locale.ROOT)
                            require(!containsKey(key))
                            put(key, text.substringAfter('=', ""))
                        }
                    }
                    1 -> {
                        require(length == 4)
                        InetAddress.getByAddress(reader.take(4))
                    }
                    else -> null
                }
                reader.position = end
                if (value != null) add(Record(name, type, value))
            }
        }
    } catch (_: IllegalArgumentException) {
        emptyList()
    } catch (_: IndexOutOfBoundsException) {
        emptyList()
    }

    class Answers {
        private val records = LinkedHashSet<Record>()
        fun add(values: List<Record>) {
            for (value in values) if (records.size < 128) records.add(value)
        }

        private fun instances(): List<List<String>> = records.filter { it.type == 12 && it.name == service }
            .mapNotNull { record ->
                @Suppress("UNCHECKED_CAST")
                (record.value as? List<String>)?.takeIf { it.size == 4 && it.drop(1) == service }
            }.distinct().take(8)

        fun followUpQuestions(): List<Question> = buildList {
            for (instance in instances()) {
                if (records.none { it.name == instance && it.type == 33 }) add(Question(instance, 33))
                if (records.none { it.name == instance && it.type == 16 }) add(Question(instance, 16))
                for (srv in records.filter { it.name == instance && it.type == 33 }.map { it.value as Srv }) {
                    if (records.none { it.name == srv.target && it.type == 1 }) add(Question(srv.target, 1))
                }
            }
        }.distinct().take(16)

        fun endpoints(gateway: InetAddress, targetBluetoothId: String): List<CarPlayBonjourEndpoint> {
            val expected = bluetoothId(targetBluetoothId) ?: return emptyList()
            return instances().flatMap { instance ->
                val matches = records.any { record ->
                    if (record.name != instance || record.type != 16) false else {
                        @Suppress("UNCHECKED_CAST")
                        bluetoothId((record.value as Map<String, String>)["id"]) == expected
                    }
                }
                if (!matches) emptyList() else records.filter { it.name == instance && it.type == 33 }
                    .map { it.value as Srv }.filter { srv ->
                        srv.port in 1..65535 && records.any {
                            it.name == srv.target && it.type == 1 && it.value == gateway
                        }
                    }.map { CarPlayBonjourEndpoint(instance.first(), gateway.hostAddress!!, it.port, expected) }
            }.distinct().take(8)
        }

        fun diagnostic(targetBluetoothId: String): String {
            val expected = bluetoothId(targetBluetoothId)
            val matchingIds = records.count { record ->
                if (record.type != 16) false else {
                    @Suppress("UNCHECKED_CAST")
                    bluetoothId((record.value as Map<String, String>)["id"]) == expected && expected != null
                }
            }
            return "records=${records.size} instances=${instances().size} matchingIds=$matchingIds"
        }
    }

    private class Reader(private val bytes: ByteArray) {
        var position = 0
        init { require(bytes.size in 12..9000) }
        fun u8(): Int { require(position < bytes.size); return bytes[position++].toInt() and 255 }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun skip(size: Int) { require(size >= 0 && position + size <= bytes.size); position += size }
        fun take(size: Int): ByteArray { val start = position; skip(size); return bytes.copyOfRange(start, position) }
        fun text(size: Int): String = take(size).toString(Charsets.UTF_8)
        fun name(): List<String> {
            val labels = ArrayList<String>()
            var cursor = position
            var resume: Int? = null
            val visited = HashSet<Int>()
            var total = 1
            while (true) {
                require(cursor in bytes.indices && visited.add(cursor) && visited.size <= 128)
                val size = bytes[cursor++].toInt() and 255
                if (size and 0xc0 == 0xc0) {
                    require(cursor < bytes.size)
                    if (resume == null) resume = cursor + 1
                    cursor = ((size and 63) shl 8) or (bytes[cursor].toInt() and 255)
                    continue
                }
                require(size <= 63 && cursor + size <= bytes.size)
                if (size == 0) break
                total += size + 1
                require(total <= 255)
                labels.add(bytes.copyOfRange(cursor, cursor + size).toString(Charsets.UTF_8).lowercase(Locale.ROOT))
                cursor += size
            }
            position = resume ?: cursor
            return labels
        }
    }
}
