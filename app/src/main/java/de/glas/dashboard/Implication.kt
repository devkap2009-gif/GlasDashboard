package de.glas.dashboard

/**
 * Implikations-Planer: Regeln der Form „A & B -> C“ (auch „wenn … dann …“),
 * Fakten, Vorwärtsverkettung mit Modus ponens und Modus tollens,
 * Plan bis zum Ziel und fehlende Voraussetzungen.
 */
object Implication {

    data class Lit(val name: String, val neg: Boolean) {
        val key get() = (if (neg) "¬" else "") + name.lowercase()
        fun negate() = Lit(name, !neg)
        override fun toString() = (if (neg) "¬" else "") + name
    }

    data class Rule(val index: Int, val ifAll: List<Lit>, val then: List<Lit>) {
        override fun toString() = ifAll.joinToString(" ∧ ") + " → " + then.joinToString(" ∧ ")
    }

    sealed class Why {
        object Fact : Why()
        data class Ponens(val rule: Rule) : Why()
        data class Tollens(val rule: Rule, val from: Lit) : Why()
    }

    data class Result(
        val rules: List<Rule>,
        val known: LinkedHashMap<String, Pair<Lit, Why>>,
        val contradictions: List<Lit>
    ) {
        fun has(l: Lit) = known.containsKey(l.key)
    }

    private val arrow = Regex("\\s*(->|=>|→|\\bdann\\b)\\s*", RegexOption.IGNORE_CASE)
    private val andSep = Regex("\\s*(&|∧|,|\\bund\\b)\\s*", RegexOption.IGNORE_CASE)

    fun lit(raw: String): Lit? {
        var s = raw.trim().removePrefix("wenn ").removePrefix("Wenn ").trim()
        var neg = false
        while (true) {
            when {
                s.startsWith("!") || s.startsWith("¬") || s.startsWith("~") -> { neg = !neg; s = s.substring(1).trim() }
                s.lowercase().startsWith("nicht ") -> { neg = !neg; s = s.substring(6).trim() }
                else -> break
            }
        }
        return if (s.isEmpty()) null else Lit(s, neg)
    }

    fun parseRules(text: String): List<Rule> {
        val out = mutableListOf<Rule>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val parts = line.split(arrow, limit = 2)
            if (parts.size != 2) throw LogicError("Regel „$line“: Es fehlt ein Pfeil (->).")
            val a = parts[0].split(andSep).mapNotNull { lit(it) }
            val b = parts[1].split(andSep).mapNotNull { lit(it) }
            if (a.isEmpty() || b.isEmpty()) throw LogicError("Regel „$line“ ist unvollständig.")
            out += Rule(out.size + 1, a, b)
        }
        return out
    }

    fun parseFacts(text: String): List<Lit> =
        text.split('\n', ',', ';').mapNotNull { lit(it) }

    fun run(rules: List<Rule>, facts: List<Lit>): Result {
        val known = LinkedHashMap<String, Pair<Lit, Why>>()
        facts.forEach { known.putIfAbsent(it.key, it to Why.Fact) }
        var changed = true
        var guard = 0
        while (changed && guard++ < 1000) {
            changed = false
            for (r in rules) {
                // Modus ponens: alle Bedingungen erfüllt → Folgen gelten
                if (r.ifAll.all { known.containsKey(it.key) }) {
                    for (c in r.then) if (!known.containsKey(c.key)) { known[c.key] = c to Why.Ponens(r); changed = true }
                }
                // Modus tollens: A → B und ¬B ⇒ ¬A (nur bei genau einer Bedingung)
                if (r.ifAll.size == 1) {
                    val a = r.ifAll[0]
                    val failed = r.then.firstOrNull { known.containsKey(it.negate().key) }
                    if (failed != null && !known.containsKey(a.negate().key)) {
                        known[a.negate().key] = a.negate() to Why.Tollens(r, failed.negate()); changed = true
                    }
                }
            }
        }
        val contra = known.values.map { it.first }.filter { !it.neg && known.containsKey(it.negate().key) }
        return Result(rules, known, contra)
    }

    /** Schritte in sinnvoller Reihenfolge, die zum Ziel führen. */
    fun plan(res: Result, goal: Lit): List<String> {
        val out = mutableListOf<String>()
        val done = mutableSetOf<String>()
        fun visit(l: Lit) {
            if (!done.add(l.key)) return
            val (lit, why) = res.known[l.key] ?: return
            when (why) {
                Why.Fact -> out += "$lit  (gegeben)"
                is Why.Ponens -> {
                    why.rule.ifAll.forEach { visit(it) }
                    out += "$lit  (Regel ${why.rule.index}: ${why.rule.ifAll.joinToString(" ∧ ")} → …)"
                }
                is Why.Tollens -> {
                    visit(why.from)
                    out += "$lit  (Regel ${why.rule.index} rückwärts: weil ${why.from} gilt)"
                }
            }
        }
        visit(goal)
        return out
    }

    /** Welche zusätzlichen Fakten würden das Ziel erreichbar machen (kleinste gefundene Menge)? */
    fun missing(res: Result, goal: Lit): Set<Lit> {
        val memo = HashMap<String, Set<Lit>>()
        fun need(l: Lit, path: Set<String>): Set<Lit> {
            if (res.has(l)) return emptySet()
            memo[l.key]?.let { return it }
            if (l.key in path) return setOf(l)
            var best: Set<Lit> = setOf(l)
            for (r in res.rules) if (r.then.any { it.key == l.key }) {
                val u = r.ifAll.flatMap { need(it, path + l.key) }.toSet()
                if (u.size < best.size || (u.size == best.size && l !in u && best == setOf(l))) best = u
            }
            memo[l.key] = best
            return best
        }
        return need(goal, emptySet())
    }
}
