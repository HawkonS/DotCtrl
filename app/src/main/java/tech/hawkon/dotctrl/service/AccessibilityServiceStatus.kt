package tech.hawkon.dotctrl.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false

    return enabledServices.split(':').any { flattened ->
        val component = ComponentName.unflattenFromString(flattened) ?: return@any false
        component.packageName == context.packageName &&
            component.className == AutoClickAccessibilityService::class.java.name
    }
}
