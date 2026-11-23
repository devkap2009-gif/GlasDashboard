package de.glas.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/** Schachbrett im Arktis-Look. Tippen: Figur wählen, dann Zielfeld. */
@SuppressLint("ViewConstructor")
class ChessView(ctx: Context, val game: Chess, val onChange: () -> Unit) : View(ctx) {
    var flip = false
    var promo = Chess.Q
    private var selected = -1
    private var targets: List<Chess.Move> = emptyList()

    private val light = 0xFF1E4468.toInt()
    private val dark = 0xFF0A1A33.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val coord = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Holo.ICE_DIM; typeface = Holo.label }
    private val symbols = mapOf(
        Chess.K to "♚︎", Chess.Q to "♛︎", Chess.R to "♜︎",
        Chess.B to "♝︎", Chess.N to "♞︎", Chess.P to "♟︎"
    )

    override fun onMeasure(w: Int, h: Int) {
        val size = MeasureSpec.getSize(w)
        setMeasuredDimension(size, size)
    }

    /** Bildschirmfeld (0..63 von oben links) → Brettfeld */
    private fun boardSq(screen: Int) = if (flip && !game.whiteToMove) 63 - screen else screen
    private fun screenSq(board: Int) = if (flip && !game.whiteToMove) 63 - board else board

    fun clearSelection() { selected = -1; targets = emptyList(); invalidate() }

    override fun onDraw(c: Canvas) {
        val s = width / 8f
        val check = game.inCheck()
        val kingSq = if (check) game.kingSquare(game.whiteToMove) else -1
        for (screen in 0..63) {
            val sq = boardSq(screen)
            val x = (screen % 8) * s; val y = (screen / 8) * s
            val isLight = (Chess.file(sq) + Chess.rank(sq)) % 2 == 1
            paint.style = Paint.Style.FILL
            paint.color = if (isLight) light else dark
            c.drawRect(x, y, x + s, y + s, paint)
            val lm = game.lastMove
            if (lm != null && (sq == lm.from || sq == lm.to)) { paint.color = 0x447FDBFF; c.drawRect(x, y, x + s, y + s, paint) }
            if (sq == selected) { paint.color = 0x887FDBFF.toInt(); c.drawRect(x, y, x + s, y + s, paint) }
            if (sq == kingSq) { paint.color = 0x99FF5A5A.toInt(); c.drawRect(x, y, x + s, y + s, paint) }
        }
        // Koordinaten
        coord.textSize = s * 0.2f
        for (i in 0..7) {
            val fileSq = boardSq(56 + i)
            c.drawText("${'a' + Chess.file(fileSq)}", i * s + s * 0.78f, 8 * s - s * 0.06f, coord)
            val rankSq = boardSq(i * 8)
            c.drawText("${Chess.rank(rankSq) + 1}", s * 0.05f, i * s + s * 0.24f, coord)
        }
        // Figuren
        glyph.textSize = s * 0.78f
        glyphStroke.textSize = s * 0.78f
        glyphStroke.strokeWidth = s * 0.06f
        val fm = glyph.fontMetrics
        for (sq in 0..63) {
            val p = game.board[sq]
            if (p == 0) continue
            val screen = screenSq(sq)
            val cx = (screen % 8) * s + s / 2
            val cy = (screen / 8) * s + s / 2 - (fm.ascent + fm.descent) / 2
            val sym = symbols[kotlin.math.abs(p)] ?: continue
            if (p > 0) {
                glyphStroke.color = 0xFF02060E.toInt(); glyph.color = Holo.WHITE
                glyph.setShadowLayer(s * 0.12f, 0f, 0f, Holo.ICE)
            } else {
                glyphStroke.color = Holo.ICE; glyph.color = 0xFF02060E.toInt()
                glyph.clearShadowLayer()
            }
            c.drawText(sym, cx, cy, glyphStroke)
            c.drawText(sym, cx, cy, glyph)
        }
        // Zielfelder
        paint.color = 0xCC7FDBFF.toInt()
        for (m in targets) {
            val screen = screenSq(m.to)
            val cx = (screen % 8) * s + s / 2; val cy = (screen / 8) * s + s / 2
            if (game.board[m.to] != 0) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.07f
                c.drawCircle(cx, cy, s * 0.44f, paint)
            } else {
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, cy, s * 0.14f, paint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)   // nicht die Seite wechseln
            return true
        }
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val s = width / 8f
        val col = (e.x / s).toInt().coerceIn(0, 7); val row = (e.y / s).toInt().coerceIn(0, 7)
        val sq = boardSq(row * 8 + col)
        val st = game.state()
        if (st == Chess.State.CHECKMATE || st == Chess.State.STALEMATE) return true
        val move = targets.firstOrNull { it.to == sq }
        if (selected >= 0 && move != null) {
            game.play(Chess.Move(move.from, move.to, if (move.promo != 0) promo else 0))
            clearSelection(); onChange(); return true
        }
        val p = game.board[sq]
        if (p != 0 && (p > 0) == game.whiteToMove) {
            selected = sq; targets = game.movesFrom(sq).distinctBy { it.to }
        } else clearSelection()
        invalidate()
        return true
    }
}

