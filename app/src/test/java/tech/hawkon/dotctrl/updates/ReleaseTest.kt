package tech.hawkon.dotctrl.updates

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class ReleaseTest {
    @Test fun versionsCompareNumericallyAndRejectPrereleaseOrMalformedTags() {
        assertTrue(Version.parse("v1.0.10")!! > Version.parse("1.0.9")!!)
        assertTrue(Version.parse("2.0.0")!! > Version.parse("1.99.99")!!)
        assertEquals(Version.parse("1.0.7"), Version.parse("V1.0.7"))
        listOf("1.0", "1.0.8-beta", "latest", "1.0.-1", "999999999999999999999.0.1").forEach { assertNull(Version.parse(it)) }
    }

    @Test fun releaseSelectsOnlyOneOfficialApkAndKeepsNotesAndDigest() {
        val result = parseRelease(releaseBody())!!
        assertEquals("1.0.8", result.version)
        assertEquals("Fixes and improvements", result.notes)
        assertEquals(100, result.apk!!.size)
        assertEquals("a".repeat(64), result.apk.sha256)
        assertTrue(result.apk.url.startsWith(RELEASES_URL))
        assertNull(parseRelease(releaseBody(assets = "[]"))!!.apk)
        assertNull(parseRelease(releaseBody(assets = "[$asset,$asset]"))!!.apk)
        assertNull(parseRelease(releaseBody(assets = "[${asset.replace("release.apk", "debug.apk")}]"))!!.apk)
        assertNull(parseRelease(releaseBody(assets = "[${asset.replace("https://github.com", "https://example.com")}]"))!!.apk)
    }

    @Test fun draftAndPrereleaseAreIgnoredAndInvalidTagsAreReported() {
        assertNull(parseRelease(releaseBody().replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(parseRelease(releaseBody().replace("\"draft\":false", "\"draft\":true")))
        assertError(UpdateError.INVALID_RELEASE) { parseRelease(releaseBody().replace("v1.0.8", "latest")) }
        assertError(UpdateError.INVALID_RELEASE) { parseRelease("not JSON") }
    }

    @Test fun httpStatusTimeoutAndGitHubHeadersAreHandled() = runTest {
        for (status in listOf(403, 429, 500)) {
            val connection = Connection(status)
            try { GitHubReleaseSource { connection }.latest(); fail("Expected failure") }
            catch (failure: UpdateException) {
                assertEquals(if (status == 500) UpdateError.NETWORK else UpdateError.RATE_LIMIT, failure.error)
            }
            assertTrue(connection.disconnected)
        }
        assertNull(GitHubReleaseSource { Connection(404) }.latest())
        val success = Connection(200)
        assertEquals("1.0.8", GitHubReleaseSource { success }.latest()!!.version)
        assertEquals("application/vnd.github+json", success.getRequestProperty("Accept"))
        assertTrue(success.getRequestProperty("User-Agent").startsWith("DotCtrl/"))
        assertNull(success.getRequestProperty("Authorization"))
        try { GitHubReleaseSource { Connection(200, timeout = true) }.latest(); fail("Expected timeout") }
        catch (failure: UpdateException) { assertEquals(UpdateError.TIMEOUT, failure.error) }
    }

    @Test fun packageVersionAndSigningCertificateMustMatchBeforeInstallation() {
        val installed = PackageIdentity("tech.hawkon.dotctrl", "1.0.7", 8, setOf("release"))
        val valid = installed.copy(version = "1.0.8", code = 9)
        verifyIdentity(installed, valid, "v1.0.8")
        assertError(UpdateError.WRONG_PACKAGE) { verifyIdentity(installed, valid.copy(name = "other.app"), "1.0.8") }
        assertError(UpdateError.OLD_APK) { verifyIdentity(installed, valid.copy(code = 8), "1.0.8") }
        assertError(UpdateError.OLD_APK) { verifyIdentity(installed, valid.copy(version = "1.0.6"), "1.0.8") }
        assertError(UpdateError.OLD_APK) { verifyIdentity(installed, valid, "1.0.9") }
        assertError(UpdateError.SIGNATURE_MISMATCH) { verifyIdentity(installed, valid.copy(signers = setOf("debug")), "1.0.8") }
        assertError(UpdateError.SIGNATURE_MISMATCH) { verifyIdentity(installed, valid.copy(signers = emptySet()), "1.0.8") }
    }

    private fun assertError(expected: UpdateError, action: () -> Unit) {
        val failure = assertThrows(UpdateException::class.java, action)
        assertEquals(expected, failure.error)
    }

    private class Connection(private val status: Int, private val timeout: Boolean = false) : HttpsURLConnection(URL(RELEASE_API)) {
        var disconnected = false
        override fun getResponseCode() = status
        override fun getInputStream() = if (timeout) throw SocketTimeoutException() else ByteArrayInputStream(releaseBody().toByteArray())
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite() = "TLS"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    companion object {
        private val asset = """{"name":"DotCtrl-1.0.8-release.apk","browser_download_url":"$RELEASES_URL/download/v1.0.8/DotCtrl-1.0.8-release.apk","size":100,"digest":"sha256:${"a".repeat(64)}"}"""
        fun releaseBody(assets: String = "[$asset]") = """{"tag_name":"v1.0.8","html_url":"$RELEASES_URL/tag/v1.0.8","body":"Fixes and improvements","draft":false,"prerelease":false,"assets":$assets}"""
    }
}
