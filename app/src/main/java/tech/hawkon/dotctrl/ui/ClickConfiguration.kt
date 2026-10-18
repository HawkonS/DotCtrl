package tech.hawkon.dotctrl.ui

import tech.hawkon.dotctrl.R
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.hawkon.dotctrl.data.*
import tech.hawkon.dotctrl.service.RuntimeState
import java.util.UUID

@Composable
internal fun SingleEditor(value: ClickSettings, library: PresetLibrary, onChange: (ClickSettings) -> Boolean,
    onPresetsChange: (PresetLibrary) -> Boolean, onPickPosition: (String, ScreenPoint?) -> Boolean,
    enabled: Boolean, cardMinHeight: Dp) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var modeReturnDialog by remember { mutableStateOf<String?>(null) }
    var modeEditorCanSaveAsPreset by remember { mutableStateOf(false) }
    var modePresetBeingEditedId by remember { mutableStateOf<String?>(null) }
    var positionPickRequestId by remember { mutableStateOf<String?>(null) }
    var positionPresetBeingEditedId by remember { mutableStateOf<String?>(null) }
    var pendingPositionPoint by remember { mutableStateOf<ScreenPoint?>(null) }
    var customPositionDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    val mode = ClickMode.from(value)
    val selectedPosition = library.positions.firstOrNull { it.matches(value) }
    val selectedMode = library.allModes.firstOrNull { it.mode == mode }
    // Built-in modes are templates. Only a user-saved mode can be edited in place.
    val selectedSavedMode = library.modes.firstOrNull { it.mode == mode }
    val positionContentHeight = (cardMinHeight - 88.dp).coerceAtLeast(144.dp)
    val previewHeight = (positionContentHeight - 12.dp).coerceIn(120.dp, 164.dp)
    fun beginPositionPick(editExisting: Boolean) {
        val requestId = UUID.randomUUID().toString()
        val initial = if (value.positionSet) ScreenPoint(value.x, value.y, value.orientation) else null
        positionPresetBeingEditedId = if (editExisting) selectedPosition?.id else null
        pendingPositionPoint = null
        customPositionDraftId = null
        if (onPickPosition(requestId, initial)) {
            positionPickRequestId = requestId
            dialog = null
        } else {
            positionPresetBeingEditedId = null
        }
    }
    fun beginModeEdit(editExisting: Boolean) {
        modePresetBeingEditedId = if (editExisting) selectedSavedMode?.id else null
        modeEditorCanSaveAsPreset = !editExisting || selectedSavedMode == null
        modeReturnDialog = if (dialog == "modes") "modes" else null
        dialog = "mode"
    }
    // Stop exposing editable dialogs if a task is started from the floating controls.
    LaunchedEffect(enabled) { if (!enabled) dialog = null }
    WorkbenchCard(context.getString(R.string.ui_click_position), library.positionLabel(context, value), enabled, cardMinHeight, onSelect = { dialog = "position" }) {
        Row(Modifier.fillMaxWidth().heightIn(min = positionContentHeight), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (value.positionSet) PositionThumbnail(ScreenPoint(value.x, value.y, value.orientation),
                shortSide = previewHeight * (2f / 3f), longSide = previewHeight)
            else Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Box(Modifier.size(previewHeight * (2f / 3f), previewHeight), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.TouchApp, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f).heightIn(min = positionContentHeight), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (value.positionSet) context.getString(R.string.ui_position_selected) else context.getString(R.string.ui_no_position_selected),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    if (value.positionSet) Text(if (value.orientation == 1) context.getString(R.string.ui_portrait) else context.getString(R.string.ui_landscape),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                }
                WorkbenchEditButton(if (value.positionSet) context.getString(R.string.ui_pick_again) else context.getString(R.string.ui_pick_on_screen), Icons.Outlined.TouchApp,
                    enabled = enabled, onClick = { beginPositionPick(editExisting = selectedPosition != null) },
                    modifier = Modifier.align(Alignment.End))
            }
        }
    }
    val pickResult by RuntimeState.positionPickResults.collectAsStateWithLifecycle()
    LaunchedEffect(pickResult, positionPickRequestId) {
        val result = pickResult
        val requestId = positionPickRequestId
        if (result == null || requestId == null || result.requestId != requestId) return@LaunchedEffect
        positionPickRequestId = null
        RuntimeState.consumePositionPick(requestId)
        val point = result.point
        if (point != null) {
            val presetId = positionPresetBeingEditedId
            if (presetId != null) {
                val updatedLibrary = library.copy(positions = library.positions.map { preset ->
                    if (preset.id == presetId) preset.copy(x = point.x, y = point.y, orientation = point.orientation) else preset
                })
                val updatedSettings = value.copy(x = point.x, y = point.y,
                    orientation = point.orientation, positionSet = true)
                if (onPresetsChange(updatedLibrary) && onChange(updatedSettings)) {
                    dialog = null
                } else {
                    dialog = "position"
                }
            } else {
                pendingPositionPoint = point
                customPositionDraftId = UUID.randomUUID().toString()
                dialog = "customPositionChoice"
            }
        } else {
            dialog = "position"
        }
        if (point == null) {
            pendingPositionPoint = null
            customPositionDraftId = null
        }
        positionPresetBeingEditedId = null
    }
    WorkbenchCard(context.getString(R.string.ui_click_mode), library.modeLabel(context, value), enabled, cardMinHeight, onSelect = { dialog = "modes" }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ClickMetric(context.getString(R.string.ui_click_interval), mode.intervalMs.toString(), "ms", Modifier.weight(1f))
            ClickMetric(if (mode.continuous) context.getString(R.string.ui_run_mode) else context.getString(R.string.ui_click_count),
                if (mode.continuous) context.getString(R.string.ui_continuous) else mode.repeatCount.toString(),
                if (mode.continuous) null else context.getString(R.string.ui_times), Modifier.weight(1f))
        }
        ModeCardFooter(mode, enabled, onEdit = {
            beginModeEdit(editExisting = selectedSavedMode != null)
        })
    }
    when (dialog) {
        "position" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(context.getString(R.string.ui_click_position)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                    library.positions.forEach { preset ->
                        PresetChoiceRow(preset.name, positionSummary(context, preset),
                            selected = preset.id == selectedPosition?.id, enabled = enabled,
                            onClick = { if (onChange(preset.applyTo(value))) dialog = null })
                    }
                    PresetChoiceRow(
                        context.getString(R.string.ui_custom),
                        context.getString(R.string.ui_apply_a_position_directly_or_save_it_as_a_preset),
                        selected = selectedPosition == null,
                        enabled = enabled,
                        onClick = { beginPositionPick(editExisting = false) },
                    )
                }
            },
            confirmButton = { TextButton(onClick = {
                beginPositionPick(editExisting = selectedPosition != null)
            }, enabled = enabled) {
                Text(if (selectedPosition != null) context.getString(R.string.ui_adjust_current_position) else context.getString(R.string.ui_pick_again))
            } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(context.getString(R.string.ui_close)) } })
        "customPositionChoice" -> pendingPositionPoint?.let { point ->
            AlertDialog(onDismissRequest = { dialog = "position" },
                title = { Text(context.getString(R.string.ui_custom_click_position)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectedPositionPreview(point)
                        Text(context.getString(R.string.ui_use_this_position_for_the_current_configuration_or_save_it_as_a_preset),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                },
                confirmButton = {
                    CompactChoiceActions(
                        onBack = { dialog = "position" },
                        onApply = {
                            if (onChange(value.copy(x = point.x, y = point.y,
                                    orientation = point.orientation, positionSet = true))) {
                                pendingPositionPoint = null
                                customPositionDraftId = null
                                dialog = null
                            }
                        },
                        onSave = { dialog = "namePosition" },
                    )
                },
                dismissButton = {})
        }
        "namePosition" -> pendingPositionPoint?.let { point ->
            PositionEditorDialog(
                initial = PositionPreset(id = customPositionDraftId ?: "custom-position-draft",
                    name = context.getString(R.string.ui_position, library.positions.size + 1),
                    x = point.x, y = point.y, orientation = point.orientation),
                onDismiss = { dialog = "customPositionChoice" },
                onSave = { preset ->
                    if (onPresetsChange(library.copy(positions = library.positions + preset)) &&
                        onChange(preset.applyTo(value))) {
                        pendingPositionPoint = null
                        customPositionDraftId = null
                        dialog = null
                    }
                },
            )
        }
        "modes" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(context.getString(R.string.ui_click_mode)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                    library.allModes.forEach { preset ->
                        PresetChoiceRow(preset.displayName(context), modeSummary(context, preset.mode),
                            selected = preset.id == selectedMode?.id, enabled = enabled,
                            onClick = { if (onChange(preset.mode.applyTo(value))) dialog = null })
                    }
                    PresetChoiceRow(context.getString(R.string.ui_custom), if (selectedMode == null) modeSummary(context, mode) else context.getString(R.string.ui_set_interval_count_and_duration),
                        selected = selectedMode == null, enabled = enabled, onClick = {
                            beginModeEdit(editExisting = false)
                        })
                }
            },
            confirmButton = { TextButton(onClick = {
                beginModeEdit(editExisting = selectedSavedMode != null)
            }, enabled = enabled) {
                Text(if (selectedSavedMode != null) context.getString(R.string.ui_adjust_current_mode) else context.getString(R.string.ui_adjust_custom_mode))
            } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(context.getString(R.string.ui_close)) } })
        "mode" -> ModeEditorDialog(
            initial = mode,
            title = if (modePresetBeingEditedId != null) {
                context.getString(R.string.ui_adjust, selectedSavedMode?.name ?: context.getString(R.string.ui_current_mode))
            } else context.getString(R.string.ui_custom_click_mode),
            allowSaveAsPreset = modeEditorCanSaveAsPreset,
            onDismiss = { dialog = modeReturnDialog }, onSave = { updated, name ->
                if (enabled) {
                    val presetId = modePresetBeingEditedId
                    if (presetId != null) {
                        val updatedLibrary = library.copy(modes = library.modes.map { preset ->
                            if (preset.id == presetId) preset.copy(mode = updated) else preset
                        })
                        if (onPresetsChange(updatedLibrary) && onChange(updated.applyTo(value))) {
                            dialog = null
                        }
                    } else {
                        val saved = name == null || onPresetsChange(
                            library.copy(modes = library.modes + ModePreset(name = name, mode = updated))
                        )
                        if (saved && onChange(updated.applyTo(value))) dialog = null
                    }
                }
            })
    }
}

