package com.shilapi.xcertplay.lincoln

import com.shilapi.xcertplay.hud.normalizeRouteRoadName

/** Fixed categories only: callers must never retain or log the road-name text itself. */
internal object LincolnRoadNameEvidence {
    fun kind(text: String?): String = when {
        text == null -> "absent"
        text.isBlank() -> "empty"
        normalizeRouteRoadName(text) == null -> "null_literal"
        else -> "text"
    }

    /** Classify a bounded NUL-terminated iAP2 string using the parser's UTF-8/trim convention. */
    fun frameKind(bytes: ByteArray, offset: Int, length: Int): String {
        if (length > MAX_FRAME_BYTES) return "oversized"
        var end = offset
        while (end < offset + length && bytes[end] != 0.toByte()) end++
        return kind(String(bytes, offset, end - offset, Charsets.UTF_8).trim())
    }

    fun wireKind(text: String?): String = when (text) {
        null -> "absent"
        "" -> "empty"
        " " -> "space"
        else -> kind(text)
    }

    private const val MAX_FRAME_BYTES = 1024
}
