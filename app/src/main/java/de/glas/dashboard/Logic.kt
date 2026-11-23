package de.glas.dashboard

/**
 * Logik-Engine: ein Parser für Aussagen-, Modal- und Prädikatenlogik höherer Stufe
 * sowie Auswertung auf Wahrheitstafeln, Kripke-Modellen und endlichen Bereichen.
 *
 * Syntax (Symbole oder ASCII):
 *   ¬ ~ !      Negation           ∧ &         und
 *   ∨ |        oder               → ->  =>    Implikation
 *   ↔ <->      Äquivalenz         ⊤ ⊥         wahr / falsch
 *   □ []       notwendig          ◇ <>        möglich
 *   ∀x φ / ∀x. φ   Allquantor    ∃x φ        Existenzquantor
 *   P(x,y)  Prädikat, x = y Gleichheit,  ⊢ |- |=  Folgerung (Prämissen mit Komma)
 */
sealed class F {
    object T : F()
    object Fa : F()
    data class Atom(val name: String) : F()
    data class Pred(val name: String, val args: List<String>) : F()
    data class Eq(val a: String, val b: String) : F()
    data class Not(val f: F) : F()
    data class And(val a: F, val b: F) : F()
    data class Or(val a: F, val b: F) : F()
    data class Imp(val a: F, val b: F) : F()
    data class Iff(val a: F, val b: F) : F()
    data class Box(val f: F) : F()
    data class Dia(val f: F) : F()
    data class All(val v: String, val f: F) : F()
    data class Ex(val v: String, val f: F) : F()

    fun show(): String = when (this) {
        T -> "⊤"; Fa -> "⊥"
        is Atom -> name
        is Pred -> if (args.isEmpty()) name else "$name(${args.joinToString(",")})"
        is Eq -> "$a=$b"
        is Not -> "¬" + wrap(f)
        is And -> wrap(a) + " ∧ " + wrap(b)
        is Or -> wrap(a) + " ∨ " + wrap(b)
        is Imp -> wrap(a) + " → " + wrap(b)
        is Iff -> wrap(a) + " ↔ " + wrap(b)
        is Box -> "□" + wrap(f)
        is Dia -> "◇" + wrap(f)
        is All -> "∀$v " + wrap(f)
        is Ex -> "∃$v " + wrap(f)
    }

    private fun wrap(f: F) = when (f) {
        is And, is Or, is Imp, is Iff -> "(" + f.show() + ")"
        else -> f.show()
    }
}

class LogicError(msg: String) : Exception(msg)

/** Eine Eingabe: entweder eine Formel oder eine Folgerung „Prämissen ⊢ Schluss“. */
data class Sequent(val premises: List<F>, val conclusion: F) {
    /** Die Folgerung als eine Formel: (P1 ∧ … ∧ Pn) → C */
    fun asFormula(): F =
        if (premises.isEmpty()) conclusion
        else F.Imp(premises.reduce { a, b -> F.And(a, b) }, conclusion)
}

object LogicParser {

    private val symbols = listOf(
        "<->" to "↔", "->" to "→", "=>" to "→", "<>" to "◇", "[]" to "□",
        "|-" to "⊢", "|=" to "⊢", "&&" to "∧", "||" to "∨"
    )

