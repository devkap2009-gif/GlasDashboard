package de.glas.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/** Kleiner JSON-Speicher für den Strategie-Planer. */
private object Store {
    private fun sp(c: Context) = c.getSharedPreferences("strategy", Context.MODE_PRIVATE)
    fun arr(c: Context, k: String): JSONArray = try { JSONArray(sp(c).getString(k, "[]")) } catch (e: Exception) { JSONArray() }
    fun obj(c: Context, k: String): JSONObject = try { JSONObject(sp(c).getString(k, "{}")) } catch (e: Exception) { JSONObject() }
    fun put(c: Context, k: String, v: Any) = sp(c).edit().putString(k, v.toString()).apply()
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun List<JSONObject>.toArr(): JSONArray = JSONArray().also { a -> forEach { a.put(it) } }

/**
 * Strategie-Planer: Ziele → Strategien → Schritte, Entscheidungsmatrix,
 * SWOT-Analyse, Szenarien mit Erwartungswert.
 */
class StrategyPage(private val ui: Ui, private val close: () -> Unit = {}) : Page {
    override val key = "strategy"
    override val title = "Strategie"

    private val ctx = ui.ctx
    private val col = ui.column()
    override val view: View = ui.page(col)
    private var tool = Prefs.int(ctx, "strat_tool2", 5)
    private val chipOrder = intArrayOf(5, 4, 0, 1, 2, 3)   // Anzeige-Reihenfolge → Werkzeug
    private val chipNames = listOf("Schnell-Check", "Karte", "Ziele", "Vergleich", "SWOT", "Szenarien")
    private val chipHelp = mapOf(
        5 to "Entscheidung in 3 Schritten: Frage, Möglichkeiten, Folgen antippen – die App rechnet die beste Wahl aus.",
        4 to "Alle Folgen als Mindmap. Knoten antippen zum Bearbeiten.",
        0 to "Ein Ziel in Strategien und abhakbare Schritte zerlegen.",
        1 to "Optionen nach gewichteten Kriterien vergleichen.",
        2 to "Stärken, Schwächen, Chancen, Risiken sammeln.",
        3 to "Mögliche Ausgänge mit Wahrscheinlichkeit und Wert – ergibt den Erwartungswert."
    )
    private lateinit var helpText: TextView
    private val box = ui.column()
    private var openGoal: Long? = null

    override fun onShow() = render()

    private fun render() {
        box.removeAllViews()
        if (::helpText.isInitialized) helpText.text = chipHelp[tool] ?: ""
        when (tool) {
            0 -> { val g = openGoal; if (g == null) goals() else goalDetail(g) }
            1 -> decision()
            2 -> swot()
            3 -> scenarios()
            4 -> mapTool()
            else -> quick()
        }
    }