/** Spiele für zwei Personen an einem Handy: Schach, Würfel-Risiko, Höher/Tiefer mit Einsatz. */
class GamesPage(private val ui: Ui) : Page {
    override val key = "games"
    override val title = "Spiele"

    private val ctx = ui.ctx
    private val col = ui.column()
    override val view: View = ui.page(col)
    private val box = ui.column()
    private var game = Prefs.int(ctx, "game_sel", 0)
    private lateinit var p1: EditText
    private lateinit var p2: EditText

    private fun n1() = p1.text.toString().ifBlank { "Spieler 1" }
    private fun n2() = p2.text.toString().ifBlank { "Spieler 2" }

    // Schach-Zustand bleibt erhalten, solange der Dienst läuft
    private val chess = Chess()

    init {
        col.addView(ui.chips(listOf("Schach", "Würfel-Risiko", "Höher / Tiefer"), game) {
            game = it; Prefs.setInt(ctx, "game_sel", it); render()
        }, ui.lpMatch(bottom = 10))
        p1 = ui.input("Spieler 1").apply { setText(Prefs.str(ctx, "p1", Prefs.profile(ctx, "name").ifBlank { "" })) }
        p2 = ui.input("Spieler 2").apply { setText(Prefs.str(ctx, "p2", "")) }
        val names = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        names.addView(p1, ui.weight().apply { marginEnd = ui.dp(5) })
        names.addView(p2, ui.weight().apply { marginStart = ui.dp(5) })
        col.addView(names, ui.lpMatch(bottom = 10))
        col.addView(box)
        render()
    }

    override fun onHide() {
        Prefs.setStr(ctx, "p1", p1.text.toString()); Prefs.setStr(ctx, "p2", p2.text.toString())
    }

    private fun render() {
        box.removeAllViews()
        when (game) {
            0 -> chessUi()
            1 -> pigUi()
            else -> hiLoUi()
        }
    }

    // ---------------- Schach ----------------

