package de.glas.dashboard

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Gemini-Chat direkt im Overlay – ohne die Gemini-App. */
class GeminiPage(private val ui: Ui, private val close: () -> Unit = {}) : Page {
    override val key = "gemini"
    override val title = "Gemini"

    private val ctx = ui.ctx
    private val main = Handler(Looper.getMainLooper())
    private val rootView = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    override val view: View = rootView

    private val scroll = ScrollView(ctx).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
    private val list = ui.column().apply { setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(16)) }
    private lateinit var input: EditText
    private var busy = false
    private var thinking: TextView? = null
    private var dots = 0
    private var attachMap = false
    private var showSettings = false
    private lateinit var bar: View
    private var askText = ""
    private var withMap = false
    private var withNote = false

    private val system = "Du bist ein scharfsinniger Denkpartner in einem Dashboard für Philosophie, Logik, Wissenschaft und Strategie. " +
        "Antworte auf Deutsch, präzise und ohne Floskeln. Nutze, wo es hilft, kurze Listen. " +
        "Bei Strategie- und Entscheidungsfragen: denke in Implikationen, nenne Wahrscheinlichkeiten und Risiken."

    private val dotTick = object : Runnable {
        override fun run() {
            dots = (dots + 1) % 4
            thinking?.text = "Gemini denkt" + ".".repeat(dots)
            if (busy) main.postDelayed(this, 350)
        }
    }

    init {
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        rootView.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        bar = inputBar()
        rootView.addView(bar)
        render()
    }

    override fun onShow() { if (!busy) render() }

    private fun inputBar(): View {
        val bar = ui.column().apply {
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(12))
            setBackgroundColor(0xE6050B18.toInt())
        }
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var attach: TextView
        attach = ui.pill("Karte anhängen", attachMap) { attachMap = !attachMap; ui.setActive(attach, attachMap) }
        chips.addView(attach)
        chips.addView(ui.pill("Neu") { Gemini.saveHistory(ctx, emptyList()); render() }, ui.lpWrap(start = 8))
        chips.addView(ui.pill("⚙") { showSettings = !showSettings; render() }, ui.lpWrap(start = 8))
        bar.addView(chips)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        input = ui.input("Frag Gemini …", lines = 1).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
        }
        row.addView(input, ui.weight())
        row.addView(ui.pill("➤", true) { send() }, ui.lpWrap(start = 8))
        bar.addView(row, ui.lpMatch(top = 8))
        return bar
    }

    private fun bubble(text: String, user: Boolean): View {
        val t = TextView(ctx).apply {
            this.text = text
            setTextColor(if (user) Holo.NAVY else Holo.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = if (user) ui.cut(Holo.ICE, Holo.ICE, 12f) else ui.cut(Holo.PANEL, Holo.LINE, 12f)
        }
        val wrap = LinearLayout(ctx).apply { gravity = if (user) Gravity.END else Gravity.START }
        wrap.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (user) marginStart = ui.dp(48) else marginEnd = ui.dp(24)
        })
        return wrap
    }

    /** Das echte Gemini-Overlay öffnen – ohne API-Schlüssel. */
    private fun overlayTile() = ui.tile().apply {
        addView(ui.label("Gemini-Overlay"))
        addView(ui.small("Öffnet das echte Gemini-Fenster über deinem Bildschirm – kein API-Schlüssel nötig."), ui.lpMatch(top = 4))
        val q = ui.input("Was willst du Gemini fragen?", lines = 2).apply { setText(askText) }
        q.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { askText = s?.toString() ?: "" }
        })
        addView(q, ui.lpMatch(top = 10))
        lateinit var mapChip: TextView
        lateinit var noteChip: TextView
        mapChip = ui.pill("+ Implikations-Karte", withMap) { withMap = !withMap; ui.setActive(mapChip, withMap) }
        noteChip = ui.pill("+ Letzte Notiz", withNote) { withNote = !withNote; ui.setActive(noteChip, withNote) }
        addView(ui.rowOf(mapChip, noteChip), ui.lpMatch(top = 8))
        addView(ui.pill("✦ Im Gemini-Overlay fragen", true) { openOverlay(buildQuestion()) }, ui.lpWrap(top = 12))
        addView(ui.pill("Gemini über diesem Bildschirm") { openOverlay(null) }, ui.lpWrap(top = 8))
        addView(ui.small("So geht's: Deine Frage liegt danach in der Zwischenablage. Im Gemini-Fenster ins Eingabefeld tippen → lange drücken → Einfügen. " +
            "„Gemini über diesem Bildschirm“ öffnet Gemini ohne Text – dort kannst du nach dem fragen, was gerade zu sehen ist."), ui.lpMatch(top = 10))
    }

    private fun buildQuestion(): String {
        val sb = StringBuilder(askText.trim())
        if (withMap) {
            val m = MapModel()
            runCatching { m.load(org.json.JSONObject(ctx.getSharedPreferences("strategy", 0).getString("map", "{}") ?: "{}")) }
            sb.append("\n\n[Meine Implikations-Karte]\n").append(m.describe())
        }
        if (withNote) {
            Prefs.notes(ctx).firstOrNull()?.let { n -> sb.append("\n\n[Notiz: ${n.title}]\n").append(n.body) }
        }
        return sb.toString().trim()
    }

    private fun openOverlay(text: String?) {
        close()
        main.postDelayed({ AssistActivity.open(ctx, text) }, 300)
    }

    private fun render() {
        list.removeAllViews()
        list.addView(overlayTile())
        val hasKey = Gemini.key(ctx).isNotBlank()
        bar.visibility = if (hasKey) View.VISIBLE else View.GONE
        if (!hasKey && !showSettings) {
            list.addView(ui.small("Optional: Mit einem API-Schlüssel kann Gemini auch direkt hier im Panel antworten ▸").apply {
                setTextColor(Holo.ICE_DIM); setPadding(0, ui.dp(6), 0, ui.dp(6)); setOnClickListener { showSettings = true; render() }
            })
            return
        }
        if (!hasKey || showSettings) { list.addView(settings()); if (!hasKey) return }
        val h = Gemini.history(ctx)
        if (h.isEmpty()) {
            list.addView(ui.tile().apply {
                addView(ui.label("Chat im Panel"))
                addView(ui.small("Stell eine Frage – zum Beispiel zu einem Logik-Problem, einem Philosophen oder deiner Strategie. Mit „Karte anhängen“ bekommt Gemini deine Implikations-Karte mit."), ui.lpMatch(top = 6))
                listOf("Erkläre Gödels Unvollständigkeitssatz in 5 Sätzen.", "Was ist der Unterschied zwischen S4 und S5?", "Prüfe meine Implikations-Karte auf Denkfehler.").forEach { s ->
                    addView(ui.small("› $s").apply { setTextColor(Holo.ICE); setPadding(0, ui.dp(10), 0, ui.dp(4)); setOnClickListener { input.setText(s) } })
                }
            })
        }
        h.forEach { list.addView(bubble(it.text, it.user), ui.lpMatch(top = 8)) }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun settings() = ui.tile().apply {
        addView(ui.label("Gemini verbinden"))
        addView(ui.small(
            "1. Öffne aistudio.google.com/apikey im Browser und erstelle einen kostenlosen API-Schlüssel.\n" +
            "2. Kopiere ihn und füge ihn hier ein. Er bleibt nur auf deinem Handy.\n" +
            "Danach läuft Gemini direkt hier im Overlay."), ui.lpMatch(top = 6))
        val k = ui.input("API-Schlüssel einfügen").apply {
            setText(Gemini.key(ctx))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addView(k, ui.lpMatch(top = 10))
        val m = ui.input("Modell").apply { setText(Gemini.model(ctx)) }
        addView(ui.small("Modell (Standard: ${Gemini.DEFAULT_MODEL})"), ui.lpMatch(top = 10))
        addView(m, ui.lpMatch(top = 4))
        val status = ui.small("")
        addView(ui.rowOf(
            ui.pill("Speichern & testen", true) {
                Gemini.setKey(ctx, k.text.toString()); Gemini.setModel(ctx, m.text.toString())
                status.text = "Teste Verbindung …"
                Thread {
                    val r = runCatching { Gemini.generate(ctx, listOf(Gemini.Msg(true, "Antworte nur mit: Verbunden.")) ) }
                    main.post {
                        status.text = r.fold({ "✓ Verbunden – ${Gemini.model(ctx)} antwortet." }, { "✗ ${it.message}" })
                        if (r.isSuccess) { showSettings = false; main.postDelayed({ render() }, 900) }
                    }
                }.start()
            },
            ui.pill("Schlüssel löschen") { Gemini.setKey(ctx, ""); render() }
        ), ui.lpMatch(top = 12))
        addView(status, ui.lpMatch(top = 8))
    }

    private fun send() {
        val q = input.text.toString().trim()
        if (q.isEmpty() || busy) return
        if (Gemini.key(ctx).isBlank()) { render(); return }
        input.setText("")
        val h = Gemini.history(ctx)
        h += Gemini.Msg(true, q)
        Gemini.saveHistory(ctx, h)
        render()
        busy = true
        thinking = ui.small("Gemini denkt").also { list.addView(it, ui.lpMatch(top = 10)) }
        main.post(dotTick)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        // Karte als Kontext an die letzte Frage hängen (nur für diese Anfrage)
        val req = h.toMutableList()
        if (attachMap) {
            val m = MapModel()
            runCatching { m.load(org.json.JSONObject(ctx.getSharedPreferences("strategy", 0).getString("map", "{}") ?: "{}")) }
            req[req.size - 1] = Gemini.Msg(true, q + "\n\n[Meine Implikations-Karte]\n" + m.describe())
        }
        Thread {
            val r = runCatching { Gemini.generate(ctx, req, system) }
            main.post {
                busy = false
                val hh = Gemini.history(ctx)
                hh += Gemini.Msg(false, r.fold({ Gemini.plain(it) }, { "⚠ ${it.message}" }))
                Gemini.saveHistory(ctx, hh)
                render()
            }
        }.start()
    }
}