@Composable
private fun ModeCardFooter(mode: ClickMode, enabled: Boolean, onEdit: () -> Unit) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Keep both the timeout and the action readable on narrow screens or with larger text.
        if (maxWidth < 264.dp * fontScale) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeRunSummary(mode)
                WorkbenchEditButton(context.getString(R.string.ui_adjust_settings), Icons.Outlined.Tune, enabled, onEdit, Modifier.align(Alignment.End))
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeRunSummary(mode, Modifier.weight(1f))
                WorkbenchEditButton(context.getString(R.string.ui_adjust_settings), Icons.Outlined.Tune, enabled, onEdit)
            }
        }
    }
}

@Composable
private fun ModeRunSummary(mode: ClickMode, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (mode.gestureType == GestureType.LONG_PRESS) Text(context.getString(R.string.ui_hold_for_ms, mode.gestureDurationMs),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Text(context.getString(R.string.ui_stop_automatically_after_min, mode.timeoutMinutes),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun WorkbenchEditButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

@Composable
private fun WorkbenchCard(title: String, value: String, enabled: Boolean, minHeight: Dp, onSelect: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(onClick = onSelect, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.heightIn(min = minHeight).padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End) {
                    Text(value, modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            content()
        }
    }
}

@Composable
private fun ClickMetric(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(value, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f, fill = false))
            if (unit != null) Text(unit, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun PresetChoiceRow(name: String, detail: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, fontWeight = FontWeight.Medium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        RadioButton(selected = selected, onClick = null, enabled = enabled)
    }
}

internal fun modeSummary(context: Context, mode: ClickMode): String =
    context.getString(R.string.ui_ms_interval, mode.intervalMs, if (mode.continuous) context.getString(R.string.ui_continuous_clicks) else context.getString(R.string.ui_clicks, mode.repeatCount))

private fun positionSummary(context: Context, position: PositionPreset): String =
    "${if (position.orientation == 1) context.getString(R.string.ui_portrait) else context.getString(R.string.ui_landscape)} · ${(position.x * 100).toInt()}%, ${(position.y * 100).toInt()}%"

private fun positionSummary(context: Context, settings: ClickSettings): String =
    if (settings.positionSet) {
        "${if (settings.orientation == 1) context.getString(R.string.ui_portrait) else context.getString(R.string.ui_landscape)} · ${(settings.x * 100).toInt()}%, ${(settings.y * 100).toInt()}%"
    } else context.getString(R.string.ui_no_position_selected)

@Composable
internal fun PresetSettings(library: PresetLibrary, current: ClickSettings, enabled: Boolean,
    scriptCount: Int = 0, onScriptsClick: () -> Unit = {},
    onChange: (PresetLibrary) -> Boolean, onPickPosition: (String, ScreenPoint?) -> Boolean) {
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    // Keep the unsaved name and point across the trip to another app and screen rotation.
    var positionJson by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPositionJson by rememberSaveable { mutableStateOf<String?>(null) }
    var pickRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var returnToEditor by rememberSaveable { mutableStateOf(false) }
    val position = positionJson?.let { AppJson.format.decodeFromString<PositionPreset>(it) }
    val pickResult by RuntimeState.positionPickResults.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf<ModePreset?>(null) }
    var deletePosition by remember { mutableStateOf<PositionPreset?>(null) }
    var deleteMode by remember { mutableStateOf<ModePreset?>(null) }
    fun pickPosition(draft: PositionPreset, initial: ScreenPoint?) {
        val requestId = UUID.randomUUID().toString()
        if (onPickPosition(requestId, initial)) {
            returnToEditor = position != null
            pendingPositionJson = AppJson.format.encodeToString(draft)
            pickRequestId = requestId
            positionJson = null
        }
    }
    LaunchedEffect(pickResult, pickRequestId) {
        val result = pickResult
        val pending = pendingPositionJson
        if (result != null && result.requestId == pickRequestId && pending != null) {
            val draft = AppJson.format.decodeFromString<PositionPreset>(pending)
            val point = result.point
            positionJson = when {
                point != null -> AppJson.format.encodeToString(draft.copy(x = point.x, y = point.y, orientation = point.orientation))
                returnToEditor -> pending
                else -> null
            }
            page = "positions"
            pendingPositionJson = null
            pickRequestId = null
            RuntimeState.consumePositionPick(result.requestId)
        }
    }
    SettingsRow(context.getString(R.string.ui_click_position), context.getString(R.string.ui_item_count, library.positions.size), Icons.Outlined.MyLocation, enabled) { page = "positions" }
    SettingsRow(context.getString(R.string.ui_click_mode), context.getString(R.string.ui_item_count, library.allModes.size), Icons.Outlined.Tune, enabled) { page = "modes" }
    SettingsRow(context.getString(R.string.ui_script_actions), context.getString(R.string.ui_item_count, scriptCount), Icons.Outlined.Layers, enabled, onScriptsClick)
    if (page != null && position == null && pickRequestId == null && mode == null && deletePosition == null && deleteMode == null) {
        val positions = page == "positions"
        AlertDialog(onDismissRequest = { page = null }, title = { Text(if (positions) context.getString(R.string.ui_click_position) else context.getString(R.string.ui_click_mode)) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (positions && library.positions.isEmpty()) Text(context.getString(R.string.ui_no_saved_positions_yet), color = MaterialTheme.colorScheme.secondary)
                    if (positions) library.positions.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { positionJson = AppJson.format.encodeToString(item) }, modifier = Modifier.weight(1f)) {
                                Text(item.name, modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { deletePosition = item }) { Text(context.getString(R.string.ui_delete), color = MaterialTheme.colorScheme.error) }
                        }
                    } else library.allModes.forEach { item ->
                        val builtIn = item.id.startsWith("builtin-")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { mode = if (builtIn) ModePreset(name = context.getString(R.string.ui_copy, item.displayName(context)), mode = item.mode) else item },
                                modifier = Modifier.weight(1f)) {
                                Text(item.displayName(context), modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (builtIn) Text(context.getString(R.string.ui_built_in), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            else TextButton(onClick = { deleteMode = item }) { Text(context.getString(R.string.ui_delete), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    if (positions) FilledTonalButton(onClick = {
                        val draft = PositionPreset(name = "", x = current.x, y = current.y, orientation = current.orientation)
                        pickPosition(draft, if (current.positionSet) ScreenPoint(current.x, current.y, current.orientation) else null)
                    }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Icon(Icons.Outlined.TouchApp, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(context.getString(R.string.ui_pick_a_position_on_screen))
                    }
                }
            },
            confirmButton = {
                if (positions) TextButton(onClick = { page = null }) { Text(context.getString(R.string.ui_close)) }
                else TextButton(onClick = { mode = ModePreset(name = "", mode = ClickMode.from(current)) }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text(context.getString(R.string.ui_new))
                }
            },
            dismissButton = { if (!positions) TextButton(onClick = { page = null }) { Text(context.getString(R.string.ui_close)) } })
    }
    position?.let { initial ->
        PositionEditorDialog(initial, onDismiss = { positionJson = null }, onSave = { edited ->
            val updated = if (library.positions.any { it.id == edited.id }) library.positions.map { if (it.id == edited.id) edited else it }
                else library.positions + edited
            if (onChange(library.copy(positions = updated))) positionJson = null
        }, onPick = { draft -> pickPosition(draft, ScreenPoint(draft.x, draft.y, draft.orientation)) })
    }
    mode?.let { initial ->
        ModeEditorDialog(initial.mode, initialName = initial.name, onDismiss = { mode = null }, onSave = { values, name ->
            val edited = initial.copy(name = name.orEmpty(), mode = values)
            val updated = if (library.modes.any { it.id == edited.id }) library.modes.map { if (it.id == edited.id) edited else it }
                else library.modes + edited
            if (onChange(library.copy(modes = updated))) mode = null
        })
    }
    if (deletePosition != null || deleteMode != null) AlertDialog(
        onDismissRequest = { deletePosition = null; deleteMode = null },
        title = { Text(context.getString(R.string.ui_delete_2, deletePosition?.name ?: deleteMode?.name)) },
        text = { Text(context.getString(R.string.ui_your_current_click_settings_will_be_kept)) },
        confirmButton = { TextButton(onClick = {
            if (onChange(library.copy(positions = library.positions.filterNot { it.id == deletePosition?.id },
                    modes = library.modes.filterNot { it.id == deleteMode?.id }))) {
                deletePosition = null; deleteMode = null
            }
        }) { Text(context.getString(R.string.ui_delete)) } },
        dismissButton = { TextButton(onClick = { deletePosition = null; deleteMode = null }) { Text(context.getString(R.string.ui_cancel)) } })
}

@Composable
private fun PositionEditorDialog(initial: PositionPreset, onDismiss: () -> Unit, onSave: (PositionPreset) -> Unit,
    onPick: ((PositionPreset) -> Unit)? = null) {
    val context = LocalContext.current
    var name by rememberSaveable(initial.id, initial.x, initial.y, initial.orientation) { mutableStateOf(initial.name) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(context.getString(R.string.ui_save_click_position)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it; error = null }, label = { Text(context.getString(R.string.ui_position_name)) },
                placeholder = { Text(context.getString(R.string.ui_e_g_confirm_button)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SelectedPositionPreview(ScreenPoint(initial.x, initial.y, initial.orientation))
            if (onPick != null) FilledTonalButton(onClick = { onPick(initial.copy(name = name)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.TouchApp, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(context.getString(R.string.ui_pick_again_on_screen))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        runCatching { initial.copy(name = name.trim()).also { it.validate() } }
            .onSuccess(onSave).onFailure { error = context.errorText(it.message) }
    }) { Text(context.getString(R.string.ui_save)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.ui_cancel)) } })
}

@Composable
private fun SelectedPositionPreview(position: ScreenPoint, name: String? = null) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PositionThumbnail(position)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(context.getString(R.string.ui_position_selected), fontWeight = FontWeight.SemiBold)
            val orientation = if (position.orientation == 1) context.getString(R.string.ui_portrait) else context.getString(R.string.ui_landscape)
            Text(listOfNotNull(name, orientation).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PositionThumbnail(position: ScreenPoint, shortSide: Dp = 44.dp, longSide: Dp = 64.dp) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(Modifier.size(if (position.orientation == 1) shortSide else longSide, if (position.orientation == 1) longSide else shortSide)) {
        val inset = 2.dp.toPx()
        val frameSize = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawRoundRect(fill, Offset(inset, inset), frameSize, CornerRadius(7.dp.toPx()))
        drawRoundRect(outline, Offset(inset, inset), frameSize, CornerRadius(7.dp.toPx()), style = Stroke(1.dp.toPx()))
        val margin = 8.dp.toPx()
        val point = Offset(margin + position.x * (size.width - 2 * margin), margin + position.y * (size.height - 2 * margin))
        drawCircle(accent.copy(alpha = .16f), 7.dp.toPx(), point)
        drawCircle(accent, 3.dp.toPx(), point)
    }
}

@Composable
private fun ModeEditorDialog(initial: ClickMode, initialName: String? = null, title: String? = null,
    allowSaveAsPreset: Boolean = true,
    onDismiss: () -> Unit, onSave: (ClickMode, String?) -> Unit) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(initialName.orEmpty()) }
    var namingStep by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    if (namingStep) {
        ModeNameDialog(name = name, onNameChange = { name = it }, mode = draft,
            applyAfterSave = initialName == null, onBack = { namingStep = false },
            onSave = { onSave(draft, name.trim()) })
        return
    }
    fun validateAndApply() {
        runCatching { draft.validate() }.onSuccess {
            error = null
            onSave(draft, null)
        }.onFailure { error = context.errorText(it.message) }
    }
    fun validateAndSave() {
        runCatching { draft.validate() }.onSuccess {
            error = null
            namingStep = true
        }.onFailure { error = context.errorText(it.message) }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title ?: context.getString(R.string.ui_click_mode)) }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GestureChoices(draft.gestureType, listOf(GestureType.TAP, GestureType.LONG_PRESS), true) {
                draft = draft.copy(gestureType = it, gestureDurationMs = if (it == GestureType.LONG_PRESS) 600 else 80)
            }
            NumberField(context.getString(R.string.ui_click_interval_ms), draft.intervalMs, 50L..60_000L) { draft = draft.copy(intervalMs = it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.ui_continuous_clicks), Modifier.weight(1f)); Switch(checked = draft.continuous, onCheckedChange = { draft = draft.copy(continuous = it) })
            }
            if (!draft.continuous) NumberField(context.getString(R.string.ui_click_count), draft.repeatCount.toLong(), 1L..100_000L) { draft = draft.copy(repeatCount = it.toInt()) }
            if (draft.gestureType == GestureType.LONG_PRESS || advanced) NumberField(context.getString(R.string.ui_hold_duration_ms), draft.gestureDurationMs,
                (if (draft.gestureType == GestureType.LONG_PRESS) 500L else 40L)..10_000L) { draft = draft.copy(gestureDurationMs = it) }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) context.getString(R.string.ui_hide_advanced_settings) else context.getString(R.string.ui_more_settings_stop_after_min, draft.timeoutMinutes)) }
            if (advanced) NumberField(context.getString(R.string.ui_auto_stop_minutes), draft.timeoutMinutes.toLong(), 1L..1_440L) { draft = draft.copy(timeoutMinutes = it.toInt()) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        if (allowSaveAsPreset && initialName == null) {
            CompactChoiceActions(
                onBack = onDismiss,
                onApply = ::validateAndApply,
                onSave = ::validateAndSave,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(context.getString(R.string.ui_cancel)) }
                Spacer(Modifier.width(8.dp))
                if (initialName != null) {
                    Button(onClick = ::validateAndSave) { Text(context.getString(R.string.ui_save)) }
                } else {
                    Button(onClick = ::validateAndApply) { Text(context.getString(R.string.ui_apply)) }
                }
            }
        }
    })
}

