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
class StrategyPage(private val ui: Ui) : Page {
    override val key = "strategy"
    override val title = "Strategie"

    private val ctx = ui.ctx
    private val col = ui.column()
    override val view: View = ui.page(col)
    private var tool = Prefs.int(ctx, "strat_tool", 0)
    private val box = ui.column()
    private var openGoal: Long? = null

    init {
        col.addView(ui.chips(listOf("Ziele", "Entscheidung", "SWOT", "Szenarien"), tool) {
            tool = it; Prefs.setInt(ctx, "strat_tool", it); openGoal = null; render()
        }, ui.lpMatch(bottom = 10))
        col.addView(box)
        render()
    }

    override fun onShow() = render()

    private fun render() {
        box.removeAllViews()
        when (tool) {
            0 -> { val g = openGoal; if (g == null) goals() else goalDetail(g) }
            1 -> decision()
            2 -> swot()
            else -> scenarios()
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
}
