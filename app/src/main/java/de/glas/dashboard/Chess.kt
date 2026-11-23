package de.glas.dashboard

/**
 * Schach-Regeln für zwei Spieler an einem Gerät: alle legalen Züge inkl. Rochade,
 * en passant, Umwandlung, Schach, Matt, Patt, dreifache Wiederholung (vereinfacht),
 * 50-Züge-Regel und ungenügendes Material.
 *
 * Feld 0 = a8 … 63 = h1. Figuren: 1 Bauer, 2 Springer, 3 Läufer, 4 Turm, 5 Dame, 6 König.
 * Positiv = Weiß, negativ = Schwarz.
 */
class Chess {
    companion object {
        const val P = 1; const val N = 2; const val B = 3; const val R = 4; const val Q = 5; const val K = 6
        fun file(sq: Int) = sq % 8
        fun rank(sq: Int) = 7 - sq / 8          // 0 = 1. Reihe
        fun name(sq: Int) = "${'a' + file(sq)}${rank(sq) + 1}"
        private val KN = intArrayOf(-17, -15, -10, -6, 6, 10, 15, 17)
        private val KG = intArrayOf(-9, -8, -7, -1, 1, 7, 8, 9)
        private val letters = mapOf(N to "S", B to "L", R to "T", Q to "D", K to "K")
    }

    data class Move(val from: Int, val to: Int, val promo: Int = 0, val castle: Boolean = false, val ep: Boolean = false)

    private data class Snap(
        val board: IntArray, val white: Boolean, val castling: BooleanArray, val ep: Int, val half: Int,
        val captured: List<Int>, val last: Move?, val history: List<String>
    )

    var board = IntArray(64); private set
    var whiteToMove = true; private set
    /** KQkq */
    private var castling = booleanArrayOf(true, true, true, true)
    private var ep = -1
    private var halfmove = 0
    val capturedByWhite = mutableListOf<Int>()
    val capturedByBlack = mutableListOf<Int>()
    var lastMove: Move? = null; private set
    val notation = mutableListOf<String>()
    private val undo = ArrayDeque<Snap>()
    private val positions = HashMap<String, Int>()

    init { reset() }

    fun reset() {
        val back = intArrayOf(R, N, B, Q, K, B, N, R)
        board = IntArray(64)
        for (f in 0..7) {
            board[f] = -back[f]; board[8 + f] = -P
            board[48 + f] = P; board[56 + f] = back[f]
        }
        whiteToMove = true
        castling = booleanArrayOf(true, true, true, true)
        ep = -1; halfmove = 0
        capturedByWhite.clear(); capturedByBlack.clear()
        lastMove = null; notation.clear(); undo.clear(); positions.clear()
        positions[key()] = 1
    }

    private fun key() = board.joinToString(",") + whiteToMove + castling.joinToString("") + ep

    private fun own(p: Int, white: Boolean) = if (white) p > 0 else p < 0
    private fun enemy(p: Int, white: Boolean) = if (white) p < 0 else p > 0

    /** Wird Feld sq von der Farbe byWhite angegriffen? */
    fun attacked(sq: Int, byWhite: Boolean, b: IntArray = board): Boolean {
        val f = file(sq); val r = rank(sq)
        val s = if (byWhite) 1 else -1
        // Bauern
        val pr = r - s
        for (df in intArrayOf(-1, 1)) {
            val ff = f + df
            if (ff in 0..7 && pr in 0..7 && b[(7 - pr) * 8 + ff] == s * P) return true
        }
        // Springer
        for (d in KN) {
            val t = sq + d
            if (t !in 0..63 || kotlin.math.abs(file(t) - f) > 2) continue
            if (b[t] == s * N) return true
        }
        // König
        for (d in KG) {
            val t = sq + d
            if (t !in 0..63 || kotlin.math.abs(file(t) - f) > 1) continue
            if (b[t] == s * K) return true
        }
        // Linien
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
            intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
        for ((i, d) in dirs.withIndex()) {
            var ff = f + d[0]; var rr = r + d[1]
            while (ff in 0..7 && rr in 0..7) {
                val p = b[(7 - rr) * 8 + ff]
                if (p != 0) {
                    val straight = i < 4
                    if (p == s * Q || (straight && p == s * R) || (!straight && p == s * B)) return true
                    break
                }
                ff += d[0]; rr += d[1]
            }
        }
        return false
    }

