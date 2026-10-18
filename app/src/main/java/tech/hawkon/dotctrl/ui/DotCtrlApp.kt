package tech.hawkon.dotctrl.ui

import tech.hawkon.dotctrl.R
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.hawkon.dotctrl.data.*
import tech.hawkon.dotctrl.BuildConfig
import tech.hawkon.dotctrl.service.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val DOTCTRL_REPOSITORY_URL = "https://github.com/HawkonS/DotCtrl"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DotCtrlApp(
    onBackground: () -> Unit,
    onExit: () -> Unit,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    language: AppLanguage = AppLanguage.CHINESE,
    onLanguageChange: (AppLanguage) -> Unit = {},
    overlayTransparency: Float = 0f,
    onOverlayTransparencyChange: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    val repository = remember { ScriptRepository(context) }
    val settingsStore = remember { ClickSettingsStore(context) }
    val presetStore = remember { PresetStore(context) }
    var presets by remember { mutableStateOf(PresetLibrary()) }
    var presetFailure by remember { mutableStateOf<String?>(null) }
    var single by remember { mutableStateOf(settingsStore.read()) }
    var scripts by remember { mutableStateOf(emptyList<ClickScript>()) }
    var loadFailure by remember { mutableStateOf<String?>(null) }
    var overlayAllowed by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedScriptId by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var quickSetup by rememberSaveable { mutableStateOf(false) }
    var pendingPermission by rememberSaveable { mutableStateOf<String?>(null) }
    var returnedFromSettings by rememberSaveable { mutableStateOf(false) }
    var configExportText by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var scriptsPage by rememberSaveable { mutableStateOf(false) }
    var permissionPageManual by rememberSaveable { mutableStateOf(false) }
    var permissionReturnPage by rememberSaveable { mutableIntStateOf(0) }
    val status by RuntimeState.status.collectAsStateWithLifecycle()
    val editRequest by RuntimeState.editRequests.collectAsStateWithLifecycle()
    var handledEdit by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current

    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    fun reload() {
        single = settingsStore.read()
        runCatching { presetStore.read() }.onSuccess { presets = it; presetFailure = null }
            .onFailure { presetFailure = context.getString(R.string.ui_could_not_load_presets_your_data_has_been_preserved) }
        runCatching {
            val stored = repository.load()
            ensureBuiltInScripts(stored, context.screenArea().orientation).also {
                if (it != stored) repository.save(it)
            }
        }.onSuccess {
            scripts = it
            if (selectedScriptId == null || it.none { script -> script.id == selectedScriptId }) {
                selectedScriptId = it.firstOrNull { script -> script.builtIn }?.id ?: it.firstOrNull()?.id
            }
            loadFailure = null
        }
            .onFailure { loadFailure = context.getString(R.string.ui_could_not_read_the_script_file_the_original_file_has_been_preserved, context.errorText(it.message)) }
        overlayAllowed = AndroidSettings.canDrawOverlays(context)
    }
    fun saveSingle(updated: ClickSettings): Boolean = runCatching {
        settingsStore.write(updated)
        single = updated
        AutoClickAccessibilityService.instance?.configurationChanged()
    }.onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_save)) }.isSuccess
    fun savePresets(updated: PresetLibrary): Boolean = runCatching {
        check(presetFailure == null) { presetFailure.orEmpty() }
        presetStore.write(updated)
        presets = updated
        AutoClickAccessibilityService.instance?.configurationChanged()
    }.onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_save)) }.isSuccess
    fun saveScripts(updated: List<ClickScript>) {
        if (loadFailure != null) { message(context.getString(R.string.ui_repair_the_local_script_file_before_saving)); return }
        runCatching { repository.save(updated) }.onSuccess { scripts = updated }
            .onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_save)) }
    }
    fun pickPresetPosition(requestId: String, initial: ScreenPoint?): Boolean {
        if (!AndroidSettings.canDrawOverlays(context) || !status.connected) {
            page = 2
            permissionPageManual = false
            return false
        }
        return runCatching {
            val service = AutoClickAccessibilityService.instance ?: error(context.getString(R.string.ui_the_service_is_not_connected_try_again_shortly))
            service.pickPresetPosition(requestId, initial)
            onBackground()
        }.onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_open_the_position_picker)) }.isSuccess
    }
    fun updateScript(script: ClickScript) {
        scripts = scripts.map { if (it.id == script.id) script else it }
    }
    fun showOverlay(id: String?, autoPickSingle: Boolean = false) {
        runCatching {
            check(overlayAllowed && status.connected) { context.getString(R.string.ui_enable_accessibility_and_overlay_permissions_first) }
            if (id == null) settingsStore.write(single) else {
                val script = scripts.first { it.id == id }
                script.validate()
                check(loadFailure == null) { context.getString(R.string.ui_the_local_script_file_is_invalid_and_cannot_be_overwritten) }
                repository.upsert(script)
            }
            val service = AutoClickAccessibilityService.instance ?: error(context.getString(R.string.ui_the_service_is_not_connected_try_again_shortly))
            service.prepare(id, autoPickSingle = autoPickSingle)
            if (RuntimeState.status.value.phase != RunPhase.ERROR) onBackground()
        }.onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_show_the_floating_window)) }
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reload() }
        owner.lifecycle.addObserver(observer)
        reload()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val permissionSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        reload()
        returnedFromSettings = true
    }

    val configImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val imported = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count == -1) break
                            require(output.size() + count <= ConfigCodec.MAX_BYTES) { context.getString(R.string.ui_the_file_must_be_smaller_than_1_mb) }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error(context.getString(R.string.ui_could_not_open_the_file))
                    ConfigCodec.decode(bytes.toString(Charsets.UTF_8))
                }
                check(presetFailure == null) { context.getString(R.string.ui_local_presets_are_invalid_and_cannot_be_overwritten) }
                check(loadFailure == null) { context.getString(R.string.ui_local_scripts_are_invalid_and_cannot_be_overwritten) }
                val scriptsWithBuiltIn = ensureBuiltInScripts(imported.scripts, context.screenArea().orientation)
                withContext(Dispatchers.IO) {
                    presetStore.write(imported.presets)
                    repository.save(scriptsWithBuiltIn)
                }
                presets = imported.presets
                scripts = scriptsWithBuiltIn
                editingId = null
                selectedScriptId = scriptsWithBuiltIn.firstOrNull()?.id
                message(context.getString(R.string.ui_imported_positions_modes_and_scripts, imported.presets.positions.size, imported.presets.modes.size, scriptsWithBuiltIn.size))
            }.onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_invalid_file_format)) }
        }
    }
    val configExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(configExportText) }
                        ?: error(context.getString(R.string.ui_could_not_write_the_file))
                }
                message(context.getString(R.string.ui_presets_exported))
            }.onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_export)) }
        }
    }

    val permissionsReady = status.connected && overlayAllowed
    // Gate the content synchronously so a missing permission never flashes the workbench.
    val visiblePage = if (permissionsReady) page else 2
    LaunchedEffect(permissionsReady) {
        if (!permissionsReady) {
            permissionPageManual = false
            page = 2
        } else if (page == 2 && !permissionPageManual) {
            page = 0
        }
        if (permissionsReady) {
            quickSetup = false
            pendingPermission = null
            permissionDialog = null
        }
    }
    LaunchedEffect(quickSetup, returnedFromSettings, pendingPermission, status.connected, overlayAllowed) {
        if (!quickSetup || !returnedFromSettings || pendingPermission == null) return@LaunchedEffect
        // The service may bind just after Android returns from its settings screen.
        val granted = if (pendingPermission == "accessibility") status.connected else overlayAllowed
        if (!granted) delay(800)
        returnedFromSettings = false
        pendingPermission = null
        if (granted) {
            permissionDialog = when {
                !status.connected -> "accessibility"
                !overlayAllowed -> "overlay"
                else -> null
            }
            quickSetup = permissionDialog != null
        } else {
            // A cancelled system screen stays here; never reopen it in a loop.
            quickSetup = false
        }
    }
    LaunchedEffect(editRequest, permissionsReady) {
        if (editRequest > handledEdit && permissionsReady) { handledEdit = editRequest; page = 0 }
    }
    BackHandler(enabled = visiblePage == 1 || (visiblePage == 2 && permissionsReady)) {
        if (visiblePage == 1 && scriptsPage) {
            val editing = scripts.firstOrNull { it.id == editingId }
            if (editing == null) scriptsPage = false
            else runCatching { editing.validate(); repository.upsert(editing) }
                .onSuccess { editingId = null }
                .onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_correct_the_script_settings_first)) }
        } else page = if (visiblePage == 2) permissionReturnPage else 0
        permissionPageManual = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (visiblePage == 2) {
                if (permissionsReady) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp)) {
                    IconButton(onClick = { page = permissionReturnPage; permissionPageManual = false }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, context.getString(R.string.ui_back))
                    }
                }
            } else AppTopCard(
                page = visiblePage,
                settingsTitle = if (scriptsPage) {
                    if (editingId == null) context.getString(R.string.ui_script_actions) else context.getString(R.string.ui_edit_script)
                } else context.getString(R.string.ui_settings),
                onSettingsBack = if (visiblePage == 1 && scriptsPage) ({
                    val editing = scripts.firstOrNull { it.id == editingId }
                    if (editing == null) scriptsPage = false
                    else runCatching { editing.validate(); repository.upsert(editing) }
                        .onSuccess { editingId = null }
                        .onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_correct_the_script_settings_first)) }
                }) else null,
                running = status.active,
                permissionsReady = status.connected && overlayAllowed,
                onPermissions = {
                    permissionReturnPage = page
                    permissionPageManual = true
                    page = 2
                },
            )
        },
        bottomBar = {
            if (visiblePage == 2) {
                PermissionActions(
                    ready = permissionsReady,
                    onExit = onExit,
                    onContinue = {
                        if (permissionsReady) {
                            permissionPageManual = false
                            page = 0
                        } else {
                            quickSetup = true
                            returnedFromSettings = false
                            pendingPermission = null
                            permissionDialog = if (!status.connected) "accessibility" else "overlay"
                        }
                    },
                )
            } else {
                Column {
                    if (visiblePage == 0) HomeActionBar(
                        scriptMode = tab == 1,
                        runLabel = if (tab == 0 && !single.positionSet) context.getString(R.string.ui_select_click_position) else context.getString(R.string.ui_open_floating_window),
                        onRun = {
                            if (tab == 0) showOverlay(null, autoPickSingle = !single.positionSet)
                            else (selectedScriptId ?: scripts.firstOrNull()?.id)?.let { showOverlay(it) }
                                ?: message(context.getString(R.string.ui_add_a_script_first))
                        },
                        onManage = { page = 1; scriptsPage = true; editingId = null },
                    )
                    if (visiblePage == 1 && !scriptsPage) {
                        Text(
                            context.getString(R.string.ui_for_learning_research_and_testing_follow_applicable_laws_and_platform_rules),
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    AppBottomBar(page = page, onPageChange = { page = it; permissionPageManual = false })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val modeSwitcherHeight = with(LocalDensity.current) {
                MaterialTheme.typography.bodyLarge.lineHeight.toDp()
            }.coerceAtLeast(18.dp) + 24.dp
            // The viewport already excludes the app bar and the fixed bottom controls.
            // Reserve the mode switcher, content padding and the two gaps between items.
            val cardMinHeight = ((maxHeight - modeSwitcherHeight - 64.dp) / 2).coerceIn(232.dp, 320.dp)
            val contentScroll = key(visiblePage, scriptsPage, if (visiblePage == 1) editingId else null) {
                rememberScrollState()
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(contentScroll)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (visiblePage == 2) {
                    PermissionPage(
                        accessibilityReady = status.connected,
                        overlayReady = overlayAllowed,
                        onAccessibility = { quickSetup = false; permissionDialog = "accessibility" },
                        onOverlay = { quickSetup = false; permissionDialog = "overlay" },
                    )
                } else {
                    if (page == 0) {
                    if (status.active || status.phase == RunPhase.ERROR) {
                        RunStatusCard(status)
                        Spacer(Modifier.height(4.dp))
                    }

                    ModeSwitcher(tab) { tab = it }
                    if (tab == 0) {
                        SingleEditor(single, presets, ::saveSingle, ::savePresets,
                            onPickPosition = { requestId, initial ->
                                if (permissionsReady) pickPresetPosition(requestId, initial) else {
                                    permissionPageManual = false
                                    page = 2
                                    false
                                }
                            }, enabled = !status.active, cardMinHeight = cardMinHeight)
                    } else {
                        ScriptQuickLaunch(
                            scripts = scripts,
                            selectedScriptId = selectedScriptId,
                            enabled = !status.active,
                            onSelect = { selectedScriptId = it },
                        )
                    }
                    } else {
                    SettingsPage(
                        scriptPage = scriptsPage,
                        onScriptsClick = { scriptsPage = true; editingId = null },
                        themeMode = themeMode,
                        onThemeModeChange = onThemeModeChange,
                        language = language,
                        onLanguageChange = onLanguageChange,
                        overlayTransparency = overlayTransparency,
                        onOverlayTransparencyChange = onOverlayTransparencyChange,
                        presets = presets,
                        current = single,
                        editingId = editingId,
                        enabled = !status.active && presetFailure == null,
                        onPresetsChange = ::savePresets,
                        onPickPosition = ::pickPresetPosition,
                        scripts = scripts,
                        loadFailure = loadFailure,
                        onNewScript = {
                            val created = ClickScript(name = context.getString(R.string.ui_script, scripts.size + 1), orientation = context.screenArea().orientation)
                            runCatching { repository.upsert(created) }.onSuccess {
                                scripts = repository.load(); editingId = created.id; selectedScriptId = created.id
                            }.onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_create)) }
                        },
                        onEditScript = { editingId = it; selectedScriptId = it },
                        onShowScript = { id ->
                            selectedScriptId = id
                            if (permissionsReady) showOverlay(id) else {
                                permissionPageManual = false
                                page = 2
                            }
                        },
                        onDeleteScript = { deleteId = it },
                        onChangeScript = ::updateScript,
                        onSaveScript = { edited ->
                            runCatching { edited.validate(); repository.upsert(edited) }
                                .onSuccess { message(context.getString(R.string.ui_script_saved)); selectedScriptId = edited.id }
                                .onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_invalid_settings)) }
                        },
                        onCloseScript = { edited ->
                            runCatching { edited.validate(); repository.upsert(edited) }
                                .onSuccess { editingId = null; selectedScriptId = edited.id }
                                .onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_correct_the_script_settings_first)) }
                        },
                        onImportConfig = { configImporter.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        onExportConfig = {
                            runCatching {
                                configExportText = ConfigCodec.encode(presets, scripts)
                                configExporter.launch("dotctrl-config.json")
                            }.onFailure { message(context.errorText(it.message) ?: context.getString(R.string.ui_correct_the_presets_first)) }
                        },
                    )
                    presetFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(22.dp))
                    }
                }
            }
        }
    }
    permissionDialog?.let { type ->
        val accessibility = type == "accessibility"
        AlertDialog(onDismissRequest = { permissionDialog = null; quickSetup = false },
            title = { Text(if (accessibility) context.getString(R.string.ui_enable_accessibility_service) else context.getString(R.string.ui_allow_floating_windows)) },
            text = { Text(if (accessibility)
                context.getString(R.string.ui_dotctrl_uses_accessibility_to_perform_taps_long_presses_and_swipes_in_other)
                else context.getString(R.string.ui_floating_windows_let_you_select_positions_add_and_reorder_actions_and_stop)) },
            confirmButton = { TextButton(onClick = {
                permissionDialog = null
                pendingPermission = type
                returnedFromSettings = false
                runCatching { permissionSettings.launch(if (accessibility) Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                    else Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                    .onFailure {
                        quickSetup = false
                        pendingPermission = null
                        message(context.getString(R.string.ui_could_not_open_settings_grant_permissions_manually_in_system_settings))
                    }
            }) { Text(context.getString(R.string.ui_open_system_settings)) } },
            dismissButton = { TextButton(onClick = { permissionDialog = null; quickSetup = false }) { Text(context.getString(R.string.ui_cancel)) } })
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(context.getString(R.string.ui_delete_this_script)) },
            text = { Text(context.getString(R.string.ui_the_local_script_will_be_removed_exported_json_files_will_be_kept)) },
            confirmButton = { TextButton(onClick = {
                saveScripts(scripts.filterNot { it.id == id }); deleteId = null
            }) { Text(context.getString(R.string.ui_delete)) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(context.getString(R.string.ui_cancel)) } })
    }
}

