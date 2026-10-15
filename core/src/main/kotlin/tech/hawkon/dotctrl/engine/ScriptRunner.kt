package tech.hawkon.dotctrl.engine

import tech.hawkon.dotctrl.data.ClickScript
import tech.hawkon.dotctrl.data.ScriptStep
import tech.hawkon.dotctrl.data.validate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

data class Progress(val loop: Int, val step: Int, val completed: Int)
sealed interface RunResult {
    data object Completed : RunResult
    data object TimedOut : RunResult
    data class Failed(val reason: String) : RunResult
}

/** Single sequential executor. Cancellation interrupts delays and awaits; it never queues future gestures. */
class ScriptRunner(
    private val dispatch: suspend (ScriptStep) -> Boolean,
    private val checkEnvironment: () -> String? = { null },
) {
    suspend fun run(script: ClickScript, onProgress: (Progress) -> Unit = {}): RunResult {
        script.validate()
        require(script.steps.isNotEmpty()) { "请先添加动作" }
        return withTimeoutOrNull(script.timeoutMinutes * 60_000L) {
            try {
                var loop = 0
                var completed = 0
                while (script.loopCount == 0 || loop < script.loopCount) {
                    loop++
                    for ((index, step) in script.steps.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        checkEnvironment()?.let { return@withTimeoutOrNull RunResult.Failed(it) }
                        onProgress(Progress(loop, index + 1, completed))
                        if (!dispatch(step)) return@withTimeoutOrNull RunResult.Failed("系统取消或拒绝了手势，已停止")
                        completed++
                        onProgress(Progress(loop, index + 1, completed))
                        val last = script.loopCount != 0 && loop == script.loopCount && index == script.steps.lastIndex
                        if (!last) delay(step.delayMs.coerceAtLeast(1))
                    }
                }
                RunResult.Completed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                RunResult.Failed(failure.message ?: "执行失败")
            }
        } ?: RunResult.TimedOut
    }
}
