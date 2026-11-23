package de.glas.dashboard

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Implikations-Planer: Regeln + Fakten → Folgerungen, Plan zum Ziel, fehlende Voraussetzungen. */
class ImplPage(private val ui: Ui) : Page {
    override val key = "impl"
    override val title = "Implikationen"

    private val ctx = ui.ctx
    private val col = ui.column()
    override val view: View = ui.page(col)

    private lateinit var rules: EditText
    private lateinit var facts: EditText
    private lateinit var goal: EditText
    private lateinit var verdict: TextView
    private lateinit var out: TextView
    private lateinit var toggles: LinearLayout

    init { build() }

    private fun build() {
        val t = ui.tile()
        t.addView(ui.label("Regeln · eine pro Zeile"))
        t.addView(ui.small("Form: A & B -> C   ·   „nicht X“ oder !X verneint   ·   # Kommentar"), ui.lpMatch(top = 4))
        rules = ui.input("Ich lerne heute & ich schlafe genug -> Prüfung läuft gut", lines = 6, monoFont = true)
        rules.setText(Prefs.str(ctx, "impl_rules",
            "Ich lerne heute & ich schlafe genug -> Prüfung läuft gut\n" +
            "Ich gehe früh ins Bett -> ich schlafe genug\n" +
            "Prüfung läuft gut -> Stipendium\n" +
            "Ich gehe heute aus -> nicht ich gehe früh ins Bett"))
        t.addView(rules, ui.lpMatch(top = 8))
        t.addView(ui.symbolBar({ rules }, listOf("->", "&", "nicht ", "\n")), ui.lpMatch(top = 6))

        t.addView(ui.label("Fakten · durch Komma getrennt"), ui.lpMatch(top = 14))
        facts = ui.input("Ich lerne heute, ich gehe früh ins Bett")
        facts.setText(Prefs.str(ctx, "impl_facts", "Ich lerne heute, Ich gehe früh ins Bett"))
        t.addView(facts, ui.lpMatch(top = 8))

        t.addView(ui.label("Ziel (optional)"), ui.lpMatch(top = 14))
        goal = ui.input("Stipendium")
        goal.setText(Prefs.str(ctx, "impl_goal", "Stipendium"))
        t.addView(goal, ui.lpMatch(top = 8))

        t.addView(ui.pill("Durchdenken", true) { run() }, ui.lpWrap(top = 12))
        col.addView(t)

        val w = ui.tile()
        w.addView(ui.label("Was wäre, wenn … · Fakten an/aus"))
        toggles = ui.column()
        w.addView(toggles, ui.lpMatch(top = 6))
        col.addView(w)

        val r = ui.tile()
        r.addView(ui.label("Ergebnis"))
        verdict = ui.title("")
        out = ui.mono("")
        r.addView(verdict, ui.lpMatch(top = 6))
        r.addView(out, ui.lpMatch(top = 6))
        col.addView(r)

        col.addView(ui.small("Der Planer wendet Modus ponens an (A und A → B ergibt B) und bei einfachen Regeln auch Modus tollens (A → B und nicht B ergibt nicht A). Widersprüche werden markiert."))

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Prefs.setStr(ctx, "impl_rules", rules.text.toString())
                Prefs.setStr(ctx, "impl_facts", facts.text.toString())
                Prefs.setStr(ctx, "impl_goal", goal.text.toString())
            }
        }
        rules.addTextChangedListener(watcher); facts.addTextChangedListener(watcher); goal.addTextChangedListener(watcher)
        run()
    }

    /** Alle Grundannahmen (Bedingungen, die von keiner Regel erzeugt werden) als Schalter. */
    private fun renderToggles(rs: List<Implication.Rule>, active: List<Implication.Lit>) {
        toggles.removeAllViews()
        val produced = rs.flatMap { it.then }.map { it.key }.toSet()
        val base = LinkedHashMap<String, Implication.Lit>()
        rs.flatMap { it.ifAll }.filter { it.key !in produced }.forEach { base.putIfAbsent(it.key, it) }
        active.forEach { base.putIfAbsent(it.key, it) }
        if (base.isEmpty()) { toggles.addView(ui.small("Noch keine Bedingungen.")); return }
        val activeKeys = active.map { it.key }.toSet()
        base.values.forEach { l ->
            val on = l.key in activeKeys
            toggles.addView(ui.pill((if (on) "● " else "○ ") + l.toString(), on) {
                val cur = Implication.parseFacts(facts.text.toString()).toMutableList()
                if (on) cur.removeAll { it.key == l.key } else cur += l
                facts.setText(cur.joinToString(", "))
                run()
            }.apply { isAllCaps = false; text = (if (on) "● " else "○ ") + l.toString() }, ui.lpWrap(top = 6))
        }
    }

    private fun run() {
        try {
            val rs = Implication.parseRules(rules.text.toString())
            val fs = Implication.parseFacts(facts.text.toString())
            val res = Implication.run(rs, fs)
            renderToggles(rs, fs)
            val sb = StringBuilder()
            val derived = res.known.values.filter { it.second !is Implication.Why.Fact }
            sb.append("Folgerungen (${derived.size}):\n")
            if (derived.isEmpty()) sb.append("  – keine –\n")
            derived.forEach { (l, why) ->
                sb.append("  ").append(l).append(
                    when (why) {
                        is Implication.Why.Ponens -> "   ⇐ R${why.rule.index}"
                        is Implication.Why.Tollens -> "   ⇐ R${why.rule.index} rückwärts"
                        else -> ""
                    }
                ).append('\n')
            }
            if (res.contradictions.isNotEmpty()) {
                sb.append("\n⚠ Widerspruch: ").append(res.contradictions.joinToString(", ") { "$it und ¬$it" }).append('\n')
            }
            val g = Implication.lit(goal.text.toString())
            var head = if (res.contradictions.isEmpty()) "${derived.size} Folgerungen" else "Widerspruch gefunden"
            var good: Boolean? = if (res.contradictions.isEmpty()) null else false
            if (g != null) {
                sb.append('\n')
                if (res.has(g)) {
                    head = "Ziel erreichbar"; good = res.contradictions.isEmpty()
                    sb.append("Plan zu „$g“:\n")
                    Implication.plan(res, g).forEachIndexed { i, s -> sb.append("  ${i + 1}. $s\n") }
                } else if (res.has(g.negate())) {
                    head = "Ziel ausgeschlossen"; good = false
                    sb.append("Aus den Fakten folgt das Gegenteil: ¬$g\n")
                    Implication.plan(res, g.negate()).forEachIndexed { i, s -> sb.append("  ${i + 1}. $s\n") }
                } else {
                    head = "Ziel noch nicht erreichbar"; good = false
                    val miss = Implication.missing(res, g)
                    sb.append("Es fehlt mindestens: ").append(miss.joinToString(", ")).append('\n')
                }
            }
            sb.append("\nRegeln:\n")
            rs.forEach { sb.append("  R${it.index}: $it\n") }
            verdict.text = head
            verdict.setTextColor(when (good) { true -> Holo.ICE; false -> 0xFFFFB86B.toInt(); null -> Holo.WHITE })
            out.text = sb.toString().trimEnd()
        } catch (e: Exception) {
            verdict.text = "Eingabe prüfen"
            verdict.setTextColor(0xFFFF8A8A.toInt())
            out.text = e.message ?: e.toString()
        }
    }
}
