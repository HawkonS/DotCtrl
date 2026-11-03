package tech.hawkon.dotctrl.updates

import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<UpdateViewModel>()
    private val release = ReleaseInfo("1.0.8", "Notes", "$RELEASES_URL/tag/v1.0.8", ReleaseApk("$RELEASES_URL/download/v1.0.8/app-release.apk", 100))
    private var sourceCalls = 0
    private var sourceFailure: UpdateError? = null
    private var remote: ReleaseInfo? = release
    private val downloads = Downloads()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { models.forEach { it.setForeground(false) }; Dispatchers.resetMain() }
    private fun model() = UpdateViewModel(object : ReleaseSource {
        override suspend fun latest(): ReleaseInfo? {
            sourceCalls++
            sourceFailure?.let { throw UpdateException(it) }
            return remote
        }
    }, downloads, "1.0.7", dispatcher).also { models += it }

    @Test fun checksOnlyAfterExplicitTapAndCoalescesRepeatedTaps() = runTest(dispatcher) {
        val model = model()
        model.setForeground(true)
        runCurrent()
        assertEquals(0, sourceCalls)
        repeat(4) { model.open() }
        assertEquals(UpdatePhase.CHECKING, model.state.value.phase)
        runCurrent()
        assertEquals(1, sourceCalls)
        assertEquals(UpdatePhase.AVAILABLE, model.state.value.phase)
    }

    @Test fun sameOlderAndMissingReleasesDoNotOfferDowngrades() = runTest(dispatcher) {
        for (version in listOf("1.0.7", "1.0.6", "1.0.0", null)) {
            remote = version?.let { release.copy(version = it) }
            val model = model()
            model.open(); runCurrent()
            assertEquals(UpdatePhase.CURRENT, model.state.value.phase)
            assertNull(model.state.value.release)
            assertEquals(version == null, model.state.value.noRelease)
        }
    }

    @Test fun networkFailureCanBeRetriedAndMissingApkIsNeverDownloaded() = runTest(dispatcher) {
        sourceFailure = UpdateError.NETWORK
        val model = model()
        model.open(); runCurrent()
        assertEquals(UpdateError.NETWORK, model.state.value.error)
        sourceFailure = null
        remote = release.copy(apk = null)
        model.check(); runCurrent()
        assertEquals(UpdatePhase.AVAILABLE, model.state.value.phase)
        model.download()
        assertEquals(0, downloads.starts)
    }

    @Test fun downloadProgressSurvivesDialogCloseAndIsNotEnqueuedTwice() = runTest(dispatcher) {
        val model = model()
        model.setForeground(true)
        model.open(); runCurrent()
        repeat(3) { model.download() }
        model.dismiss(); runCurrent()
        assertEquals(1, downloads.starts)
        assertEquals(10, model.state.value.downloaded)
        assertFalse(model.state.value.dialogVisible)
        downloads.snapshot = DownloadSnapshot(complete = true)
        advanceTimeBy(500); runCurrent()
        assertEquals(UpdatePhase.READY, model.state.value.phase)
        assertEquals(1, downloads.verifications)
        assertTrue(model.state.value.installRequested)
        model.consumeInstallRequest()
        model.setForeground(false); model.setForeground(true); runCurrent()
        assertFalse(model.state.value.installRequested)
    }

    @Test fun backgroundCompletionAndProcessRestoreRequireExplicitInstall() = runTest(dispatcher) {
        val original = model()
        original.setForeground(true)
        original.open(); runCurrent(); original.download(); runCurrent()
        original.setForeground(false)
        downloads.snapshot = DownloadSnapshot(complete = true)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(UpdatePhase.DOWNLOADING, original.state.value.phase)
        val restored = model()
        restored.setForeground(true); runCurrent()
        assertEquals(1, downloads.starts)
        assertEquals(1, sourceCalls)
        assertEquals(UpdatePhase.READY, restored.state.value.phase)
        assertFalse(restored.state.value.installRequested)
        restored.requestInstall()
        assertTrue(restored.state.value.installRequested)
    }

    @Test fun cancellationRemovesTaskAndLateCompletionCannotInstall() = runTest(dispatcher) {
        val model = model()
        model.setForeground(true); model.open(); runCurrent(); model.download(); runCurrent()
        model.cancelDownload()
        downloads.snapshot = DownloadSnapshot(complete = true)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(UpdatePhase.AVAILABLE, model.state.value.phase)
        assertNull(downloads.saved)
        assertEquals(1, downloads.discards)
        assertEquals(0, downloads.verifications)
        assertFalse(model.state.value.installRequested)
    }

    @Test fun rejectedCertificateAndInsufficientSpaceAreRetryableWithoutInstalling() = runTest(dispatcher) {
        val model = model()
        model.setForeground(true); model.open(); runCurrent()
        downloads.startError = UpdateError.NO_SPACE
        model.download()
        assertEquals(UpdateError.NO_SPACE, model.state.value.error)
        downloads.startError = null
        downloads.verifyError = UpdateError.SIGNATURE_MISMATCH
        downloads.snapshot = DownloadSnapshot(complete = true)
        model.download(); runCurrent()
        assertEquals(UpdatePhase.ERROR, model.state.value.phase)
        assertEquals(UpdateError.SIGNATURE_MISMATCH, model.state.value.error)
        assertNull(downloads.saved)
        assertFalse(model.state.value.installRequested)
    }

    @Test fun permissionRefusalAndInstallerCancellationKeepTheVerifiedDownload() = runTest(dispatcher) {
        val model = model()
        model.setForeground(true); model.open(); runCurrent()
        downloads.snapshot = DownloadSnapshot(complete = true)
        model.download(); runCurrent()
        model.consumeInstallRequest()
        model.installPermissionReturned(false)
        assertEquals(UpdatePhase.READY, model.state.value.phase)
        assertEquals(UpdateError.INSTALL_PERMISSION, model.state.value.error)
        assertNotNull(downloads.saved)
        model.installPermissionReturned(true)
        assertTrue(model.state.value.installRequested)
        model.consumeInstallRequest() // Installer opened, then user cancelled.
        model.setForeground(false); model.setForeground(true); runCurrent()
        assertEquals(UpdatePhase.READY, model.state.value.phase)
        assertFalse(model.state.value.installRequested)
        model.requestInstall()
        assertTrue(model.state.value.installRequested)
    }

    @Test fun restoredTaskForAlreadyInstalledVersionIsCleanedUp() = runTest(dispatcher) {
        downloads.saved = DownloadTask(1, release.copy(version = "1.0.7"))
        assertEquals(UpdatePhase.IDLE, model().state.value.phase)
        assertNull(downloads.saved)
        assertEquals(0, sourceCalls)
    }

    private class Downloads : UpdateDownloads {
        var saved: DownloadTask? = null
        var snapshot = DownloadSnapshot(bytes = 10, total = 100)
        var starts = 0
        var discards = 0
        var verifications = 0
        var startError: UpdateError? = null
        var verifyError: UpdateError? = null
        override fun restore() = saved
        override fun start(release: ReleaseInfo): DownloadTask {
            startError?.let { throw UpdateException(it) }
            starts++
            return DownloadTask(starts.toLong(), release).also { saved = it }
        }
        override fun query(task: DownloadTask) = snapshot
        override fun discard(task: DownloadTask) { discards++; saved = null }
        override fun verify(task: DownloadTask) { verifications++; verifyError?.let { throw UpdateException(it) } }
        override fun installerUri(task: DownloadTask): Uri = error("Not needed by state tests")
    }
}