@Composable
private fun AppTopCard(page: Int, running: Boolean, permissionsReady: Boolean,
    settingsTitle: String, onSettingsBack: (() -> Unit)?, onPermissions: () -> Unit) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (page == 1) 0.dp else 2.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (page != 0 && onSettingsBack != null) {
                    IconButton(onClick = onSettingsBack, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, context.getString(R.string.ui_back))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f).heightIn(min = 42.dp), contentAlignment = Alignment.CenterStart) {
                    Text(if (page == 1) settingsTitle else context.getString(R.string.ui_dotctrl),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                if (page != 2) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = when {
                            running -> MaterialTheme.colorScheme.primaryContainer
                            permissionsReady -> MaterialTheme.colorScheme.surfaceContainer
                            else -> MaterialTheme.colorScheme.tertiaryContainer
                        },
                        modifier = Modifier.clickable(enabled = !running, onClick = onPermissions),
                    ) {
                        Text(when {
                            running -> context.getString(R.string.ui_running)
                            permissionsReady -> context.getString(R.string.ui_ready)
                            else -> context.getString(R.string.ui_enable_permissions)
                        }, Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = when {
                                running -> MaterialTheme.colorScheme.onPrimaryContainer
                                permissionsReady -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onTertiaryContainer
                            })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppBottomBar(page: Int, onPageChange: (Int) -> Unit) {
    val context = LocalContext.current
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = page == 0,
            onClick = { onPageChange(0) },
            icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
            label = { Text(context.getString(R.string.ui_workbench)) },
        )
        NavigationBarItem(
            selected = page == 1,
            onClick = { onPageChange(1) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            label = { Text(context.getString(R.string.ui_settings)) },
        )
    }
}

@Composable
private fun SettingsPage(scriptPage: Boolean, onScriptsClick: () -> Unit, themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit,
    language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit,
    overlayTransparency: Float, onOverlayTransparencyChange: (Float) -> Unit,
    presets: PresetLibrary, current: ClickSettings, enabled: Boolean, onPresetsChange: (PresetLibrary) -> Boolean,
    onPickPosition: (String, ScreenPoint?) -> Boolean,
    editingId: String?,
    scripts: List<ClickScript>, loadFailure: String?,
    onNewScript: () -> Unit, onEditScript: (String) -> Unit, onShowScript: (String) -> Unit,
    onDeleteScript: (String) -> Unit, onChangeScript: (ClickScript) -> Unit,
    onSaveScript: (ClickScript) -> Unit, onCloseScript: (ClickScript) -> Unit,
    onImportConfig: () -> Unit, onExportConfig: () -> Unit) {
    var storageDialog by rememberSaveable { mutableStateOf(false) }
    var appearanceDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (scriptPage) {
            ScriptWorkspace(
                scripts = scripts,
                loadFailure = loadFailure,
                editingId = editingId,
                enabled = enabled,
                onNew = onNewScript,
                onEdit = onEditScript,
                onShow = onShowScript,
                onDelete = onDeleteScript,
                onChange = onChangeScript,
                onSave = onSaveScript,
                onClose = onCloseScript,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                SettingsGroup(context.getString(R.string.ui_presets)) {
                    PresetSettings(
                        library = presets,
                        current = current,
                        enabled = enabled,
                        scriptCount = scripts.size,
                        onScriptsClick = onScriptsClick,
                        onChange = onPresetsChange,
                        onPickPosition = onPickPosition,
                    )
                }
                SettingsGroup(context.getString(R.string.ui_general)) {
                    SettingsRow(context.getString(R.string.ui_storage), context.getString(R.string.ui_on_this_device), Icons.Outlined.Storage) { storageDialog = true }
                    SettingsRow(context.getString(R.string.ui_display), context.getString(themeMode.labelRes), Icons.Outlined.Palette) { appearanceDialog = true }
                }
                SettingsGroup(context.getString(R.string.ui_about)) {
                    SettingsRow(context.getString(R.string.ui_version), BuildConfig.VERSION_NAME, Icons.Outlined.Info)
                    SettingsRow(context.getString(R.string.ui_source_code), "GitHub", Icons.Outlined.Code) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOTCTRL_REPOSITORY_URL)))
                    }
                }
            }
        }
    }
    if (storageDialog) {
        AlertDialog(
            onDismissRequest = { storageDialog = false },
            title = { Text(context.getString(R.string.ui_on_this_device)) },
            text = { Text(context.getString(R.string.ui_positions_click_modes_and_scripts_are_saved_on_this_device_import_or_export)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = enabled, onClick = { storageDialog = false; onImportConfig() }) { Text(context.getString(R.string.ui_import)) }
                    Button(enabled = enabled, onClick = { storageDialog = false; onExportConfig() }) { Text(context.getString(R.string.ui_export)) }
                }
            },
            dismissButton = { TextButton(onClick = { storageDialog = false }) { Text(context.getString(R.string.ui_close)) } },
        )
    }
    if (appearanceDialog) {
        DisplaySettingsDialog(
            themeMode, onThemeModeChange, language, onLanguageChange,
            overlayTransparency, onOverlayTransparencyChange,
            onDismiss = { appearanceDialog = false },
        )
    }
}

