package tech.hawkon.dotctrl.updates

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.FileProvider
import kotlinx.serialization.json.Json
import tech.hawkon.dotctrl.R
import java.io.File
import java.security.MessageDigest

internal class AndroidUpdateDownloads(private val context: Context) : UpdateDownloads {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun restore(): DownloadTask? = runCatching {
        preferences.getString("task", null)?.let { json.decodeFromString<DownloadTask>(it) }
    }.getOrNull()

    private fun file(release: ReleaseInfo): File {
        if (Version.parse(release.version) == null) throw UpdateException(UpdateError.INVALID_RELEASE)
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw UpdateException(UpdateError.NO_SPACE)
        val directory = File(base, "updates")
        if (!directory.exists() && !directory.mkdirs()) throw UpdateException(UpdateError.NO_SPACE)
        return File(directory, "DotCtrl-${release.version}.apk")
    }

    override fun start(release: ReleaseInfo): DownloadTask {
        val apk = release.apk ?: throw UpdateException(UpdateError.INVALID_RELEASE)
        val file = file(release)
        if (StatFs(file.parent!!).availableBytes < apk.size + 2_000_000) throw UpdateException(UpdateError.NO_SPACE)
        if (file.exists() && !file.delete()) throw UpdateException(UpdateError.DOWNLOAD_FAILED)
        val request = DownloadManager.Request(Uri.parse(apk.url))
            .setTitle(context.getString(R.string.update_download_title, release.version))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/${file.name}")
        val task = DownloadTask(manager.enqueue(request), release)
        if (!preferences.edit().putString("task", json.encodeToString(task)).commit()) {
            manager.remove(task.id)
            throw UpdateException(UpdateError.DOWNLOAD_FAILED)
        }
        return task
    }

    override fun query(task: DownloadTask): DownloadSnapshot {
        manager.query(DownloadManager.Query().setFilterById(task.id))?.use { cursor ->
            if (!cursor.moveToFirst()) throw UpdateException(UpdateError.FILE_MISSING)
            fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> return DownloadSnapshot(complete = true)
                DownloadManager.STATUS_FAILED -> throw UpdateException(
                    if (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) == DownloadManager.ERROR_INSUFFICIENT_SPACE)
                        UpdateError.NO_SPACE else UpdateError.DOWNLOAD_FAILED)
                else -> return DownloadSnapshot(bytes = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { it > 0 } ?: task.release.apk!!.size)
            }
        }
        throw UpdateException(UpdateError.DOWNLOAD_FAILED)
    }

    override fun discard(task: DownloadTask) {
        manager.remove(task.id)
        runCatching { file(task.release).delete() }
        preferences.edit().remove("task").commit()
    }

    override fun verify(task: DownloadTask) {
        val file = file(task.release)
        if (!file.isFile) throw UpdateException(UpdateError.FILE_MISSING)
        val asset = task.release.apk ?: throw UpdateException(UpdateError.INVALID_APK)
        if (file.length() != asset.size) throw UpdateException(UpdateError.INVALID_APK)
        asset.sha256?.let { expected ->
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            if (!hash.digest().hex().equals(expected, ignoreCase = true)) throw UpdateException(UpdateError.INVALID_APK)
        }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateException(UpdateError.INVALID_APK)
        if ((candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT)
            throw UpdateException(UpdateError.INVALID_APK)
        verifyIdentity(identity(installed), identity(candidate), task.release.version)
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): PackageIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return PackageIdentity(info.packageName, info.versionName.orEmpty(),
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet())
    }

    override fun installerUri(task: DownloadTask): Uri {
        val file = file(task.release)
        if (!file.isFile) throw UpdateException(UpdateError.FILE_MISSING)
        return FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