    private fun tokenize(src: String): List<String> {
        var s = src
        symbols.forEach { (a, b) -> s = s.replace(a, b) }
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isLetterOrDigit() || c == '_' || c == '\'' -> {
                    val st = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '\'')) i++
                    out += s.substring(st, i)
                }
                else -> {
                    val t = when (c) {
                        '~', '!', '¬' -> "¬"
                        '&', '∧', '^' -> "∧"
                        '|', '∨', 'v' -> "∨"
                        else -> c.toString()
                    }
                    out += t; i++
                }
            }
        }
        return out
    }

    fun parseSequent(src: String): Sequent {
        if (src.isBlank()) throw LogicError("Bitte eine Formel eingeben.")
        val toks = tokenize(src)
        val turn = toks.indexOf("⊢")
        if (turn < 0) return Sequent(emptyList(), parseTokens(toks))
        val left = toks.subList(0, turn)
        val right = toks.subList(turn + 1, toks.size)
        // Prämissen an Kommas auf oberster Klammerebene trennen
        val prem = mutableListOf<List<String>>()
        var depth = 0
        var cur = mutableListOf<String>()
        for (t in left) {
            when (t) {
                "(" -> depth++
                ")" -> depth--
            }
            if (t == "," && depth == 0) { prem += cur; cur = mutableListOf() } else cur += t
        }
        if (cur.isNotEmpty()) prem += cur
        return Sequent(prem.filter { it.isNotEmpty() }.map { parseTokens(it) }, parseTokens(right))
    }

    fun parse(src: String): F = parseSequent(src).asFormula()

    private fun parseTokens(toks: List<String>): F {
        if (toks.isEmpty()) throw LogicError("Leere Formel.")
        val p = P(toks)
        val f = p.iff()
        if (p.i < toks.size) throw LogicError("Unerwartetes Zeichen „${toks[p.i]}“.")
        return f
    }

    private class P(val t: List<String>) {
        var i = 0
        fun peek() = t.getOrNull(i)
        fun eat(s: String): Boolean = if (peek() == s) { i++; true } else false
        fun expect(s: String) { if (!eat(s)) throw LogicError("„$s“ erwartet" + (peek()?.let { " statt „$it“" } ?: " am Ende")) }

        fun iff(): F {
            var a = imp()
            while (eat("↔")) a = F.Iff(a, imp())
            return a
        }

        fun imp(): F {
            val a = or()
            return if (eat("→")) F.Imp(a, imp()) else a   // rechtsassoziativ
        }

        fun or(): F {
            var a = and()
            while (eat("∨")) a = F.Or(a, and())
            return a
        }

        fun and(): F {
            var a = unary()
            while (eat("∧")) a = F.And(a, unary())
            return a
        }

        fun unary(): F {
            val tok = peek() ?: throw LogicError("Formel endet zu früh.")
            return when (tok) {
                "¬" -> { i++; F.Not(unary()) }
                "□" -> { i++; F.Box(unary()) }
                "◇" -> { i++; F.Dia(unary()) }
                "∀", "∃" -> {
                    i++
                    val v = peek()?.takeIf { it.first().isLetter() } ?: throw LogicError("Nach $tok fehlt eine Variable.")
                    i++
                    val body = if (eat(".")) iff() else unary()
                    if (tok == "∀") F.All(v, body) else F.Ex(v, body)
                }
                else -> atom()
            }
        }

        fun atom(): F {
            val tok = peek() ?: throw LogicError("Formel endet zu früh.")
            i++
            return when {
                tok == "(" -> { val f = iff(); expect(")"); f }
                tok == "⊤" || tok == "1" -> F.T
                tok == "⊥" || tok == "0" -> F.Fa
                tok.first().isLetter() -> {
                    when {
                        peek() == "(" -> {
                            i++
                            val args = mutableListOf<String>()
                            if (!eat(")")) {
                                do {
                                    val a = peek()?.takeIf { it.first().isLetterOrDigit() } ?: throw LogicError("Argument erwartet in $tok(…).")
                                    args += a; i++
                                } while (eat(","))
                                expect(")")
                            }
                            F.Pred(tok, args)
                        }
                        peek() == "=" -> {
                            i++
                            val b = peek()?.takeIf { it.first().isLetterOrDigit() } ?: throw LogicError("Nach = fehlt ein Term.")
                            i++
                            F.Eq(tok, b)
                        }
                        else -> F.Atom(tok)
                    }
                }
                else -> throw LogicError("Unerwartetes Zeichen „$tok“.")
            }
        }
    }
}

// ======================================================================
// Aussagenlogik
// ======================================================================

object Prop {
    fun atoms(f: F, out: MutableSet<String> = linkedSetOf()): Set<String> {
        when (f) {
            is F.Atom -> out += f.name
            is F.Pred -> throw LogicError("Prädikate wie ${f.show()} gibt es in der Aussagenlogik nicht – nutze den HOL-Modus.")
            is F.Eq -> throw LogicError("Gleichheit gibt es nur im HOL-Modus.")
            is F.Not -> atoms(f.f, out)
            is F.And -> { atoms(f.a, out); atoms(f.b, out) }
            is F.Or -> { atoms(f.a, out); atoms(f.b, out) }
            is F.Imp -> { atoms(f.a, out); atoms(f.b, out) }
            is F.Iff -> { atoms(f.a, out); atoms(f.b, out) }
            is F.Box, is F.Dia -> throw LogicError("□ und ◇ gehören in den Modallogik-Modus.")
            is F.All, is F.Ex -> throw LogicError("Quantoren gehören in den HOL-Modus.")
            F.T, F.Fa -> {}
        }
        return out
    }

