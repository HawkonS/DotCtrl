package tech.hawkon.dotctrl.updates

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tech.hawkon.dotctrl.BuildConfig
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class GitHubReleaseSource(
    private val connect: () -> HttpsURLConnection = { URL(RELEASE_API).openConnection() as HttpsURLConnection },
) : ReleaseSource {
    override suspend fun latest(): ReleaseInfo? {
        val connection = connect()
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "DotCtrl/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            when (connection.responseCode) {
                404 -> return null
                403, 429 -> throw UpdateException(UpdateError.RATE_LIMIT)
                200 -> Unit
                else -> throw UpdateException(UpdateError.NETWORK)
            }
            val body = connection.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    text.append(buffer, 0, count)
                    if (text.length > 2_000_000) throw UpdateException(UpdateError.INVALID_RELEASE)
                }
                text.toString()
            }
            return parseRelease(body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: SocketTimeoutException) {
            throw UpdateException(UpdateError.TIMEOUT)
        } catch (failure: IOException) {
            throw UpdateException(UpdateError.NETWORK)
        } finally {
            connection.disconnect()
        }
    }
}

@Serializable
private data class ApiRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val page: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ApiAsset> = emptyList(),
)

@Serializable
private data class ApiAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long,
    val digest: String? = null,
)

private val releaseJson = Json { ignoreUnknownKeys = true }

internal fun parseRelease(body: String): ReleaseInfo? {
    val release = try { releaseJson.decodeFromString<ApiRelease>(body) }
    catch (failure: IllegalArgumentException) { throw UpdateException(UpdateError.INVALID_RELEASE) }
    if (release.draft || release.prerelease) return null
    if (Version.parse(release.tag) == null || !release.page.startsWith("$RELEASES_URL/tag/"))
        throw UpdateException(UpdateError.INVALID_RELEASE)
    val apk = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }.singleOrNull()?.takeIf {
        it.size > 0 && it.url.startsWith("$RELEASES_URL/download/") && !it.name.contains("debug", ignoreCase = true)
    }?.let { asset ->
        val digest = asset.digest?.takeIf { Regex("sha256:[a-fA-F0-9]{64}").matches(it) }?.substringAfter(':')
        ReleaseApk(asset.url, asset.size, digest)
    }
    return ReleaseInfo(release.tag.removePrefix("v").removePrefix("V"), release.body.orEmpty(), release.page, apk)
}
