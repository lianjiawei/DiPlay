package com.shilapi.xcertplay.update

internal object UpdateVersion {
    private val TRIPLE = Regex("(\\d+)\\.(\\d+)\\.(\\d+)")
    private val PERSONAL_REVISION = Regex("-precision\\.(\\d+)(?:$|[-+])")
    private val PERSONAL_ONLY = Regex("^v?precision\\.(\\d+)(?:$|[-+])")

    private fun revision(value: String): Long? =
        (PERSONAL_ONLY.find(value) ?: PERSONAL_REVISION.find(value))
            ?.groupValues?.get(1)?.toLongOrNull()

    internal fun parse(value: String): Triple<Int, Int, Int>? =
        TRIPLE.find(value)?.let { match ->
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            Triple(major, minor, patch)
        }

    internal fun isNewer(remote: String, installed: String): Boolean {
        // New personal builds omit the upstream base. Keep legacy release tags readable
        // so existing clients can discover the first build with the shorter version name.
        if (PERSONAL_ONLY.containsMatchIn(remote) || PERSONAL_ONLY.containsMatchIn(installed)) {
            val target = revision(remote) ?: return false
            val current = revision(installed) ?: 0L
            return target > current
        }
        val target = parse(remote) ?: return false
        val current = parse(installed) ?: return true
        if (target.first != current.first) return target.first > current.first
        if (target.second != current.second) return target.second > current.second
        if (target.third != current.third) return target.third > current.third
        // Preserve the existing beta/RC policy; only this fork's numbered revisions compare
        // within the same upstream version. Tags and the APK's versionName use the same suffix.
        return (revision(remote) ?: 0L) > (revision(installed) ?: 0L)
    }
}