    fun eval(f: F, v: Map<String, Boolean>): Boolean = when (f) {
        F.T -> true
        F.Fa -> false
        is F.Atom -> v[f.name] ?: false
        is F.Not -> !eval(f.f, v)
        is F.And -> eval(f.a, v) && eval(f.b, v)
        is F.Or -> eval(f.a, v) || eval(f.b, v)
        is F.Imp -> !eval(f.a, v) || eval(f.b, v)
        is F.Iff -> eval(f.a, v) == eval(f.b, v)
        else -> throw LogicError("Nicht aussagenlogisch: ${f.show()}")
    }

    data class Result(
        val atoms: List<String>,
        val rows: List<Pair<List<Boolean>, Boolean>>,
        val trueCount: Int
    ) {
        val total get() = rows.size
        val tautology get() = trueCount == total
        val contradiction get() = trueCount == 0
    }

    fun table(f: F): Result {
        val at = atoms(f).toList()
        if (at.size > 12) throw LogicError("Höchstens 12 Variablen (sonst über 4096 Zeilen).")
        val n = 1 shl at.size
        val rows = ArrayList<Pair<List<Boolean>, Boolean>>(n)
        var tc = 0
        for (m in 0 until n) {
            val vals = at.indices.map { k -> (m shr (at.size - 1 - k)) and 1 == 1 }
            val r = eval(f, at.zip(vals).toMap())
            if (r) tc++
            rows += vals to r
        }
        return Result(at, rows, tc)
    }

    /** Disjunktive Normalform aus den wahren Zeilen (kanonisch). */
    fun dnf(r: Result): String {
        if (r.tautology) return "⊤"
        if (r.contradiction) return "⊥"
        return r.rows.filter { it.second }.joinToString(" ∨ ") { (vals, _) ->
            "(" + r.atoms.zip(vals).joinToString(" ∧ ") { (a, b) -> if (b) a else "¬$a" } + ")"
        }
    }

    fun cnf(r: Result): String {
        if (r.tautology) return "⊤"
        if (r.contradiction) return "⊥"
        return r.rows.filter { !it.second }.joinToString(" ∧ ") { (vals, _) ->
            "(" + r.atoms.zip(vals).joinToString(" ∨ ") { (a, b) -> if (b) "¬$a" else a } + ")"
        }
    }
}

// ======================================================================
// Modallogik (Kripke-Semantik)
// ======================================================================

class Kripke(val n: Int, val rel: Array<BooleanArray>, val v: Map<String, BooleanArray>) {

    fun eval(f: F, w: Int): Boolean = when (f) {
        F.T -> true
        F.Fa -> false
        is F.Atom -> v[f.name]?.get(w) ?: false
        is F.Not -> !eval(f.f, w)
        is F.And -> eval(f.a, w) && eval(f.b, w)
        is F.Or -> eval(f.a, w) || eval(f.b, w)
        is F.Imp -> !eval(f.a, w) || eval(f.b, w)
        is F.Iff -> eval(f.a, w) == eval(f.b, w)
        is F.Box -> (0 until n).all { u -> !rel[w][u] || eval(f.f, u) }
        is F.Dia -> (0 until n).any { u -> rel[w][u] && eval(f.f, u) }
        else -> throw LogicError("In der Modallogik nicht erlaubt: ${f.show()}")
    }

    fun reflexive() = (0 until n).all { rel[it][it] }
    fun symmetric() = (0 until n).all { a -> (0 until n).all { b -> !rel[a][b] || rel[b][a] } }
    fun transitive() = (0 until n).all { a -> (0 until n).all { b -> (0 until n).all { c -> !(rel[a][b] && rel[b][c]) || rel[a][c] } } }
    fun serial() = (0 until n).all { a -> (0 until n).any { rel[a][it] } }
    fun euclidean() = (0 until n).all { a -> (0 until n).all { b -> (0 until n).all { c -> !(rel[a][b] && rel[a][c]) || rel[b][c] } } }

    fun describe(): String {
        val sb = StringBuilder()
        sb.append("Welten: ").append((1..n).joinToString(", ") { "w$it" }).append('\n')
        val edges = mutableListOf<String>()
        for (a in 0 until n) for (b in 0 until n) if (rel[a][b]) edges += "w${a + 1}→w${b + 1}"
        sb.append("Zugänglichkeit R: ").append(if (edges.isEmpty()) "keine" else edges.joinToString(", ")).append('\n')
        v.forEach { (p, arr) ->
            val ws = (0 until n).filter { arr[it] }.map { "w${it + 1}" }
            sb.append("$p wahr in: ").append(if (ws.isEmpty()) "–" else ws.joinToString(", ")).append('\n')
        }
        return sb.toString().trimEnd()
    }
}

