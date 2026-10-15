package tech.hawkon.dotctrl.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val storageValue: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    ;

    companion object {
        fun fromStorage(value: String?): ThemeMode = entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

private val Colors = lightColorScheme(
    primary = Color(0xFF147562),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC6F1E5),
    onPrimaryContainer = Color(0xFF05382E),
    secondary = Color(0xFF52655E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD8E8E2),
    onSecondaryContainer = Color(0xFF10201B),
    tertiary = Color(0xFFB84F3C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD2),
    onTertiaryContainer = Color(0xFF3D0903),
    background = Color(0xFFF7FAF8),
    onBackground = Color(0xFF18201D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF18201D),
    surfaceVariant = Color(0xFFDDE9E4),
    onSurfaceVariant = Color(0xFF40504A),
    surfaceContainer = Color(0xFFEEF4F1),
    surfaceContainerHigh = Color(0xFFE5EEEA),
    outline = Color(0xFF6D7C75),
    outlineVariant = Color(0xFFC5D2CC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7AD9C0),
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF075443),
    onPrimaryContainer = Color(0xFF9AF5DA),
    secondary = Color(0xFFB5CCC3),
    onSecondary = Color(0xFF20342E),
    secondaryContainer = Color(0xFF354B44),
    onSecondaryContainer = Color(0xFFD1E8DF),
    tertiary = Color(0xFFFFB5A5),
    onTertiary = Color(0xFF5F160C),
    tertiaryContainer = Color(0xFF7D2E22),
    onTertiaryContainer = Color(0xFFFFDAD2),
    background = Color(0xFF101513),
    onBackground = Color(0xFFE0E9E4),
    surface = Color(0xFF101513),
    onSurface = Color(0xFFE0E9E4),
    surfaceVariant = Color(0xFF3F4D48),
    onSurfaceVariant = Color(0xFFC0CEC8),
    surfaceContainer = Color(0xFF1B2421),
    surfaceContainerHigh = Color(0xFF252F2B),
    outline = Color(0xFF899791),
    outlineVariant = Color(0xFF3F4D48),
)

@Composable
fun DotCtrlTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else Colors, content = content)
}
