package tech.hawkon.dotctrl.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import tech.hawkon.dotctrl.MainActivity
import tech.hawkon.dotctrl.data.*
import tech.hawkon.dotctrl.engine.RunResult
import tech.hawkon.dotctrl.engine.ScriptRunner
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class AutoClickAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runJob: Job? = null
    private lateinit var overlay: OverlayController
    private lateinit var repository: ScriptRepository
    private lateinit var settingsStore: ClickSettingsStore
    private lateinit var presetStore: PresetStore
    private var scriptId: String? = null
    private var prepared = false
    private var pointSelectionOnly = false
    private var presetPickRequestId: String? = null
    private var initialArea: ScreenArea? = null
    private var restrictedPackage: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stop("屏幕已关闭，已停止") }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        repository = ScriptRepository(this)
        settingsStore = ClickSettingsStore(this)
        presetStore = PresetStore(this)
        overlay = OverlayController(this, ::startRun, { stop("已手动停止") }, ::closePanel,
            ::selectSinglePoint, ::addStep, ::openEditor, ::applyPosition, ::applyMode)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        RuntimeState.update { RuntimeStatus(connected = true, message = "服务就绪") }
        scope.launch {
            while (isActive) {
                if (RuntimeState.status.value.active) {
                    environmentIssue()?.let { stop(it) }
                    val current = screenArea()
                    if (initialArea != null && current != initialArea) pauseForRotation()
                }
                delay(250)
            }
        }
    }

    /** Called only by our activity on the main thread; does not start a new Android service. */
    fun prepare(selectedScriptId: String? = null, autoPickSingle: Boolean = false) {
        if (!Settings.canDrawOverlays(this)) {
            RuntimeState.update { it.copy(message = "请先允许悬浮窗", phase = RunPhase.ERROR) }
            return
        }
        stop("已准备")
        scriptId = selectedScriptId
        prepared = true
        pointSelectionOnly = autoPickSingle && selectedScriptId == null
        restrictedPackage = null
        if (pointSelectionOnly) {
            // Point selection is a separate flow. Do not expose the run controls
            // while the user is only confirming a coordinate.
            overlay.dismiss()
            RuntimeState.update { it.copy(phase = RunPhase.READY, message = "拖动准星选择位置") }
            selectSinglePoint(pickOnly = true)
        } else {
            RuntimeState.update { it.copy(phase = RunPhase.READY, message = "切换到目标应用，在浮窗中开始") }
            refreshPanel()
        }
    }

    private fun currentScript(): ClickScript =
        scriptId?.let { id -> repository.load().firstOrNull { it.id == id } ?: error("脚本已被删除") }
            ?: settingsStore.read().asScript()

    /** Select a preset draft without changing the workbench's active click settings. */
    fun pickPresetPosition(requestId: String, initial: ScreenPoint?) {
        check(Settings.canDrawOverlays(this)) { "请先允许悬浮窗" }
        check(!RuntimeState.status.value.active) { "请先停止点击" }
        closePanel()
        presetPickRequestId = requestId
        pointSelectionOnly = true
        RuntimeState.update { it.copy(phase = RunPhase.READY, message = "拖动准星选择位置") }
        try {
            overlay.pick("拖动准星\n确认位置", initial = initial,
                onCancelled = { finishPresetPosition(null) }, onSelected = ::finishPresetPosition)
        } catch (failure: Exception) {
            presetPickRequestId = null
            pointSelectionOnly = false
            overlay.dismiss()
            stop("无法打开选点浮窗")
            throw failure
        }
    }

    private fun finishPresetPosition(point: ScreenPoint?) {
        val requestId = presetPickRequestId ?: return
        presetPickRequestId = null
        pointSelectionOnly = false
        overlay.dismiss()
        RuntimeState.finishPositionPick(requestId, point)
        stop(if (point == null) "已取消选点" else "位置已选择")
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun refreshPanel() {
        runCatching {
            val script = currentScript()
            overlay.show(script, scriptId != null, settingsStore.read(), presetStore.read())
        }.onFailure { reportError(it.message ?: "无法读取脚本") }
    }

    fun configurationChanged() {
        if (prepared && !pointSelectionOnly && !RuntimeState.status.value.active) refreshPanel()
    }

    private fun startRun() {
        if (RuntimeState.status.value.active || !prepared || pointSelectionOnly) return
        val script = runCatching {
            currentScript().also {
                it.validate()
                require(scriptId != null || settingsStore.read().positionSet) { "请先选取点击位置" }
                require(it.steps.isNotEmpty()) { "请先添加动作" }
            }
        }.getOrElse { reportError(it.message ?: "配置无效"); return }
        initialArea = screenArea()
        if (script.orientation != initialArea?.orientation) {
            reportError("屏幕方向不匹配，请切回保存时的方向或重新选点")
            return
        }
        environmentIssue()?.let { reportError(it); return }
        runJob = scope.launch {
            RuntimeState.update { it.copy(phase = RunPhase.COUNTDOWN, progress = "") }
            for (count in 3 downTo 1) {
                RuntimeState.update { it.copy(message = "${count} 秒后开始") }
                delay(1_000)
                environmentIssue()?.let { reportError(it); return@launch }
            }
            RuntimeState.update { it.copy(phase = RunPhase.RUNNING, message = script.name) }
            val result = ScriptRunner(::dispatchStep, ::environmentIssue).run(script) { progress ->
                RuntimeState.update { it.copy(progress = if (scriptId == null) "已点击 ${progress.completed} 次"
                    else "${progress.loop} 轮 · ${progress.step}/${script.steps.size} 步") }
            }
            when (result) {
                RunResult.Completed -> stop("执行完成")
                RunResult.TimedOut -> stop("达到 ${script.timeoutMinutes} 分钟安全时限，已停止")
                is RunResult.Failed -> reportError(result.reason)
            }
        }
    }

    private fun environmentIssue(): String? {
        if (!Settings.canDrawOverlays(this)) return "悬浮窗权限已撤销，已停止"
        if (!getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) return "锁屏或屏幕关闭，已停止"
        if (restrictedPackage != null) return "当前为系统或安全界面，请返回目标应用"
        if (initialArea != null && RuntimeState.status.value.active && screenArea() != initialArea)
            return "屏幕可用区域发生变化，请重新确认坐标"
        return null
    }

    private suspend fun dispatchStep(step: ScriptStep): Boolean {
        val area = initialArea ?: return false
        val start = CoordinateMapper.toPixels(ScreenPoint(step.x, step.y, area.orientation), area)
        val end = if (step.gestureType == GestureType.SWIPE)
            CoordinateMapper.toPixels(ScreenPoint(step.endX, step.endY, area.orientation), area) else start
        check(!overlay.intersects(start, end)) { "动作与控制浮窗重叠，请移动浮窗后重试" }
        val path = Path().apply {
            moveTo(start.first, start.second)
            if (step.gestureType == GestureType.SWIPE) lineTo(end.first, end.second)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, step.durationMs)).build()
        return withTimeoutOrNull(step.durationMs + 2_000) {
            suspendCancellableCoroutine { continuation ->
                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }, Handler(Looper.getMainLooper()))
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        } ?: false
    }

    fun stop(reason: String = "已停止") {
        runJob?.cancel()
        runJob = null
        initialArea = null
        RuntimeState.update { it.copy(phase = RunPhase.STOPPED, message = reason, progress = "") }
    }

    private fun reportError(reason: String) {
        stop(reason)
        RuntimeState.update { it.copy(phase = RunPhase.ERROR) }
        android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun pauseForRotation() {
        stop("屏幕方向或可用区域改变，已暂停；确认坐标后可重新开始")
        RuntimeState.update { it.copy(phase = RunPhase.PAUSED) }
    }

    private fun selectSinglePoint(pickOnly: Boolean = false) {
        if (RuntimeState.status.value.active) return
        val saved = settingsStore.read()
        overlay.pick(
            hint = "拖动准星\n确认位置",
            initial = if (saved.positionSet) ScreenPoint(saved.x, saved.y, saved.orientation) else null,
            onSelected = { point ->
            runCatching {
                settingsStore.write(settingsStore.read().copy(x = point.x, y = point.y, orientation = point.orientation, positionSet = true))
            }.onSuccess {
                if (pickOnly) {
                    prepared = false
                    pointSelectionOnly = false
                    overlay.dismiss()
                    RuntimeState.update { it.copy(phase = RunPhase.STOPPED, message = "位置已保存") }
                    startActivity(Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ))
                } else {
                    RuntimeState.update { it.copy(phase = RunPhase.READY, message = "位置已更新") }
                    refreshPanel()
                }
            }.onFailure { reportError(it.message ?: "保存失败") }
            },
            onCancelled = if (pickOnly) {
                {
                    prepared = false
                    pointSelectionOnly = false
                    overlay.dismiss()
                    RuntimeState.update { it.copy(phase = RunPhase.STOPPED, message = "已取消选点") }
                    startActivity(Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ))
                }
            } else ::refreshPanel,
        )
    }

    private fun addStep(type: GestureType) {
        if (RuntimeState.status.value.active) return
        val script = runCatching { currentScript() }.getOrElse { reportError("脚本读取失败"); return }
        if (script.steps.size >= 100) { reportError("每个脚本最多 100 步"); return }
        if (script.steps.isNotEmpty() && script.orientation != screenArea().orientation) {
            reportError("请切回脚本方向后添加"); return
        }
        overlay.pick(if (type == GestureType.SWIPE) "拖动准星\n选择滑动起点" else "拖动准星\n选择动作位置",
            onCancelled = ::refreshPanel) { start ->
            fun save(end: ScreenPoint = start) {
                runCatching {
                    check(start.orientation == end.orientation) { "选点期间方向改变，请重新添加" }
                    val step = ScriptStep(gestureType = type, x = start.x, y = start.y,
                        endX = end.x, endY = end.y, durationMs = if (type == GestureType.TAP) 80 else 600)
                    repository.upsert(script.copy(orientation = start.orientation, steps = script.steps + step))
                }.onFailure { reportError(it.message ?: "保存失败") }
                refreshPanel()
            }
            if (type == GestureType.SWIPE) overlay.pick("拖动准星\n选择滑动终点", onCancelled = ::refreshPanel, initial = start) { end -> save(end) }
            else save()
        }
    }

    internal fun closePanel() {
        stop("浮窗已关闭")
        prepared = false
        pointSelectionOnly = false
        overlay.dismiss()
    }

    private fun openEditor() {
        stop("返回编辑")
        prepared = false
        pointSelectionOnly = false
        overlay.dismiss()
        RuntimeState.requestEditor()
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun applyPosition(preset: PositionPreset) {
        stop("位置已更新")
        runCatching {
            preset.validate()
            require(preset.orientation == screenArea().orientation) { "请切换到${if (preset.orientation == 1) "竖屏" else "横屏"}后使用此位置" }
            settingsStore.write(preset.applyTo(settingsStore.read()))
            refreshPanel()
        }.onFailure { reportError(it.message ?: "位置切换失败") }
    }

    private fun applyMode(mode: ClickMode) {
        stop("模式已更新")
        runCatching {
            settingsStore.write(mode.applyTo(settingsStore.read()))
            refreshPanel()
        }.onFailure { reportError(it.message ?: "模式切换失败") }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        // Only the window's package identifier is used; no node tree or text is retrieved.
        restrictedPackage = packageName.takeIf { it in restrictedPackages }
        if (restrictedPackage != null && RuntimeState.status.value.active) stop("进入系统或安全界面，已停止")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) overlay.cancelPick()
        if (RuntimeState.status.value.active) pauseForRotation()
        if (prepared && !pointSelectionOnly) refreshPanel()
    }

    override fun onInterrupt() { stop("无障碍服务被系统中断") }

    override fun onDestroy() {
        presetPickRequestId?.let { RuntimeState.finishPositionPick(it, null) }
        presetPickRequestId = null
        stop("无障碍服务已关闭")
        if (::overlay.isInitialized) overlay.dismiss()
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        instance = null
        RuntimeState.update { it.copy(connected = false) }
        super.onDestroy()
    }

    companion object {
        var instance: AutoClickAccessibilityService? = null
            private set
        private val restrictedPackages = setOf(
            "com.android.systemui", "com.android.settings",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
        )
    }
}
