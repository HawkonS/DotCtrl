package tech.hawkon.dotctrl

import android.content.Context
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import tech.hawkon.dotctrl.data.*
import tech.hawkon.dotctrl.service.OverlayController
import tech.hawkon.dotctrl.service.screenArea

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayTransparencyTest {
    @Test fun transparencyUpdatesExistingControlsAndPickerAndSurvivesReopening() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DisplayPreferences(context)
        store.preferences.edit().clear().commit()
        val manager = Shadow.extract<ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java))
        val overlay = OverlayController(context, {}, {}, {}, {}, {}, {}, {}, {})
        val settings = ClickSettings(positionSet = true, orientation = context.screenArea().orientation)
        try {
            overlay.show(settings.asScript(), false, settings, PresetLibrary())
            assertEquals(2, manager.views.size)
            val panel = manager.views.last()
            val originalFlags = (panel.layoutParams as WindowManager.LayoutParams).flags
            store.preferences.edit().putString("theme_mode", "light").commit()
            assertEquals(originalFlags, (panel.layoutParams as WindowManager.LayoutParams).flags)
            assertTrue(originalFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
            assertEquals(1f, (panel.layoutParams as WindowManager.LayoutParams).alpha, 0f)
            assertEquals(1f, panel.alpha, 0f)
            // Theme changes recreate the current overlay through configurationChanged().
            overlay.show(settings.asScript(), false, settings, PresetLibrary())
            assertEquals(2, manager.views.size)
            val restoredPanel = manager.views.last()
            assertEquals(originalFlags, (restoredPanel.layoutParams as WindowManager.LayoutParams).flags)
            store.transparency = .5f
            overlay.updateDisplaySettings(languageChanged = false)
            assertEquals(.5f, restoredPanel.alpha, 0f)
            assertEquals(1f, (restoredPanel.layoutParams as WindowManager.LayoutParams).alpha, 0f)

            overlay.pick("Pick position", onSelected = {})
            assertEquals(3, manager.views.size)
            store.transparency = .75f
            overlay.updateDisplaySettings(languageChanged = false)
            manager.views.forEach { assertEquals(.25f, it.alpha, .001f) }
            assertTrue(manager.views.all { (it.layoutParams as WindowManager.LayoutParams).alpha == 1f })

            overlay.dismiss()
            assertTrue(manager.views.isEmpty())
            overlay.show(settings.asScript(), false, settings, PresetLibrary())
            assertEquals(.25f, manager.views.last().alpha, 0f)
            assertEquals(1f, (manager.views.last().layoutParams as WindowManager.LayoutParams).alpha, 0f)
        } finally {
            overlay.dismiss()
        }
    }
}
