package tech.hawkon.dotctrl.data

import android.content.Context
import tech.hawkon.dotctrl.R

// Translate built-in labels by identity; user-created names stay as entered.
fun ModePreset.displayName(context: Context): String = when (id) {
    "builtin-standard" -> context.getString(R.string.mode_standard)
    "builtin-fast" -> context.getString(R.string.mode_fast)
    "builtin-hold" -> context.getString(R.string.ui_long_press)
    else -> name
}

fun ClickScript.displayName(context: Context): String =
    if (builtIn && id == "builtin-swipe-down") context.getString(R.string.script_swipe_down) else name

fun PresetLibrary.positionLabel(context: Context, settings: ClickSettings): String =
    if (!settings.positionSet) context.getString(R.string.position_not_selected)
    else positions.firstOrNull { it.matches(settings) }?.name ?: context.getString(R.string.ui_custom)

fun PresetLibrary.modeLabel(context: Context, settings: ClickSettings): String =
    allModes.firstOrNull { it.mode == ClickMode.from(settings) }?.displayName(context) ?: context.getString(R.string.ui_custom)
