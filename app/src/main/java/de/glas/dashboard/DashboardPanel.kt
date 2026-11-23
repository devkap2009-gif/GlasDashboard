package de.glas.dashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.BatteryManager
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.min

private const val SCRIM_MAX_ALPHA = 215
private const val BLUR_MAX = 60

/**
 * Das Overlay-Panel: Kopfzeile, Reiter und waagerecht wischbare Bereiche.
 * Ein eigenes Vollbild-Fenster über allen Apps, das nur existiert, solange es sichtbar ist.
 */
class DashboardPanel(private val ctx: Context, private val wm: WindowManager) {

    private val ui = Ui(ctx)
    private val screenH = ctx.resources.displayMetrics.heightPixels.toFloat()
    private val blurOk = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
    private val scrim = ColorDrawable(Holo.SCRIM).apply { alpha = 0 }

    private val root = PanelRoot(ctx)
    private val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val pager = ViewPager2(ctx)
    private val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val tabScroll = HorizontalScrollView(ctx)
    private val tabs = mutableListOf<TextView>()
    private lateinit var battery: TextView

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        if (blurOk) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
    }

    private var attached = false
    private var progress = 0f
    private var anim: ValueAnimator? = null

    // ---------- Bereiche ----------
    private val start = StartPage(ui, close = { close() }, openProfile = { goTo("profile") })
    private val all: Map<String, Page> = linkedMapOf<String, Page>().apply {
        put("start", start)
        put("gemini", GeminiPage(ui) { close() })
        put("strategy", StrategyPage(ui) { close() })
        put("logic", LogicPage(ui))
        put("impl", ImplPage(ui))
        put("games", GamesPage(ui))
        put("notes", NotesPage(ui) { id -> openNote(id) })
    }.let { m ->
        val withProfile = LinkedHashMap(m)
        withProfile["profile"] = ProfilePage(ui, { if (this::pages.isInitialized) pages.map { it.key to it.title } else emptyList() },
            { keys -> reorder(keys) }, { color -> changeAccent(color) })
        withProfile
    }
    private lateinit var pages: List<Page>

    private fun orderedPages(): List<Page> {
        val result = Prefs.pageOrder(ctx).mapNotNull { all[it] }.toMutableList()
        // neue Bereiche an ihrer Standardstelle einfügen (direkt hinter ihrem Vorgänger)
        val keys = all.keys.toList()
        keys.forEachIndexed { i, k ->
            val p = all[k] ?: return@forEachIndexed
            if (p in result) return@forEachIndexed
            val prev = keys.take(i).lastOrNull { pk -> result.any { it.key == pk } }
            val at = if (prev == null) 0 else result.indexOfFirst { it.key == prev } + 1
            result.add(at, p)
        }
        return result
    }

    private inner class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = pages.size
        override fun getItemId(position: Int) = pages[position].key.hashCode().toLong()
        override fun getItemViewType(position: Int) = position
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(FrameLayout(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val v = pages[position].view
            (v.parent as? ViewGroup)?.removeView(v)
            holder.frame.removeAllViews()
            holder.frame.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = showBattery(i)
    }

    init {
        pages = orderedPages()
        build()
        root.background = scrim
        root.onBack = { if (editorLayer != null) closeEditor() else close() }
        root.setOnApplyWindowInsetsListener { _, ins ->
            imeBottom = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.ime()).bottom else 0
            applyIme()
            ins
        }
        root.onDrag = { dy -> anim?.cancel(); setProgress(1f + dy / (screenH * 0.45f)) }
        root.onRelease = { dy -> settle(dy > -ctx.dpf(70f)) }
    }

    // ---------- Aufbau ----------

    private fun build() {
        container.setPadding(0, ctx.statusBarHeight() + ctx.dp(6), 0, 0)

        // Kopfzeile: Uhr, Datum, Akku, Schließen
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), 0, ctx.dp(8), 0)
        }
        val clock = TextClock(ctx).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"
            setTextColor(Holo.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = Holo.thin; includeFontPadding = false
            setShadowLayer(ctx.dpf(14f), 0f, 0f, Holo.ICE)
        }
        val dateCol = ui.column().apply { setPadding(ctx.dp(12), 0, 0, 0) }
        dateCol.addView(TextClock(ctx).apply {
            format24Hour = "EEEE"; format12Hour = "EEEE"
            setTextColor(Holo.ICE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Holo.label; letterSpacing = 0.15f
            isAllCaps = true
        })
        dateCol.addView(TextClock(ctx).apply {
            format24Hour = "d. MMMM"; format12Hour = "d. MMMM"
            setTextColor(Holo.TEXT_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        battery = ui.label("–")
        head.addView(clock)
        head.addView(dateCol, ui.weight())
        head.addView(battery, ui.lpWrap(end = 8))
        head.addView(TextView(ctx).apply {
            text = "×"; contentDescription = "Schließen"
            setTextColor(Holo.ICE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); gravity = Gravity.CENTER
            setOnClickListener { close() }
        }, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
        container.addView(head)

        // Reiter
        tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.overScrollMode = View.OVER_SCROLL_NEVER
        tabRow.setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        tabScroll.addView(tabRow)
        container.addView(tabScroll)
        container.addView(View(ctx).apply { setBackgroundColor(Holo.LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)))

        // Bereiche
        pager.adapter = adapter
        pager.offscreenPageLimit = all.size
        // Tiefe beim Wischen: Nachbarseiten werden kleiner und blasser
        pager.setPageTransformer { page, pos ->
            val a = min(1f, abs(pos))
            page.alpha = 1f - 0.55f * a
            page.scaleX = 1f - 0.08f * a
            page.scaleY = 1f - 0.08f * a
            page.rotationY = -12f * pos.coerceIn(-1f, 1f)
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                highlightTab(position)
                hideKeyboard()
                pages.getOrNull(position)?.let { ui.animateIn(it.view) }
                Prefs.setInt(ctx, "last_page", position)
            }
        })
        container.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Punkte als Seitenanzeige
        root.addView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        buildTabs()
        pager.setCurrentItem(Prefs.int(ctx, "last_page", 0).coerceIn(0, pages.size - 1), false)
    }

    private fun buildTabs() {
        tabRow.removeAllViews(); tabs.clear()
        pages.forEachIndexed { i, p ->
            val t = ui.pill(p.title) { pager.currentItem = i }
            tabs += t
            tabRow.addView(t, ui.lpWrap(end = 8))
        }
        highlightTab(pager.currentItem)
    }

    private fun highlightTab(pos: Int) {
        tabs.forEachIndexed { i, t -> ui.setActive(t, i == pos) }
        tabs.getOrNull(pos)?.let { t -> tabScroll.post { tabScroll.smoothScrollTo((t.left - ctx.dp(40)).coerceAtLeast(0), 0) } }
    }

    private fun goTo(key: String) {
        val i = pages.indexOfFirst { it.key == key }
        if (i >= 0) pager.currentItem = i
    }

    private fun reorder(keys: List<String>) {
        val currentKey = pages.getOrNull(pager.currentItem)?.key
        Prefs.setPageOrder(ctx, keys)
        pages = orderedPages()
        adapter.notifyDataSetChanged()
        buildTabs()
        val i = pages.indexOfFirst { it.key == currentKey }
        if (i >= 0) pager.setCurrentItem(i, false)
        highlightTab(pager.currentItem)
    }

    // ---------- Notiz-Editor (eigene Ebene über allem) ----------

    private var editorLayer: View? = null
    private var editorSave: (() -> Unit)? = null
    private var editorId: Long = 0
    private var imeBottom = 0

    private fun applyIme() {
        editorLayer?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, ctx.dp(12) + imeBottom) }
        container.setPadding(0, container.paddingTop, 0, imeBottom)
    }

    fun openNote(id: Long) {
        if (editorLayer != null) closeEditor()
        val note = Prefs.notes(ctx).firstOrNull { it.id == id } ?: return
        editorId = id
        val layer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2050B18.toInt())
            setPadding(ctx.dp(14), ctx.statusBarHeight() + ctx.dp(10), ctx.dp(14), ctx.dp(12))
            isClickable = true
        }
        val count = ui.small("")
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.pill("← Fertig", true) { closeEditor() })
        head.addView(count.apply { gravity = Gravity.CENTER }, ui.weight())
        head.addView(ui.pill("Löschen") {
            Prefs.deleteNote(ctx, id); editorSave = null; closeEditor()
        })
        layer.addView(head, ui.lpMatch(bottom = 12))
        val titleIn: EditText = ui.input("Titel").apply {
            setText(note.title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        }
        val bodyIn: EditText = ui.input("Schreib los …", lines = 4).apply {
            setText(note.body)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setLineSpacing(0f, 1.25f)
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(false)
        }
        layer.addView(titleIn, ui.lpMatch(bottom = 10))
        layer.addView(bodyIn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val save = {
            Prefs.saveNote(ctx, note.copy(title = titleIn.text.toString(), body = bodyIn.text.toString(), updated = System.currentTimeMillis()))
        }
        editorSave = save
        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                editorSave?.invoke()
                val words = bodyIn.text.split(Regex("\\s+")).count { it.isNotBlank() }
                count.text = "$words Wörter · gespeichert"
            }
        }
        titleIn.addTextChangedListener(w); bodyIn.addTextChangedListener(w)
        count.text = "Neue Notiz"
        root.addView(layer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        editorLayer = layer
        applyIme()
        val target = if (note.title.isBlank() && note.body.isBlank()) titleIn else bodyIn
        target.postDelayed({
            target.requestFocus()
            if (target === bodyIn) bodyIn.setSelection(bodyIn.text.length)
            ui.showKeyboard(target)
        }, 250)
    }

    fun closeEditor() {
        val layer = editorLayer ?: return
        editorSave?.invoke()
        editorSave = null
        Prefs.notes(ctx).firstOrNull { it.id == editorId }?.let { if (it.title.isBlank() && it.body.isBlank()) Prefs.deleteNote(ctx, it.id) }
        hideKeyboard()
        root.removeView(layer)
        editorLayer = null
        (all["notes"] as? NotesPage)?.render()
    }

    /** Gedrückt halten auf dem Punkt: Panel öffnen und sofort eine neue Notiz anlegen. */
    fun openQuickNote() {
        settle(true)
        goTo("notes")
        val n = Note(System.currentTimeMillis(), "", "", System.currentTimeMillis())
        Prefs.saveNote(ctx, n)
        openNote(n.id)
    }

    fun openAt(key: String) { settle(true); goTo(key) }

    private fun changeAccent(color: Int) {
        Prefs.setAccent(ctx, color)
        root.post { (ctx as? OverlayService)?.rebuildPanel("profile") }
    }

    // ---------- öffentliche Steuerung ----------

    fun drag(p: Float) { anim?.cancel(); attach(); setProgress(p) }
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
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled && target == 0f) detach() }
            })
            start()
        }
    }

    fun destroy() {
        anim?.cancel()
        detach()
        start.destroy()
    }

    // ---------- Fenster ----------

    private fun attach() {
        if (attached) return
        if (Build.VERSION.SDK_INT >= 31) lp.blurBehindRadius = 0
        runCatching { wm.addView(root, lp) }.onSuccess {
            attached = true
            ctx.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { showBattery(it) }
            pages.forEach { p -> runCatching { p.onShow() } }
            pages.getOrNull(pager.currentItem)?.let { p -> root.post { ui.animateIn(p.view) } }
        }
    }

    private fun detach() {
        if (!attached) return
        closeEditor()
        pages.forEach { p -> runCatching { p.onHide() } }
        runCatching { ctx.unregisterReceiver(batteryReceiver) }
        runCatching { wm.removeView(root) }
        attached = false
        progress = 0f
    }

    private fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        container.translationY = -(1f - progress) * screenH
        container.alpha = 0.4f + 0.6f * progress
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
        ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
        root.findFocus()?.clearFocus()
    }

    private fun showBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return
        val pct = level * 100 / scale.coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        battery.text = (if (charging) "⚡ " else "") + "$pct %"
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
