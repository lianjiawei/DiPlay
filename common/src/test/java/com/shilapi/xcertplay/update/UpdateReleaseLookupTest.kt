package com.shilapi.xcertplay.update

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLConnection
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class UpdateReleaseLookupTest {
    private lateinit var server: HttpServer
    private lateinit var root: String
    private val requests = mutableListOf<String>()
    private var mirrorStatus = 200
    private var mirrorBody = catalog("https://updates.example.com/assets")
    private var testBody = "[]"
    private val githubBody = catalog("https://github.com/lianjiawei/DiPlay/releases/download/v1.1.7")

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        root = "http://${server.address.hostString}:${server.address.port}"
        server.createContext("/mirror") { exchange ->
            val bytes = mirrorBody.toByteArray()
            exchange.sendResponseHeaders(mirrorStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/github") { exchange ->
            val bytes = githubBody.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/test") { exchange ->
            val bytes = testBody.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After fun stop() { server.stop(0) }

    private fun connection(url: URL): URLConnection {
        requests += url.toString()
        return URL(root + when (url.toString()) {
            UpdateClient.MIRROR_RELEASES_URL -> "/mirror"
            UpdateClient.RELEASES_URL -> "/github"
            UpdateClient.TEST_RELEASES_URL -> "/test"
            else -> error("Unexpected network request: $url")
        }).openConnection()
    }

    @Test fun latestAndHistoryNeedOnlyTheMirrorWhenItIsAvailable() {
        val release = requireNotNull(UpdateReleaseLookup.latest("1.1.6", "test", ::connection))
        assertTrue(release.apkUrl.startsWith("https://updates.example.com/"))
        assertTrue(release.checksumsUrl.startsWith("https://updates.example.com/"))
        assertEquals(listOf(release), UpdateReleaseLookup.history("test", ::connection))
        assertEquals(listOf(UpdateClient.MIRROR_RELEASES_URL, UpdateClient.MIRROR_RELEASES_URL), requests)
    }

    @Test fun serverFailureFallsBackToTheOriginalFork() {
        mirrorStatus = 503
        val release = requireNotNull(UpdateReleaseLookup.latest("1.1.6", "test", ::connection))
        assertTrue(release.apkUrl.startsWith("https://github.com/lianjiawei/DiPlay/"))
        assertEquals(listOf(UpdateClient.MIRROR_RELEASES_URL, UpdateClient.RELEASES_URL), requests)
    }

    @Test fun invalidMirrorCatalogFallsBackWithoutReportingUpToDate() {
        mirrorBody = "<html>temporary error</html>"
        assertNotNull(UpdateReleaseLookup.latest("1.1.6", "test", ::connection))
        assertEquals(2, requests.size)
    }

    @Test fun bothSourcesFailAsAnErrorInsteadOfReportingUpToDate() {
        try {
            UpdateReleaseLookup.latest("1.1.6", "test") { url ->
                requests += url.toString()
                throw IOException("offline")
            }
            fail("Expected failure")
        } catch (expected: IOException) {
            assertEquals(2, requests.size)
        }
    }

    @Test fun testHistoryUsesOnlySeparateMirrorAndRejectsUnsafeEntries() {
        val base = "https://weclaw-agent.cloud/diplay-updates/test-channel/builds/test-1"
        testBody = """[
          {"tag_name":"test-1","prerelease":true,"assets":[
            {"name":"app.apk","browser_download_url":"$base/app.apk"},
            {"name":"SHA256SUMS.txt","browser_download_url":"$base/SHA256SUMS.txt"}]},
          {"tag_name":"bad","assets":[
            {"name":"../bad.apk","browser_download_url":"$base/../bad.apk"},
            {"name":"SHA256SUMS.txt","browser_download_url":"$base/SHA256SUMS.txt"}]},
          {"tag_name":"wrong-build","assets":[
            {"name":"app.apk","browser_download_url":"$base/app.apk"},
            {"name":"SHA256SUMS.txt","browser_download_url":"$base/SHA256SUMS.txt"}]}
        ]"""
        assertEquals(listOf("test-1"), UpdateReleaseLookup.testHistory("test", ::connection).map { it.tagName })
        assertEquals(listOf(UpdateClient.TEST_RELEASES_URL), requests)
    }

    @Test fun githubFallbackDoesNotExposePrereleasesToStableUsers() {
        mirrorStatus = 503
        val body = """[{"tag_name":"v9.0.0-rc1","prerelease":true,"assets":[
            {"name":"app.apk","browser_download_url":"https://github.com/test/app.apk"},
            {"name":"SHA256SUMS.txt","browser_download_url":"https://github.com/test/SHA256SUMS.txt"}]}]"""
        val standard = catalog("https://github.com/lianjiawei/DiPlay/releases/download/v1.1.7")
        server.removeContext("/github")
        server.createContext("/github") { exchange ->
            val bytes = (body.dropLast(1) + "," + standard.drop(1)).toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        assertEquals("v1.1.7", requireNotNull(UpdateReleaseLookup.latest("1.1.6", "test", ::connection)).tagName)
    }

    private fun catalog(base: String) = """[{"tag_name":"v1.1.7","assets":[
        {"name":"app.apk","browser_download_url":"$base/app.apk"},
        {"name":"SHA256SUMS.txt","browser_download_url":"$base/SHA256SUMS.txt"}
    ]}]"""
}