@Composable
private fun HomeActionBar(scriptMode: Boolean, runLabel: String, onRun: () -> Unit, onManage: () -> Unit) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onRun,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(runLabel, style = MaterialTheme.typography.labelLarge)
                }
                if (scriptMode) {
                    OutlinedButton(
                        onClick = onManage,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(context.getString(R.string.ui_manage_scripts), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionPage(
    accessibilityReady: Boolean,
    overlayReady: Boolean,
    onAccessibility: () -> Unit,
    onOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val ready = accessibilityReady && overlayReady
    Column(Modifier.fillMaxWidth().padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (ready) context.getString(R.string.ui_permissions) else context.getString(R.string.ui_enable_permissions),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(if (ready) context.getString(R.string.ui_both_permissions_are_enabled_dotctrl_is_ready) else context.getString(R.string.ui_enable_both_permissions_to_start_using_dotctrl),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center)
        }
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
            Column {
                PermissionItem(context.getString(R.string.ui_accessibility_service), context.getString(R.string.ui_perform_taps_and_swipes), accessibilityReady, onAccessibility)
                HorizontalDivider(Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                PermissionItem(context.getString(R.string.ui_floating_window), context.getString(R.string.ui_select_positions_and_control_clicks), overlayReady, onOverlay)
            }
        }
    }
}

@Composable
private fun PermissionItem(title: String, detail: String, granted: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (granted) context.getString(R.string.ui_enabled) else context.getString(R.string.ui_disabled), style = MaterialTheme.typography.labelMedium,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
            Icon(if (granted) Icons.Outlined.Check else Icons.Outlined.ChevronRight, null,
                Modifier.size(18.dp), tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun PermissionActions(ready: Boolean, onContinue: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onExit, modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                shape = RoundedCornerShape(18.dp)) { Text(context.getString(R.string.ui_exit)) }
            Button(onClick = onContinue, modifier = Modifier.weight(2f).heightIn(min = 54.dp),
                shape = RoundedCornerShape(18.dp)) { Text(if (ready) context.getString(R.string.ui_open_workbench) else context.getString(R.string.ui_quick_setup)) }
        }
    }
}

@Composable
private fun RunStatusCard(status: RuntimeStatus) {
    val context = LocalContext.current
    val running = status.active
    val accent = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                Icon(if (running) Icons.Outlined.PlayArrow else Icons.Outlined.StopCircle, null,
                    tint = accent, modifier = Modifier.size(25.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(if (running) context.getString(R.string.ui_running_2) else context.getString(R.string.ui_ready_2), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(status.progress.ifBlank { status.message }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary)
            }
            if (running) FilledTonalButton(onClick = { AutoClickAccessibilityService.instance?.stop(context.getString(R.string.ui_stopped_from_the_app)) }) {
                Text(context.getString(R.string.ui_stop))
            }
        }
    }
}

@Composable
private fun ModeSwitcher(selected: Int, onSelect: (Int) -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        listOf(context.getString(R.string.ui_single_point) to Icons.Outlined.TouchApp, context.getString(R.string.ui_scripts) to Icons.Outlined.Layers).forEachIndexed { index, item ->
            val active = selected == index
            Surface(onClick = { onSelect(index) }, modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp), color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent) {
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.second, null, Modifier.size(18.dp), tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(7.dp))
                    Text(item.first, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun ScriptQuickLaunch(
    scripts: List<ClickScript>,
    selectedScriptId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val selected = scripts.firstOrNull { it.id == selectedScriptId } ?: scripts.firstOrNull()
    Section(context.getString(R.string.ui_scripts), Icons.Outlined.Layers, context.getString(R.string.ui_manage_scripts_in_settings)) {
        if (scripts.isEmpty()) {
            Text(context.getString(R.string.ui_no_scripts_yet), color = MaterialTheme.colorScheme.secondary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                scripts.forEach { script ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (selected?.id == script.id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable(enabled = enabled) { onSelect(script.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(script.displayName(context), Modifier.weight(1f), fontWeight = if (selected?.id == script.id) FontWeight.Bold else FontWeight.Normal)
                        Text(context.getString(R.string.ui_actions, script.steps.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptWorkspace(scripts: List<ClickScript>, loadFailure: String?, editingId: String?, enabled: Boolean,
    onNew: () -> Unit, onEdit: (String) -> Unit, onShow: (String) -> Unit, onDelete: (String) -> Unit,
    onChange: (ClickScript) -> Unit, onSave: (ClickScript) -> Unit, onClose: (ClickScript) -> Unit) {
    val context = LocalContext.current
    val editing = scripts.firstOrNull { it.id == editingId }
    if (editing != null) {
        key(editing.id) {
            ScriptEditor(editing, onChange = onChange, onSave = { onSave(editing) }, onShow = { onShow(editing.id) },
                onClose = { onClose(editing) }, enabled = enabled)
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(context.getString(R.string.ui_saved, scripts.size), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(enabled = enabled && loadFailure == null, onClick = onNew) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(context.getString(R.string.ui_new_script))
            }
        }
        loadFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (scripts.isEmpty()) Text(context.getString(R.string.ui_no_scripts_yet_tap_the_add_button_to_create_one), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            scripts.forEach { script ->
                key(script.id) { ScriptRow(script, enabled, onEdit, onShow, onDelete) }
            }
        }
    }
}

@Composable
private fun ScriptRow(script: ClickScript, enabled: Boolean, onEdit: (String) -> Unit,
    onShow: (String) -> Unit, onDelete: (String) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled && !script.builtIn) { onEdit(script.id) }
        .heightIn(min = 84.dp).padding(start = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Layers, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(script.displayName(context), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(listOfNotNull(if (script.builtIn) context.getString(R.string.ui_built_in) else null, context.getString(R.string.ui_actions_2, script.steps.size),
                if (script.loopCount == 0) context.getString(R.string.ui_repeat_continuously) else context.getString(R.string.ui_loops, script.loopCount)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(enabled = enabled, onClick = { onShow(script.id) }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, context.getString(R.string.ui_open_floating_window_for, script.displayName(context)), Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        if (!script.builtIn) Box {
            IconButton(enabled = enabled, onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, context.getString(R.string.ui_options_for, script.displayName(context)), Modifier.size(20.dp))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(context.getString(R.string.ui_edit)) }, onClick = { menu = false; onEdit(script.id) })
                DropdownMenuItem(text = { Text(context.getString(R.string.ui_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete(script.id) })
            }
        }
    }
}

@Composable
internal fun Section(title: String, icon: ImageVector? = null, subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
            content()
        }
    }
}

@Composable
private fun ScriptEditor(script: ClickScript, onChange: (ClickScript) -> Unit,
    onSave: () -> Unit, onShow: () -> Unit, onClose: () -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(value = script.name, onValueChange = { onChange(script.copy(name = it)) },
            label = { Text(context.getString(R.string.ui_script_name)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            isError = script.name.isBlank() || script.name.length > 60)
        NumberField(context.getString(R.string.ui_loop_count_0_continuous), script.loopCount.toLong(), 0L..100_000L, enabled) { onChange(script.copy(loopCount = it.toInt())) }
        NumberField(context.getString(R.string.ui_safety_timeout_minutes), script.timeoutMinutes.toLong(), 1L..1_440L, enabled) { onChange(script.copy(timeoutMinutes = it.toInt())) }
        Text(context.getString(R.string.ui_orientation_100_steps, if (script.orientation == 1) context.getString(R.string.ui_portrait) else context.getString(R.string.ui_landscape), script.steps.size),
            style = MaterialTheme.typography.bodySmall)
        FilledTonalButton(
            onClick = onShow,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) { Text(context.getString(R.string.ui_open_action_controls)) }
        if (script.steps.isEmpty()) Text(context.getString(R.string.ui_select_action_positions_in_the_target_app_then_return_to_set_delays))
        script.steps.forEachIndexed { index, step ->
            key(step.id) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(R.string.ui_action, index + 1), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    var menuExpanded by rememberSaveable(step.id) { mutableStateOf(false) }
                    Box {
                        IconButton(enabled = enabled, onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.MoreVert, context.getString(R.string.ui_action_options), Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(context.getString(R.string.ui_move_up)) },
                                leadingIcon = { Icon(Icons.Outlined.KeyboardArrowUp, null) },
                                enabled = index > 0,
                                onClick = {
                                    val list = script.steps.toMutableList()
                                    list.add(index - 1, list.removeAt(index)); onChange(script.copy(steps = list))
                                    menuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(context.getString(R.string.ui_move_down)) },
                                leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null) },
                                enabled = index < script.steps.lastIndex,
                                onClick = {
                                    val list = script.steps.toMutableList()
                                    list.add(index + 1, list.removeAt(index)); onChange(script.copy(steps = list))
                                    menuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(context.getString(R.string.ui_remove)) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    onChange(script.copy(steps = script.steps.filterNot { it.id == step.id }))
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
                fun update(updated: ScriptStep) { onChange(script.copy(steps = script.steps.map { if (it.id == step.id) updated else it })) }
                GestureChoices(step.gestureType, GestureType.entries, enabled) {
                    update(step.copy(gestureType = it, durationMs = if (it == GestureType.TAP) 80 else 600))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoordinateField("X（%）", step.x, enabled, Modifier.weight(1f)) { update(step.copy(x = it)) }
                    CoordinateField("Y（%）", step.y, enabled, Modifier.weight(1f)) { update(step.copy(y = it)) }
                }
                if (step.gestureType == GestureType.SWIPE) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoordinateField(context.getString(R.string.ui_end_x), step.endX, enabled, Modifier.weight(1f)) { update(step.copy(endX = it)) }
                    CoordinateField(context.getString(R.string.ui_end_y), step.endY, enabled, Modifier.weight(1f)) { update(step.copy(endY = it)) }
                }
                NumberField(context.getString(R.string.ui_gesture_duration_ms), step.durationMs, (if (step.gestureType == GestureType.LONG_PRESS) 500L else 40L)..10_000L, enabled) { update(step.copy(durationMs = it)) }
                NumberField(context.getString(R.string.ui_delay_after_action_ms), step.delayMs, 0L..60_000L, enabled) { update(step.copy(delayMs = it)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = enabled) { Text(context.getString(R.string.ui_save)) }
            TextButton(onClick = onClose, enabled = enabled) { Text(context.getString(R.string.ui_save_and_close)) }
        }
    }
}

@Composable
internal fun GestureChoices(selected: GestureType, values: List<GestureType>, enabled: Boolean, onChange: (GestureType) -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { type ->
            FilterChip(selected = selected == type, onClick = { onChange(type) }, enabled = enabled, label = { Text(type.label(context)) })
        }
    }
}

@Composable
internal fun NumberField(label: String, value: Long, range: LongRange, enabled: Boolean = true, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) { if ((text.toLongOrNull() ?: -1) != value) text = value.toString() }
    OutlinedTextField(value = text, onValueChange = {
        text = it
        onChange(it.toLongOrNull()?.coerceIn(-1, Int.MAX_VALUE.toLong()) ?: -1)
    }, label = { Text(label) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = value !in range, supportingText = if (value !in range) ({ Text(context.getString(R.string.ui_enter, range.first, range.last)) }) else null)
}

@Composable
internal fun CoordinateField(label: String, value: Float, enabled: Boolean, modifier: Modifier, onChange: (Float) -> Unit) {
    var text by remember { mutableStateOf("%.2f".format(java.util.Locale.US, value * 100)) }
    LaunchedEffect(value) {
        val parsed = text.toFloatOrNull()?.div(100) ?: -1f
        if (kotlin.math.abs(parsed - value) > 0.00001f) text = "%.2f".format(java.util.Locale.US, value * 100)
    }
    OutlinedTextField(value = text, onValueChange = { text = it; onChange(it.toFloatOrNull()?.div(100) ?: -1f) },
        label = { Text(label) }, enabled = enabled, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), isError = !value.isFinite() || value !in 0f..1f)
}