    private fun chessUi() {
        val t = ui.tile()
        val status = ui.title("")
        val moves = ui.small("")
        val captured = ui.small("")
        lateinit var board: ChessView
        fun refresh() {
            val st = chess.state()
            val who = if (chess.whiteToMove) "Weiß (${n1()})" else "Schwarz (${n2()})"
            val other = if (chess.whiteToMove) "Schwarz (${n2()})" else "Weiß (${n1()})"
            status.text = when (st) {
                Chess.State.CHECKMATE -> "Schachmatt – $other gewinnt"
                Chess.State.STALEMATE -> "Patt – Remis"
                Chess.State.REPETITION -> "Dreifache Wiederholung – Remis möglich"
                Chess.State.FIFTY -> "50-Züge-Regel – Remis möglich"
                Chess.State.MATERIAL -> "Remis – zu wenig Material"
                Chess.State.CHECK -> "Schach! $who ist am Zug"
                Chess.State.PLAYING -> "$who ist am Zug"
            }
            val pairs = chess.notation.chunked(2).mapIndexed { i, m -> "${i + 1}. ${m.joinToString(" ")}" }
            moves.text = if (pairs.isEmpty()) "Noch keine Züge." else pairs.takeLast(8).joinToString("   ")
            val sym = mapOf(1 to "♟", 2 to "♞", 3 to "♝", 4 to "♜", 5 to "♛")
            captured.text = "Weiß schlug: " + chess.capturedByWhite.sorted().joinToString("") { sym[it] ?: "" }.ifEmpty { "–" } +
                "     Schwarz schlug: " + chess.capturedByBlack.sorted().joinToString("") { sym[it] ?: "" }.ifEmpty { "–" }
            board.invalidate()
        }
        board = ChessView(ctx, chess) { refresh() }
        board.flip = Prefs.int(ctx, "chess_flip", 0) == 1
        t.addView(status)
        t.addView(board, ui.lpMatch(top = 10))
        t.addView(captured, ui.lpMatch(top = 8))
        t.addView(moves, ui.lpMatch(top = 6))
        val ctrls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        ctrls.addView(ui.pill("↶ Zurück") { chess.undoMove(); board.clearSelection(); refresh() })
        ctrls.addView(ui.pill("Neue Partie") { chess.reset(); board.clearSelection(); refresh() }, ui.lpWrap(start = 8))
        ctrls.addView(ui.pill("Drehen", board.flip) {}.also { v ->
            v.setOnClickListener {
                board.flip = !board.flip; Prefs.setInt(ctx, "chess_flip", if (board.flip) 1 else 0)
                ui.setActive(v as TextView, board.flip); board.clearSelection(); refresh()
            }
        }, ui.lpWrap(start = 8))
        t.addView(ctrls, ui.lpMatch(top = 10))
        t.addView(ui.small("Umwandlung in:"), ui.lpMatch(top = 10))
        t.addView(ui.chips(listOf("Dame", "Turm", "Läufer", "Springer"), 0) {
            board.promo = intArrayOf(Chess.Q, Chess.R, Chess.B, Chess.N)[it]
        }, ui.lpMatch(top = 6))
        t.addView(ui.small("„Drehen“ dreht das Brett nach jedem Zug zum Spieler, der dran ist."), ui.lpMatch(top = 8))
        box.addView(t)
        refresh()
    }

    // ---------------- Würfel-Risiko (Pig) ----------------

    private var pigScore = IntArray(2)
    private var pigTurn = 0
    private var pigPlayer = 0
    private var pigLast = 0
    private var pigTarget = 100
    private var pigLog = "Würfle so oft du willst. Eine 1 löscht alle Punkte dieser Runde."

