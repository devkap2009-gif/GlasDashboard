package de.glas.dashboard

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Logik-Rechner: Aussagenlogik, Modallogik (Kripke), HOL über endlichen Bereichen. */
class LogicPage(private val ui: Ui) : Page {
    override val key = "logic"
    override val title = "Logik"

    private val ctx = ui.ctx
    private val main = Handler(Looper.getMainLooper())
    private var mode = Prefs.int(ctx, "logic_mode", 0)
    private var system = ModalSystem.values()[Prefs.int(ctx, "logic_sys", 0).coerceIn(0, 5)]
    private var worlds = Prefs.int(ctx, "logic_worlds", 3).coerceIn(1, 3)
    private var domain = Prefs.int(ctx, "logic_domain", 3).coerceIn(1, 4)
    @Volatile private var job = 0

    private val col = ui.column()
    private lateinit var input: EditText
    private lateinit var out: TextView
    private lateinit var verdict: TextView
    private lateinit var optionsBox: LinearLayout
    private lateinit var examplesBox: LinearLayout
    private lateinit var modelBox: LinearLayout
    private lateinit var mWorlds: EditText
    private lateinit var mRel: EditText
    private lateinit var mVal: EditText
    private var modelOpen = false

    override val view: View = ui.page(col)

    private val examples = listOf(
        listOf("((p → q) → p) → p", "p → q, ¬q ⊢ ¬p", "(p ∧ q) ↔ ¬(¬p ∨ ¬q)", "p ∨ q, ¬p ⊢ q", "(p → q) ↔ (¬q → ¬p)"),
        listOf("□(p → q) → (□p → □q)", "□p → p", "□p → □□p", "◇p → □◇p", "p → □◇p", "□p → ◇p"),
        listOf("∀x P(x) → P(a)", "∀P (P(a) → P(b)) → a = b", "∃x ∀y R(x,y) → ∀y ∃x R(x,y)", "∀y ∃x R(x,y) → ∃x ∀y R(x,y)", "∀p (p ∨ ¬p)", "∃P ∀x (P(x) ↔ ¬Q(x))")
    )

    init { build() }

    private fun build() {
        col.addView(ui.chips(listOf("Aussagenlogik", "Modallogik", "HOL"), mode) { i ->
            Prefs.setInt(ctx, "logic_mode", i); mode = i; input.setText(Prefs.str(ctx, "logic_in_$i", examples[i][0])); renderOptions()
            verdict.text = ""; out.text = ""
        }, ui.lpMatch(bottom = 10))

        val t = ui.tile()
        t.addView(ui.label("Formel"))
        input = ui.input("z. B. p → q, p ⊢ q", lines = 2, monoFont = true)
        input.setText(Prefs.str(ctx, "logic_in_$mode", examples[mode][0]))
        t.addView(input, ui.lpMatch(top = 8))
        t.addView(ui.symbolBar({ input }, listOf("¬", "∧", "∨", "→", "↔", "□", "◇", "∀", "∃", "⊢", "(", ")", ",", "=", "⊤", "⊥")), ui.lpMatch(top = 8))
        optionsBox = ui.column()
        t.addView(optionsBox, ui.lpMatch(top = 10))
        col.addView(t)

        val res = ui.tile()
        verdict = ui.title("")
        out = ui.mono("")
        res.addView(ui.label("Ergebnis"))
        res.addView(verdict, ui.lpMatch(top = 6))
        res.addView(out, ui.lpMatch(top = 6))
        col.addView(res)

        val ex = ui.tile()
        ex.addView(ui.label("Beispiele"))
        examplesBox = ui.column()
        ex.addView(examplesBox)
        col.addView(ex)

        col.addView(ui.small(
            "Syntax: ¬ ∧ ∨ → ↔ (oder ~ & | -> <->), □ ◇ (oder [] <>), ∀x ∃x mit optionalem Punkt („∀x. …“). " +
            "Folgerungen: Prämissen mit Komma, dann ⊢ (oder |-). HOL: kleine Namen sind Individuen/Konstanten, " +
            "große sind Prädikate; quantifizieren darfst du über beides."
        ))
        renderOptions()
    }

