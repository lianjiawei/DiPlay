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

    internal fun isSupportedInstalledVersion(value: String): Boolean =
        parse(value) != null || (PERSONAL_ONLY.containsMatchIn(value) && revision(value) != null)

    internal fun isNewer(remote: String, installed: String): Boolean {
        val target = parse(remote)
        val current = parse(installed)
        val targetRevision = revision(remote)
        val currentRevision = revision(installed)
        // CorsairPlay 1.x and later replace the retired precision release line. Its revision
        // number must not make an old preview look newer than an installed product release.
        if (currentRevision != null && targetRevision == null && target != null && target.first >= 1) return true
        if (targetRevision != null && currentRevision == null && current != null && current.first >= 1) return false
        // New personal builds omit the upstream base. Keep legacy release tags readable
        // so existing clients can discover the first build with the shorter version name.
        if (PERSONAL_ONLY.containsMatchIn(remote) || PERSONAL_ONLY.containsMatchIn(installed)) {
            return (targetRevision ?: return false) > (currentRevision ?: 0L)
        }
        if (target == null) return false
        if (current == null) return true
        if (target.first != current.first) return target.first > current.first
        if (target.second != current.second) return target.second > current.second
        if (target.third != current.third) return target.third > current.third
        // Preserve the existing beta/RC policy; only this fork's numbered revisions compare
        // within the same upstream version. Tags and the APK's versionName use the same suffix.
        return (targetRevision ?: 0L) > (currentRevision ?: 0L)
    }
}
