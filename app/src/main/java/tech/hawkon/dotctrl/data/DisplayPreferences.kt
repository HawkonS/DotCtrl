package tech.hawkon.dotctrl.data

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.view.ContextThemeWrapper
import java.util.Locale
import androidx.annotation.StringRes
import tech.hawkon.dotctrl.R

enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    CHINESE("zh", R.string.language_chinese),
    ENGLISH("en", R.string.language_english),
    ;

    fun localizedContext(context: Context): Context = ContextThemeWrapper(context, context.theme).apply {
        // Preserve the base Activity so Compose can find its activity-result owner.
        applyOverrideConfiguration(Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(tag)))
        })
    }

    companion object {
        fun fromStorage(value: String?): AppLanguage = entries.firstOrNull { it.tag == value } ?: CHINESE
    }
}

class DisplayPreferences(context: Context) {
    val preferences = context.getSharedPreferences("app_preferences", Context.MODE_PRIVATE)

    var language: AppLanguage
        get() = AppLanguage.fromStorage(preferences.getString(LANGUAGE_KEY, null))
        set(value) { preferences.edit().putString(LANGUAGE_KEY, value.tag).apply() }

    // Keep the controls visible and touchable even at the most transparent setting.
    var transparency: Float
        get() = normalizeTransparency(preferences.getFloat(TRANSPARENCY_KEY, 0f))
        set(value) { preferences.edit().putFloat(TRANSPARENCY_KEY, normalizeTransparency(value)).apply() }

    val overlayAlpha: Float get() = 1f - transparency

    companion object {
        const val LANGUAGE_KEY = "app_language"
        const val TRANSPARENCY_KEY = "overlay_transparency"
        const val MAX_TRANSPARENCY = .8f

        fun normalizeTransparency(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, MAX_TRANSPARENCY) else 0f
    }
}
