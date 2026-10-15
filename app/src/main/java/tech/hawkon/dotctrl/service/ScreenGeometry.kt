package tech.hawkon.dotctrl.service

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import tech.hawkon.dotctrl.data.ScreenArea

@Suppress("DEPRECATION", "DiscouragedApi")
fun Context.screenArea(): ScreenArea {
    val manager = getSystemService(WindowManager::class.java)
    if (Build.VERSION.SDK_INT >= 30) {
        val metrics = manager.maximumWindowMetrics
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return ScreenArea(bounds.left + insets.left, bounds.top + insets.top,
            (bounds.width() - insets.left - insets.right).coerceAtLeast(2),
            (bounds.height() - insets.top - insets.bottom).coerceAtLeast(2))
    }
    // On Android 8/9 the overlay is laid out in real-screen coordinates. Use
    // the real display size and remove both system bars so saved points match
    // the area the user can actually touch.
    val real = DisplayMetrics()
    manager.defaultDisplay.getRealMetrics(real)
    fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
    val top = systemDimension("status_bar_height")
    val portrait = real.heightPixels >= real.widthPixels
    if (portrait) {
        val bottom = systemDimension("navigation_bar_height")
        return ScreenArea(0, top, real.widthPixels.coerceAtLeast(2),
            (real.heightPixels - top - bottom).coerceAtLeast(2))
    }
    // In landscape the navigation bar is normally a side inset. Some OEMs
    // expose only navigation_bar_height, so fall back to that value when a
    // dedicated width resource is not available.
    val side = systemDimension("navigation_bar_width").takeIf { it > 0 }
        ?: systemDimension("navigation_bar_height")
    return ScreenArea(0, top, (real.widthPixels - side).coerceAtLeast(2),
        (real.heightPixels - top).coerceAtLeast(2))
}
