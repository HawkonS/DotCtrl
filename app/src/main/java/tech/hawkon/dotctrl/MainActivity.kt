package tech.hawkon.dotctrl

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import tech.hawkon.dotctrl.data.DisplayPreferences
import tech.hawkon.dotctrl.ui.DotCtrlApp
import tech.hawkon.dotctrl.service.AutoClickAccessibilityService
import tech.hawkon.dotctrl.ui.DotCtrlTheme
import tech.hawkon.dotctrl.ui.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val displayPreferences = remember { DisplayPreferences(this) }
            val preferences = displayPreferences.preferences
            var language by remember { mutableStateOf(displayPreferences.language) }
            var transparency by remember { mutableFloatStateOf(displayPreferences.transparency) }
            val configuration = LocalConfiguration.current
            val localizedContext = remember(language, configuration) { language.localizedContext(this) }
            var themeMode by rememberSaveable {
                mutableStateOf(ThemeMode.fromStorage(preferences.getString("theme_mode", null)))
            }
            val darkMode = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
            ) {
                DotCtrlTheme(darkTheme = darkMode) {
                    DotCtrlApp(
                        onBackground = { moveTaskToBack(true) },
                        onExit = {
                            AutoClickAccessibilityService.instance?.closePanel()
                            finishAndRemoveTask()
                        },
                        themeMode = themeMode,
                        onThemeModeChange = {
                            themeMode = it
                            preferences.edit().putString("theme_mode", it.storageValue).remove("dark_mode").apply()
                            AutoClickAccessibilityService.instance?.configurationChanged()
                        },
                        language = language,
                        onLanguageChange = { language = it; displayPreferences.language = it },
                        overlayTransparency = transparency,
                        onOverlayTransparencyChange = { transparency = it; displayPreferences.transparency = it },
                    )
                }
            }
        }
    }
}
