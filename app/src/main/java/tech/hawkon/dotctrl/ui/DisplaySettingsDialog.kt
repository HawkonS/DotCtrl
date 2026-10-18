package tech.hawkon.dotctrl.ui

import androidx.compose.ui.platform.LocalContext
import tech.hawkon.dotctrl.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.hawkon.dotctrl.data.AppLanguage
import tech.hawkon.dotctrl.data.DisplayPreferences
import kotlin.math.roundToInt

@Composable
internal fun DisplaySettingsDialog(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    overlayTransparency: Float,
    onOverlayTransparencyChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.getString(R.string.ui_display)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.selectableGroup()) {
                    DisplaySectionTitle(context.getString(R.string.ui_color))
                    listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM).forEach { option ->
                        DisplayChoice(context.getString(option.labelRes), themeMode == option) { onThemeModeChange(option) }
                    }
                }
                HorizontalDivider()
                Column(Modifier.selectableGroup()) {
                    DisplaySectionTitle(context.getString(R.string.ui_language))
                    AppLanguage.entries.forEach { option ->
                        DisplayChoice(context.getString(option.labelRes), language == option) { onLanguageChange(option) }
                    }
                }
                HorizontalDivider()
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(context.getString(R.string.ui_floating_window_transparency), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text("${(overlayTransparency * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = overlayTransparency,
                        onValueChange = onOverlayTransparencyChange,
                        valueRange = 0f..DisplayPreferences.MAX_TRANSPARENCY,
                        modifier = Modifier.semantics { contentDescription = context.getString(R.string.ui_floating_window_transparency) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(context.getString(R.string.ui_opaque), style = MaterialTheme.typography.bodySmall)
                        Text(context.getString(R.string.ui_more_transparent), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(context.getString(R.string.ui_done)) } },
    )
}

@Composable
private fun DisplaySectionTitle(title: String) {
    Text(title, Modifier.padding(bottom = 4.dp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun DisplayChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}
