package tech.hawkon.dotctrl.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
enum class GestureType { TAP, LONG_PRESS, SWIPE }

@Serializable
data class ClickSettings(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val orientation: Int = 1,
    val intervalMs: Long = 100,
    val repeatCount: Int = 100,
    val continuous: Boolean = false,
    val gestureType: GestureType = GestureType.TAP,
    val gestureDurationMs: Long = 80,
    val timeoutMinutes: Int = 30,
    /** True after the user confirms a screen position in the floating picker. */
    val positionSet: Boolean = false,
) {
    fun asScript() = ClickScript(
        name = "单点连点", orientation = orientation,
        loopCount = if (continuous) 0 else repeatCount,
        timeoutMinutes = timeoutMinutes,
        positionSet = positionSet,
        steps = listOf(ScriptStep(gestureType = gestureType, x = x, y = y,
            durationMs = gestureDurationMs, delayMs = intervalMs)),
    )
}

@Serializable
data class ScriptStep(
    val id: String = UUID.randomUUID().toString(),
    val gestureType: GestureType = GestureType.TAP,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val endX: Float = 0.7f,
    val endY: Float = 0.5f,
    // Wait after completing this gesture; never overlap gestures.
    val delayMs: Long = 100,
    val durationMs: Long = 80,
)

@Serializable
data class ClickScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新建脚本",
    val orientation: Int = 1,
    // Zero means repeat until stopped or the mandatory deadline is reached.
    val loopCount: Int = 1,
    val timeoutMinutes: Int = 30,
    /** For single point mode, false means the user still needs to pick a point. */
    val positionSet: Boolean = true,
    /** Built-in scripts are shipped with the app and are kept as ready-to-run examples. */
    val builtIn: Boolean = false,
    val steps: List<ScriptStep> = emptyList(),
)

@Serializable
data class ScriptFile(val formatVersion: Int = 1, val scripts: List<ClickScript> = emptyList())

/** A portable snapshot of all user-maintained common configurations. */
@Serializable
data class ConfigFile(
    val formatVersion: Int = 1,
    val presets: PresetLibrary = PresetLibrary(),
    val scripts: List<ClickScript> = emptyList(),
)

data class ScreenPoint(val x: Float, val y: Float, val orientation: Int)
data class ScreenArea(val left: Int, val top: Int, val width: Int, val height: Int) {
    init { require(width > 1 && height > 1) }
    val orientation: Int get() = orientationFor(width, height)
}

object CoordinateMapper {
    fun toPixels(point: ScreenPoint, area: ScreenArea): Pair<Float, Float> {
        require(point.x.isFinite() && point.y.isFinite())
        return (area.left + point.x.coerceIn(0f, 1f) * (area.width - 1)) to
            (area.top + point.y.coerceIn(0f, 1f) * (area.height - 1))
    }

    fun normalize(x: Float, y: Float, area: ScreenArea): ScreenPoint = ScreenPoint(
        ((x - area.left) / (area.width - 1)).coerceIn(0f, 1f),
        ((y - area.top) / (area.height - 1)).coerceIn(0f, 1f), area.orientation,
    )
}

fun orientationFor(width: Int, height: Int): Int = if (width >= height) 2 else 1

fun ClickSettings.validate() {
    require(gestureType != GestureType.SWIPE) { "单点模式不支持滑动" }
    require(intervalMs in 50..60_000) { "点击间隔须为 50–60000 毫秒" }
    require(repeatCount in 1..100_000) { "次数须为 1–100000" }
    asScript().validate()
}

fun ClickScript.validate() {
    require(id.isNotBlank() && id.length <= 100) { "脚本标识无效" }
    require(name.isNotBlank() && name.length <= 60) { "名称须为 1–60 字符" }
    require(orientation == 1 || orientation == 2) { "屏幕方向无效" }
    require(loopCount in 0..100_000) { "循环次数须为 0–100000（0 为持续运行）" }
    require(timeoutMinutes in 1..1_440) { "安全超时须为 1–1440 分钟" }
    require(steps.size <= 100) { "每个脚本最多 100 步" }
    require(steps.map { it.id }.distinct().size == steps.size) { "动作标识重复" }
    steps.forEach { step ->
        require(step.id.isNotBlank() && step.id.length <= 100) { "动作标识无效" }
        require(listOf(step.x, step.y, step.endX, step.endY).all { it.isFinite() && it in 0f..1f }) { "坐标须在 0–100% 之间" }
        require(step.durationMs in 40..10_000) { "手势时长须为 40–10000 毫秒" }
        require(step.gestureType != GestureType.LONG_PRESS || step.durationMs >= 500) { "长按时长至少 500 毫秒" }
        require(step.delayMs in 0..60_000) { "步骤延时须为 0–60000 毫秒" }
    }
}

object AppJson {
    val format = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
}

object ScriptCodec {
    const val MAX_BYTES = 1_048_576
    fun encode(scripts: List<ClickScript>): String {
        validate(scripts)
        return AppJson.format.encodeToString(ScriptFile(scripts = scripts))
    }
    fun decode(json: String): List<ClickScript> {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "文件须小于 1 MB" }
        val document = AppJson.format.decodeFromString<ScriptFile>(json)
        require(document.formatVersion == 1) { "不支持的脚本文件版本：${document.formatVersion}" }
        validate(document.scripts)
        return document.scripts
    }
    private fun validate(scripts: List<ClickScript>) {
        require(scripts.size <= 500) { "最多保存 500 个脚本" }
        require(scripts.map { it.id }.distinct().size == scripts.size) { "脚本标识重复" }
        scripts.forEach { it.validate() }
    }
}

object ConfigCodec {
    const val MAX_BYTES = 1_048_576

    fun encode(presets: PresetLibrary, scripts: List<ClickScript>): String {
        presets.validate()
        scripts.forEach { it.validate() }
        require(scripts.size <= 500) { "最多保存 500 个脚本" }
        return AppJson.format.encodeToString(ConfigFile(presets = presets, scripts = scripts))
    }

    fun decode(json: String): ConfigFile {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "文件须小于 1 MB" }
        val document = AppJson.format.decodeFromString<ConfigFile>(json)
        require(document.formatVersion == 1) { "不支持的配置文件版本：${document.formatVersion}" }
        document.presets.validate()
        require(document.scripts.size <= 500) { "最多保存 500 个脚本" }
        require(document.scripts.map { it.id }.distinct().size == document.scripts.size) { "脚本标识重复" }
        document.scripts.forEach { it.validate() }
        return document
    }
}
