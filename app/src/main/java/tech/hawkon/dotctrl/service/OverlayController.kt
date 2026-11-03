package tech.hawkon.dotctrl.service

import tech.hawkon.dotctrl.R
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.*
import android.widget.*
import tech.hawkon.dotctrl.data.*
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One compact controller, with independent draggable point selection. */
class OverlayController(
    private val baseContext: Context,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onClose: () -> Unit,
    private val onPickSingle: () -> Unit,
    private val onAdd: (GestureType) -> Unit,
    private val onEditor: () -> Unit,
    private val onPosition: (PositionPreset) -> Unit,
    private val onMode: (ClickMode) -> Unit,
) {
    private val displayPreferences = DisplayPreferences(baseContext)
    private var context = displayPreferences.language.localizedContext(baseContext)
    private val manager = context.getSystemService(WindowManager::class.java)
    private val green = Color.rgb(23, 123, 101)
    private val red = Color.rgb(204, 65, 71)
    private val ink = Color.rgb(35, 53, 46)
    private val muted = Color.rgb(104, 122, 114)
    private val white = Color.rgb(252, 254, 253)
    private val pale = Color.rgb(237, 243, 239)
    private var panel: LinearLayout? = null
    private var bodyScroll: ScrollView? = null
    private val pickerViews = mutableListOf<View>()
    private var pickerCancel: (() -> Unit)? = null
    private var collector: Job? = null
    private var markerLayer: MarkerLayer? = null
    private val markers = mutableListOf<Marker>()
    private var script: ClickScript? = null
    private var settings = ClickSettings()
    private var library = PresetLibrary()
    private var scriptMode = false
    private var minimized = true
    private var page = "main"
    private var statusText: TextView? = null
    private var runControl: SymbolButton? = null
    private var toggleControl: SymbolButton? = null
    private var runControlStateAlpha = 1f
    private var runControlWasActive = false
    private var lastRunEndedAt = Long.MIN_VALUE
    private val parameters = windowParams(dp(112), -2).apply { x = dp(12); y = dp(100) }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun windowParams(width: Int, height: Int) = WindowManager.LayoutParams(width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        // Window alpha participates in Android's obscured-touch filtering.
        // Apply the user's visual opacity to each view instead.
        alpha = 1f
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    fun updateDisplaySettings(languageChanged: Boolean) {
        if (languageChanged) {
            context = displayPreferences.language.localizedContext(baseContext)
            if (panel != null && pickerViews.isEmpty()) refreshPanel()
        }
        applyDisplayOpacity()
    }

    private fun applyDisplayOpacity() {
        val alpha = displayPreferences.overlayAlpha
        panel?.let { applyViewOpacity(it, alpha) }
        runControl?.alpha = alpha * runControlStateAlpha
        markerLayer?.alpha = alpha
        pickerViews.forEach { applyViewOpacity(it, alpha) }
    }

    private fun applyViewOpacity(view: View, alpha: Float) {
        if (view is ViewGroup) {
            view.alpha = 1f
            view.background?.alpha = (alpha * 255).toInt()
            for (index in 0 until view.childCount) applyViewOpacity(view.getChildAt(index), alpha)
        } else {
            view.alpha = alpha
        }
    }

    fun show(script: ClickScript, isScript: Boolean, settings: ClickSettings, library: PresetLibrary) {
        this.script = script
        this.settings = settings
        this.library = library
        scriptMode = isScript
        page = "main"
        renderMarkers(script)
        refreshPanel()
    }

    private fun createPanel(): LinearLayout {
        val root = column().apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = surface(white, 24)
            elevation = dp(8).toFloat()
        }
        val header = row()
        runControl = SymbolButton(context, "play", green).apply {
            var stoppingTouch = false
            setOnClickListener { if (stoppingTouch || RuntimeState.status.value.active) onStop() else onStart() }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // A physical touch can cancel an injected gesture before ACTION_UP.
                        // Stop now and consume the release so it cannot become a new start.
                        // Cancellation can also reach us before this DOWN is delivered.
                        // Use the original touch time to preserve that press's stop intent.
                        stoppingTouch = RuntimeState.status.value.active || runControlWasActive || event.downTime <= lastRunEndedAt
                        if (stoppingTouch) view.performClick()
                        stoppingTouch
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stoppingTouch.also { stoppingTouch = false }
                    else -> stoppingTouch
                }
            }
        }
        header.addView(runControl, LinearLayout.LayoutParams(dp(48), dp(48)))
        statusText = label(context.getString(R.string.ui_ready_3), 12).apply {
            setPadding(dp(10), 0, dp(4), 0); maxLines = 2
        }
        header.addView(statusText, LinearLayout.LayoutParams(0, dp(48), 1f))
        installDrag(statusText!!, root, parameters)
        val toggle = SymbolButton(context, "plus", pale).apply {
            setOnClickListener {
                minimized = !minimized
                val pageChanged = page != "main"
                page = "main"
                if (pageChanged) refreshPanel() else updatePanelLayout()
            }
        }
        toggleControl = toggle
        header.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(48)))
        installDrag(toggle, root, parameters, clickable = true)
        root.addView(header)
        bodyScroll = ScrollView(context).apply { isFillViewport = false }.also {
            root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        panel = root
        return root
    }

    private fun refreshPanel() {
        val attach = panel == null
        if (attach) createPanel()
        val body = when (page) {
            "positions" -> positionChoices()
            "modes" -> modeChoices()
            "custom" -> customMode()
            else -> mainControls()
        }
        bodyScroll?.apply {
            removeAllViews()
            addView(body)
            scrollTo(0, 0)
        }
        updatePanelLayout(attach)
        if (attach) {
            collector = CoroutineScope(Dispatchers.Main.immediate).launch {
                RuntimeState.status.collect(::updateRuntimeControls)
            }
        }
    }

    private fun updatePanelLayout(attach: Boolean = false) {
        val root = panel ?: return
        statusText?.visibility = if (minimized) View.GONE else View.VISIBLE
        toggleControl?.apply {
            setExpanded(!minimized)
            contentDescription = context.getString(if (minimized) R.string.ui_expand else R.string.ui_collapse)
        }
        bodyScroll?.visibility = if (minimized) View.GONE else View.VISIBLE
        updateRuntimeControls(RuntimeState.status.value)
        val area = context.screenArea()
        parameters.width = dp(if (minimized) 112 else 260).coerceAtMost(area.width)
        parameters.flags = if (page == "custom" && !minimized) {
            parameters.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            parameters.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        parameters.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        bodyScroll?.let { scroll ->
            val body = scroll.getChildAt(0) ?: return@let
            val maxHeight = (area.height - dp(220)).coerceAtLeast(dp(110)).coerceAtMost(dp(350))
            body.measure(View.MeasureSpec.makeMeasureSpec((parameters.width - dp(16)).coerceAtLeast(0), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            scroll.layoutParams = scroll.layoutParams.apply { height = min(body.measuredHeight, maxHeight) }
        }
        parameters.x = parameters.x.coerceIn(area.left, (area.left + area.width - parameters.width).coerceAtLeast(area.left))
        root.measure(View.MeasureSpec.makeMeasureSpec(parameters.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        parameters.y = parameters.y.coerceIn(area.top, (area.top + area.height - root.measuredHeight).coerceAtLeast(area.top))
        applyDisplayOpacity()
        // Keep the window surface and header attached while resizing or changing pages.
        if (attach) manager.addView(root, parameters) else manager.updateViewLayout(root, parameters)
    }

    private fun updateRuntimeControls(state: RuntimeStatus) {
        // A full-screen application overlay marks touches as obscured even when
        // it is not touchable. Hide the coordinate layer before the countdown
        // ends so target views with touch protection can receive our gestures.
        markerLayer?.visibility = if (state.active) View.GONE else View.VISIBLE
        if (runControlWasActive && !state.active) lastRunEndedAt = SystemClock.uptimeMillis()
        runControlWasActive = state.active
        runControl?.apply {
            setRunState(state, red, green)
            isEnabled = page == "main" || state.active
        }
        runControlStateAlpha = if (page == "main" || state.active) 1f else .35f
        runControl?.alpha = displayPreferences.overlayAlpha * runControlStateAlpha
        statusText?.text = when {
            state.phase == RunPhase.RUNNING -> state.progress.ifBlank { context.getString(R.string.ui_running) }
            state.phase == RunPhase.READY -> context.getString(R.string.ui_ready)
            else -> state.message
        }
    }

    private fun navigate(destination: String) {
        if (RuntimeState.status.value.active) onStop()
        page = destination
        refreshPanel()
    }

    private fun mainControls() = column().apply {
        if (scriptMode) {
            addView(label(script?.displayName(context).orEmpty(), 13).apply { setPadding(dp(8), dp(6), dp(8), dp(8)) })
            addView(row().apply {
                GestureType.entries.forEach { type -> addView(compactButton(type.label(context)) {
                    if (RuntimeState.status.value.active) onStop()
                    onAdd(type)
                }, compactWeighted()) }
            })
            script?.steps?.take(4)?.forEachIndexed { index, step ->
                addView(row().apply {
                    addView(label("${index + 1} · ${step.gestureType.label(context)}", 12), LinearLayout.LayoutParams(0, dp(36), 1f))
                }, LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(2) })
            }
        } else {
            addView(choice(context.getString(R.string.ui_position_2), library.positionLabel(context, settings)) { navigate("positions") })
            addView(choice(context.getString(R.string.ui_tap), library.modeLabel(context, settings)) { navigate("modes") }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        }
        addView(row().apply {
            addView(compactButton(context.getString(R.string.ui_back)) { onEditor() }, compactWeighted())
            addView(compactButton(context.getString(R.string.ui_exit)) { onClose() }, compactWeighted())
        }, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })
    }

    private fun positionChoices() = column().apply {
        addView(button(context.getString(R.string.ui_click_position_2)) { navigate("main") })
        library.positions.forEach { preset ->
            addView(choice(if (preset.matches(settings)) "✓" else "", preset.name) { onPosition(preset) },
                LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        }
        addView(button(context.getString(R.string.ui_pick_again_2)) { onPickSingle() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
    }

    private fun modeChoices() = column().apply {
        addView(button(context.getString(R.string.ui_click_mode_2)) { navigate("main") })
        library.allModes.forEach { preset ->
            addView(choice(if (preset.mode == ClickMode.from(settings)) "✓" else "", preset.displayName(context)) { onMode(preset.mode) },
                LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        }
        addView(button(context.getString(R.string.ui_adjust_count_and_interval)) { navigate("custom") }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
    }

    private fun customMode() = column().apply {
        addView(button(context.getString(R.string.ui_custom_clicks)) { navigate("modes") })
        fun number(title: String, value: Long): EditText {
            addView(label(title, 12).apply { setPadding(dp(8), dp(10), 0, dp(4)) })
            return EditText(context).apply {
                setText(value.toString()); textSize = 15f; setTextColor(ink); setSingleLine()
                contentDescription = title; inputType = InputType.TYPE_CLASS_NUMBER
                setPadding(dp(12), 0, dp(12), 0); background = surface(pale, 12)
            }.also { addView(it, LinearLayout.LayoutParams(-1, dp(48))) }
        }
        val interval = number(context.getString(R.string.ui_click_interval_ms), settings.intervalMs)
        val count = number(context.getString(R.string.ui_click_count), settings.repeatCount.toLong())
        val continuous = CheckBox(context).apply {
            text = context.getString(R.string.ui_continuous_clicks); isChecked = settings.continuous; setTextColor(ink)
            setOnCheckedChangeListener { _, checked -> count.isEnabled = !checked }
        }
        count.isEnabled = !settings.continuous
        addView(continuous, LinearLayout.LayoutParams(-1, dp(48)))
        val error = label("", 12).apply { setTextColor(red) }
        addView(error)
        addView(button(context.getString(R.string.ui_apply)) {
            runCatching {
                ClickMode.from(settings).copy(intervalMs = interval.text.toString().toLongOrNull() ?: -1,
                    repeatCount = if (continuous.isChecked) settings.repeatCount else count.text.toString().toIntOrNull() ?: -1,
                    continuous = continuous.isChecked).also { it.validate() }
            }.onSuccess { mode ->
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(interval.windowToken, 0)
                onMode(mode)
            }.onFailure { error.text = context.errorText(it.message) }
        }, LinearLayout.LayoutParams(-1, dp(48)))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installDrag(handle: View, root: View, params: WindowManager.LayoutParams, clickable: Boolean = false,
        centerAtEdge: Boolean = false) {
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var moved = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) moved = true
                    if (moved) {
                        val area = context.screenArea()
                        val halfX = if (centerAtEdge) root.width / 2 else 0
                        val halfY = if (centerAtEdge) root.height / 2 else 0
                        params.x = (originX + event.rawX - downX).toInt().coerceIn(area.left - halfX,
                            (area.left + area.width - root.width + halfX - 1).coerceAtLeast(area.left - halfX))
                        params.y = (originY + event.rawY - downY).toInt().coerceIn(area.top - halfY,
                            (area.top + area.height - root.height + halfY - 1).coerceAtLeast(area.top - halfY))
                        runCatching { manager.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && clickable && view.isEnabled) view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    fun pick(hint: String, onCancelled: (() -> Unit)? = null, initial: ScreenPoint? = null, onSelected: (ScreenPoint) -> Unit) {
        cancelPick(notify = false)
        panel?.visibility = View.GONE
        if (!scriptMode) clearMarkers()
        pickerCancel = onCancelled
        val area = context.screenArea()
        val point = initial?.takeIf { it.orientation == area.orientation } ?: ScreenPoint(.5f, .5f, area.orientation)
        val pixels = CoordinateMapper.toPixels(point, area)
        val target = TargetView(context)
        val targetParams = windowParams(dp(64), dp(64)).apply {
            x = pixels.first.toInt() - dp(32); y = pixels.second.toInt() - dp(32)
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        // The target's center is the saved coordinate. Other apps remain touchable.
        installDrag(target, target, targetParams, centerAtEdge = true)
        manager.addView(target, targetParams)
        pickerViews += target
        val toolbar = row().apply {
            setPadding(dp(8), dp(6), dp(6), dp(6)); background = surface(white, 24); elevation = dp(8).toFloat()
        }
        val title = label(hint, 12).apply { maxLines = 2; setPadding(dp(8), 0, dp(4), 0) }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(SymbolButton(context, "check", green).apply {
            setOnClickListener {
                if (context.screenArea() != area) { cancelPick(); return@setOnClickListener }
                val location = IntArray(2); target.getLocationOnScreen(location)
                val selected = CoordinateMapper.normalize(location[0] + target.width / 2f, location[1] + target.height / 2f, area)
                cancelPick(notify = false)
                addMarker(selected.x, selected.y, area, if (scriptMode) context.getString(R.string.ui_selected) else "1")
                onSelected(selected)
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(SymbolButton(context, "close", pale).apply { setOnClickListener { cancelPick() } }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val toolbarParams = windowParams(dp(240).coerceAtMost(area.width), dp(60)).apply { x = area.left + dp(12); y = area.top + dp(16) }
        installDrag(title, toolbar, toolbarParams)
        pickerViews += target
        pickerViews += toolbar
        applyViewOpacity(target, displayPreferences.overlayAlpha)
        applyViewOpacity(toolbar, displayPreferences.overlayAlpha)
        manager.addView(toolbar, toolbarParams)
    }

    fun cancelPick(notify: Boolean = true) {
        val callback = pickerCancel
        pickerCancel = null
        pickerViews.toList().forEach { runCatching { manager.removeView(it) } }
        pickerViews.clear()
        panel?.visibility = View.VISIBLE
        if (notify) callback?.invoke()
    }

    private fun renderMarkers(current: ClickScript) {
        clearMarkers()
        if (!scriptMode && !current.positionSet) return
        val area = context.screenArea()
        if (current.orientation != area.orientation) return
        current.steps.forEachIndexed { index, step ->
            addMarker(step.x, step.y, area, "${index + 1}")
            if (step.gestureType == GestureType.SWIPE) addMarker(step.endX, step.endY, area, context.getString(R.string.ui_end, index + 1), step.x to step.y)
        }
    }

    private fun addMarker(x: Float, y: Float, area: ScreenArea, label: String, from: Pair<Float, Float>? = null) {
        markers += Marker(label, CoordinateMapper.toPixels(ScreenPoint(x, y, area.orientation), area),
            from?.let { CoordinateMapper.toPixels(ScreenPoint(it.first, it.second, area.orientation), area) })
        if (markerLayer == null) {
            val view = MarkerLayer(context, markers)
            val params = windowParams(-1, -1).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                // Android 12+ permits touches through this single, non-touchable layer.
                alpha = .6f
            }
            manager.addView(view, params)
            markerLayer = view
            view.alpha = displayPreferences.overlayAlpha
        }
        markerLayer?.invalidate()
    }

    private fun clearMarkers() {
        markerLayer?.let { runCatching { manager.removeView(it) } }
        markerLayer = null; markers.clear()
    }

    fun intersects(start: Pair<Float, Float>, end: Pair<Float, Float>): Boolean {
        val view = panel ?: return true
        val location = IntArray(2); view.getLocationOnScreen(location)
        return !(location[0] + view.width < min(start.first, end.first) - 1 || location[0] > max(start.first, end.first) + 1 ||
            location[1] + view.height < min(start.second, end.second) - 1 || location[1] > max(start.second, end.second) + 1)
    }

    fun dismiss() { cancelPick(notify = false); clearMarkers(); removePanel(); page = "main" }
    private fun removePanel() {
        collector?.cancel(); collector = null
        panel?.let { runCatching { manager.removeView(it) } }
        panel = null; bodyScroll = null; statusText = null; runControl = null; toggleControl = null
    }
    private fun surface(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weighted() = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(2); marginEnd = dp(2) }
    private fun compactWeighted() = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(2); marginEnd = dp(2) }
    private fun label(text: String, size: Int) = TextView(context).apply { this.text = text; textSize = size.toFloat(); setTextColor(ink); gravity = Gravity.CENTER_VERTICAL }
    private fun choice(title: String, value: String, action: () -> Unit): View = button("${if (title.isBlank()) "" else "${title}："}${value}  ›", action).apply {
        gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private fun button(text: String, action: () -> Unit) = Button(context).apply {
        this.text = text; textSize = 13f; isAllCaps = false; minWidth = 0; minimumWidth = 0
        minHeight = dp(48); minimumHeight = dp(48); setTextColor(ink); background = surface(pale, 14)
        setPadding(dp(12), 0, dp(12), 0); stateListAnimator = null; setOnClickListener { action() }
    }
    private fun compactButton(text: String, action: () -> Unit) = Button(context).apply {
        this.text = text; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0
        minHeight = dp(36); minimumHeight = dp(36); setTextColor(ink); background = surface(pale, 11)
        setPadding(dp(6), 0, dp(6), 0); stateListAnimator = null; setOnClickListener { action() }
    }
}

@SuppressLint("ViewConstructor")
private class SymbolButton(context: Context, private var symbol: String, private var color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    init { isClickable = true; isFocusable = true; updateDescription() }
    fun setRunState(state: RuntimeStatus, red: Int, green: Int) {
        val symbol = when (state.phase) {
            RunPhase.COUNTDOWN -> state.countdownSeconds.coerceIn(1, 3).toString()
            RunPhase.RUNNING -> "stop"
            else -> "play"
        }
        setSymbol(symbol, if (state.phase == RunPhase.RUNNING) red else green)
    }
    fun setExpanded(expanded: Boolean) { setSymbol(if (expanded) "minus" else "plus", color) }
    private fun setSymbol(symbol: String, color: Int) {
        if (this.symbol == symbol && this.color == color) return
        this.symbol = symbol; this.color = color
        updateDescription(); invalidate()
    }
    private fun updateDescription() {
        contentDescription = when (symbol) {
            "1", "2", "3" -> context.getString(R.string.ui_countdown_tap_to_cancel, symbol.toInt())
            "play" -> context.getString(R.string.ui_start)
            "stop" -> context.getString(R.string.ui_stop)
            "plus" -> context.getString(R.string.ui_expand)
            "minus" -> context.getString(R.string.ui_collapse)
            "check" -> context.getString(R.string.ui_confirm_position)
            else -> context.getString(R.string.ui_cancel_position_selection)
        }
    }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val x = width / 2f; val y = height / 2f
        paint.color = color; paint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, min(width, height) / 2f - 3 * d, paint)
        paint.color = when (symbol) {
            "play", "stop", "check", "1", "2", "3" -> Color.WHITE
            else -> Color.rgb(48, 72, 60)
        }
        paint.strokeWidth = 2.3f * d; paint.strokeCap = Paint.Cap.ROUND
        when (symbol) {
            "1", "2", "3" -> {
                paint.textSize = 24 * d; paint.typeface = Typeface.DEFAULT_BOLD; paint.textAlign = Paint.Align.CENTER
                canvas.drawText(symbol, x, y - (paint.ascent() + paint.descent()) / 2f, paint)
            }
            "play" -> canvas.drawPath(path.apply { reset(); moveTo(x - 5*d, y - 9*d); lineTo(x + 9*d, y); lineTo(x - 5*d, y + 9*d); close() }, paint)
            "stop" -> canvas.drawRoundRect(x - 7*d, y - 7*d, x + 7*d, y + 7*d, 2*d, 2*d, paint)
            "plus", "minus" -> {
                canvas.drawLine(x - 8*d, y, x + 8*d, y, paint)
                if (symbol == "plus") canvas.drawLine(x, y - 8*d, x, y + 8*d, paint)
            }
            "check" -> { paint.style = Paint.Style.STROKE; canvas.drawPath(path.apply { reset(); moveTo(x - 8*d, y); lineTo(x - 2*d, y + 6*d); lineTo(x + 9*d, y - 7*d) }, paint) }
            else -> { canvas.drawLine(x - 6*d, y - 6*d, x + 6*d, y + 6*d, paint); canvas.drawLine(x + 6*d, y - 6*d, x - 6*d, y + 6*d, paint) }
        }
    }
}

private class TargetView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription = context.getString(R.string.ui_drag_the_crosshair_to_select_a_position) }
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density; val x = width/2f; val y = height/2f
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 5*d; paint.color = Color.WHITE
        canvas.drawCircle(x, y, 20*d, paint)
        paint.strokeWidth = 2*d; paint.color = Color.rgb(23, 123, 101)
        canvas.drawCircle(x, y, 20*d, paint)
        canvas.drawLine(x - 28*d, y, x + 28*d, y, paint); canvas.drawLine(x, y - 28*d, x, y + 28*d, paint)
        paint.style = Paint.Style.FILL; canvas.drawCircle(x, y, 4*d, paint)
    }
}

private data class Marker(val label: String, val point: Pair<Float, Float>, val from: Pair<Float, Float>?)
@SuppressLint("ViewConstructor")
private class MarkerLayer(context: Context, private val markers: List<Marker>) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val origin = IntArray(2)
    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        // Some OEMs inset a full-screen overlay even when fitInsetsTypes is 0.
        // Stored coordinates are absolute; drawing coordinates are view-local.
        getLocationOnScreen(origin)
        markers.forEach { marker ->
            val x = marker.point.first - origin[0]; val y = marker.point.second - origin[1]
            paint.color = Color.rgb(6, 103, 79); paint.strokeWidth = 2*d; paint.style = Paint.Style.STROKE
            marker.from?.let { canvas.drawLine(it.first - origin[0], it.second - origin[1], x, y, paint) }
            canvas.drawCircle(x, y, 14*d, paint)
            canvas.drawLine(x - 22*d, y, x + 22*d, y, paint); canvas.drawLine(x, y - 22*d, x, y + 22*d, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(x + 15*d, y - 32*d, x + 48*d, y - 8*d, 10*d, 10*d, paint)
            paint.color = Color.WHITE; paint.textSize = 12*d; paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(marker.label, x + 21*d, y - 15*d, paint)
        }
    }
}

fun GestureType.label(context: Context): String = when (this) {
    GestureType.TAP -> context.getString(R.string.ui_tap)
    GestureType.LONG_PRESS -> context.getString(R.string.ui_long_press)
    GestureType.SWIPE -> context.getString(R.string.ui_swipe)
}
