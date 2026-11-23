package de.glas.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** Gemeinsame Bausteine im „Arktis-Kommando“-Look. */
class Ui(val ctx: Context) {

    fun dp(v: Int) = ctx.dp(v)
    fun dpf(v: Float) = ctx.dpf(v)

    fun cut(fill: Int = Holo.PANEL, stroke: Int = Holo.LINE, c: Float = 18f, w: Float = 1f) =
        CutDrawable(dpf(c), fill, stroke, dpf(w))

    fun lpMatch(bottom: Int = 0, top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom); topMargin = dp(top) }

    fun lpWrap(start: Int = 0, end: Int = 0, top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = dp(start); marginEnd = dp(end); topMargin = dp(top) }

    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    fun column() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun rowOf(vararg views: View, gap: Int = 8) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v -> addView(v, lpWrap(start = if (i == 0) 0 else gap)) }
    }

    fun tile() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = cut()
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = lpMatch(bottom = 10)
    }

    /** Seite: senkrecht scrollbar, mit Innenabstand. */
    fun page(content: View) = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (content is LinearLayout) content.setPadding(dp(14), dp(10), dp(14), dp(40))
    }

    fun label(t: String) = TextView(ctx).apply {
        text = t.uppercase()
        setTextColor(Holo.ICE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Holo.label
        letterSpacing = 0.18f
    }

    fun title(t: String) = TextView(ctx).apply {
        text = t
        setTextColor(Holo.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Holo.light
        setShadowLayer(dpf(10f), 0f, 0f, Holo.ICE_DIM)
    }

    fun body(t: String, sp: Float = 15f) = TextView(ctx).apply {
        text = t
        setTextColor(Holo.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setLineSpacing(0f, 1.2f)
    }

    fun small(t: String) = TextView(ctx).apply {
        text = t
        setTextColor(Holo.TEXT_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(0f, 1.15f)
    }

    fun mono(t: String) = TextView(ctx).apply {
        text = t
        setTextColor(Holo.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    fun big(t: String) = TextView(ctx).apply {
        text = t
        setTextColor(Holo.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        typeface = Holo.light
        maxLines = 1
        setShadowLayer(dpf(10f), 0f, 0f, Holo.ICE_DIM)
    }

    fun pill(t: String, active: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        text = t.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Holo.label
        letterSpacing = 0.1f
        gravity = Gravity.CENTER
        minHeight = dp(44); minWidth = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        setActive(this, active)
        setOnClickListener { onClick() }
        pressable(this)
    }

    /** Kleines Einsinken beim Antippen. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
            }
            false
        }
    }

    /** Kacheln einer Seite nacheinander hereingleiten lassen. */
    fun animateIn(page: View) {
        val content = (page as? android.view.ViewGroup)?.getChildAt(0) as? android.view.ViewGroup ?: return
        for (i in 0 until content.childCount) {
            val c = content.getChildAt(i)
            c.animate().cancel()
            c.alpha = 0f; c.translationY = dpf(22f)
            c.animate().alpha(1f).translationY(0f).setStartDelay(40L * i.coerceAtMost(8)).setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
    }

    fun setActive(v: TextView, active: Boolean) {
        v.setTextColor(if (active) Holo.NAVY else Holo.WHITE)
        v.background = if (active) cut(Holo.ICE, Holo.ICE, 10f) else cut(Holo.PANEL_DEEP, Holo.LINE, 10f)
    }

    /** Waagerechte Auswahl („Chips“), wischbar wenn zu breit. */
    fun chips(options: List<String>, selected: Int, onPick: (Int) -> Unit): HorizontalScrollView {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val views = mutableListOf<TextView>()
        options.forEachIndexed { i, o ->
            val v = pill(o, i == selected) {
                views.forEachIndexed { k, tv -> setActive(tv, k == i) }
                onPick(i)
            }
            views += v
            row.addView(v, lpWrap(end = 8))
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
    }

    fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Holo.ICE)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = cut(Holo.PANEL_DEEP, Holo.LINE, 10f)
        setOnClickListener { onClick() }
    }

    fun input(hint: String, lines: Int = 1, monoFont: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(0x80B9D7EE.toInt())
        setTextColor(Holo.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (monoFont) 15f else 16f)
        if (monoFont) typeface = Typeface.MONOSPACE
        background = cut(Holo.PANEL_DEEP, Holo.LINE, 10f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        // Tastatur zuverlässig öffnen (Overlay-Fenster)
        setOnFocusChangeListener { v, has -> if (has) showKeyboard(v) }
        setOnClickListener { showKeyboard(it) }
        if (lines > 1) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = lines; gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
        }
    }

    /** Leiste mit Sonderzeichen, die in ein Eingabefeld eingefügt werden. */
    fun symbolBar(target: () -> EditText?, symbols: List<String>): HorizontalScrollView {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        symbols.forEach { s ->
            row.addView(TextView(ctx).apply {
                text = s
                setTextColor(Holo.ICE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                minWidth = dp(44); minHeight = dp(44)
                background = cut(Holo.PANEL_DEEP, Holo.LINE, 8f)
                setOnClickListener {
                    val e = target() ?: return@setOnClickListener
                    val st = e.selectionStart.coerceAtLeast(0)
                    val en = e.selectionEnd.coerceAtLeast(0)
                    e.text.replace(minOf(st, en), maxOf(st, en), s)
                }
            }, lpWrap(end = 6))
        }
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
    }

    fun showKeyboard(v: View) {
        v.post {
            v.requestFocus()
            ctx.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(v, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun bar() = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressTintList = ColorStateList.valueOf(Holo.ICE)
        progressBackgroundTintList = ColorStateList.valueOf(Holo.a(0x33))
    }

    fun hline() = View(ctx).apply { setBackgroundColor(Holo.a(0x26)) }

    fun ellipsize(t: TextView, lines: Int) { t.maxLines = lines; t.ellipsize = TextUtils.TruncateAt.END }
}

/** Ein Bereich im Panel, zwischen denen man waagerecht wischt. */
interface Page {
    val key: String
    val title: String
    val view: View
    fun onShow() {}
    fun onHide() {}
}