    private fun renderOptions() {
        optionsBox.removeAllViews()
        when (mode) {
            0 -> optionsBox.addView(ui.pill("Wahrheitstafel berechnen", true) { runProp() })
            1 -> {
                optionsBox.addView(ui.label("System"))
                optionsBox.addView(ui.chips(ModalSystem.values().map { it.title }, system.ordinal) {
                    system = ModalSystem.values()[it]; Prefs.setInt(ctx, "logic_sys", it)
                }, ui.lpMatch(top = 6, bottom = 8))
                optionsBox.addView(ui.label("Gegenmodelle suchen mit bis zu … Welten"))
                optionsBox.addView(ui.chips(listOf("1", "2", "3"), worlds - 1) {
                    worlds = it + 1; Prefs.setInt(ctx, "logic_worlds", worlds)
                }, ui.lpMatch(top = 6, bottom = 8))
                optionsBox.addView(ui.rowOf(
                    ui.pill("Gültigkeit prüfen", true) { runModal() },
                    ui.pill(if (modelOpen) "Eigenes Modell ▾" else "Eigenes Modell ▸") { modelOpen = !modelOpen; renderOptions() }
                ))
                modelBox = ui.column()
                if (modelOpen) {
                    mWorlds = ui.input("Anzahl Welten, z. B. 3").apply { setText(Prefs.str(ctx, "m_w", "3")) }
                    mRel = ui.input("R: 1-2, 2-3, 3-3", monoFont = true).apply { setText(Prefs.str(ctx, "m_r", "1-2, 2-3, 3-3")) }
                    mVal = ui.input("Belegung: p: 1,2; q: 3", monoFont = true).apply { setText(Prefs.str(ctx, "m_v", "p: 2,3; q: 3")) }
                    modelBox.addView(ui.small("Welten"), ui.lpMatch(top = 10)); modelBox.addView(mWorlds)
                    modelBox.addView(ui.small("Zugänglichkeit"), ui.lpMatch(top = 8)); modelBox.addView(mRel)
                    modelBox.addView(ui.small("Wo welche Variable wahr ist"), ui.lpMatch(top = 8)); modelBox.addView(mVal)
                    modelBox.addView(ui.pill("Im Modell auswerten", true) { runModel() }, ui.lpWrap(top = 10))
                }
                optionsBox.addView(modelBox)
            }
            else -> {
                optionsBox.addView(ui.label("Alle Strukturen mit bis zu … Elementen"))
                optionsBox.addView(ui.chips(listOf("1", "2", "3", "4"), domain - 1) {
                    domain = it + 1; Prefs.setInt(ctx, "logic_domain", domain)
                }, ui.lpMatch(top = 6, bottom = 8))
                optionsBox.addView(ui.pill("Prüfen", true) { runHol() })
            }
        }
        examplesBox.removeAllViews()
        examples[mode].forEach { e ->
            examplesBox.addView(ui.mono(e).apply {
                setTextColor(Holo.ICE)
                setTextIsSelectable(false)
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                setOnClickListener { input.setText(e) }
            })
        }
    }

    private fun formula(): String {
        val s = input.text.toString()
        Prefs.setStr(ctx, "logic_in_$mode", s)
        return s
    }

    private fun fail(e: Throwable) {
        verdict.text = "Eingabe prüfen"
        verdict.setTextColor(0xFFFF8A8A.toInt())
        out.text = e.message ?: e.toString()
    }

    private fun ok(head: String, good: Boolean?, text: String) {
        verdict.text = head
        verdict.setTextColor(when (good) { true -> Holo.ICE; false -> 0xFFFFB86B.toInt(); null -> Holo.WHITE })
        out.text = text
    }

    private fun runProp() {
        try {
            val seq = LogicParser.parseSequent(formula())
            val f = seq.asFormula()
            val r = Prop.table(f)
            val head = when {
                seq.premises.isNotEmpty() && r.tautology -> "Gültige Folgerung"
                seq.premises.isNotEmpty() -> "Keine gültige Folgerung"
                r.tautology -> "Tautologie"
                r.contradiction -> "Widerspruch"
                else -> "Erfüllbar, nicht allgemeingültig"
            }
            val sb = StringBuilder()
            sb.append("Formel: ").append(f.show()).append('\n')
            sb.append("Wahr in ${r.trueCount} von ${r.total} Belegungen\n")
            val counter = r.rows.firstOrNull { !it.second }
            if (counter != null && !r.contradiction) {
                sb.append("Gegenbeispiel: ").append(r.atoms.zip(counter.first).joinToString(", ") { (a, b) -> "$a=${if (b) "W" else "F"}" }).append('\n')
            }
            if (r.atoms.size <= 4) {
                sb.append("\nDNF: ").append(Prop.dnf(r)).append('\n')
                sb.append("KNF: ").append(Prop.cnf(r)).append('\n')
            }
            sb.append('\n')
            val w = r.atoms.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
            sb.append(r.atoms.joinToString(" ") { it.padEnd(w) }).append(" │ φ\n")
            sb.append("─".repeat((w + 1) * r.atoms.size)).append("┼──\n")
            r.rows.take(64).forEach { (vals, res) ->
                sb.append(vals.joinToString(" ") { (if (it) "W" else "F").padEnd(w) }).append(" │ ").append(if (res) "W" else "F").append('\n')
            }
            if (r.total > 64) sb.append("… ${r.total - 64} weitere Zeilen\n")
            ok(head, r.tautology || (seq.premises.isEmpty() && !r.contradiction && false), sb.toString().trimEnd())
            if (!r.tautology) verdict.setTextColor(if (r.contradiction || seq.premises.isNotEmpty()) 0xFFFFB86B.toInt() else Holo.WHITE)
        } catch (e: Exception) { fail(e) }
    }

