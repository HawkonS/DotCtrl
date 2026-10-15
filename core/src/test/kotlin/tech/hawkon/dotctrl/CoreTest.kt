package tech.hawkon.dotctrl

import tech.hawkon.dotctrl.data.*
import tech.hawkon.dotctrl.engine.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun mapsNormalizedCoordinatesToAvailableArea() {
        val area = ScreenArea(10, 30, 100, 200)
        assertEquals(10f to 30f, CoordinateMapper.toPixels(ScreenPoint(0f, 0f, 1), area))
        assertEquals(109f to 229f, CoordinateMapper.toPixels(ScreenPoint(1f, 1f, 1), area))
        val normalized = CoordinateMapper.normalize(59f, 129f, area)
        assertEquals(0.4949f, normalized.x, 0.001f)
        assertEquals(0.4975f, normalized.y, 0.001f)
    }

    @Test fun codecRejectsInvalidCoordinatesAndRoundTrips() {
        val script = ClickScript(steps = listOf(ScriptStep(x = .2f, y = .8f)))
        val json = ScriptCodec.encode(listOf(script))
        assertEquals(script, ScriptCodec.decode(json).single())
        assertThrows(IllegalArgumentException::class.java) {
            ScriptCodec.encode(listOf(script.copy(steps = listOf(ScriptStep(x = 2f)))))
        }
    }

    @Test fun runnerStopsAfterRequestedLoops() = runTest {
        val dispatched = mutableListOf<ScriptStep>()
        val result = ScriptRunner({ dispatched += it; true }).run(
            ClickScript(loopCount = 2, steps = listOf(ScriptStep(delayMs = 1))))
        assertEquals(RunResult.Completed, result)
        assertEquals(2, dispatched.size)
    }

    @Test fun runnerReportsDispatchFailure() = runTest {
        val result = ScriptRunner({ false }).run(ClickScript(steps = listOf(ScriptStep())))
        assertTrue(result is RunResult.Failed)
    }

    @Test fun runnerHonorsSafetyTimeout() = runTest {
        val job = backgroundScope.launch {
            val result = ScriptRunner({ true }).run(
                ClickScript(loopCount = 0, timeoutMinutes = 1,
                    steps = listOf(ScriptStep(delayMs = 60_000))))
            assertEquals(RunResult.TimedOut, result)
        }
        advanceTimeBy(60_001)
        job.join()
    }

    @Test fun cancellingRunnerStopsWithoutDispatchingMoreActions() = runTest {
        val dispatched = mutableListOf<ScriptStep>()
        val job = backgroundScope.launch {
            ScriptRunner({ dispatched += it; true }).run(
                ClickScript(loopCount = 0, steps = listOf(ScriptStep(delayMs = 60_000))))
        }
        advanceTimeBy(1)
        job.cancel()
        job.join()
        assertEquals(1, dispatched.size)
    }
}