enum class ModalSystem(val title: String, val info: String) {
    K("K", "alle Rahmen"),
    D("D", "seriell"),
    T("T", "reflexiv"),
    B("B", "reflexiv + symmetrisch"),
    S4("S4", "reflexiv + transitiv"),
    S5("S5", "Äquivalenzrelation");

    fun accepts(m: Kripke): Boolean = when (this) {
        K -> true
        D -> m.serial()
        T -> m.reflexive()
        B -> m.reflexive() && m.symmetric()
        S4 -> m.reflexive() && m.transitive()
        S5 -> m.reflexive() && m.symmetric() && m.transitive()
    }
}

object Modal {
    fun atoms(f: F, out: MutableSet<String> = linkedSetOf()): Set<String> {
        when (f) {
            is F.Atom -> out += f.name
            is F.Not -> atoms(f.f, out)
            is F.Box -> atoms(f.f, out)
            is F.Dia -> atoms(f.f, out)
            is F.And -> { atoms(f.a, out); atoms(f.b, out) }
            is F.Or -> { atoms(f.a, out); atoms(f.b, out) }
            is F.Imp -> { atoms(f.a, out); atoms(f.b, out) }
            is F.Iff -> { atoms(f.a, out); atoms(f.b, out) }
            F.T, F.Fa -> {}
            else -> throw LogicError("In der Modallogik nicht erlaubt: ${f.show()} – nutze Aussagenvariablen mit □ und ◇.")
        }
        return out
    }

    data class Counter(val model: Kripke, val world: Int)

    /**
     * Sucht ein Gegenmodell mit bis zu [maxWorlds] Welten. null = keins gefunden
     * (in den untersuchten Modellen gültig).
     */
    fun findCountermodel(f: F, sys: ModalSystem, maxWorlds: Int, cancel: () -> Boolean = { false }): Counter? {
        val at = atoms(f).toList()
        for (n in 1..maxWorlds) {
            val pairs = n * n
            val relCount = 1L shl pairs
            val valBits = at.size * n
            if (valBits > 20) throw LogicError("Zu viele Variablen für $n Welten – höchstens ${20 / n}.")
            val valCount = 1L shl valBits
            for (r in 0L until relCount) {
                if (cancel()) return null
                val rel = Array(n) { a -> BooleanArray(n) { b -> (r shr (a * n + b)) and 1L == 1L } }
                val frame = Kripke(n, rel, emptyMap())
                if (!sys.accepts(frame)) continue
                for (vb in 0L until valCount) {
                    val v = at.mapIndexed { k, p -> p to BooleanArray(n) { w -> (vb shr (k * n + w)) and 1L == 1L } }.toMap()
                    val m = Kripke(n, rel, v)
                    for (w in 0 until n) if (!m.eval(f, w)) return Counter(m, w)
                }
            }
        }
        return null
    }

    /**
     * Liest ein eigenes Modell:
     *   Welten: Anzahl,  R: „1-2, 2-3, 3-3“,  Belegung: „p: 1,2; q: 3“
     */
    fun parseModel(worlds: Int, relText: String, valText: String): Kripke {
        if (worlds !in 1..12) throw LogicError("1 bis 12 Welten.")
        val rel = Array(worlds) { BooleanArray(worlds) }
        relText.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { e ->
            val parts = e.split('-', '>', '→').map { it.trim().removePrefix("w") }.filter { it.isNotEmpty() }
            if (parts.size != 2) throw LogicError("Kante „$e“ – Format: 1-2")
            val a = parts[0].toIntOrNull() ?: throw LogicError("Welt „${parts[0]}“ ist keine Zahl.")
            val b = parts[1].toIntOrNull() ?: throw LogicError("Welt „${parts[1]}“ ist keine Zahl.")
            if (a !in 1..worlds || b !in 1..worlds) throw LogicError("Kante $a-$b: Welten gehen von 1 bis $worlds.")
            rel[a - 1][b - 1] = true
        }
        val v = linkedMapOf<String, BooleanArray>()
        valText.split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
            val idx = part.indexOf(':')
            if (idx < 0) throw LogicError("Belegung „$part“ – Format: p: 1,2")
            val name = part.substring(0, idx).trim()
            val arr = BooleanArray(worlds)
            part.substring(idx + 1).split(',', ' ').map { it.trim().removePrefix("w") }.filter { it.isNotEmpty() }.forEach {
                val w = it.toIntOrNull() ?: throw LogicError("Welt „$it“ ist keine Zahl.")
                if (w !in 1..worlds) throw LogicError("Welt $w gibt es nicht.")
                arr[w - 1] = true
            }
            v[name] = arr
        }
        return Kripke(worlds, rel, v)
    }
}

