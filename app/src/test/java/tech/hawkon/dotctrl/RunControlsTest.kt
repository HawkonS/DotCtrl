package tech.hawkon.dotctrl

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowKeyguardManager
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import tech.hawkon.dotctrl.data.AppLanguage
import tech.hawkon.dotctrl.data.ClickSettings
import tech.hawkon.dotctrl.data.ClickSettingsStore
import tech.hawkon.dotctrl.data.DisplayPreferences
import tech.hawkon.dotctrl.service.AutoClickAccessibilityService
import tech.hawkon.dotctrl.service.RunPhase
import tech.hawkon.dotctrl.service.RuntimeState
import tech.hawkon.dotctrl.service.screenArea
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp")
class RunControlsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val previousStatus = RuntimeState.status.value
    private lateinit var controller: ServiceController<AutoClickAccessibilityService>
    private lateinit var service: AutoClickAccessibilityService
    private lateinit var accessibility: ShadowAccessibilityService
    private lateinit var run: View
    private lateinit var markers: View

    @Before fun prepareRun() {
        val windowConfiguration = ReflectionHelpers.getField<Any>(context.resources.configuration, "windowConfiguration")
        ReflectionHelpers.callInstanceMethod<Unit>(windowConfiguration, "setMaxBounds",
            ClassParameter.from(Rect::class.java, Rect(0, 0, 1080, 2400)))
        ShadowSettings.setCanDrawOverlays(true)
        Shadow.extract<ShadowPowerManager>(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        Shadow.extract<ShadowKeyguardManager>(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        DisplayPreferences(context).language = AppLanguage.CHINESE
        controller = Robolectric.buildService(AutoClickAccessibilityService::class.java).create()
        service = controller.get()
        accessibility = Shadow.extract(service)
        accessibility.setCanDispatchGestures(true)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        ClickSettingsStore(service).write(ClickSettings(x = .8f, y = .8f, positionSet = true,
            continuous = true, orientation = service.screenArea().orientation))
        service.prepare()
        ShadowLooper.idleMainLooper()
        val manager = Shadow.extract<ShadowWindowManagerImpl>(service.getSystemService(WindowManager::class.java))
        val panel = manager.views.filterIsInstance<LinearLayout>().single()
        markers = manager.views.single { it !is LinearLayout }
        run = (panel.getChildAt(0) as LinearLayout).getChildAt(0)
    }

    @After fun destroyService() {
        controller.destroy()
        ShadowLooper.idleMainLooper()
        RuntimeState.update { previousStatus }
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test fun countdownShowsEachSecondAndDispatchesOnlyAfterThreeFullSeconds() {
        val startedAt = SystemClock.uptimeMillis()
        run.performClick()
        ShadowLooper.idleMainLooper()
        for (seconds in 3 downTo 1) {
            assertEquals(RunPhase.COUNTDOWN, RuntimeState.status.value.phase)
            assertEquals(seconds, RuntimeState.status.value.countdownSeconds)
            assertEquals("$seconds 秒后开始，点击取消", run.contentDescription)
            assertTrue(accessibility.gesturesDispatched.isEmpty())
            // Hiding the marker may schedule a view traversal during idle().
            // Assert against the start time rather than adding that layout time.
            val nextSecond = startedAt + (4 - seconds) * 1_000L
            advance(nextSecond - 1 - SystemClock.uptimeMillis())
            assertEquals(seconds, RuntimeState.status.value.countdownSeconds)
            assertTrue(accessibility.gesturesDispatched.isEmpty())
            advance(1)
        }
        assertEquals(RunPhase.RUNNING, RuntimeState.status.value.phase)
        assertEquals(0, RuntimeState.status.value.countdownSeconds)
        assertEquals("停止", run.contentDescription)
        assertEquals(1, accessibility.gesturesDispatched.size)
    }

    @Test fun cancellingAtAnyCountdownSecondNeverDispatchesOrRestartsOnRelease() {
        for (elapsedSeconds in 0..2) {
            run.performClick()
            advance(elapsedSeconds * 1_000L)
            assertEquals(RunPhase.COUNTDOWN, RuntimeState.status.value.phase)
            val downTime = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, downTime)
            assertEquals(RunPhase.STOPPED, RuntimeState.status.value.phase)
            touch(MotionEvent.ACTION_UP, downTime)
            advance(4_000)
            assertEquals(RunPhase.STOPPED, RuntimeState.status.value.phase)
            assertEquals(0, RuntimeState.status.value.countdownSeconds)
            assertEquals("开始", run.contentDescription)
            assertTrue(accessibility.gesturesDispatched.isEmpty())
        }
    }

    @Test fun oneStopPressDuringExecutionIgnoresLateGestureCallbacksAndDoesNotRestart() {
        run.performClick()
        advance(3_000)
        val first = accessibility.gesturesDispatched.single()
        first.callback().onCompleted(first.description())
        advance(100)
        assertEquals(2, accessibility.gesturesDispatched.size)
        val inFlight = accessibility.gesturesDispatched.last()

        val downTime = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, downTime)
        assertEquals(RunPhase.STOPPED, RuntimeState.status.value.phase)
        inFlight.callback().onCancelled(inFlight.description())
        ShadowLooper.idleMainLooper()
        touch(MotionEvent.ACTION_UP, downTime)
        advance(5_000)

        assertEquals(RunPhase.STOPPED, RuntimeState.status.value.phase)
        assertEquals("开始", run.contentDescription)
        assertEquals(2, accessibility.gesturesDispatched.size)
    }

    @Test fun coordinateMarkersAreHiddenBeforeGesturesAndRestoredAfterStopping() {
        assertEquals(View.VISIBLE, markers.visibility)
        run.performClick()
        ShadowLooper.idleMainLooper()
        assertEquals(View.GONE, markers.visibility)
        assertTrue(accessibility.gesturesDispatched.isEmpty())
        advance(3_000)
        assertEquals(1, accessibility.gesturesDispatched.size)
        assertEquals(View.GONE, markers.visibility)
        service.stop()
        ShadowLooper.idleMainLooper()
        assertEquals(View.VISIBLE, markers.visibility)
    }

    private fun advance(millis: Long) { ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(millis)) }

    private fun touch(action: Int, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, run.width / 2f, run.height / 2f, 0)
        try { assertTrue(run.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
}
