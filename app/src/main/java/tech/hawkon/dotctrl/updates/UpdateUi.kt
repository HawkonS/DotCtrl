package tech.hawkon.dotctrl.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.hawkon.dotctrl.BuildConfig
import tech.hawkon.dotctrl.R
import tech.hawkon.dotctrl.service.AutoClickAccessibilityService
import tech.hawkon.dotctrl.ui.SettingsRow

@Composable
internal fun UpdateSettingsRow(model: UpdateViewModel) {
    val context = LocalContext.current
    SettingsRow(context.getString(R.string.ui_version), BuildConfig.VERSION_NAME,
        Icons.Outlined.Info, onClick = model::open)
}

/** Kept at the activity root so navigation does not interrupt downloads or permission results. */
@Composable
internal fun UpdateHost(model: UpdateViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state by model.state.collectAsStateWithLifecycle()
    var permissionResultPending by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionResultPending = true
    }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Cancellation leaves the verified file available for another explicit install attempt.
    }
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.setForeground(true)
            if (event == Lifecycle.Event.ON_PAUSE) model.setForeground(false)
        }
        owner.lifecycle.addObserver(observer)
        model.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { owner.lifecycle.removeObserver(observer); model.setForeground(false) }
    }
    LaunchedEffect(permissionResultPending, state.phase) {
        if (permissionResultPending && state.phase == UpdatePhase.READY) {
            permissionResultPending = false
            model.installPermissionReturned(context.packageManager.canRequestPackageInstalls())
        }
    }
    LaunchedEffect(state.installRequestId) {
        if (state.installRequested) {
            model.consumeInstallRequest()
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                try {
                    val uri = model.installerUri()
                    AutoClickAccessibilityService.instance?.stop(context.getString(R.string.update_stopped_for_install))
                    if (!context.packageManager.canRequestPackageInstalls()) {
                        permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    } else {
                        installer.launch(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                } catch (failure: Exception) {
                    model.installFailed((failure as? UpdateException)?.error ?: UpdateError.INSTALL_UNAVAILABLE)
                }
            }
        }
    }
    if (state.dialogVisible) {
        UpdateDialog(state, model::dismiss, model::download, model::cancelDownload, model::requestInstall, model::check,
            onReleasePage = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.release?.pageUrl ?: RELEASES_URL))) }
                    .onFailure { model.installFailed(UpdateError.INSTALL_UNAVAILABLE) }
            })
    }
}

@Composable
internal fun UpdateDialog(state: UpdateState, onDismiss: () -> Unit, onDownload: () -> Unit,
    onCancel: () -> Unit, onInstall: () -> Unit, onCheck: () -> Unit, onReleasePage: () -> Unit) {
    val context = LocalContext.current
    val bodyHeight = (LocalConfiguration.current.screenHeightDp.dp - 240.dp).coerceIn(80.dp, 180.dp)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        title = { Text(context.getString(R.string.update_check)) },
        text = {
            Column(Modifier.fillMaxWidth().height(bodyHeight).verticalScroll(rememberScrollState()).heightIn(min = bodyHeight),
                verticalArrangement = Arrangement.spacedBy(12.dp,
                    if (state.release == null) Alignment.CenterVertically else Alignment.Top)) {
                state.release?.let { release ->
                    Text(context.getString(R.string.update_version_found, release.version), style = MaterialTheme.typography.titleMedium)
                    release.apk?.let { Text(Formatter.formatShortFileSize(context, it.size)) }
                }
                when (state.phase) {
                    UpdatePhase.IDLE, UpdatePhase.CHECKING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(context.getString(R.string.update_checking))
                    }
                    UpdatePhase.CURRENT -> Text(context.getString(if (state.noRelease) R.string.update_no_release else R.string.update_current))
                    UpdatePhase.AVAILABLE -> {
                        Text(state.release?.notes?.ifBlank { context.getString(R.string.update_no_notes) }.orEmpty())
                        if (state.release?.apk == null) Text(context.getString(R.string.update_no_apk))
                    }
                    UpdatePhase.DOWNLOADING -> {
                        if (state.total > 0) LinearProgressIndicator(
                            progress = { (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(context.getString(R.string.update_download_progress, Formatter.formatShortFileSize(context, state.downloaded),
                            Formatter.formatShortFileSize(context, state.total.coerceAtLeast(0))))
                        Text(context.getString(R.string.update_background_hint))
                    }
                    UpdatePhase.VERIFYING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(context.getString(R.string.update_verifying))
                    }
                    UpdatePhase.READY -> Text(context.getString(R.string.update_ready))
                    UpdatePhase.ERROR -> Unit
                }
                state.error?.let { Text(context.getString(it.messageRes()), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.AVAILABLE -> if (state.release?.apk != null) {
                    Button(onClick = onDownload) { Text(context.getString(R.string.update_download_install)) }
                } else TextButton(onClick = onReleasePage) { Text(context.getString(R.string.update_release_page)) }
                UpdatePhase.DOWNLOADING -> TextButton(onClick = onCancel) { Text(context.getString(R.string.update_cancel_download)) }
                UpdatePhase.READY -> Button(onClick = onInstall) { Text(context.getString(R.string.update_install)) }
                UpdatePhase.ERROR -> TextButton(onClick = {
                    if (state.release?.apk != null && state.error?.canRetryDownload == true) onDownload() else onCheck()
                }) { Text(context.getString(R.string.update_retry)) }
                UpdatePhase.CURRENT -> TextButton(onClick = onDismiss) { Text(context.getString(R.string.ui_close)) }
                else -> Unit
            }
        },
        dismissButton = {
            Row {
                if (state.phase == UpdatePhase.ERROR && state.release != null)
                    TextButton(onClick = onReleasePage) { Text(context.getString(R.string.update_release_page)) }
                if (state.phase != UpdatePhase.CURRENT)
                    TextButton(onClick = onDismiss) { Text(context.getString(R.string.update_later)) }
            }
        },
    )
}

internal fun UpdateError.messageRes(): Int = when (this) {
    UpdateError.NETWORK -> R.string.update_error_network
    UpdateError.TIMEOUT -> R.string.update_error_timeout
    UpdateError.RATE_LIMIT -> R.string.update_error_rate_limit
    UpdateError.INVALID_RELEASE -> R.string.update_error_release
    UpdateError.DOWNLOAD_FAILED -> R.string.update_error_download
    UpdateError.NO_SPACE -> R.string.update_error_space
    UpdateError.FILE_MISSING -> R.string.update_error_missing
    UpdateError.INVALID_APK -> R.string.update_error_apk
    UpdateError.WRONG_PACKAGE -> R.string.update_error_package
    UpdateError.OLD_APK -> R.string.update_error_old
    UpdateError.SIGNATURE_MISMATCH -> R.string.update_error_signature
    UpdateError.INSTALL_PERMISSION -> R.string.update_error_permission
    UpdateError.INSTALL_UNAVAILABLE -> R.string.update_error_install
}
