package com.shilapi.xcertplay.update

import java.net.URL
import java.net.URLConnection
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

    private fun releases(userAgent: String, openConnection: (URL) -> URLConnection): List<UpdateRelease> {
        var lastFailure: Exception? = null
        for (source in listOf(UpdateClient.MIRROR_RELEASES_URL, UpdateClient.RELEASES_URL)) {
            try {
                return UpdateCatalog.parseAll(UpdateClient.fetchText(source, "application/json", userAgent, openConnection))
            } catch (failure: Exception) {
                if (failure !is IOException && failure !is JSONException) throw failure
                lastFailure = failure
            }
        }
        throw requireNotNull(lastFailure)
    }
}
