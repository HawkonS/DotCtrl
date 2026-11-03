package tech.hawkon.dotctrl.updates

import android.net.Uri
import kotlinx.serialization.Serializable

const val RELEASES_URL = "https://github.com/HawkonS/DotCtrl/releases"
internal const val RELEASE_API = "https://api.github.com/repos/HawkonS/DotCtrl/releases/latest"

internal data class Version(val major: Long, val minor: Long, val patch: Long) : Comparable<Version> {
    override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)
    companion object {
        fun parse(text: String): Version? {
            val parts = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(text.trim())?.groupValues ?: return null
            return Version(parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null,
                parts[3].toLongOrNull() ?: return null)
        }
    }
}

@Serializable
data class ReleaseApk(val url: String, val size: Long, val sha256: String? = null)

@Serializable
data class ReleaseInfo(val version: String, val notes: String, val pageUrl: String, val apk: ReleaseApk?)

@Serializable
internal data class DownloadTask(val id: Long, val release: ReleaseInfo)

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }
enum class UpdateError {
    NETWORK, TIMEOUT, RATE_LIMIT, INVALID_RELEASE, DOWNLOAD_FAILED, NO_SPACE, FILE_MISSING,
    INVALID_APK, WRONG_PACKAGE, OLD_APK, SIGNATURE_MISMATCH, INSTALL_PERMISSION, INSTALL_UNAVAILABLE;

    val canRetryDownload get() = this in setOf(DOWNLOAD_FAILED, NO_SPACE, FILE_MISSING, NETWORK, TIMEOUT)
}

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: ReleaseInfo? = null,
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: UpdateError? = null,
    val noRelease: Boolean = false,
    val dialogVisible: Boolean = false,
    val installRequested: Boolean = false,
    val installRequestId: Long = 0,
)

internal class UpdateException(val error: UpdateError) : Exception(error.name)
internal interface ReleaseSource { suspend fun latest(): ReleaseInfo? }
internal data class DownloadSnapshot(val complete: Boolean = false, val bytes: Long = 0, val total: Long = 0)
internal interface UpdateDownloads {
    fun restore(): DownloadTask?
    fun start(release: ReleaseInfo): DownloadTask
    fun query(task: DownloadTask): DownloadSnapshot
    fun discard(task: DownloadTask)
    fun verify(task: DownloadTask)
    fun installerUri(task: DownloadTask): Uri
}

internal data class PackageIdentity(val name: String, val version: String, val code: Long, val signers: Set<String>)

internal fun verifyIdentity(installed: PackageIdentity, candidate: PackageIdentity, target: String) {
    if (candidate.name != installed.name) throw UpdateException(UpdateError.WRONG_PACKAGE)
    val version = Version.parse(candidate.version)
    if (version == null || version != Version.parse(target) || candidate.code <= installed.code ||
        version <= (Version.parse(installed.version) ?: throw UpdateException(UpdateError.INVALID_APK))) {
        throw UpdateException(UpdateError.OLD_APK)
    }
    if (candidate.signers.isEmpty() || candidate.signers != installed.signers) throw UpdateException(UpdateError.SIGNATURE_MISMATCH)
}