    private fun addRow(hint: String, onAdd: (String) -> Unit): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val e = ui.input(hint)
        fun go() { val s = e.text.toString().trim(); if (s.isNotEmpty()) { onAdd(s); e.setText("") } }
        e.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { go(); true } else false }
        row.addView(e, ui.weight())
        row.addView(ui.pill("+", true) { go() }, ui.lpWrap(start = 8))
        return row
    }

    private fun xBtn(onClick: () -> Unit) = TextView(ctx).apply {
        text = "×"; contentDescription = "Löschen"; setTextColor(Holo.ICE_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
    }

    private fun meter(pct: Int) = ui.bar().apply { max = 100; progress = pct.coerceIn(0, 100) }

    // =====================================================================
    // Schnell-Check: Frage → Möglichkeiten → Folgen (nur antippen)
    // =====================================================================

    private val likely = listOf("Selten" to 0.1, "Möglich" to 0.4, "Wahrscheinlich" to 0.7, "Fast sicher" to 0.95)
    private val impact = listOf("Sehr schlecht" to -100.0, "Schlecht" to -40.0, "Gut" to 40.0, "Sehr gut" to 100.0)

    private fun loadQuick(): JSONObject = Store.obj(ctx, "quick").also { q ->
        if (!q.has("options")) {
            q.put("question", "Soll ich nächstes Semester ein Auslandssemester machen?")
            q.put("options", JSONArray()
                .put(JSONObject().put("t", "Ja, ins Ausland").put("c", JSONArray()
                    .put(JSONObject().put("t", "Neue Sprache & Kontakte").put("l", 2).put("i", 3))
                    .put(JSONObject().put("t", "Höhere Kosten").put("l", 3).put("i", 1))
                    .put(JSONObject().put("t", "Kurse werden nicht angerechnet").put("l", 1).put("i", 0))))
                .put(JSONObject().put("t", "Nein, hierbleiben").put("c", JSONArray()
                    .put(JSONObject().put("t", "Studium läuft planmäßig").put("l", 3).put("i", 2))
                    .put(JSONObject().put("t", "Später ärgern, es nicht gemacht zu haben").put("l", 1).put("i", 1)))))
        }
    }

    private fun quick() {
        val q = loadQuick()
        fun save() = Store.put(ctx, "quick", q)
        val options = q.getJSONArray("options")

        // Ergebnis ganz oben
        val scores = options.objects().map { o ->
            val cs = o.optJSONArray("c")?.objects() ?: emptyList()
            val ev = cs.sumOf { likely[it.optInt("l").coerceIn(0, 3)].second * impact[it.optInt("i").coerceIn(0, 3)].second }
            val risk = cs.filter { it.optInt("i") <= 1 }.maxOfOrNull { likely[it.optInt("l").coerceIn(0, 3)].second } ?: 0.0
            Triple(o.optString("t"), ev, risk)
        }
        val res = ui.tile()
        res.addView(ui.label("Ergebnis"))
        if (scores.isEmpty()) res.addView(ui.small("Füge unten mindestens eine Möglichkeit hinzu."), ui.lpMatch(top = 6))
        else {
            val best = scores.maxByOrNull { it.second }!!
            res.addView(ui.title("Empfehlung: ${best.first}"), ui.lpMatch(top = 6))
            val maxAbs = scores.maxOf { abs(it.second) }.coerceAtLeast(1.0)
            scores.sortedByDescending { it.second }.forEach { (name, ev, risk) ->
                res.addView(ui.small("$name  ·  Bilanz ${"%+.0f".format(ev)}  ·  Risiko ${(risk * 100).toInt()} %"), ui.lpMatch(top = 10))
                res.addView(meter(((ev / maxAbs + 1) * 50).toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(6)).apply { topMargin = ui.dp(4) })
            }
            res.addView(ui.small("Bilanz = Summe aus Wahrscheinlichkeit × Wirkung aller Folgen. Risiko = Chance der wahrscheinlichsten schlechten Folge."), ui.lpMatch(top = 10))
        }
        res.addView(ui.pill("Als Karte ansehen", true) { quickToMap(q) }, ui.lpWrap(top = 12))
        box.addView(res)

        // 1. Frage
        val t1 = ui.tile()
        t1.addView(ui.label("1 · Worum geht es?"))
        val qIn = ui.input("Deine Entscheidung …").apply { setText(q.optString("question")) }
        qIn.addTextChangedListener(saver { q.put("question", qIn.text.toString()); save() })
        t1.addView(qIn, ui.lpMatch(top = 6))
        box.addView(t1)

        // 2. Möglichkeiten mit 3. Folgen
        options.objects().forEachIndexed { oi, o ->
            val t = ui.tile()
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(ui.label("Möglichkeit ${oi + 1}"), ui.weight())
            head.addView(xBtn { options.remove(oi); save(); render() })
            t.addView(head)
            t.addView(ui.title(o.optString("t")).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f) })
            val cs = o.optJSONArray("c") ?: JSONArray().also { o.put("c", it) }
            cs.objects().forEachIndexed { ci, c ->
                t.addView(ui.hline(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)).apply { topMargin = ui.dp(12) })
                val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(ui.body(c.optString("t"), 15f), ui.weight())
                r.addView(xBtn { cs.remove(ci); save(); render() })
                t.addView(r, ui.lpMatch(top = 6))
                t.addView(ui.chips(likely.map { it.first }, c.optInt("l")) { c.put("l", it); save(); render() }, ui.lpMatch(top = 4))
                t.addView(ui.chips(impact.map { it.first }, c.optInt("i")) { c.put("i", it); save(); render() }, ui.lpMatch(top = 6))
            }
            t.addView(addRow("Was könnte passieren?") { txt ->
                cs.put(JSONObject().put("t", txt).put("l", 1).put("i", 2)); save(); render()
            }, ui.lpMatch(top = 12))
            box.addView(t)
        }
        val add = ui.tile()
        add.addView(ui.label("2 · Möglichkeit hinzufügen"))
        add.addView(addRow("z. B. Erst nächstes Jahr") { txt ->
            options.put(JSONObject().put("t", txt).put("c", JSONArray())); save(); render()
        }, ui.lpMatch(top = 6))
        add.addView(ui.small("3 · Zu jeder Möglichkeit Folgen eintragen und antippen, wie wahrscheinlich und wie gut/schlecht sie sind."), ui.lpMatch(top = 10))
        add.addView(ui.pill("Neu anfangen") { Store.put(ctx, "quick", JSONObject().put("question", "").put("options", JSONArray())); render() }, ui.lpWrap(top = 10))
        box.addView(add)
    }

    /** Schnell-Check als Mindmap darstellen. */
    private fun quickToMap(q: JSONObject) {
        map.load(JSONObject())
        val opts = q.getJSONArray("options").objects()
        opts.forEach { o ->
            val on = map.add(o.optString("t"), 1.0)
            (o.optJSONArray("c")?.objects() ?: emptyList()).forEach { c ->
                val cn = map.add(c.optString("t"), 0.0, impact[c.optInt("i").coerceIn(0, 3)].second)
                map.link(on.id, cn.id, likely[c.optInt("l").coerceIn(0, 3)].second)
            }
        }
        mapSel = null
        Store.put(ctx, "map", map.toJson())
        tool = 4; Prefs.setInt(ctx, "strat_tool2", 4)
        // Chips neu aufbauen, damit „Karte“ markiert ist
        col.removeViewAt(0)
        col.addView(ui.chips(chipNames, chipOrder.indexOf(tool)) {
            tool = chipOrder[it]; Prefs.setInt(ctx, "strat_tool2", tool); openGoal = null; render()
        }, 0, ui.lpMatch(bottom = 6))
        render()
    }

    // =====================================================================
    // Implikations-Karte (Mindmap mit Wahrscheinlichkeiten)
    // =====================================================================

    private val map = MapModel().also { m ->
        val saved = Store.obj(ctx, "map")
        if (saved.has("nodes")) m.load(saved) else { val ex = MapModel.example(); m.load(ex.toJson()) }
    }
    private var mapView: ImplMapView? = null
    private var mapSel: Int? = null
    private var advanced = false

    private fun saveMap() { Store.put(ctx, "map", map.toJson()); mapView?.relayout() }

    private fun mapTool() {
        val t = ui.tile()
        t.addView(ui.label("Implikations-Karte"))
        t.addView(ui.small("Knoten antippen zum Bearbeiten · ziehen = verschieben · zwei Finger = zoomen. Linienstärke und Tempo der Punkte zeigen, wie wahrscheinlich eine Folge eintritt."), ui.lpMatch(top = 4))
        val mv = ImplMapView(ctx, map) { id -> mapSel = id; renderEditor() }
        mv.selected = mapSel
        mapView = mv
        t.addView(mv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(460)).apply { topMargin = ui.dp(10) })
        mv.relayout(); mv.replay()
        t.addView(ui.rowOf(ui.pill("Zentrieren") { mv.center() }, ui.pill("▶ Abspielen") { mv.replay() }), ui.lpMatch(top = 10))
        box.addView(t)

        editorBox = ui.column()
        box.addView(editorBox)
        renderEditor()

        summaryBox = ui.column()
        box.addView(summaryBox)
        renderSummary()

        val more = ui.tile()
        more.addView(ui.label("Karte"))
        more.addView(addRow("Neuer Ausgangspunkt (z. B. eine Entscheidung)") { txt ->
            val n = map.add(txt, 1.0); mapSel = n.id; saveMap(); render()
        }, ui.lpMatch(top = 8))
        more.addView(ui.rowOf(
            ui.pill("Aus Implikationen") { importRules() },
            ui.pill("Beispiel") { map.load(MapModel.example().toJson()); mapSel = null; saveMap(); render() },
            ui.pill("Leeren") { map.load(JSONObject()); mapSel = null; saveMap(); render() }
        ), ui.lpMatch(top = 10))
        box.addView(more)

        box.addView(geminiTile())
    }

    private lateinit var editorBox: LinearLayout
    private lateinit var summaryBox: LinearLayout

    private fun renderEditor() {
        if (!::editorBox.isInitialized) return
        editorBox.removeAllViews()
        val n = mapSel?.let { map.node(it) } ?: return
        val t = ui.tile()
        t.addView(ui.label("Ausgewählt"))
        val name = ui.input("Ereignis / Zustand").apply { setText(n.text); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
        name.setOnEditorActionListener { v, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { n.text = v.text.toString(); saveMap(); renderSummary(); true } else false }
        name.setOnFocusChangeListener { v, has -> if (!has) { n.text = (v as EditText).text.toString(); saveMap(); renderSummary() } }
        t.addView(name, ui.lpMatch(top = 6))
        t.addView(ui.title("Gesamtwahrscheinlichkeit: ${MapModel.pct(n.p)}").apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }, ui.lpMatch(top = 10))

        fun stepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ui.small(label), ui.weight())
            addView(ui.pill("−") { minus() })
            addView(ui.label(value).apply { gravity = Gravity.CENTER; minWidth = ui.dp(64) })
            addView(ui.pill("+") { plus() })
        }
        val root = map.isRoot(n.id)
        // Einfach: Wahrscheinlichkeit der eingehenden Folge per Antippen
        val inc = map.incoming(n.id).firstOrNull()
        if (inc != null && map.incoming(n.id).size == 1) {
            t.addView(ui.small("Wie wahrscheinlich folgt das aus „${map.node(inc.a)?.text}“?"), ui.lpMatch(top = 10))
            val cur = likely.indexOfFirst { abs(it.second - inc.p) < 0.06 }
            t.addView(ui.chips(likely.map { it.first }, cur) { i -> inc.p = likely[i].second; saveMap(); renderEditor(); renderSummary() }, ui.lpMatch(top = 4))
        }
        t.addView(ui.small("Wie gut oder schlecht wäre das?"), ui.lpMatch(top = 10))
        val curI = impact.indexOfFirst { n.value != null && abs(it.second - (n.value ?: 0.0)) < 1 }
        t.addView(ui.chips(impact.map { it.first } + "Neutral", if (n.value == null) 4 else curI) { i ->
            n.value = if (i == 4) null else impact[i].second; saveMap(); renderEditor(); renderSummary()
        }, ui.lpMatch(top = 4))
        val aiStatus = ui.small("")
        t.addView(ui.pill("✨ KI: Folgen vorschlagen", true) { aiSuggest(n.id, aiStatus) }, ui.lpWrap(top = 14))
        t.addView(aiStatus, ui.lpMatch(top = 4))
        t.addView(ui.label("Neue Folge"), ui.lpMatch(top = 14))
        t.addView(addRow("Was folgt daraus?") { txt ->
            val c = map.add(txt); map.link(n.id, c.id, 0.4); mapSel = c.id; saveMap(); mapView?.selected = c.id; renderEditor(); renderSummary()
        }, ui.lpMatch(top = 6))
        t.addView(ui.rowOf(
            ui.pill("Knoten löschen") { map.remove(n.id); mapSel = null; mapView?.selected = null; saveMap(); renderEditor(); renderSummary() },
            ui.pill("Fertig", true) { mapSel = null; mapView?.selected = null; renderEditor() },
            ui.pill(if (advanced) "Weniger ▴" else "Mehr ▾") { advanced = !advanced; renderEditor() }
        ), ui.lpMatch(top = 14))
        if (!advanced) { editorBox.addView(t); return }
        t.addView(ui.label("Erweitert"), ui.lpMatch(top = 16))
        t.addView(stepper(if (root) "Wahrscheinlichkeit (Ausgang)" else "Eigene Grundchance", MapModel.pct(n.base),
            { n.base = ((n.base - 0.1).coerceAtLeast(0.0) * 100).toInt() / 100.0; saveMap(); renderEditor(); renderSummary() },
            { n.base = ((n.base + 0.1).coerceAtMost(1.0) * 100).toInt() / 100.0; saveMap(); renderEditor(); renderSummary() }), ui.lpMatch(top = 8))
        val v = n.value
        t.addView(stepper("Wert / Nutzen", v?.toInt()?.toString() ?: "–",
            { n.value = (v ?: 0.0) - 10; saveMap(); renderEditor(); renderSummary() },
            { n.value = (v ?: 0.0) + 10; saveMap(); renderEditor(); renderSummary() }), ui.lpMatch(top = 6))
        if (v != null) t.addView(ui.pill("Wert entfernen") { n.value = null; saveMap(); renderEditor(); renderSummary() }, ui.lpWrap(top = 6))

        // ausgehende Folgen
        val outs = map.outgoing(n.id)
        if (outs.isNotEmpty()) t.addView(ui.label("Folgen"), ui.lpMatch(top = 14))
        outs.forEach { e ->
            val b = map.node(e.b) ?: return@forEach
            t.addView(ui.body((if (e.neg) "verhindert → " else "führt zu → ") + b.text, 15f).apply { setTextColor(if (e.neg) 0xFFFF6B7A.toInt() else Holo.WHITE) }, ui.lpMatch(top = 8))
            val row = stepper("bedingt", MapModel.pct(e.p),
                { e.p = ((e.p - 0.1).coerceAtLeast(0.0) * 100).toInt() / 100.0; saveMap(); renderEditor(); renderSummary() },
                { e.p = ((e.p + 0.1).coerceAtMost(1.0) * 100).toInt() / 100.0; saveMap(); renderEditor(); renderSummary() })
            row.addView(xBtn { map.edges.remove(e); saveMap(); renderEditor(); renderSummary() })
            t.addView(row)
        }
        t.addView(addRow("Was wird dadurch verhindert?") { txt ->
            val c = map.add(txt, 0.5); map.link(n.id, c.id, 0.5, neg = true); saveMap(); renderEditor(); renderSummary()
        }, ui.lpMatch(top = 6))
        // mit vorhandenem Knoten verbinden
        val others = map.nodes.filter { it.id != n.id && map.outgoing(n.id).none { e -> e.b == it.id } }
        if (others.isNotEmpty()) {
            t.addView(ui.small("Mit vorhandenem Knoten verbinden:"), ui.lpMatch(top = 10))
            t.addView(ui.chips(others.map { it.text.take(24) }, -1) { i ->
                map.link(n.id, others[i].id, 0.5); saveMap(); renderEditor(); renderSummary()
            }, ui.lpMatch(top = 6))
        }
        editorBox.addView(t)
    }

    /** Gemini schlägt direkte Folgen mit Wahrscheinlichkeit und Wert vor und hängt sie an. */
    private fun aiSuggest(id: Int, status: TextView) {
        val n = map.node(id) ?: return
        if (Gemini.key(ctx).isBlank()) {
            // ohne Schlüssel: im echten Gemini-Overlay fragen
            val q = "Ereignis: „${n.text}“. Kontext – meine Implikations-Karte:\n${map.describe()}\n\n" +
                "Nenne 3 bis 4 realistische direkte Folgen dieses Ereignisses mit Wahrscheinlichkeit (in %) und ob sie gut oder schlecht wären."
            close(); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ AssistActivity.open(ctx, q) }, 300)
            return
        }
        status.text = "Gemini denkt nach …"
        val prompt = "Ereignis: „${n.text}“.\nKontext – meine bisherige Implikations-Karte:\n${map.describe()}\n\n" +
            "Nenne 3 bis 4 realistische, noch nicht enthaltene direkte Folgen dieses Ereignisses. " +
            "Antworte NUR als JSON-Array: [{\"folge\": \"max. 6 Wörter\", \"wahrscheinlichkeit\": 0.0-1.0, \"wert\": -100 bis 100}]"
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val r = runCatching {
                val txt = Gemini.generate(ctx, listOf(Gemini.Msg(true, prompt)), json = true)
                val start = txt.indexOf('['); val end = txt.lastIndexOf(']')
                JSONArray(if (start >= 0 && end > start) txt.substring(start, end + 1) else txt)
            }
            h.post {
                r.fold({ arr ->
                    var added = 0
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val t = o.optString("folge")
                        if (t.isBlank()) continue
                        val c = map.add(t, 0.0, o.optDouble("wert", 0.0).takeIf { !it.isNaN() && it != 0.0 })
                        map.link(id, c.id, o.optDouble("wahrscheinlichkeit", 0.5).coerceIn(0.0, 1.0))
                        added++
                    }
                    saveMap(); mapView?.replay(); renderEditor(); renderSummary()
                    if (::editorBox.isInitialized && added == 0) status.text = "Keine Vorschläge erhalten."
                }, { status.text = "⚠ ${it.message}" })
            }
        }.start()
    }

    private fun renderSummary() {
        if (!::summaryBox.isInitialized) return
        summaryBox.removeAllViews()
        map.compute()
        val t = ui.tile()
        t.addView(ui.label("Auswertung"))
        val leaves = map.nodes.filter { map.outgoing(it.id).isEmpty() && !map.isRoot(it.id) }.sortedByDescending { it.p }
        if (leaves.isEmpty()) { t.addView(ui.small("Füge Folgen hinzu, dann siehst du hier die wahrscheinlichsten Endpunkte."), ui.lpMatch(top = 6)) }
        leaves.take(5).forEach { n ->
            t.addView(ui.small("${n.text}  ·  ${MapModel.pct(n.p)}"), ui.lpMatch(top = 10))
            t.addView(meter((n.p * 100).toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(5)).apply { topMargin = ui.dp(4) })
        }
        map.expectedValue()?.let { ev ->
            t.addView(ui.small("Erwartungswert aller bewerteten Folgen"), ui.lpMatch(top = 14))
            t.addView(ui.big("%.1f".format(ev)))
        }
        // Hebel: welcher Pfeil bewegt den Erwartungswert am meisten?
        val base = map.expectedValue()
        if (base != null && map.edges.isNotEmpty()) {
            var best: Pair<MapModel.E, Double>? = null
            for (e in map.edges) {
                val old = e.p
                e.p = (old + 0.1).coerceAtMost(1.0); map.compute()
                val gain = (map.expectedValue() ?: 0.0) - base
                e.p = old
                if (best == null || abs(gain) > abs(best.second)) best = e to gain
            }
            map.compute()
            best?.let { (e, g) ->
                val a = map.node(e.a)?.text ?: ""; val b = map.node(e.b)?.text ?: ""
                t.addView(ui.small("Größter Hebel: „$a → $b“. +10 % an dieser Stelle ändern den Erwartungswert um ${"%+.1f".format(g)}."), ui.lpMatch(top = 10))
            }
        }
        summaryBox.addView(t)
    }

    /** Übernimmt Regeln aus dem Implikations-Planer als Karte (UND-Bedingungen werden zu einzelnen Pfeilen). */
    private fun importRules() {
        try {
            val rules = Implication.parseRules(Prefs.str(ctx, "impl_rules", ""))
            val facts = Implication.parseFacts(Prefs.str(ctx, "impl_facts", "")).map { it.key }.toSet()
            map.load(JSONObject())
            val ids = HashMap<String, Int>()
            fun id(l: Implication.Lit): Int = ids.getOrPut(l.key) { map.add(l.toString(), if (l.key in facts) 1.0 else 0.0).id }
            rules.forEach { r -> r.ifAll.forEach { a -> r.then.forEach { b -> map.link(id(a), id(b), 1.0 / r.ifAll.size.coerceAtLeast(1)) } } }
            mapSel = null; saveMap(); render()
        } catch (e: Exception) {
            editorBox.removeAllViews(); editorBox.addView(ui.tile().apply { addView(ui.small("Import nicht möglich: ${e.message}")) })
        }
    }

    // ---------- Gemini ----------

    private fun geminiTile() = ui.tile().apply {
        addView(ui.label("Mit Gemini weiterdenken"))
        addView(ui.small("Öffnet das Gemini-Overlay über dem Bildschirm. Deine Karte samt Analyse-Frage liegt in der Zwischenablage – im Gemini-Feld lange drücken → Einfügen."), ui.lpMatch(top = 4))
        addView(ui.rowOf(
            ui.pill("✦ Im Gemini-Overlay besprechen", true) {
                close(); android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ AssistActivity.open(ctx, geminiPrompt()) }, 300)
            },
            ui.pill("Teilen …") { sendToGemini() }
        ), ui.lpMatch(top = 10))
    }

    private fun geminiPrompt() = "Hier ist meine Implikations-Karte (Wahrscheinlichkeiten in %, Werte als Nutzen):\n\n" +
        map.describe() + "\n\nBitte prüfe: Welche wichtigen Folgen fehlen? Sind die Wahrscheinlichkeiten realistisch? " +
        "Welche Strategie maximiert den Erwartungswert und senkt das Risiko? Antworte knapp mit konkreten Vorschlägen."

    private fun sendToGemini() {
        val text = geminiPrompt()
        runCatching {
            ctx.getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("Implikations-Karte", text))
        }
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { ctx.startActivity(android.content.Intent(send).setPackage("com.google.android.apps.bard")) }.isSuccess ||
            runCatching {
                ctx.startActivity(android.content.Intent.createChooser(send, "Karte senden an …").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
        if (ok) close()
    }

    private fun openAssistant() {
        runCatching { ctx.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("Implikations-Karte", geminiPrompt())) }
        val ok = runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_ASSIST).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess ||
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VOICE_COMMAND).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) close()
    }

    // =====================================================================
    // Ziele → Strategien → Schritte
    // =====================================================================

    private fun loadGoals() = Store.arr(ctx, "goals").objects().toMutableList()
    private fun saveGoals(g: List<JSONObject>) = Store.put(ctx, "goals", g.toArr())

    private fun progress(goal: JSONObject): Pair<Int, Int> {
        var done = 0; var total = 0
        goal.optJSONArray("strategies")?.objects()?.forEach { st ->
            st.optJSONArray("steps")?.objects()?.forEach { total++; if (it.optBoolean("done")) done++ }
        }
        return done to total
    }

    private fun goals() {
        val t = ui.tile()
        t.addView(ui.label("Neues Ziel"))
        t.addView(addRow("z. B. Logik-Prüfung mit 1,0 bestehen") { title ->
            val g = loadGoals()
            g.add(0, JSONObject().put("id", System.currentTimeMillis()).put("title", title).put("why", "").put("due", "")
                .put("strategies", JSONArray()))
            saveGoals(g); render()
        }, ui.lpMatch(top = 8))
        box.addView(t)
        val list = loadGoals()
        if (list.isEmpty()) box.addView(ui.tile().apply { addView(ui.small("Noch keine Ziele. Lege oben eins an, dann teilst du es in Strategien und konkrete Schritte auf.")) })
        list.forEach { g ->
            val (done, total) = progress(g)
            val pct = if (total == 0) 0 else done * 100 / total
            box.addView(ui.tile().apply {
                val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(ui.title(g.optString("title")).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f) }, ui.weight())
                head.addView(ui.label("$pct %"))
                addView(head)
                val due = g.optString("due")
                addView(ui.small((if (due.isNotBlank()) "Bis $due · " else "") +
                    "${g.optJSONArray("strategies")?.length() ?: 0} Strategien · $done / $total Schritte"), ui.lpMatch(top = 4))
                addView(meter(pct), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(6)).apply { topMargin = ui.dp(10) })
                setOnClickListener { openGoal = g.optLong("id"); render() }
            })
        }
    }

    private fun goalDetail(id: Long) {
        val all = loadGoals()
        val g = all.firstOrNull { it.optLong("id") == id } ?: run { openGoal = null; render(); return }
        fun save() = saveGoals(all)

        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.pill("← Ziele", true) { openGoal = null; render() })
        head.addView(View(ctx), ui.weight())
        head.addView(ui.pill("Ziel löschen") { saveGoals(all.filter { it.optLong("id") != id }); openGoal = null; render() })
        box.addView(head, ui.lpMatch(bottom = 10))

        val t = ui.tile()
        t.addView(ui.label("Ziel"))
        val titleIn = ui.input("Ziel").apply { setText(g.optString("title")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f) }
        val whyIn = ui.input("Warum ist dir das wichtig?", lines = 2).apply { setText(g.optString("why")) }
        val dueIn = ui.input("Frist, z. B. 15.02.").apply { setText(g.optString("due")) }
        t.addView(titleIn, ui.lpMatch(top = 6))
        t.addView(ui.small("Warum"), ui.lpMatch(top = 10)); t.addView(whyIn, ui.lpMatch(top = 4))
        t.addView(ui.small("Frist"), ui.lpMatch(top = 10)); t.addView(dueIn, ui.lpMatch(top = 4))
        val (done, total) = progress(g)
        val pct = if (total == 0) 0 else done * 100 / total
        t.addView(ui.small("Fortschritt: $done von $total Schritten ($pct %)"), ui.lpMatch(top = 12))
        t.addView(meter(pct), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(6)).apply { topMargin = ui.dp(6) })
        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                g.put("title", titleIn.text.toString()); g.put("why", whyIn.text.toString()); g.put("due", dueIn.text.toString()); save()
            }
        }
        titleIn.addTextChangedListener(w); whyIn.addTextChangedListener(w); dueIn.addTextChangedListener(w)
        box.addView(t)

        val strategies = g.optJSONArray("strategies") ?: JSONArray().also { g.put("strategies", it) }
        strategies.objects().forEachIndexed { si, st ->
            val s = ui.tile()
            val sh = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            sh.addView(ui.label("Strategie ${si + 1}"), ui.weight())
            sh.addView(xBtn { strategies.remove(si); save(); render() })
            s.addView(sh)
            s.addView(ui.body(st.optString("title"), 17f))
            val steps = st.optJSONArray("steps") ?: JSONArray().also { st.put("steps", it) }
            steps.objects().forEachIndexed { k, step ->
                val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(44) }
                val cb = CheckBox(ctx).apply {
                    text = step.optString("t"); isChecked = step.optBoolean("done")
                    setTextColor(if (isChecked) Holo.ICE_DIM else Holo.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    buttonTintList = ColorStateList.valueOf(Holo.ICE)
                    if (isChecked) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    setOnCheckedChangeListener { _, c -> step.put("done", c); save(); box.post { render() } }
                }
                line.addView(cb, ui.weight())
                line.addView(xBtn { steps.remove(k); save(); render() })
                s.addView(line)
            }
            s.addView(addRow("Nächster konkreter Schritt …") { txt ->
                steps.put(JSONObject().put("t", txt).put("done", false)); save(); render()
            }, ui.lpMatch(top = 6))
            box.addView(s)
        }
        val ns = ui.tile()
        ns.addView(ui.label("Neue Strategie"))
        ns.addView(addRow("z. B. Jeden Tag eine Beweisaufgabe") { txt ->
            strategies.put(JSONObject().put("title", txt).put("steps", JSONArray())); save(); render()
        }, ui.lpMatch(top = 8))
        box.addView(ns)
    }

    // =====================================================================
    // Entscheidungsmatrix
    // =====================================================================

    private fun decision() {
        val d = Store.obj(ctx, "decision")
        if (!d.has("options")) {
            d.put("options", JSONArray().put("Option A").put("Option B"))
            d.put("criteria", JSONArray().put(JSONObject().put("n", "Nutzen").put("w", 3)).put(JSONObject().put("n", "Aufwand (niedrig = gut)").put("w", 2)))
            d.put("scores", JSONObject())
        }
        fun save() = Store.put(ctx, "decision", d)
        val options = d.getJSONArray("options")
        val criteria = d.getJSONArray("criteria")
        val scores = d.getJSONObject("scores")
        fun sc(o: Int, c: Int) = scores.optInt("${options.getString(o)}|${criteria.getJSONObject(c).getString("n")}", 5)
        fun setSc(o: Int, c: Int, v: Int) { scores.put("${options.getString(o)}|${criteria.getJSONObject(c).getString("n")}", v.coerceIn(0, 10)); save() }

        // Ergebnis zuerst
        val res = ui.tile()
        res.addView(ui.label("Ergebnis"))
        val totalW = (0 until criteria.length()).sumOf { criteria.getJSONObject(it).optInt("w", 1) }.coerceAtLeast(1)
        val ranked = (0 until options.length()).map { o ->
            val s = (0 until criteria.length()).sumOf { c -> sc(o, c) * criteria.getJSONObject(c).optInt("w", 1) }.toDouble() / totalW
            options.getString(o) to s
        }.sortedByDescending { it.second }
        if (ranked.isNotEmpty()) {
            res.addView(ui.title("Beste Wahl: ${ranked[0].first}"), ui.lpMatch(top = 6))
            ranked.forEach { (name, s) ->
                res.addView(ui.small("$name  ·  ${"%.1f".format(s)} / 10"), ui.lpMatch(top = 10))
                res.addView(meter((s * 10).toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(6)).apply { topMargin = ui.dp(4) })
            }
            if (ranked.size > 1 && ranked[0].second - ranked[1].second < 0.5)
                res.addView(ui.small("Knapp! Der Abstand ist kleiner als 0,5 – prüfe die Gewichte."), ui.lpMatch(top = 10))
        }
        box.addView(res)

        // Optionen
        val ot = ui.tile()
        ot.addView(ui.label("Optionen"))
        for (o in 0 until options.length()) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(ui.body(options.getString(o), 16f), ui.weight())
            row.addView(xBtn { options.remove(o); save(); render() })
            ot.addView(row)
        }
        ot.addView(addRow("Option hinzufügen") { options.put(it); save(); render() }, ui.lpMatch(top = 6))
        box.addView(ot)

        // Kriterien mit Gewicht
        val ct = ui.tile()
        ct.addView(ui.label("Kriterien · Gewicht 1–5"))
        for (c in 0 until criteria.length()) {
            val cr = criteria.getJSONObject(c)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(ui.body(cr.getString("n"), 15f), ui.weight())
            row.addView(ui.pill("−") { cr.put("w", (cr.optInt("w", 1) - 1).coerceAtLeast(1)); save(); render() })
            row.addView(ui.label("×${cr.optInt("w", 1)}").apply { gravity = Gravity.CENTER; minWidth = ui.dp(40) })
            row.addView(ui.pill("+") { cr.put("w", (cr.optInt("w", 1) + 1).coerceAtMost(5)); save(); render() })
            row.addView(xBtn { criteria.remove(c); save(); render() })
            ct.addView(row, ui.lpMatch(top = 6))
        }
        ct.addView(addRow("Kriterium hinzufügen") { criteria.put(JSONObject().put("n", it).put("w", 3)); save(); render() }, ui.lpMatch(top = 8))
        box.addView(ct)

        // Bewertung
        if (options.length() > 0 && criteria.length() > 0) {
            val bt = ui.tile()
            bt.addView(ui.label("Bewertung 0–10"))
            for (o in 0 until options.length()) {
                bt.addView(ui.title(options.getString(o)).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f) }, ui.lpMatch(top = 12))
                for (c in 0 until criteria.length()) {
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(ui.small(criteria.getJSONObject(c).getString("n")), ui.weight())
                    row.addView(ui.pill("−") { setSc(o, c, sc(o, c) - 1); render() })
                    row.addView(ui.big("${sc(o, c)}").apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); gravity = Gravity.CENTER; minWidth = ui.dp(44) })
                    row.addView(ui.pill("+") { setSc(o, c, sc(o, c) + 1); render() })
                    bt.addView(row, ui.lpMatch(top = 4))
                }
            }
            box.addView(bt)
        }
        box.addView(ui.pill("Matrix zurücksetzen") { Store.put(ctx, "decision", JSONObject()); render() }, ui.lpWrap(top = 4))
    }

    // =====================================================================
    // SWOT
    // =====================================================================

    private fun swot() {
        val d = Store.obj(ctx, "swot")
        box.addView(ui.tile().apply {
            addView(ui.label("Thema"))
            val topic = ui.input("Worum geht es? z. B. Wechsel ins Philosophie-Studium").apply { setText(d.optString("topic")) }
            addView(topic, ui.lpMatch(top = 6))
            topic.addTextChangedListener(saver { d.put("topic", topic.text.toString()); Store.put(ctx, "swot", d) })
        })
        val fields = listOf(
            "s" to "Stärken · intern, hilft", "w" to "Schwächen · intern, hindert",
            "o" to "Chancen · extern, hilft", "t" to "Risiken · extern, hindert"
        )
        fields.forEach { (k, label) ->
            val t = ui.tile()
            t.addView(ui.label(label))
            val e = ui.input("Ein Punkt pro Zeile …", lines = 4).apply { setText(d.optString(k)) }
            t.addView(e, ui.lpMatch(top = 6))
            e.addTextChangedListener(saver { d.put(k, e.text.toString()); Store.put(ctx, "swot", d) })
            box.addView(t)
        }
        box.addView(ui.small("Daraus Strategien ableiten: Stärken nutzen, um Chancen zu ergreifen (SO) · Schwächen abbauen, um Chancen zu nutzen (WO) · Stärken gegen Risiken einsetzen (ST) · Schwächen und Risiken zugleich meiden (WT)."))
    }

    private fun saver(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    // =====================================================================
    // Szenarien & Erwartungswert
    // =====================================================================

    private fun scenarios() {
        val d = Store.obj(ctx, "scen")
        if (!d.has("list")) {
            d.put("question", "")
            d.put("list", JSONArray()
                .put(JSONObject().put("n", "Bester Fall").put("p", 20).put("v", 100))
                .put(JSONObject().put("n", "Wahrscheinlicher Fall").put("p", 60).put("v", 40))
                .put(JSONObject().put("n", "Schlechtester Fall").put("p", 20).put("v", -50)))
        }
        fun save() = Store.put(ctx, "scen", d)
        val list = d.getJSONArray("list")
        val items = list.objects()

        val q = ui.tile()
        q.addView(ui.label("Entscheidung / Frage"))
        val qIn = ui.input("z. B. Soll ich das Projekt starten?").apply { setText(d.optString("question")) }
        q.addView(qIn, ui.lpMatch(top = 6))
        qIn.addTextChangedListener(saver { d.put("question", qIn.text.toString()); save() })
        box.addView(q)

        // Kennzahlen
        val sumP = items.sumOf { it.optInt("p") }
        val ev = items.sumOf { it.optInt("p") * it.optDouble("v") } / sumP.coerceAtLeast(1)
        val varc = items.sumOf { it.optInt("p") * Math.pow(it.optDouble("v") - ev, 2.0) } / sumP.coerceAtLeast(1)
        val r = ui.tile()
        r.addView(ui.label("Erwartungswert"))
        r.addView(ui.big("%.1f".format(ev)), ui.lpMatch(top = 4))
        val best = items.maxByOrNull { it.optDouble("v") }
        val worst = items.minByOrNull { it.optDouble("v") }
        val lossP = items.filter { it.optDouble("v") < 0 }.sumOf { it.optInt("p") }
        r.addView(ui.small(
            "Streuung (Standardabweichung): ${"%.1f".format(sqrt(varc))}\n" +
            (if (best != null) "Bester Ausgang: ${best.optString("n")} (${best.optDouble("v").fmt()})\n" else "") +
            (if (worst != null) "Schlechtester Ausgang: ${worst.optString("n")} (${worst.optDouble("v").fmt()})\n" else "") +
            "Verlustwahrscheinlichkeit: ${if (sumP > 0) lossP * 100 / sumP else 0} %" +
            (if (sumP != 100) "\n⚠ Wahrscheinlichkeiten ergeben $sumP % statt 100 % – es wird anteilig gerechnet." else "")
        ), ui.lpMatch(top = 6))
        box.addView(r)

        items.forEachIndexed { i, s ->
            val t = ui.tile()
            val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(ui.title(s.optString("n")).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }, ui.weight())
            head.addView(xBtn { list.remove(i); save(); render() })
            t.addView(head)
            // Wahrscheinlichkeit
            val pRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            pRow.addView(ui.small("Wahrscheinlichkeit"), ui.weight())
            pRow.addView(ui.pill("−5") { s.put("p", (s.optInt("p") - 5).coerceAtLeast(0)); save(); render() })
            pRow.addView(ui.label("${s.optInt("p")} %").apply { gravity = Gravity.CENTER; minWidth = ui.dp(56) })
            pRow.addView(ui.pill("+5") { s.put("p", (s.optInt("p") + 5).coerceAtMost(100)); save(); render() })
            t.addView(pRow, ui.lpMatch(top = 6))
            t.addView(meter(s.optInt("p")), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4)).apply { topMargin = ui.dp(6) })
            // Wert
            val vRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            vRow.addView(ui.small("Wert / Nutzen"), ui.weight())
            val vIn = ui.input("0").apply {
                setText(s.optDouble("v").fmt())
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
                gravity = Gravity.END
                setOnEditorActionListener { v, a, _ ->
                    if (a == EditorInfo.IME_ACTION_DONE) {
                        s.put("v", v.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0); save(); render(); true
                    } else false
                }
                setOnFocusChangeListener { v, has ->
                    if (!has) { s.put("v", (v as EditText).text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0); save() }
                }
            }
            vRow.addView(vIn, LinearLayout.LayoutParams(ui.dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
            t.addView(vRow, ui.lpMatch(top = 8))
            box.addView(t)
        }
        val add = ui.tile()
        add.addView(ui.label("Szenario hinzufügen"))
        add.addView(addRow("Name des Szenarios") { list.put(JSONObject().put("n", it).put("p", 10).put("v", 0)); save(); render() }, ui.lpMatch(top = 8))
        box.addView(add)
        box.addView(ui.small("Erwartungswert = Σ Wahrscheinlichkeit × Wert. Er zeigt, was im Schnitt herauskommt – die Streuung zeigt, wie riskant das ist."))
    }

    private fun Double.fmt() = if (this == Math.floor(this)) "%.0f".format(this) else "%.1f".format(this)

    // Ganz am Ende, damit alle Felder (z. B. die Karte) schon existieren
    init {
        col.addView(ui.chips(chipNames, chipOrder.indexOf(tool).coerceAtLeast(0)) {
            tool = chipOrder[it]; Prefs.setInt(ctx, "strat_tool2", tool); openGoal = null; render()
        }, ui.lpMatch(bottom = 6))
        helpText = ui.small("")
        col.addView(helpText, ui.lpMatch(bottom = 10))
        col.addView(box)
        render()
    }

}
