package com.shilapi.xcertplay.update

import java.net.URL
import java.net.URLConnection
import java.net.URI
import java.io.IOException
import org.json.JSONException

internal object UpdateReleaseLookup {
    fun latest(
        installedVersion: String,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): UpdateRelease? {
        val release = releases(userAgent, openConnection).firstOrNull() ?: return null
        return release.takeIf { UpdateVersion.isNewer(it.tagName, installedVersion) }
    }

    fun history(
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): List<UpdateRelease> {
        return releases(userAgent, openConnection)
    }

    /** Test builds are intentionally manual and never fall back to the public release feed. */
    fun testHistory(userAgent: String, openConnection: (URL) -> URLConnection): List<UpdateRelease> =
        UpdateCatalog.parseAll(UpdateClient.fetchText(
            UpdateClient.TEST_RELEASES_URL, "application/json", userAgent, openConnection,
        )).filter(::safeTestRelease).take(5)

    private fun safeTestRelease(release: UpdateRelease): Boolean {
        val safeName = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,159}")
        if (!safeName.matches(release.tagName) || !safeName.matches(release.apkName) || !release.apkName.endsWith(".apk")) return false
        fun safeUrl(value: String, name: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "weclaw-agent.cloud" && uri.userInfo == null &&
                uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null &&
                Regex("/diplay-updates/test-channel/builds/[A-Za-z0-9][A-Za-z0-9._+-]{0,159}/" +
                    Regex.escape(name)).matches(uri.rawPath)
        }.getOrDefault(false)
        return safeUrl(release.apkUrl, release.apkName) && safeUrl(release.checksumsUrl, UpdateCatalog.CHECKSUMS_FILE) &&
            release.apkUrl.substringBeforeLast('/') == release.checksumsUrl.substringBeforeLast('/') &&
            release.apkUrl.substringBeforeLast('/').substringAfterLast('/') == release.tagName
    }

    private fun releases(userAgent: String, openConnection: (URL) -> URLConnection): List<UpdateRelease> {
        var lastFailure: Exception? = null
        for (source in listOf(UpdateClient.MIRROR_RELEASES_URL, UpdateClient.RELEASES_URL)) {
            try {
                return UpdateCatalog.parseAll(UpdateClient.fetchText(source, "application/json", userAgent, openConnection),
                    includePrereleases = false)
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is JSONException) throw failure
                lastFailure = failure
            }
        }
        throw requireNotNull(lastFailure)
    }
}