    private fun pigUi() {
        val t = ui.tile()
        val score = ui.body("", 17f)
        val die = TextView(ctx).apply {
            setTextColor(Holo.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 84f)
            gravity = Gravity.CENTER
            setShadowLayer(ui.dpf(16f), 0f, 0f, Holo.ICE)
        }
        val info = ui.title("")
        val log = ui.small("")
        val faces = listOf("–", "⚀", "⚁", "⚂", "⚃", "⚄", "⚅")
        fun name(i: Int) = if (i == 0) n1() else n2()
        fun refresh() {
            score.text = "${name(0)}: ${pigScore[0]}     ${name(1)}: ${pigScore[1]}     Ziel: $pigTarget"
            die.text = faces[pigLast]
            val won = pigScore.indexOfFirst { it >= pigTarget }
            info.text = if (won >= 0) "${name(won)} gewinnt!" else "${name(pigPlayer)} · Rundenpunkte: $pigTurn"
            log.text = pigLog
        }
        t.addView(score)
        t.addView(die, ui.lpMatch(top = 6))
        t.addView(info)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ui.pill("Würfeln", true) {
            if (pigScore.any { it >= pigTarget }) return@pill
            pigLast = Random.nextInt(1, 7)
            if (pigLast == 1) {
                pigLog = "${name(pigPlayer)} würfelt eine 1 – $pigTurn Punkte verloren."
                pigTurn = 0; pigPlayer = 1 - pigPlayer
            } else { pigTurn += pigLast; pigLog = "${name(pigPlayer)} riskiert weiter …" }
            refresh()
        })
        row.addView(ui.pill("Behalten") {
            if (pigScore.any { it >= pigTarget } || pigTurn == 0) return@pill
            pigScore[pigPlayer] += pigTurn
            pigLog = "${name(pigPlayer)} sichert $pigTurn Punkte."
            pigTurn = 0
            if (pigScore[pigPlayer] < pigTarget) pigPlayer = 1 - pigPlayer
            refresh()
        }, ui.lpWrap(start = 8))
        row.addView(ui.pill("Neu") {
            pigScore = IntArray(2); pigTurn = 0; pigPlayer = 0; pigLast = 0
            pigLog = "Neues Spiel. ${name(0)} beginnt."; refresh()
        }, ui.lpWrap(start = 8))
        t.addView(row, ui.lpMatch(top = 12))
        t.addView(ui.chips(listOf("Ziel 50", "Ziel 100"), if (pigTarget == 50) 0 else 1) { pigTarget = if (it == 0) 50 else 100; refresh() }, ui.lpMatch(top = 10))
        t.addView(log, ui.lpMatch(top = 10))
        box.addView(t)
        refresh()
    }

    // ---------------- Höher / Tiefer mit Einsatz ----------------

    private var chips = intArrayOf(100, 100)
    private var hlPlayer = 0
    private var deck = mutableListOf<Int>()
    private var card = -1
    private var bet = 10
    private var hlLog = "Setze Chips: Ist die nächste Karte höher oder tiefer? Richtig = der Gegner zahlt, falsch = du zahlst."

    private fun draw(): Int {
        if (deck.isEmpty()) deck = (0 until 52).shuffled().toMutableList()
        return deck.removeAt(deck.size - 1)
    }

    private fun cardText(c: Int): String {
        val ranks = listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "B", "D", "K", "A")
        val suits = listOf("♠", "♥", "♦", "♣")
        return ranks[c % 13] + suits[c / 13]
    }

    private fun hiLoUi() {
        if (card < 0) card = draw()
        val t = ui.tile()
        val score = ui.body("", 17f)
        val cardV = TextView(ctx).apply {
            setTextColor(Holo.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            gravity = Gravity.CENTER; typeface = Holo.light
            setShadowLayer(ui.dpf(16f), 0f, 0f, Holo.ICE)
            background = ui.cut(Holo.PANEL_DEEP, Holo.ICE, 16f, 1.5f)
            setPadding(0, ui.dp(18), 0, ui.dp(18))
        }
        val info = ui.title("")
        val log = ui.small("")
        fun name(i: Int) = if (i == 0) n1() else n2()
        fun maxBet() = minOf(chips[0], chips[1])
        fun refresh() {
            score.text = "${name(0)}: ${chips[0]} Chips     ${name(1)}: ${chips[1]} Chips"
            cardV.text = cardText(card)
            val broke = chips.indexOfFirst { it <= 0 }
            info.text = if (broke >= 0) "${name(1 - broke)} gewinnt alle Chips!" else "${name(hlPlayer)} setzt ${minOf(bet, maxBet())}"
            log.text = hlLog
        }
        fun guess(higher: Boolean) {
            if (chips.any { it <= 0 }) return
            val b = minOf(bet, maxBet())
            val next = draw()
            val a = card % 13; val n = next % 13
            val me = hlPlayer
            hlLog = when {
                n == a -> "${cardText(next)} – gleich hoch. Einsatz zurück."
                (n > a) == higher -> { chips[me] += b; chips[1 - me] -= b; "${cardText(next)} – richtig! ${name(me)} gewinnt $b." }
                else -> { chips[me] -= b; chips[1 - me] += b; "${cardText(next)} – falsch. ${name(me)} zahlt $b an ${name(1 - me)}." }
            }
            card = next
            hlPlayer = 1 - hlPlayer
            refresh()
        }
        t.addView(score)
        t.addView(cardV, ui.lpMatch(top = 10))
        t.addView(info, ui.lpMatch(top = 10))
        t.addView(ui.small("Einsatz"), ui.lpMatch(top = 8))
        val betOptions = listOf(5, 10, 25, 50, Int.MAX_VALUE)
        t.addView(ui.chips(listOf("5", "10", "25", "50", "Alles"), betOptions.indexOf(bet).coerceAtLeast(1)) {
            bet = betOptions[it]; refresh()
        }, ui.lpMatch(top = 6))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ui.pill("▲ Höher", true) { guess(true) })
        row.addView(ui.pill("▼ Tiefer", true) { guess(false) }, ui.lpWrap(start = 8))
        row.addView(ui.pill("Neu") {
            chips = intArrayOf(100, 100); hlPlayer = 0; deck.clear(); card = draw()
            hlLog = "Neues Spiel. ${name(0)} beginnt."; refresh()
        }, ui.lpWrap(start = 8))
        t.addView(row, ui.lpMatch(top = 12))
        t.addView(log, ui.lpMatch(top = 10))
        box.addView(t)
        refresh()
    }
}