    private fun background(work: () -> Unit) {
        val my = ++job
        verdict.text = "Rechne …"; verdict.setTextColor(Holo.TEXT_DIM); out.text = ""
        Thread {
            try { work() } catch (e: Exception) { main.post { if (my == job) fail(e) } }
        }.start()
    }

    private fun runModal() {
        val src = formula()
        val sys = system
        val n = worlds
        background {
            val my = job
            val seq = LogicParser.parseSequent(src)
            val f = seq.asFormula()
            val c = Modal.findCountermodel(f, sys, n) { my != job }
            main.post {
                if (my != job) return@post
                if (c == null) {
                    ok(if (seq.premises.isNotEmpty()) "Folgerung gilt in ${sys.title}" else "Gültig in ${sys.title}", true,
                        "${f.show()}\n\nKein Gegenmodell in allen ${sys.title}-Modellen (${sys.info}) mit bis zu $n Welten.\n" +
                        "Hinweis: Das ist eine vollständige Suche bis zu dieser Größe, aber kein Beweis für beliebig große Modelle.")
                } else {
                    ok("Nicht gültig in ${sys.title}", false,
                        "${f.show()}\n\nGegenmodell – falsch in Welt w${c.world + 1}:\n" + c.model.describe())
                }
            }
        }
    }

    private fun runModel() {
        try {
            Prefs.setStr(ctx, "m_w", mWorlds.text.toString()); Prefs.setStr(ctx, "m_r", mRel.text.toString()); Prefs.setStr(ctx, "m_v", mVal.text.toString())
            val n = mWorlds.text.toString().trim().toIntOrNull() ?: throw LogicError("Anzahl der Welten fehlt.")
            val m = Modal.parseModel(n, mRel.text.toString(), mVal.text.toString())
            val f = LogicParser.parse(formula())
            Modal.atoms(f)
            val sb = StringBuilder(f.show()).append("\n\n")
            var all = true
            for (w in 0 until n) {
                val v = m.eval(f, w); if (!v) all = false
                sb.append("w${w + 1}: ").append(if (v) "wahr" else "falsch").append('\n')
            }
            sb.append("\nRahmen: ")
            val props = listOfNotNull(
                if (m.reflexive()) "reflexiv" else null, if (m.symmetric()) "symmetrisch" else null,
                if (m.transitive()) "transitiv" else null, if (m.serial()) "seriell" else null,
                if (m.euclidean()) "euklidisch" else null)
            sb.append(if (props.isEmpty()) "keine besonderen Eigenschaften" else props.joinToString(", "))
            sb.append("\nPasst zu: ").append(ModalSystem.values().filter { it.accepts(m) }.joinToString(", ") { it.title })
            sb.append("\n\n").append(m.describe())
            ok(if (all) "Im Modell gültig" else "In manchen Welten falsch", all, sb.toString())
        } catch (e: Exception) { fail(e) }
    }

    private fun runHol() {
        val src = formula()
        val n = domain
        background {
            val my = job
            val seq = LogicParser.parseSequent(src)
            val f = seq.asFormula()
            val r = HOL.check(f, n) { my != job }
            main.post {
                if (my != job) return@post
                val sb = StringBuilder(f.show()).append("\n\nGeprüft: ${r.checked} Interpretationen auf Bereichen mit 1 bis $n Elementen.\n")
                if (r.valid) {
                    ok(if (seq.premises.isNotEmpty()) "Folgerung gilt" else "Gültig", true,
                        sb.append("Wahr in allen. (Für unendliche Bereiche ist das kein Beweis.)").toString())
                } else {
                    sb.append(if (r.satisfiable) "Erfüllbar, aber nicht allgemeingültig.\n" else "Unerfüllbar – in keiner Interpretation wahr.\n")
                    sb.append("\nGegenmodell:\n").append(r.counter?.describe() ?: "")
                    ok(if (r.satisfiable) "Nicht allgemeingültig" else "Widerspruch", false, sb.toString())
                }
            }
        }
    }

    override fun onHide() { job++ }
}
