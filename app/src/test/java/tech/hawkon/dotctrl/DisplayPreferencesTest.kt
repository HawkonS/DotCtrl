package tech.hawkon.dotctrl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.hawkon.dotctrl.data.AppLanguage
import tech.hawkon.dotctrl.data.DisplayPreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun reset() {
        DisplayPreferences(context).preferences.edit().clear().commit()
    }

    @Test fun languageAndTransparencySurviveNewStoreAndLocalizeResources() {
        DisplayPreferences(context).apply {
            language = AppLanguage.ENGLISH
            transparency = .55f
        }
        val restored = DisplayPreferences(context)
        assertEquals(AppLanguage.ENGLISH, restored.language)
        assertEquals(.45f, restored.overlayAlpha, .001f)
        val english = restored.language.localizedContext(context)
        assertEquals("Display", english.getString(R.string.ui_display))
        assertEquals("Light", english.getString(R.string.theme_light))
        assertEquals("显示", AppLanguage.CHINESE.localizedContext(english).getString(R.string.ui_display))
    }

    @Test fun invalidPreferencesRecoverToVisibleControls() {
        val store = DisplayPreferences(context)
        store.preferences.edit().putString(DisplayPreferences.LANGUAGE_KEY, "invalid")
            .putFloat(DisplayPreferences.TRANSPARENCY_KEY, Float.NaN).commit()
        assertEquals(AppLanguage.CHINESE, store.language)
        assertEquals(1f, store.overlayAlpha, 0f)
        store.transparency = 1f
        assertEquals(.2f, store.overlayAlpha, .001f)
        store.transparency = -1f
        assertEquals(1f, store.overlayAlpha, 0f)
    }
}
