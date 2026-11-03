package tech.hawkon.dotctrl

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import tech.hawkon.dotctrl.data.*
import tech.hawkon.dotctrl.service.*
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp")
class OverlayPanelTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = Shadow.extract<ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java))
    private val settings = ClickSettings(positionSet = true, orientation = context.screenArea().orientation)
    private val previousStatus = RuntimeState.status.value
    private var starts = 0
    private var stops = 0
    private lateinit var overlay: OverlayController

    @Before fun showPanel() {
        // Robolectric does not populate the maximum window bounds for an app context.
        val windowConfiguration = ReflectionHelpers.getField<Any>(context.resources.configuration, "windowConfiguration")
        ReflectionHelpers.callInstanceMethod<Unit>(windowConfiguration, "setMaxBounds",
            ClassParameter.from(Rect::class.java, Rect(0, 0, 1080, 2400)))
        DisplayPreferences(context).apply { language = AppLanguage.CHINESE; transparency = .5f }
        RuntimeState.update { RuntimeStatus(connected = true, phase = RunPhase.READY) }
        overlay = OverlayController(context, { starts++ }, { stops++ }, {}, {}, {}, {}, {}, {})
        overlay.show(settings.asScript(), false, settings, PresetLibrary())
        ShadowLooper.idleMainLooper()
    }

    @After fun dismissPanel() {
        overlay.dismiss()
        RuntimeState.update { previousStatus }
        assertTrue(manager.views.isEmpty())
    }

    @Test fun expandingAndCollapsingKeepWindowHeaderAndBodyAttached() {
        val panel = manager.views.last() as LinearLayout
        val header = panel.getChildAt(0) as LinearLayout
        val start = header.getChildAt(0)
        val toggle = header.getChildAt(header.childCount - 1)
        val scroll = panel.getChildAt(1) as ScrollView
        val body = scroll.getChildAt(0)
        val background = panel.background
        val origin = panel.layoutParams as WindowManager.LayoutParams
        val x = origin.x
        val y = origin.y
        val compactWidth = origin.width
        var detaches = 0
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { detaches++ }
        })

        repeat(10) {
            assertTrue(toggle.performClick())
            ShadowLooper.idleMainLooper()
            val expanded = it % 2 == 0
            assertSame(panel, manager.views.last())
            assertSame(header, panel.getChildAt(0))
            assertSame(start, header.getChildAt(0))
            assertSame(toggle, header.getChildAt(header.childCount - 1))
            assertSame(body, scroll.getChildAt(0))
            assertSame(background, panel.background)
            assertTrue(panel.isAttachedToWindow)
            assertEquals(0, detaches)
            assertEquals(if (expanded) View.VISIBLE else View.GONE, scroll.visibility)
            assertEquals(if (expanded) "收起" else "展开", toggle.contentDescription)
            val params = panel.layoutParams as WindowManager.LayoutParams
            assertEquals(x, params.x)
            assertEquals(y, params.y)
            if (expanded) assertTrue("compact=$compactWidth expanded=${params.width} area=${context.screenArea()}", params.width > compactWidth)
            else assertEquals(compactWidth, params.width)
            assertEquals(.5f, start.alpha, 0f)
            assertEquals(.5f, background.alpha / 255f, .01f)
            assertTrue(start.performClick())
        }
        assertEquals(10, starts)
        assertEquals(0, stops)
    }

    @Test fun pageNavigationUpdatesBodyAndKeyboardFocusWithoutReplacingHeader() {
        val panel = manager.views.last() as LinearLayout
        val header = panel.getChildAt(0) as LinearLayout
        val start = header.getChildAt(0)
        val toggle = header.getChildAt(header.childCount - 1)
        val scroll = panel.getChildAt(1) as ScrollView
        toggle.performClick()
        val main = scroll.getChildAt(0) as LinearLayout
        main.getChildAt(1).performClick() // Modes.
        val modes = scroll.getChildAt(0) as LinearLayout
        modes.getChildAt(modes.childCount - 1).performClick() // Custom mode with text inputs.
        val custom = scroll.getChildAt(0) as LinearLayout
        assertNotSame(modes, custom)
        assertSame(panel, manager.views.last())
        assertSame(start, header.getChildAt(0))
        assertFalse(start.isEnabled)
        assertEquals(0, (panel.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        toggle.performClick() // Collapse back to the main page and restore touch-through focus policy.
        assertSame(panel, manager.views.last())
        assertSame(header, panel.getChildAt(0))
        assertSame(toggle, header.getChildAt(header.childCount - 1))
        assertEquals(View.GONE, scroll.visibility)
        assertTrue(start.isEnabled)
        assertTrue((panel.layoutParams as WindowManager.LayoutParams).flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        toggle.performClick()
        RuntimeState.update { it.copy(phase = RunPhase.RUNNING, progress = "3 clicks") }
        ShadowLooper.idleMainLooper()
        assertEquals("停止", start.contentDescription)
        assertEquals("3 clicks", (header.getChildAt(1) as TextView).text.toString())
        start.performClick()
        assertEquals(1, stops)
    }

    @Test fun stopTapCannotBecomeStartWhenGestureCancellationChangesStateBeforeRelease() {
        val panel = manager.views.filterIsInstance<LinearLayout>().single()
        val run = (panel.getChildAt(0) as LinearLayout).getChildAt(0)
        RuntimeState.update { it.copy(phase = RunPhase.RUNNING) }
        ShadowLooper.idleMainLooper()
        val downTime = SystemClock.uptimeMillis()
        touch(run, MotionEvent.ACTION_DOWN, downTime)

        // Touching the screen can cancel an injected gesture before the finger lifts.
        RuntimeState.update { it.copy(phase = RunPhase.ERROR) }
        ShadowLooper.idleMainLooper()
        touch(run, MotionEvent.ACTION_UP, downTime)
        ShadowLooper.idleMainLooper()

        assertEquals(0, starts)
        assertEquals(1, stops)
    }

    @Test fun readyControlStartsOnceOnRelease() {
        val panel = manager.views.filterIsInstance<LinearLayout>().single()
        val run = (panel.getChildAt(0) as LinearLayout).getChildAt(0)
        val downTime = SystemClock.uptimeMillis()
        touch(run, MotionEvent.ACTION_DOWN, downTime)
        assertEquals(0, starts)
        touch(run, MotionEvent.ACTION_UP, downTime)
        ShadowLooper.idleMainLooper()
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test fun cancellationDeliveredBeforeTouchDownStillPreservesTheOriginalStopPress() {
        val panel = manager.views.filterIsInstance<LinearLayout>().single()
        val run = (panel.getChildAt(0) as LinearLayout).getChildAt(0)
        RuntimeState.update { it.copy(phase = RunPhase.RUNNING) }
        ShadowLooper.idleMainLooper()
        val downTime = SystemClock.uptimeMillis()
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(20))
        RuntimeState.update { it.copy(phase = RunPhase.ERROR) }
        ShadowLooper.idleMainLooper()

        // Android created the physical DOWN before cancelling the injected gesture,
        // but delivered its cancellation callback to the app first.
        touch(run, MotionEvent.ACTION_DOWN, downTime)
        touch(run, MotionEvent.ACTION_UP, downTime)
        ShadowLooper.idleMainLooper()
        assertEquals(0, starts)
        assertEquals(1, stops)

        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(20))
        val newPress = SystemClock.uptimeMillis()
        touch(run, MotionEvent.ACTION_DOWN, newPress)
        touch(run, MotionEvent.ACTION_UP, newPress)
        ShadowLooper.idleMainLooper()
        assertEquals(1, starts)
        assertEquals(1, stops)
    }

    private fun touch(view: View, action: Int, downTime: Long = SystemClock.uptimeMillis()) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, view.width / 2f, view.height / 2f, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
}
