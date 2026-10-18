package com.shilapi.xcertplay.update

internal object UpdateVersion {
    private val TRIPLE = Regex("(\\d+)\\.(\\d+)\\.(\\d+)")
    private val PERSONAL_REVISION = Regex("-precision\\.(\\d+)(?:$|[-+])")

    internal fun parse(value: String): Triple<Int, Int, Int>? =
        TRIPLE.find(value)?.let { match ->
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            Triple(major, minor, patch)
        }

    internal fun isNewer(remote: String, installed: String): Boolean {
        val target = parse(remote) ?: return false
        val current = parse(installed) ?: return true
        if (target.first != current.first) return target.first > current.first
        if (target.second != current.second) return target.second > current.second
        if (target.third != current.third) return target.third > current.third
        // Preserve the existing beta/RC policy; only this fork's numbered revisions compare
        // within the same upstream version. Tags and the APK's versionName use the same suffix.
        fun revision(value: String) = PERSONAL_REVISION.find(value)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return revision(remote) > revision(installed)
    }
}
