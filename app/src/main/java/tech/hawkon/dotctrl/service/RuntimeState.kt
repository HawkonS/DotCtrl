package tech.hawkon.dotctrl.service

import tech.hawkon.dotctrl.data.ScreenPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunPhase { IDLE, READY, COUNTDOWN, RUNNING, PAUSED, STOPPED, ERROR }
data class PositionPickResult(val requestId: String, val point: ScreenPoint?)
data class RuntimeStatus(
    val connected: Boolean = false,
    val phase: RunPhase = RunPhase.IDLE,
    val message: String = "先开启无障碍服务",
    val progress: String = "",
) {
    val active get() = phase == RunPhase.COUNTDOWN || phase == RunPhase.RUNNING
}

object RuntimeState {
    private val positionPickMutable = MutableStateFlow<PositionPickResult?>(null)
    val positionPickResults = positionPickMutable.asStateFlow()
    internal fun finishPositionPick(requestId: String, point: ScreenPoint?) {
        positionPickMutable.value = PositionPickResult(requestId, point)
    }
    fun consumePositionPick(requestId: String) {
        if (positionPickMutable.value?.requestId == requestId) positionPickMutable.value = null
    }
    private val editMutable = MutableStateFlow(0)
    val editRequests = editMutable.asStateFlow()
    internal fun requestEditor() { editMutable.value += 1 }
    private val mutable = MutableStateFlow(RuntimeStatus())
    val status = mutable.asStateFlow()
    internal fun update(transform: (RuntimeStatus) -> RuntimeStatus) { mutable.value = transform(mutable.value) }
}