// ======================================================================
// Höherstufige Logik über endlichen Bereichen
// ======================================================================

/**
 * Wertet Formeln mit Quantoren über Individuen (∀x) UND über Prädikate (∀P) aus –
 * auf allen Strukturen mit 1 bis n Elementen. Kleingeschriebene freie Namen sind
 * Konstanten, Großgeschriebene sind Prädikate/Relationen.
 */
object HOL {

    private data class Sym(val arity: Int, val individual: Boolean)

    data class Counter(val size: Int, val consts: Map<String, Int>, val preds: Map<String, Set<List<Int>>>, val arity: Map<String, Int>) {
        fun describe(): String {
            val sb = StringBuilder("Bereich: {").append((0 until size).joinToString(", ") { "${it + 1}" }).append("}\n")
            consts.forEach { (c, e) -> sb.append("$c = ${e + 1}\n") }
            preds.forEach { (p, ext) ->
                val s = when {
                    (arity[p] ?: 1) == 0 -> if (ext.isNotEmpty()) "wahr" else "falsch"
                    else -> "{" + ext.sortedWith(compareBy({ it.getOrNull(0) }, { it.getOrNull(1) }))
                        .joinToString(", ") { t -> if (t.size == 1) "${t[0] + 1}" else "(" + t.joinToString(",") { "${it + 1}" } + ")" } + "}"
                }
                sb.append("$p = $s\n")
            }
            return sb.toString().trimEnd()
        }
    }

    /** Sammelt Symbole: Name → (Stelligkeit, Individuum?) für freie und gebundene Namen. */
    private fun collect(f: F, out: MutableMap<String, Sym>) {
        fun put(n: String, s: Sym) {
            val old = out[n]
            if (old != null && old != s) throw LogicError("„$n“ wird unterschiedlich verwendet (als ${if (old.individual) "Individuum" else "${old.arity}-stelliges Prädikat"} und als ${if (s.individual) "Individuum" else "${s.arity}-stelliges Prädikat"}).")
            out[n] = s
        }
        when (f) {
            is F.Atom -> put(f.name, Sym(0, false))
            is F.Pred -> { put(f.name, Sym(f.args.size, false)); f.args.forEach { put(it, Sym(0, true)) } }
            is F.Eq -> { put(f.a, Sym(0, true)); put(f.b, Sym(0, true)) }
            is F.Not -> collect(f.f, out)
            is F.And -> { collect(f.a, out); collect(f.b, out) }
            is F.Or -> { collect(f.a, out); collect(f.b, out) }
            is F.Imp -> { collect(f.a, out); collect(f.b, out) }
            is F.Iff -> { collect(f.a, out); collect(f.b, out) }
            is F.All -> collect(f.f, out)
            is F.Ex -> collect(f.f, out)
            is F.Box, is F.Dia -> throw LogicError("□/◇ gehören in den Modallogik-Modus.")
            F.T, F.Fa -> {}
        }
    }

    private fun bound(f: F, out: MutableSet<String> = mutableSetOf()): Set<String> {
        when (f) {
            is F.All -> { out += f.v; bound(f.f, out) }
            is F.Ex -> { out += f.v; bound(f.f, out) }
            is F.Not -> bound(f.f, out)
            is F.And -> { bound(f.a, out); bound(f.b, out) }
            is F.Or -> { bound(f.a, out); bound(f.b, out) }
            is F.Imp -> { bound(f.a, out); bound(f.b, out) }
            is F.Iff -> { bound(f.a, out); bound(f.b, out) }
            else -> {}
        }
        return out
    }

    private fun tuples(n: Int, arity: Int): List<List<Int>> {
        var acc = listOf(emptyList<Int>())
        repeat(arity) { acc = acc.flatMap { t -> (0 until n).map { t + it } } }
        return acc
    }

