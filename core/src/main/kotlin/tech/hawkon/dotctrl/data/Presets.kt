package tech.hawkon.dotctrl.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class PositionPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val x: Float,
    val y: Float,
    val orientation: Int,
) {
    fun applyTo(settings: ClickSettings) = settings.copy(x = x, y = y, orientation = orientation, positionSet = true)
    fun matches(settings: ClickSettings) = settings.positionSet && x == settings.x && y == settings.y && orientation == settings.orientation
    fun validate() {
        require(id.isNotBlank() && id.length <= 100) { "位置标识无效" }
        require(name.isNotBlank() && name.length <= 24) { "名称须为 1–24 字符" }
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) { "坐标须为 0–100%" }
        require(orientation in 1..2) { "屏幕方向无效" }
    }
}

@Serializable
data class ClickMode(
    val intervalMs: Long = 100,
    val repeatCount: Int = 100,
    val continuous: Boolean = false,
    val gestureType: GestureType = GestureType.TAP,
    val gestureDurationMs: Long = 80,
    val timeoutMinutes: Int = 30,
) {
    fun applyTo(settings: ClickSettings) = settings.copy(
        intervalMs = intervalMs, repeatCount = repeatCount, continuous = continuous,
        gestureType = gestureType, gestureDurationMs = gestureDurationMs, timeoutMinutes = timeoutMinutes,
    )
    fun validate() = applyTo(ClickSettings()).validate()
    companion object {
        fun from(settings: ClickSettings) = ClickMode(settings.intervalMs, settings.repeatCount,
            settings.continuous, settings.gestureType, settings.gestureDurationMs, settings.timeoutMinutes)
    }
}

@Serializable
data class ModePreset(val id: String = UUID.randomUUID().toString(), val name: String, val mode: ClickMode = ClickMode()) {
    fun validate() {
        require(id.isNotBlank() && id.length <= 100) { "模式标识无效" }
        require(name.isNotBlank() && name.length <= 24) { "名称须为 1–24 字符" }
        mode.validate()
    }
}

val builtInModes = listOf(
    ModePreset("builtin-standard", "标准", ClickMode(intervalMs = 500)),
    ModePreset("builtin-fast", "快速", ClickMode(intervalMs = 100)),
    ModePreset("builtin-hold", "长按", ClickMode(intervalMs = 1_000, gestureType = GestureType.LONG_PRESS, gestureDurationMs = 600)),
)

fun builtInSwipeScript(orientation: Int): ClickScript = ClickScript(
    id = "builtin-swipe-down",
    name = "下滑",
    orientation = orientation,
    loopCount = 1,
    timeoutMinutes = 30,
    positionSet = true,
    builtIn = true,
    steps = listOf(
        ScriptStep(
            id = "builtin-swipe-down-step",
            gestureType = GestureType.SWIPE,
            x = 0.5f,
            y = 0.28f,
            endX = 0.5f,
            endY = 0.72f,
            durationMs = 420,
            delayMs = 400,
        ),
    ),
)

fun ensureBuiltInScripts(scripts: List<ClickScript>, orientation: Int): List<ClickScript> {
    val builtIn = builtInSwipeScript(orientation)
    val existingIndex = scripts.indexOfFirst { it.id == builtIn.id }
    return if (existingIndex < 0) scripts + builtIn
    else scripts.mapIndexed { index, script -> if (index == existingIndex) builtIn else script }
}

@Serializable
data class PresetLibrary(val version: Int = 1, val positions: List<PositionPreset> = emptyList(), val modes: List<ModePreset> = emptyList()) {
    fun validate() {
        require(version == 1) { "不支持的预设版本" }
        require(positions.size <= 100 && modes.size <= 100) { "最多保存 100 个位置或模式" }
        require(positions.map { it.id }.distinct().size == positions.size && modes.map { it.id }.distinct().size == modes.size) { "预设标识重复" }
        require(modes.none { saved -> builtInModes.any { it.id == saved.id } }) { "不能覆盖内置模式" }
        positions.forEach { it.validate() }
        modes.forEach { it.validate() }
    }
    val allModes get() = modes + builtInModes
    fun positionName(settings: ClickSettings) = if (!settings.positionSet) "未选择" else positions.firstOrNull { it.matches(settings) }?.name ?: "自定义"
    fun modeName(settings: ClickSettings) = allModes.firstOrNull { it.mode == ClickMode.from(settings) }?.name ?: "自定义"
}