    fun kingSquare(white: Boolean, b: IntArray = board) = b.indexOfFirst { it == (if (white) K else -K) }

    fun inCheck(white: Boolean = whiteToMove) = kingSquare(white).let { it >= 0 && attacked(it, !white) }

    private fun pseudo(white: Boolean): List<Move> {
        val out = mutableListOf<Move>()
        val s = if (white) 1 else -1
        for (sq in 0..63) {
            val p = board[sq]
            if (!own(p, white)) continue
            val f = file(sq); val r = rank(sq)
            fun at(ff: Int, rr: Int) = (7 - rr) * 8 + ff
            when (kotlin.math.abs(p)) {
                P -> {
                    val r1 = r + s
                    val promoRank = if (white) 7 else 0
                    fun addPawn(to: Int, e: Boolean = false) {
                        if (rank(to) == promoRank) for (pp in intArrayOf(Q, R, B, N)) out += Move(sq, to, pp)
                        else out += Move(sq, to, ep = e)
                    }
                    if (r1 in 0..7 && board[at(f, r1)] == 0) {
                        addPawn(at(f, r1))
                        val start = if (white) 1 else 6
                        val r2 = r + 2 * s
                        if (r == start && board[at(f, r2)] == 0) out += Move(sq, at(f, r2))
                    }
                    for (df in intArrayOf(-1, 1)) {
                        val ff = f + df
                        if (ff !in 0..7 || r1 !in 0..7) continue
                        val t = at(ff, r1)
                        if (enemy(board[t], white)) addPawn(t)
                        else if (t == ep) addPawn(t, true)
                    }
                }
                N -> for (d in KN) {
                    val t = sq + d
                    if (t !in 0..63 || kotlin.math.abs(file(t) - f) > 2) continue
                    if (!own(board[t], white)) out += Move(sq, t)
                }
                K -> {
                    for (d in KG) {
                        val t = sq + d
                        if (t !in 0..63 || kotlin.math.abs(file(t) - f) > 1) continue
                        if (!own(board[t], white)) out += Move(sq, t)
                    }
                    // Rochade
                    val home = if (white) 60 else 4
                    if (sq == home && !attacked(home, !white)) {
                        val ks = if (white) castling[0] else castling[2]
                        val qs = if (white) castling[1] else castling[3]
                        if (ks && board[home + 1] == 0 && board[home + 2] == 0 && board[home + 3] == s * R &&
                            !attacked(home + 1, !white) && !attacked(home + 2, !white))
                            out += Move(sq, home + 2, castle = true)
                        if (qs && board[home - 1] == 0 && board[home - 2] == 0 && board[home - 3] == 0 && board[home - 4] == s * R &&
                            !attacked(home - 1, !white) && !attacked(home - 2, !white))
                            out += Move(sq, home - 2, castle = true)
                    }
                }
                else -> {
                    val dirs = when (kotlin.math.abs(p)) {
                        B -> listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
                        R -> listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
                        else -> listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1, 1 to 0, -1 to 0, 0 to 1, 0 to -1)
                    }
                    for ((df, dr) in dirs) {
                        var ff = f + df; var rr = r + dr
                        while (ff in 0..7 && rr in 0..7) {
                            val t = at(ff, rr)
                            if (own(board[t], white)) break
                            out += Move(sq, t)
                            if (board[t] != 0) break
                            ff += df; rr += dr
                        }
                    }
                }
            }
        }
        return out
    }

    /** Spielt den Zug auf einer Kopie und prüft, ob der eigene König sicher ist. */
    private fun legal(m: Move, white: Boolean): Boolean {
        val b = board.copyOf()
        apply(b, m)
        val k = kingSquare(white, b)
        return k >= 0 && !attacked(k, !white, b)
    }

    private fun apply(b: IntArray, m: Move) {
        val p = b[m.from]
        b[m.to] = if (m.promo != 0) (if (p > 0) m.promo else -m.promo) else p
        b[m.from] = 0
        if (m.ep) b[m.to + (if (p > 0) 8 else -8)] = 0
        if (m.castle) {
            if (m.to > m.from) { b[m.to - 1] = b[m.to + 1]; b[m.to + 1] = 0 }
            else { b[m.to + 1] = b[m.to - 2]; b[m.to - 2] = 0 }
        }
    }

    fun legalMoves(): List<Move> = pseudo(whiteToMove).filter { legal(it, whiteToMove) }

    fun movesFrom(sq: Int) = legalMoves().filter { it.from == sq }

    fun play(m: Move): Boolean {
        val legalM = legalMoves().firstOrNull { it.from == m.from && it.to == m.to && (it.promo == m.promo || (m.promo == 0 && it.promo == Q)) }
            ?: return false
        undo.addLast(Snap(board.copyOf(), whiteToMove, castling.copyOf(), ep, halfmove,
            capturedByWhite.toList() + listOf(-999) + capturedByBlack.toList(), lastMove, notation.toList()))
        val p = board[legalM.from]
        val target = if (legalM.ep) board[legalM.to + (if (p > 0) 8 else -8)] else board[legalM.to]
        val san = describe(legalM)
        if (target != 0) (if (p > 0) capturedByWhite else capturedByBlack) += kotlin.math.abs(target)
        apply(board, legalM)
        // Rochaderechte
        fun lose(sq: Int) {
            when (sq) {
                60 -> { castling[0] = false; castling[1] = false }
                4 -> { castling[2] = false; castling[3] = false }
                63 -> castling[0] = false
                56 -> castling[1] = false
                7 -> castling[2] = false
                0 -> castling[3] = false
            }
        }
        lose(legalM.from); lose(legalM.to)
        ep = if (kotlin.math.abs(p) == P && kotlin.math.abs(legalM.to - legalM.from) == 16) (legalM.from + legalM.to) / 2 else -1
        halfmove = if (kotlin.math.abs(p) == P || target != 0) 0 else halfmove + 1
        whiteToMove = !whiteToMove
        lastMove = legalM
        val k = key(); positions[k] = (positions[k] ?: 0) + 1
        val suffix = when { legalMoves().isEmpty() && inCheck() -> "#"; inCheck() -> "+"; else -> "" }
        notation += san + suffix
        return true
    }

    fun undoMove(): Boolean {
        val s = undo.removeLastOrNull() ?: return false
        positions[key()]?.let { if (it <= 1) positions.remove(key()) else positions[key()] = it - 1 }
        board = s.board; whiteToMove = s.white; castling = s.castling; ep = s.ep; halfmove = s.half
        val sep = s.captured.indexOf(-999)
        capturedByWhite.clear(); capturedByWhite += s.captured.subList(0, sep)
        capturedByBlack.clear(); capturedByBlack += s.captured.subList(sep + 1, s.captured.size)
        lastMove = s.last
        notation.clear(); notation += s.history
        return true
    }

    private fun describe(m: Move): String {
        val p = kotlin.math.abs(board[m.from])
        if (m.castle) return if (m.to > m.from) "O-O" else "O-O-O"
        val capture = board[m.to] != 0 || m.ep
        val pre = if (p == P) (if (capture) "${'a' + file(m.from)}" else "") else letters[p] ?: ""
        return pre + (if (capture) "x" else "") + name(m.to) + (if (m.promo != 0) "=" + (letters[m.promo] ?: "") else "")
    }

    enum class State { PLAYING, CHECK, CHECKMATE, STALEMATE, REPETITION, FIFTY, MATERIAL }

    fun state(): State {
        val moves = legalMoves()
        val check = inCheck()
        if (moves.isEmpty()) return if (check) State.CHECKMATE else State.STALEMATE
        if ((positions[key()] ?: 0) >= 3) return State.REPETITION
        if (halfmove >= 100) return State.FIFTY
        if (insufficient()) return State.MATERIAL
        return if (check) State.CHECK else State.PLAYING
    }

    private fun insufficient(): Boolean {
        val rest = board.filter { it != 0 && kotlin.math.abs(it) != K }.map { kotlin.math.abs(it) }
        return rest.isEmpty() || (rest.size == 1 && (rest[0] == N || rest[0] == B))
    }
}
