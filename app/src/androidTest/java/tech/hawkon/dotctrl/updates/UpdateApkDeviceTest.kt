package tech.hawkon.dotctrl.updates

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.serialization.json.Json

/** Optional device fixtures: a newer same-key APK and a copy signed with a different key. */
@RunWith(AndroidJUnit4::class)
class UpdateApkDeviceTest {
    @Test fun cleansUpManualInstallerFixture() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("cleanupFixture") == "true")
        val version = requireNotNull(arguments.getString("fixtureVersion"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = AndroidUpdateDownloads(context)
        backend.restore()?.let { task ->
            check(task.release.version == version && task.release.notes == "Local device test fixture") {
                "Do not remove a non-test update task"
            }
            backend.discard(task)
        }
        File(context.filesDir, "update-fixtures").deleteRecursively()
    }

    /** Opt-in fixture setup for exercising the real permission/installer UI without publishing a release. */
    @Suppress("DEPRECATION")
    @Test fun stagesCompletedUpdateForManualInstallerTest() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("stageInstaller") == "true")
        val version = requireNotNull(arguments.getString("fixtureVersion"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = AndroidUpdateDownloads(context)
        check(backend.restore() == null) { "Do not replace an existing update task" }
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
        check(directory.isDirectory || directory.mkdirs())
        val source = File(context.filesDir, "update-fixtures/fixture-valid.apk")
        val destination = File(directory, "DotCtrl-$version.apk")
        check(!destination.exists()) { "Do not replace an existing APK" }
        source.copyTo(destination)
        val manager = context.getSystemService(DownloadManager::class.java)
        var id: Long? = null
        try {
            id = manager.addCompletedDownload("DotCtrl installer test", "Local same-signature test fixture", false,
                "application/vnd.android.package-archive", destination.absolutePath, destination.length(), true)
            val task = DownloadTask(id, ReleaseInfo(version, "Local device test fixture", RELEASES_URL,
                ReleaseApk("$RELEASES_URL/download/v$version/fixture.apk", destination.length())))
            assertTrue(backend.query(task).complete)
            backend.verify(task)
            assertTrue(context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit()
                .putString("task", Json.encodeToString(task)).commit())
        } catch (failure: Throwable) {
            id?.let { manager.remove(it) }
            destination.delete()
            throw failure
        }
    }

    @Test fun verifiesRealApksAndSharesTheAcceptedFileWithTheInstaller() {
        val arguments = InstrumentationRegistry.getArguments()
        val version = arguments.getString("fixtureVersion")
        assumeNotNull(version)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
        check(directory.isDirectory || directory.mkdirs())
        val valid = File(context.filesDir, "update-fixtures/fixture-valid.apk")
        val other = File(context.filesDir, "update-fixtures/fixture-other-signature.apk")
        assertTrue("Provide fixture-valid.apk", valid.isFile)
        assertTrue("Provide fixture-other-signature.apk", other.isFile)
        val destination = File(directory, "DotCtrl-$version.apk")
        check(!destination.exists()) { "A cached update already exists; do not overwrite it" }
        val backend = AndroidUpdateDownloads(context)
        fun task(file: File) = DownloadTask(-1, ReleaseInfo(version!!, "Fixture", RELEASES_URL,
            ReleaseApk("$RELEASES_URL/download/v$version/fixture.apk", file.length())))
        try {
            valid.copyTo(destination)
            backend.verify(task(valid))
            val uri = backend.installerUri(task(valid))
            assertEquals("content", uri.scheme)
            context.contentResolver.openInputStream(uri)!!.use { input ->
                assertEquals('P'.code, input.read()); assertEquals('K'.code, input.read())
            }
            other.copyTo(destination, overwrite = true)
            try { backend.verify(task(other)); fail("A different certificate must be rejected") }
            catch (failure: UpdateException) { assertEquals(UpdateError.SIGNATURE_MISMATCH, failure.error) }
        } finally { destination.delete() }
    }
}
