package tech.hawkon.dotctrl.updates

import android.app.DownloadManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class AndroidUpdateDownloadsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val release = ReleaseInfo("1.0.8", "Notes", "$RELEASES_URL/tag/v1.0.8",
        ReleaseApk("$RELEASES_URL/download/v1.0.8/app-release.apk", 4))

    @Test fun downloadUsesPrivateDirectoryPersistsTaskAndRemovesItOnCancel() {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        ShadowStatFs.registerStats(directory.absolutePath, 100_000, 90_000, 80_000)
        val backend = AndroidUpdateDownloads(context)
        val task = backend.start(release)
        assertEquals(task, AndroidUpdateDownloads(context).restore())
        val manager = Shadow.extract<ShadowDownloadManager>(context.getSystemService(DownloadManager::class.java))
        val request = Shadow.extract<ShadowDownloadManager.ShadowRequest>(manager.getRequest(task.id))
        assertEquals(release.apk!!.url, request.uri.toString())
        assertTrue(request.destination.path!!.endsWith("/Download/updates/DotCtrl-1.0.8.apk"))
        assertEquals("application/vnd.android.package-archive", request.mimeType)
        request.setStatus(DownloadManager.STATUS_RUNNING)
        request.setTotalSize(4); request.setBytesSoFar(2)
        assertEquals(DownloadSnapshot(bytes = 2, total = 4), backend.query(task))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        assertTrue(backend.query(task).complete)
        backend.discard(task)
        assertNull(backend.restore())
        assertNull(manager.getRequest(task.id))
    }

    @Test fun packageInspectionChecksVersionAndSignatures() {
        val backend = AndroidUpdateDownloads(context)
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates/DotCtrl-1.0.8.apk")
        file.parentFile!!.mkdirs(); file.writeText("test")
        val packages = Shadow.extract<ShadowPackageManager>(context.packageManager)
        packages.installPackage(info("1.0.7", 8, "001122"))
        packages.setPackageArchiveInfo(file.absolutePath, info("1.0.8", 9, "001122"))
        val task = DownloadTask(1, release)
        backend.verify(task)
        backend.verify(task.copy(release = release.copy(apk = release.apk!!.copy(
            sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"))))
        assertEquals(UpdateError.INVALID_APK, assertThrows(UpdateException::class.java) {
            backend.verify(task.copy(release = release.copy(apk = release.apk!!.copy(sha256 = "0".repeat(64)))))
        }.error)
        packages.setPackageArchiveInfo(file.absolutePath, info("1.0.8", 9, "334455"))
        assertEquals(UpdateError.SIGNATURE_MISMATCH,
            assertThrows(UpdateException::class.java) { backend.verify(task) }.error)
        packages.setPackageArchiveInfo(file.absolutePath, info("1.0.8", 9, "001122").apply { packageName = "other.app" })
        assertEquals(UpdateError.WRONG_PACKAGE,
            assertThrows(UpdateException::class.java) { backend.verify(task) }.error)
        file.delete()
        assertEquals(UpdateError.FILE_MISSING,
            assertThrows(UpdateException::class.java) { backend.verify(task) }.error)
    }

    @Test fun fileProviderSharesOnlyUpdateDirectory() {
        val backend = AndroidUpdateDownloads(context)
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates/DotCtrl-1.0.8.apk")
        file.parentFile!!.mkdirs(); file.writeText("test")
        val uri = backend.installerUri(DownloadTask(1, release))
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.updates", uri.authority)
        assertTrue(uri.path!!.startsWith("/updates/"))
        assertEquals("test", context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.filesDir, "scripts.json"))
        }
    }

    @Suppress("DEPRECATION")
    private fun info(version: String, code: Int, certificate: String) = PackageInfo().apply {
        packageName = context.packageName; versionName = version; versionCode = code
        applicationInfo = ApplicationInfo().apply { packageName = context.packageName; minSdkVersion = 26 }
        signatures = arrayOf(Signature(certificate))
        if (Build.VERSION.SDK_INT >= 28) signingInfo = SigningInfo().also {
            Shadow.extract<ShadowSigningInfo>(it).setSignatures(signatures)
        }
    }
}
