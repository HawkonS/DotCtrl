package tech.hawkon.dotctrl.service

import tech.hawkon.dotctrl.R
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
    private val displayPreferences by lazy { DisplayPreferences(this) }
    private lateinit var displayContext: Context
    private val context: Context get() = if (::displayContext.isInitialized) displayContext else this
    private val displayPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // The app theme belongs to the activity. Reapplying overlay window params
        // for that preference can disturb touch dispatch on some Android builds.
        if (key != DisplayPreferences.LANGUAGE_KEY && key != DisplayPreferences.TRANSPARENCY_KEY) return@OnSharedPreferenceChangeListener
        val languageChanged = key == DisplayPreferences.LANGUAGE_KEY
        if (languageChanged) displayContext = displayPreferences.language.localizedContext(this)
        if (::overlay.isInitialized) overlay.updateDisplaySettings(languageChanged)
    }
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
        override fun onReceive(context: Context?, intent: Intent?) {
            stop(this@AutoClickAccessibilityService.context.getString(R.string.ui_screen_off_stopped))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        displayContext = displayPreferences.language.localizedContext(this)
        instance = this
        repository = ScriptRepository(this)
        settingsStore = ClickSettingsStore(this)
        presetStore = PresetStore(this)
        overlay = OverlayController(this, ::startRun, { stop(context.getString(R.string.ui_stopped_manually)) }, ::closePanel,
            ::selectSinglePoint, ::addStep, ::openEditor, ::applyPosition, ::applyMode)
        displayPreferences.preferences.registerOnSharedPreferenceChangeListener(displayPreferenceListener)
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        RuntimeState.update { RuntimeStatus(connected = true, message = context.getString(R.string.ui_service_ready)) }
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
            RuntimeState.update { it.copy(message = context.getString(R.string.ui_allow_floating_windows_first), phase = RunPhase.ERROR) }
            return
        }
        stop(context.getString(R.string.ui_prepared))
        scriptId = selectedScriptId
        prepared = true
        pointSelectionOnly = autoPickSingle && selectedScriptId == null
        restrictedPackage = null
        if (pointSelectionOnly) {
            // Point selection is a separate flow. Do not expose the run controls
            // while the user is only confirming a coordinate.
            overlay.dismiss()
            RuntimeState.update { it.copy(phase = RunPhase.READY, message = context.getString(R.string.ui_drag_the_crosshair_to_select_a_position)) }
            selectSinglePoint(pickOnly = true)
        } else {
            RuntimeState.update { it.copy(phase = RunPhase.READY, message = context.getString(R.string.ui_switch_to_the_target_app_and_start_from_the_floating_window)) }
            refreshPanel()
        }
    }

    private fun currentScript(): ClickScript =
        scriptId?.let { id -> repository.load().firstOrNull { it.id == id } ?: error(context.getString(R.string.ui_the_script_has_been_deleted)) }
            ?: settingsStore.read().asScript()

    /** Select a preset draft without changing the workbench's active click settings. */
    fun pickPresetPosition(requestId: String, initial: ScreenPoint?) {
        check(Settings.canDrawOverlays(this)) { context.getString(R.string.ui_allow_floating_windows_first) }
        check(!RuntimeState.status.value.active) { context.getString(R.string.ui_stop_clicking_first) }
        closePanel()
        presetPickRequestId = requestId
        pointSelectionOnly = true
        RuntimeState.update { it.copy(phase = RunPhase.READY, message = context.getString(R.string.ui_drag_the_crosshair_to_select_a_position)) }
        try {
            overlay.pick(context.getString(R.string.ui_drag_crosshair_nconfirm_position), initial = initial,
                onCancelled = { finishPresetPosition(null) }, onSelected = ::finishPresetPosition)
        } catch (failure: Exception) {
            presetPickRequestId = null
            pointSelectionOnly = false
            overlay.dismiss()
            stop(context.getString(R.string.ui_could_not_open_the_position_picker))
            throw failure
        }
    }

    private fun finishPresetPosition(point: ScreenPoint?) {
        val requestId = presetPickRequestId ?: return
        presetPickRequestId = null
        pointSelectionOnly = false
        overlay.dismiss()
        RuntimeState.finishPositionPick(requestId, point)
        stop(if (point == null) context.getString(R.string.ui_position_selection_cancelled) else context.getString(R.string.ui_position_selected_2))
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun refreshPanel() {
        runCatching {
            val script = currentScript()
            overlay.show(script, scriptId != null, settingsStore.read(), presetStore.read())
        }.onFailure { reportError(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_read_the_script)) }
    }

    fun configurationChanged() {
        if (prepared && !pointSelectionOnly && !RuntimeState.status.value.active) refreshPanel()
    }

    private fun startRun() {
        if (RuntimeState.status.value.active || !prepared || pointSelectionOnly) return
        val script = runCatching {
            currentScript().also {
                it.validate()
                require(scriptId != null || settingsStore.read().positionSet) { context.getString(R.string.ui_select_a_click_position_first) }
                require(it.steps.isNotEmpty()) { context.getString(R.string.ui_add_an_action_first) }
            }
        }.getOrElse { reportError(context.errorText(it.message) ?: context.getString(R.string.ui_invalid_configuration)); return }
        initialArea = screenArea()
        if (script.orientation != initialArea?.orientation) {
            reportError(context.getString(R.string.ui_screen_orientation_does_not_match_restore_the_saved_orientation_or_pick_pos))
            return
        }
        environmentIssue()?.let { reportError(it); return }
        runJob = scope.launch {
            RuntimeState.update { it.copy(phase = RunPhase.COUNTDOWN, progress = "") }
            for (count in 3 downTo 1) {
                RuntimeState.update { it.copy(message = context.getString(R.string.ui_starting_in_s, count)) }
                delay(1_000)
                environmentIssue()?.let { reportError(it); return@launch }
            }
            RuntimeState.update { it.copy(phase = RunPhase.RUNNING, message = if (scriptId == null) context.getString(R.string.ui_single_point) else script.displayName(context)) }
            val result = ScriptRunner(::dispatchStep, ::environmentIssue).run(script) { progress ->
                RuntimeState.update { it.copy(progress = if (scriptId == null) context.getString(R.string.ui_clicks_completed, progress.completed)
                    else context.getString(R.string.ui_loop_step, progress.loop, progress.step, script.steps.size)) }
            }
            when (result) {
                RunResult.Completed -> stop(context.getString(R.string.ui_completed))
                RunResult.TimedOut -> stop(context.getString(R.string.ui_stopped_at_the_minute_safety_limit, script.timeoutMinutes))
                is RunResult.Failed -> reportError(context.errorText(result.reason).orEmpty())
            }
        }
    }

    private fun environmentIssue(): String? {
        if (!Settings.canDrawOverlays(this)) return context.getString(R.string.ui_overlay_permission_revoked_stopped)
        if (!getSystemService(PowerManager::class.java).isInteractive ||
            getSystemService(KeyguardManager::class.java).isKeyguardLocked) return context.getString(R.string.ui_screen_locked_or_off_stopped)
        if (restrictedPackage != null) return context.getString(R.string.ui_system_or_security_screen_open_return_to_the_target_app)
        if (initialArea != null && RuntimeState.status.value.active && screenArea() != initialArea)
            return context.getString(R.string.ui_screen_area_changed_check_the_coordinates_again)
        return null
    }

    private suspend fun dispatchStep(step: ScriptStep): Boolean {
        val area = initialArea ?: return false
        val start = CoordinateMapper.toPixels(ScreenPoint(step.x, step.y, area.orientation), area)
        val end = if (step.gestureType == GestureType.SWIPE)
            CoordinateMapper.toPixels(ScreenPoint(step.endX, step.endY, area.orientation), area) else start
        check(!overlay.intersects(start, end)) { context.getString(R.string.ui_an_action_overlaps_the_controls_move_the_floating_window_and_try_again) }
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

    fun stop(reason: String = context.getString(R.string.ui_stopped)) {
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
        stop(context.getString(R.string.ui_screen_orientation_or_area_changed_paused_check_the_coordinates_before_rest))
        RuntimeState.update { it.copy(phase = RunPhase.PAUSED) }
    }

    private fun selectSinglePoint(pickOnly: Boolean = false) {
        if (RuntimeState.status.value.active) return
        val saved = settingsStore.read()
        overlay.pick(
            hint = context.getString(R.string.ui_drag_crosshair_nconfirm_position),
            initial = if (saved.positionSet) ScreenPoint(saved.x, saved.y, saved.orientation) else null,
            onSelected = { point ->
            runCatching {
                settingsStore.write(settingsStore.read().copy(x = point.x, y = point.y, orientation = point.orientation, positionSet = true))
            }.onSuccess {
                if (pickOnly) {
                    prepared = false
                    pointSelectionOnly = false
                    overlay.dismiss()
                    RuntimeState.update { it.copy(phase = RunPhase.STOPPED, message = context.getString(R.string.ui_position_saved)) }
                    startActivity(Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ))
                } else {
                    RuntimeState.update { it.copy(phase = RunPhase.READY, message = context.getString(R.string.ui_position_updated)) }
                    refreshPanel()
                }
            }.onFailure { reportError(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_save)) }
            },
            onCancelled = if (pickOnly) {
                {
                    prepared = false
                    pointSelectionOnly = false
                    overlay.dismiss()
                    RuntimeState.update { it.copy(phase = RunPhase.STOPPED, message = context.getString(R.string.ui_position_selection_cancelled)) }
                    startActivity(Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ))
                }
            } else ::refreshPanel,
        )
    }

    private fun addStep(type: GestureType) {
        if (RuntimeState.status.value.active) return
        val script = runCatching { currentScript() }.getOrElse { reportError(context.getString(R.string.ui_could_not_read_the_script_2)); return }
        if (script.steps.size >= 100) { reportError(context.getString(R.string.ui_a_script_can_contain_up_to_100_steps)); return }
        if (script.steps.isNotEmpty() && script.orientation != screenArea().orientation) {
            reportError(context.getString(R.string.ui_restore_the_script_orientation_before_adding_actions)); return
        }
        overlay.pick(if (type == GestureType.SWIPE) context.getString(R.string.ui_drag_crosshair_nselect_swipe_start) else context.getString(R.string.ui_drag_crosshair_nselect_action_position),
            onCancelled = ::refreshPanel) { start ->
            fun save(end: ScreenPoint = start) {
                runCatching {
                    check(start.orientation == end.orientation) { context.getString(R.string.ui_orientation_changed_while_picking_add_the_action_again) }
                    val step = ScriptStep(gestureType = type, x = start.x, y = start.y,
                        endX = end.x, endY = end.y, durationMs = if (type == GestureType.TAP) 80 else 600)
                    repository.upsert(script.copy(orientation = start.orientation, steps = script.steps + step))
                }.onFailure { reportError(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_save)) }
                refreshPanel()
            }
            if (type == GestureType.SWIPE) overlay.pick(context.getString(R.string.ui_drag_crosshair_nselect_swipe_end), onCancelled = ::refreshPanel, initial = start) { end -> save(end) }
            else save()
        }
    }

    internal fun closePanel() {
        stop(context.getString(R.string.ui_floating_window_closed))
        prepared = false
        pointSelectionOnly = false
        overlay.dismiss()
    }

    private fun openEditor() {
        stop(context.getString(R.string.ui_return_to_editor))
        prepared = false
        pointSelectionOnly = false
        overlay.dismiss()
        RuntimeState.requestEditor()
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun applyPosition(preset: PositionPreset) {
        stop(context.getString(R.string.ui_position_updated))
        runCatching {
            preset.validate()
            require(preset.orientation == screenArea().orientation) { context.getString(R.string.ui_switch_to_to_use_this_position, if (preset.orientation == 1) context.getString(R.string.ui_portrait) else context.getString(R.string.ui_landscape)) }
            settingsStore.write(preset.applyTo(settingsStore.read()))
            refreshPanel()
        }.onFailure { reportError(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_switch_position)) }
    }

    private fun applyMode(mode: ClickMode) {
        stop(context.getString(R.string.ui_mode_updated))
        runCatching {
            settingsStore.write(mode.applyTo(settingsStore.read()))
            refreshPanel()
        }.onFailure { reportError(context.errorText(it.message) ?: context.getString(R.string.ui_could_not_switch_mode)) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        // Only the window's package identifier is used; no node tree or text is retrieved.
        restrictedPackage = packageName.takeIf { it in restrictedPackages }
        if (restrictedPackage != null && RuntimeState.status.value.active) stop(context.getString(R.string.ui_system_or_security_screen_opened_stopped))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) overlay.cancelPick()
        if (RuntimeState.status.value.active) pauseForRotation()
        if (prepared && !pointSelectionOnly) refreshPanel()
    }

    override fun onInterrupt() { stop(context.getString(R.string.ui_accessibility_service_interrupted_by_the_system)) }

    override fun onDestroy() {
        displayPreferences.preferences.unregisterOnSharedPreferenceChangeListener(displayPreferenceListener)
        presetPickRequestId?.let { RuntimeState.finishPositionPick(it, null) }
        presetPickRequestId = null
        stop(context.getString(R.string.ui_accessibility_service_closed))
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