    /** Alle Extensionen eines k-stelligen Prädikats auf einem n-Bereich. */
    private fun extensions(n: Int, arity: Int): List<Set<List<Int>>> {
        val ts = tuples(n, arity)
        if (ts.size > 16) throw LogicError("Zu groß: ein $arity-stelliges Prädikat auf $n Elementen hat 2^${ts.size} Möglichkeiten.")
        return (0 until (1 shl ts.size)).map { m -> ts.filterIndexed { i, _ -> (m shr i) and 1 == 1 }.toSet() }
    }

    private class Env(val n: Int, val ind: Map<String, Int>, val pred: Map<String, Set<List<Int>>>)

    private fun eval(f: F, e: Env, syms: Map<String, Sym>): Boolean = when (f) {
        F.T -> true
        F.Fa -> false
        is F.Atom -> e.pred[f.name]?.isNotEmpty() ?: false
        is F.Pred -> {
            val tup = f.args.map { e.ind[it] ?: throw LogicError("Unbekannter Term „$it“.") }
            e.pred[f.name]?.contains(tup) ?: false
        }
        is F.Eq -> (e.ind[f.a] ?: -1) == (e.ind[f.b] ?: -2)
        is F.Not -> !eval(f.f, e, syms)
        is F.And -> eval(f.a, e, syms) && eval(f.b, e, syms)
        is F.Or -> eval(f.a, e, syms) || eval(f.b, e, syms)
        is F.Imp -> !eval(f.a, e, syms) || eval(f.b, e, syms)
        is F.Iff -> eval(f.a, e, syms) == eval(f.b, e, syms)
        is F.All -> domainOf(f.v, e, syms).all { eval(f.f, it, syms) }
        is F.Ex -> domainOf(f.v, e, syms).any { eval(f.f, it, syms) }
        else -> throw LogicError("Nicht erlaubt: ${f.show()}")
    }

    /** Alle Umgebungen, in denen die gebundene Variable v einen Wert hat. */
    private fun domainOf(v: String, e: Env, syms: Map<String, Sym>): Sequence<Env> {
        val s = syms[v] ?: Sym(0, true)
        return if (s.individual) {
            (0 until e.n).asSequence().map { d -> Env(e.n, e.ind + (v to d), e.pred) }
        } else {
            extensions(e.n, s.arity).asSequence().map { ext -> Env(e.n, e.ind, e.pred + (v to ext)) }
        }
    }

    data class Report(val valid: Boolean, val checked: Long, val counter: Counter?, val satisfiable: Boolean)

    fun check(f: F, maxSize: Int, cancel: () -> Boolean = { false }): Report {
        val syms = linkedMapOf<String, Sym>()
        collect(f, syms)
        val free = syms.keys - bound(f)
        val freeInd = free.filter { syms[it]!!.individual }
        val freePred = free.filter { !syms[it]!!.individual }
        var checked = 0L
        var counter: Counter? = null
        var sat = false
        for (n in 1..maxSize) {
            // Aufwand abschätzen
            var est = 1.0
            freeInd.forEach { est *= n }
            freePred.forEach { est *= Math.pow(2.0, Math.pow(n.toDouble(), syms[it]!!.arity.toDouble())) }
            if (est > 3_000_000) throw LogicError("Zu viele Möglichkeiten bei Bereichsgröße $n (≈${est.toLong()}). Wähle einen kleineren Bereich.")
            val predChoices = freePred.map { p -> extensions(n, syms[p]!!.arity) }
            val indChoices = freeInd.map { (0 until n).toList() }
            for (pc in cartesian(predChoices)) {
                for (ic in cartesian(indChoices)) {
                    if (cancel()) return Report(counter == null, checked, counter, sat)
                    val env = Env(n, freeInd.zip(ic).toMap(), freePred.zip(pc).toMap())
                    checked++
                    if (eval(f, env, syms)) sat = true
                    else if (counter == null) counter = Counter(n, env.ind, env.pred, syms.mapValues { it.value.arity })
                    if (counter != null && sat) return Report(false, checked, counter, true)
                }
            }
        }
        return Report(counter == null, checked, counter, sat)
    }

    private fun <T> cartesian(lists: List<List<T>>): Sequence<List<T>> = sequence {
        if (lists.isEmpty()) { yield(emptyList()); return@sequence }
        val idx = IntArray(lists.size)
        if (lists.any { it.isEmpty() }) return@sequence
        while (true) {
            yield(lists.indices.map { lists[it][idx[it]] })
            var k = lists.size - 1
            while (k >= 0) {
                idx[k]++
                if (idx[k] < lists[k].size) break
                idx[k] = 0; k--
            }
            if (k < 0) break
        }
    }
}
