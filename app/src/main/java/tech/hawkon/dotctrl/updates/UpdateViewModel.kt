package tech.hawkon.dotctrl.updates

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tech.hawkon.dotctrl.BuildConfig

class UpdateViewModel internal constructor(
    private val source: ReleaseSource,
    private val downloads: UpdateDownloads,
    private val currentVersion: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private var task: DownloadTask? = downloads.restore()
    private var foreground = false
    private var autoInstall = false
    private var monitor: Job? = null
    private var checking: Job? = null
    private var installSequence = 0L

    init {
        task?.let { saved ->
            val target = Version.parse(saved.release.version)
            if (target == null || target <= requireNotNull(Version.parse(currentVersion))) {
                runCatching { downloads.discard(saved) }
                task = null
            } else mutable.value = UpdateState(phase = UpdatePhase.DOWNLOADING, release = saved.release)
        }
    }

    fun setForeground(active: Boolean) {
        foreground = active
        if (!active) {
            autoInstall = false
            mutable.update { it.copy(installRequested = false) }
            monitor?.cancel(); monitor = null
        } else if (task != null && state.value.phase == UpdatePhase.DOWNLOADING) monitorDownload()
        else if (task != null && state.value.phase == UpdatePhase.VERIFYING) monitorDownload()
    }

    fun open() {
        mutable.update { it.copy(dialogVisible = true) }
        if (state.value.phase in setOf(UpdatePhase.IDLE, UpdatePhase.CURRENT) ||
            (state.value.phase == UpdatePhase.ERROR && state.value.release == null)) check()
    }

    fun dismiss() { mutable.update { it.copy(dialogVisible = false) } }

    fun check() {
        if (checking?.isActive == true || task != null) return
        mutable.update { UpdateState(phase = UpdatePhase.CHECKING, dialogVisible = it.dialogVisible) }
        checking = viewModelScope.launch {
            try {
                val release = withContext(io) { source.latest() }
                val newer = release != null && requireNotNull(Version.parse(release.version)) > requireNotNull(Version.parse(currentVersion))
                mutable.update { it.copy(phase = if (newer) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT,
                    release = release.takeIf { newer }, noRelease = release == null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { fail(failure, UpdateError.NETWORK) }
        }
    }

    fun download() {
        val release = state.value.release ?: return
        if (task != null || release.apk == null || state.value.phase !in setOf(UpdatePhase.AVAILABLE, UpdatePhase.ERROR)) return
        try {
            task = downloads.start(release)
            autoInstall = foreground
            mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, error = null, downloaded = 0, total = release.apk.size) }
            monitorDownload()
        } catch (failure: Exception) { fail(failure, UpdateError.DOWNLOAD_FAILED) }
    }

    fun cancelDownload() {
        val saved = task ?: return
        monitor?.cancel(); monitor = null
        autoInstall = false
        task = null
        runCatching { downloads.discard(saved) }
        mutable.update { it.copy(phase = UpdatePhase.AVAILABLE, error = null, downloaded = 0, installRequested = false) }
    }

    private fun monitorDownload() {
        if (!foreground || monitor?.isActive == true) return
        monitor = viewModelScope.launch {
            try {
                while (isActive) {
                    val saved = task ?: break
                    val snapshot = withContext(io) { downloads.query(saved) }
                    if (snapshot.complete) {
                        mutable.update { it.copy(phase = UpdatePhase.VERIFYING) }
                        withContext(io) { downloads.verify(saved) }
                        val install = autoInstall && foreground
                        val requestId = if (install) ++installSequence else state.value.installRequestId
                        mutable.update { it.copy(phase = UpdatePhase.READY, error = null, installRequested = install, installRequestId = requestId) }
                        autoInstall = false
                        break
                    }
                    mutable.update { it.copy(phase = UpdatePhase.DOWNLOADING, downloaded = snapshot.bytes, total = snapshot.total) }
                    delay(500)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                task?.let { saved -> withContext(io) { runCatching { downloads.discard(saved) } } }
                task = null
                autoInstall = false
                fail(failure, UpdateError.DOWNLOAD_FAILED)
            }
        }
    }

    fun requestInstall() {
        if (task != null && state.value.phase == UpdatePhase.READY) {
            val requestId = ++installSequence
            mutable.update { it.copy(installRequested = true, installRequestId = requestId, error = null) }
        }
    }

    fun consumeInstallRequest() { mutable.update { it.copy(installRequested = false) } }

    fun installPermissionReturned(allowed: Boolean) {
        if (allowed) requestInstall() else installFailed(UpdateError.INSTALL_PERMISSION)
    }

    fun installFailed(error: UpdateError) {
        if (error == UpdateError.FILE_MISSING) {
            task?.let { runCatching { downloads.discard(it) } }
            task = null
            mutable.update { it.copy(phase = UpdatePhase.ERROR) }
        }
        mutable.update { it.copy(error = error, installRequested = false, dialogVisible = true) }
    }

    internal fun installerUri() = downloads.installerUri(task ?: throw UpdateException(UpdateError.FILE_MISSING))

    private fun fail(failure: Exception, fallback: UpdateError) {
        mutable.update { it.copy(phase = UpdatePhase.ERROR, error = (failure as? UpdateException)?.error ?: fallback, installRequested = false) }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(
                GitHubReleaseSource(), AndroidUpdateDownloads(context.applicationContext), BuildConfig.VERSION_NAME) as T
        }
    }
}
