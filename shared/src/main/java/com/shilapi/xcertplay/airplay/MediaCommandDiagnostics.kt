package com.shilapi.xcertplay.airplay

/** Correlates only media-key replies. A transport acknowledgment is not playback confirmation. */
internal class MediaCommandDiagnostics(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val log: (String) -> Unit,
) {
    private data class Pending(val index: Int, val sentAt: Long)
    private val pending = LinkedHashMap<Int, Pending>()

    @Synchronized
    fun sent(cseq: Int, index: Int) {
        if (pending.size >= MAX_PENDING) {
            val oldest = pending.entries.first()
            log("airplay media response cseq=${oldest.key} index=${oldest.value.index} result=unanswered-evicted")
            pending.remove(oldest.key)
        }
        pending[cseq] = Pending(index, nowMillis())
        log("airplay media request cseq=$cseq index=$index awaitingResponse=true")
    }

    @Synchronized
    fun writeFailed(cseq: Int) {
        val command = pending.remove(cseq) ?: return
        log("airplay media response cseq=$cseq index=${command.index} result=write-failed")
    }

    @Synchronized
    fun response(message: RtspMessage.Request) {
        val cseq = message.headers["cseq"]?.toIntOrNull() ?: return
        val command = pending.remove(cseq) ?: return
        val status = message.path.toIntOrNull()
        // Responses may carry a numeric command status even with RTSP 200. Never export the
        // body, application metadata or arbitrary strings received from the phone.
        val decoded = if (message.body.size in 1..MAX_STATUS_BYTES) {
            runCatching { BplistCodec.decode(message.body) as? Map<*, *> }.getOrNull()
        } else null
        val params = decoded?.get("params") as? Map<*, *>
        val commandStatus = (decoded?.get("status") as? Number)?.toLong()
            ?: (params?.get("status") as? Number)?.toLong()
        val errorCode = (decoded?.get("errorCode") as? Number)?.toLong()
        log("airplay media response cseq=$cseq index=${command.index} rtspStatus=${status ?: "unknown"} " +
            "commandStatus=${commandStatus ?: "absent"} errorCode=${errorCode ?: "absent"} " +
            "elapsedMs=${(nowMillis() - command.sentAt).coerceAtLeast(0L)} playbackConfirmed=false")
    }

    @Synchronized
    fun clear() {
        pending.forEach { (cseq, command) ->
            log("airplay media response cseq=$cseq index=${command.index} result=closed-without-response")
        }
        pending.clear()
    }

    companion object {
        private const val MAX_PENDING = 16
        private const val MAX_STATUS_BYTES = 4_096
    }
}

/** A small numeric snapshot; it records ownership evidence without interpreting it as playback. */
internal fun audioModeDiagnostic(params: Map<String, Any?>): String {
    fun entries(key: String, fields: List<String>): String {
        val entries = params[key] as? List<*> ?: return "absent"
        return entries.take(8).joinToString(prefix = "[", postfix = "]") { item ->
            val entry = item as? Map<*, *> ?: return@joinToString "invalid"
            fields.mapNotNull { field ->
                when (val value = entry[field]) {
                    is Number -> "$field=${value.toLong()}"
                    is Boolean -> "$field=$value"
                    else -> null
                }
            }.joinToString(prefix = "{", postfix = "}")
        }
    }
    val apps = entries("appStates", listOf("appStateID", "entity", "speechMode", "state"))
    val resources = entries("resources", listOf("resourceID", "entity", "owner", "borrower",
        "transferType", "transferPriority", "takeConstraint", "borrowConstraint", "unborrowConstraint"))
    return "airplay audio modes appStates=$apps resources=$resources"
}
