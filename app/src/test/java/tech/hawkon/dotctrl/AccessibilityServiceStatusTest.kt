package tech.hawkon.dotctrl

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.hawkon.dotctrl.service.AutoClickAccessibilityService
import tech.hawkon.dotctrl.service.isAccessibilityServiceEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityServiceStatusTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var previousEnabledServices: String? = null

    @Before fun preserveEnabledServices() {
        previousEnabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    }

    @After fun restoreEnabledServices() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            previousEnabledServices,
        )
    }

    @Test fun detectsDotCtrlAmongEnabledServices() {
        val dotCtrl = ComponentName(context, AutoClickAccessibilityService::class.java).flattenToString()
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "other.package/.OtherService:$dotCtrl",
        )

        assertTrue(isAccessibilityServiceEnabled(context))
    }

    @Test fun reportsDisabledWhenDotCtrlIsAbsent() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "other.package/.OtherService",
        )

        assertFalse(isAccessibilityServiceEnabled(context))
    }
}