/** The three choices intentionally stay on one line in both position and mode dialogs. */
@Composable
private fun CompactChoiceActions(onBack: () -> Unit, onApply: () -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.weight(0.78f),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
        ) { Text(context.getString(R.string.ui_back), maxLines = 1, fontSize = 13.sp) }
        TextButton(
            onClick = onApply,
            modifier = Modifier.weight(0.95f),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
        ) { Text(context.getString(R.string.ui_apply_only), maxLines = 1, fontSize = 13.sp) }
        Button(
            onClick = onSave,
            modifier = Modifier.weight(1.3f),
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
        ) { Text(context.getString(R.string.ui_save_preset), maxLines = 1, fontSize = 13.sp) }
    }
}

@Composable
private fun ModeNameDialog(name: String, onNameChange: (String) -> Unit, mode: ClickMode,
    applyAfterSave: Boolean, onBack: () -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var error by remember { mutableStateOf<String?>(null) }
    fun save() {
        if (name.trim().isEmpty() || name.trim().length > 24) {
            error = context.getString(R.string.ui_name_must_contain_1_24_characters)
        } else {
            keyboard?.hide()
            onSave()
        }
    }
    fun back() { keyboard?.hide(); onBack() }
    AlertDialog(onDismissRequest = ::back, title = { Text(context.getString(R.string.ui_save_click_mode)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { onNameChange(it); error = null },
                label = { Text(context.getString(R.string.ui_mode_name)) }, placeholder = { Text(context.getString(R.string.ui_e_g_rapid_clicks)) },
                singleLine = true, isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester))
            Text(modeSummary(context, mode), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }, confirmButton = { Button(onClick = ::save) { Text(if (applyAfterSave) context.getString(R.string.ui_save_and_apply) else context.getString(R.string.ui_save)) } },
        dismissButton = { TextButton(onClick = ::back) { Text(context.getString(R.string.ui_back_to_edit)) } })
    LaunchedEffect(Unit) { focusRequester.requestFocus(); keyboard?.show() }
}
