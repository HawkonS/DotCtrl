package tech.hawkon.dotctrl

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import tech.hawkon.dotctrl.data.AppLanguage
import tech.hawkon.dotctrl.data.DisplayPreferences
import tech.hawkon.dotctrl.service.RuntimeState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w400dp-h900dp")
class DisplaySettingsDialogTest {
    @get:Rule val compose = createEmptyComposeRule()

    private fun dialogText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    @Test fun displaySettingsUpdateImmediatelyAndSurviveActivityRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DisplayPreferences(context)
        store.preferences.edit().clear().commit()
        ShadowSettings.setCanDrawOverlays(true)
        val previousStatus = RuntimeState.status.value
        RuntimeState.update { it.copy(connected = true) }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("深色模式").assertDoesNotExist()
            compose.onNodeWithText("显示").performClick()
            dialogText("深色").performClick()
            dialogText("显示").assertIsDisplayed()
            compose.runOnIdle { assertEquals("dark", store.preferences.getString("theme_mode", null)) }
            dialogText("英文").performScrollTo().performClick()
            dialogText("Display").assertIsDisplayed()
            dialogText("Dark").assertIsSelected()
            compose.onNodeWithContentDescription("Floating window transparency").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
            dialogText("50%").assertExists()
            compose.runOnIdle {
                assertEquals(AppLanguage.ENGLISH, DisplayPreferences(context).language)
                assertEquals(.5f, DisplayPreferences(context).overlayAlpha, .001f)
                activity.recreate()
            }
            dialogText("Display").assertIsDisplayed()
            dialogText("Dark").assertIsSelected()
            dialogText("50%").assertExists()
            dialogText("中文").performScrollTo().performClick()
            dialogText("深色").assertIsSelected()
            dialogText("完成").performClick()
            compose.onNodeWithText("通用").assertIsDisplayed()
        } finally {
            activity.pause().stop().destroy()
            RuntimeState.update { previousStatus }
            ShadowSettings.setCanDrawOverlays(false)
        }
    }
}
