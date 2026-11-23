package de.glas.dashboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.abs

private val WHITE = Color.WHITE
private val ROSE = 0xFFFF8FA3.toInt()
private val GLASS = 0x26FFFFFF
private val LINE = 0x3DFFFFFF
private const val SCRIM_MAX_ALPHA = 140   // 0..255 – wie dunkel der Hintergrund maximal wird
private const val BLUR_MAX = 70           // Unschärfe in px (Android 12+)

/**
 * Das transparente Glas-Panel. Ein eigenes Vollbild-Fenster über allen Apps,
 * das nur existiert, solange es sichtbar ist.
 */
@SuppressLint("SetTextI18n")
class DashboardPanel(private val ctx: Context, private val wm: WindowManager) {

    private val main = Handler(Looper.getMainLooper())
    private val screenH = ctx.resources.displayMetrics.heightPixels.toFloat()
    private val blurOk = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
    private val scrim = ColorDrawable(0xFF0A040E.toInt()).apply { alpha = 0 }

    private val root = PanelRoot(ctx)
    private val scroll = ScrollView(ctx)
    private val sheet = LinearLayout(ctx)

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        if (blurOk) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
    }

    private var attached = false
    private var progress = 0f
    private var anim: ValueAnimator? = null

    // Widgets, die aktualisiert werden
    private lateinit var batteryText: TextView
    private lateinit var batteryBar: ProgressBar
    private lateinit var timerText: TextView
    private lateinit var timerBtn: TextView
    private lateinit var todoBox: LinearLayout
    private val toggleViews = mutableListOf<Pair<FrameLayout, () -> Boolean>>()

    // Timer
    private val presets = intArrayOf(1, 5, 10, 25)
    private var presetIdx = 1
    private var timerLeft = presets[presetIdx] * 60
    private var timerRunning = false
    private val tick = object : Runnable {
        override fun run() {
            timerLeft--
            if (timerLeft <= 0) { timerDone(); return }
            updateTimer()
            main.postDelayed(this, 1000)
        }
    }

    // Taschenlampe
    private val cam = ctx.getSystemService(CameraManager::class.java)
    private val torchId: String? = runCatching {
        cam.cameraIdList.firstOrNull {
            cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()
    private var torchOn = false
    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) { torchOn = enabled; refreshToggles() }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = showBattery(i)
    }

    init {
        torchId?.let { runCatching { cam.registerTorchCallback(torchCb, main) } }
        build()
        root.background = scrim
        root.isClickable = true
        root.setOnClickListener { close() }       // daneben tippen = schließen
        root.onBack = { close() }
        root.onDrag = { dy -> anim?.cancel(); setProgress(1f + dy / (screenH * 0.45f)) }
        root.onRelease = { dy -> settle(dy > -ctx.dpf(70f)) }
    }

    // ---------- öffentliche Steuerung ----------

    fun drag(p: Float) {
        anim?.cancel()
        attach()
        setProgress(p)
    }

    fun open() = settle(true)
    fun close() = settle(false)

    fun settle(open: Boolean) {
        if (!attached) {
            if (!open) return
            attach(); setProgress(0f)
        }
        anim?.cancel()
        val target = if (open) 1f else 0f
        if (!open) hideKeyboard()
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = (320 * abs(target - progress)).toLong().coerceAtLeast(120)
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!cancelled && target == 0f) detach()
                }
            })
            start()
        }
    }

    fun destroy() {
        anim?.cancel()
        main.removeCallbacks(tick)
        detach()
        runCatching { cam.unregisterTorchCallback(torchCb) }
    }

    // ---------- Fenster ----------

    private fun attach() {
        if (attached) return
        if (Build.VERSION.SDK_INT >= 31) lp.blurBehindRadius = 0
        runCatching { wm.addView(root, lp) }.onSuccess {
            attached = true
            ctx.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.let { showBattery(it) }
            refreshToggles()
            updateTimer()
        }
    }

    private fun detach() {
        if (!attached) return
        runCatching { ctx.unregisterReceiver(batteryReceiver) }
        runCatching { wm.removeView(root) }
        attached = false
        progress = 0f
    }

    private fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        scroll.translationY = -(1f - progress) * screenH
        scroll.alpha = 0.4f + 0.6f * progress
        scrim.alpha = (progress * SCRIM_MAX_ALPHA).toInt()
        if (blurOk && attached && Build.VERSION.SDK_INT >= 31) {
            val r = (progress * BLUR_MAX).toInt()
            if (abs(r - lp.blurBehindRadius) >= 6 || r == 0 || r == BLUR_MAX) {
                lp.blurBehindRadius = r
                runCatching { wm.updateViewLayout(root, lp) }
            }
        }
    }

    private fun hideKeyboard() {
        ctx.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(root.windowToken, 0)
        root.findFocus()?.clearFocus()
    }

    // ---------- Aufbau der Oberfläche ----------

    private fun build() {
        sheet.orientation = LinearLayout.VERTICAL
        sheet.isClickable = true   // Tippen auf das Panel schließt es nicht
        val side = ctx.dp(16)
        sheet.setPadding(side, ctx.statusBarHeight() + ctx.dp(18), side, ctx.dp(20))

        val clock = TextClock(ctx).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"
            setTextColor(WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 76f)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
        }
        val date = TextClock(ctx).apply {
            format24Hour = "EEEE, d. MMMM"; format12Hour = "EEEE, d. MMMM"
            setTextColor(0xD9FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(ctx.dp(4), 0, 0, ctx.dp(16))
        }
        sheet.addView(clock); sheet.addView(date)

        sheet.addView(tile().apply { addView(toggleRow()) })
        sheet.addView(row(batteryTile(), timerTile()))
        sheet.addView(todoTile())
        sheet.addView(noteTile())

        sheet.addView(View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = ctx.dpf(3f); setColor(0x99FFFFFF.toInt()) }
        }, LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(14)
        })
        sheet.addView(label("Schließen: zwei Finger nach oben, daneben tippen oder Zurück").apply {
            gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, 0)
        })

        scroll.isFillViewport = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.addView(sheet, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply {
            val maxW = ctx.dp(560)
            if (ctx.resources.displayMetrics.widthPixels > maxW) width = maxW
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        })
    }

    private fun glass(radius: Float = 22f, color: Int = GLASS) = GradientDrawable().apply {
        cornerRadius = ctx.dpf(radius); setColor(color); setStroke(ctx.dp(1), LINE)
    }

    private fun tile() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = glass()
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = ctx.dp(10) }
    }

    private fun row(a: View, b: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = ctx.dp(5) })
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = ctx.dp(5) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = ctx.dp(10) }
    }

    private fun label(t: String) = TextView(ctx).apply {
        text = t
        setTextColor(0xBFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun big(t: String) = TextView(ctx).apply {
        text = t
        setTextColor(WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        maxLines = 1
    }

    private fun pill(t: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = t
        setTextColor(WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        minHeight = ctx.dp(44); minWidth = ctx.dp(44)
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        background = glass(22f)
        setOnClickListener { onClick() }
    }

    private fun input(hint: String, multi: Boolean) = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(0x8CFFFFFF.toInt())
        setTextColor(WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = glass(14f, 0x2E000000)
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
        }
    }

    // --- Schalter ---

    private fun toggleRow(): View {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        fun add(icon: Int, name: String, isOn: () -> Boolean, onTap: () -> Unit) {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val circle = FrameLayout(ctx).apply {
                contentDescription = name
                setOnClickListener { onTap(); refreshToggles() }
                addView(ImageView(ctx).apply { setImageResource(icon) },
                    FrameLayout.LayoutParams(ctx.dp(26), ctx.dp(26), Gravity.CENTER))
            }
            col.addView(circle, LinearLayout.LayoutParams(ctx.dp(58), ctx.dp(58)))
            col.addView(label(name).apply {
                gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, 0); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
            rowView.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            toggleViews += circle to isOn
        }

        add(R.drawable.ic_wifi, "WLAN", {
            runCatching { ctx.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)
        }) {
            openSettings(if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                         else Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        add(R.drawable.ic_bt, "Bluetooth", {
            runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)
        }) { openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        add(R.drawable.ic_dnd, "Nicht stören", {
            nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        }) {
            if (nm.isNotificationPolicyAccessGranted) {
                val on = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(
                    if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
                )
            } else openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        add(R.drawable.ic_flash, "Lampe", { torchOn }) {
            torchId?.let { id -> runCatching { cam.setTorchMode(id, !torchOn) } }
        }
        return rowView
    }

    private fun refreshToggles() {
        // kurz verzögert, damit das System den neuen Zustand schon kennt
        main.postDelayed({
            toggleViews.forEach { (v, isOn) ->
                val on = runCatching { isOn() }.getOrDefault(false)
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (on) ROSE else GLASS)
                    setStroke(ctx.dp(1), if (on) ROSE else LINE)
                }
            }
        }, 150)
    }

    private fun openSettings(i: Intent) {
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        close()
    }

    // --- Akku ---

    private fun batteryTile() = tile().apply {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        addView(label("Akku"))
        batteryText = big("–")
        addView(batteryText)
        batteryBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(ROSE)
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
        }
        addView(batteryBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(10)).apply { topMargin = ctx.dp(8) })
    }

    private fun showBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return
        val pct = level * 100 / scale.coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        batteryText.text = "$pct %" + if (charging) " ⚡" else ""
        batteryBar.progress = pct
    }

    // --- Timer ---

    private fun timerTile() = tile().apply {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        addView(label("Timer · Zeit antippen"))
        timerText = big("05:00").apply {
            setOnClickListener {
                if (!timerRunning) {
                    presetIdx = (presetIdx + 1) % presets.size
                    timerLeft = presets[presetIdx] * 60
                    updateTimer()
                }
            }
        }
        addView(timerText)
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        timerBtn = pill("Start") { toggleTimer() }
        btns.addView(timerBtn)
        btns.addView(pill("↺") { resetTimer() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(8) })
        addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
    }

    private fun toggleTimer() {
        if (timerRunning) {
            timerRunning = false; main.removeCallbacks(tick)
        } else {
            if (timerLeft <= 0) timerLeft = presets[presetIdx] * 60
            timerRunning = true; main.postDelayed(tick, 1000)
        }
        updateTimer()
    }

    private fun resetTimer() {
        timerRunning = false; main.removeCallbacks(tick)
        timerLeft = presets[presetIdx] * 60
        updateTimer()
    }

    private fun updateTimer() {
        if (!::timerText.isInitialized) return
        timerText.text = if (timerLeft <= 0) "Fertig!" else "%02d:%02d".format(timerLeft / 60, timerLeft % 60)
        timerBtn.text = if (timerRunning) "Pause" else "Start"
        timerBtn.background = if (timerRunning) glass(22f, ROSE) else glass(22f)
    }

    private fun timerDone() {
        timerRunning = false
        timerLeft = 0
        updateTimer()
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)
        vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).notify(
                2,
                Notification.Builder(ctx, OverlayService.CHANNEL_TIMER)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Timer fertig")
                    .setContentText("${presets[presetIdx]} Minuten sind um.")
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    // --- Aufgaben ---

    private fun todoTile() = tile().apply {
        addView(label("Aufgaben").apply { setPadding(0, 0, 0, ctx.dp(8)) })
        val field = input("Neue Aufgabe …", false)
        field.setOnEditorActionListener { v, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                val t = v.text.toString().trim()
                if (t.isNotEmpty()) {
                    val list = Prefs.todos(ctx); list.add(Todo(t, false)); Prefs.setTodos(ctx, list)
                    v.text = ""
                    renderTodos()
                }
                true
            } else false
        }
        addView(field)
        todoBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addView(todoBox)
        renderTodos()
    }

    private fun renderTodos() {
        todoBox.removeAllViews()
        val list = Prefs.todos(ctx)
        list.forEachIndexed { i, todo ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dp(44)
            }
            val cb = CheckBox(ctx).apply {
                text = todo.text
                isChecked = todo.done
                setTextColor(if (todo.done) 0x80FFFFFF.toInt() else WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                buttonTintList = ColorStateList.valueOf(ROSE)
                paintFlags = if (todo.done) paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                             else paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                setOnCheckedChangeListener { _, checked ->
                    val l = Prefs.todos(ctx)
                    if (i < l.size) { l[i] = l[i].copy(done = checked); Prefs.setTodos(ctx, l) }
                    renderTodos()
                }
            }
            val del = TextView(ctx).apply {
                text = "×"
                contentDescription = "Löschen"
                setTextColor(0x99FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                setOnClickListener {
                    val l = Prefs.todos(ctx)
                    if (i < l.size) { l.removeAt(i); Prefs.setTodos(ctx, l) }
                    renderTodos()
                }
            }
            line.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(del, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
            todoBox.addView(line)
        }
    }

    // --- Notiz ---

    private fun noteTile() = tile().apply {
        addView(label("Notiz").apply { setPadding(0, 0, 0, ctx.dp(8)) })
        val note = input("Schnell etwas aufschreiben …", true)
        note.setText(Prefs.note(ctx))
        note.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { Prefs.setNote(ctx, s?.toString() ?: "") }
        })
        addView(note)
    }
}

/**
 * Wurzel-View des Panels: erkennt zwei Finger nach oben (schließen), auch wenn
 * darunter gescrollt oder getippt wird, und fängt die Zurück-Taste ab.
 */
class PanelRoot(ctx: Context) : FrameLayout(ctx) {
    var onBack: () -> Unit = {}
    var onDrag: (Float) -> Unit = {}
    var onRelease: (Float) -> Unit = {}

    private val slop = ctx.dpf(24f)
    private var startY = 0f
    private var dy = 0f
    private var tracking = false
    private var stealing = false
    private var swallow = false

    private fun avg(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tracking = false; stealing = false; swallow = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && !stealing) {
                startY = avg(e); dy = 0f; tracking = true
            }
            MotionEvent.ACTION_MOVE -> if (tracking && e.pointerCount >= 2) {
                dy = avg(e) - startY
                if (!stealing && dy < -slop) {
                    stealing = true
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (stealing) {
                    stealing = false; tracking = false; swallow = true
                    onRelease(dy)
                } else if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) tracking = false
            }
        }
        if (stealing) { onDrag(dy); return true }
        if (swallow) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallow = false
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}
