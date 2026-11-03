package tech.hawkon.dotctrl

import android.content.Context
import android.view.WindowManager
import android.widget.LinearLayout
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
import tech.hawkon.dotctrl.service.RunPhase
import tech.hawkon.dotctrl.service.RuntimeState
import tech.hawkon.dotctrl.service.RuntimeStatus
import tech.hawkon.dotctrl.service.screenArea

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayTransparencyTest {
    @Test fun startControlReceivesAStandardTapAfterShowingPanel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = Shadow.extract<ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java))
        var starts = 0
        val overlay = OverlayController(context, { starts++ }, {}, {}, {}, {}, {}, {}, {})
        val settings = ClickSettings(positionSet = true, orientation = context.screenArea().orientation)
        RuntimeState.update { RuntimeStatus(connected = true, phase = RunPhase.READY) }
        try {
            overlay.show(settings.asScript(), false, settings, PresetLibrary())
            val panel = manager.views.last()
            val header = (panel as LinearLayout).getChildAt(0) as LinearLayout
            val button = header.getChildAt(0)
            assertTrue(button.performClick())
            assertEquals(1, starts)
        } finally {
            overlay.dismiss()
            RuntimeState.update { RuntimeStatus() }
        }
    }

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
            // Configuration changes refresh the existing panel through configurationChanged().
            overlay.show(settings.asScript(), false, settings, PresetLibrary())
            assertEquals(2, manager.views.size)
            val restoredPanel = manager.views.filterIsInstance<LinearLayout>().single()
            assertEquals(originalFlags, (restoredPanel.layoutParams as WindowManager.LayoutParams).flags)
            store.transparency = .5f
            overlay.updateDisplaySettings(languageChanged = false)
            assertEquals(1f, restoredPanel.alpha, 0f)
            assertEquals(.5f, restoredPanel.background.alpha / 255f, .01f)
            val compactHeader = (restoredPanel as LinearLayout).getChildAt(0) as LinearLayout
            assertEquals(.5f, compactHeader.getChildAt(0).alpha, 0f)
            compactHeader.getChildAt(compactHeader.childCount - 1).performClick()
            val expandedPanel = manager.views.filterIsInstance<LinearLayout>().single()
            assertEquals(1f, expandedPanel.alpha, 0f)
            assertEquals(.5f, expandedPanel.background.alpha / 255f, .01f)
            val expandedHeader = (expandedPanel as LinearLayout).getChildAt(0) as LinearLayout
            assertEquals(.5f, expandedHeader.getChildAt(0).alpha, 0f)
            assertEquals(1f, (restoredPanel.layoutParams as WindowManager.LayoutParams).alpha, 0f)

            overlay.pick("Pick position", onSelected = {})
            assertEquals(3, manager.views.size)
            store.transparency = .75f
            overlay.updateDisplaySettings(languageChanged = false)
            assertEquals(1f, manager.views[0].alpha, 0f)
            assertEquals(.25f, manager.views[0].background.alpha / 255f, .01f)
            assertEquals(.25f, manager.views[1].alpha, 0f)
            assertEquals(1f, manager.views[2].alpha, 0f)
            assertEquals(.25f, (manager.views[2] as LinearLayout).getChildAt(0).alpha, 0f)
            assertTrue(manager.views.all { (it.layoutParams as WindowManager.LayoutParams).alpha == 1f })

            overlay.dismiss()
            assertTrue(manager.views.isEmpty())
            overlay.show(settings.asScript(), false, settings, PresetLibrary())
            assertEquals(1f, manager.views.last().alpha, 0f)
            assertEquals(.25f, manager.views.last().background.alpha / 255f, .01f)
            assertEquals(1f, (manager.views.last().layoutParams as WindowManager.LayoutParams).alpha, 0f)
        } finally {
            overlay.dismiss()
        }
    }

}
