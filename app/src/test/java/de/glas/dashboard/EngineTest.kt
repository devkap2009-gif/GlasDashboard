package de.glas.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    // ---------- Aussagenlogik ----------
    @Test fun propTautology() {
        assertTrue(Prop.table(LogicParser.parse("p ∨ ¬p")).tautology)
        assertTrue(Prop.table(LogicParser.parse("p & ~p")).contradiction)
        assertTrue(Prop.table(LogicParser.parse("p -> q, p |- q")).tautology)
        assertFalse(Prop.table(LogicParser.parse("p -> q, q |- p")).tautology)
        assertTrue(Prop.table(LogicParser.parse("((p -> q) -> p) -> p")).tautology)   // Peirce
        assertEquals(8, Prop.table(LogicParser.parse("a ∧ b ∨ c")).total)
    }

    @Test fun precedence() {
        // ∧ bindet stärker als ∨, → ist rechtsassoziativ
        assertEquals(LogicParser.parse("(a ∧ b) ∨ c"), LogicParser.parse("a ∧ b ∨ c"))
        assertEquals(LogicParser.parse("a → (b → c)"), LogicParser.parse("a → b → c"))
    }

    // ---------- Modallogik ----------
    @Test fun modal() {
        val k = LogicParser.parse("□(p → q) → (□p → □q)")
        assertNull(Modal.findCountermodel(k, ModalSystem.K, 3))
        val t = LogicParser.parse("[]p -> p")
        assertNotNull(Modal.findCountermodel(t, ModalSystem.K, 2))
        assertNull(Modal.findCountermodel(t, ModalSystem.T, 3))
        val four = LogicParser.parse("□p → □□p")
        assertNotNull(Modal.findCountermodel(four, ModalSystem.T, 3))
        assertNull(Modal.findCountermodel(four, ModalSystem.S4, 3))
        val five = LogicParser.parse("◇p → □◇p")
        assertNotNull(Modal.findCountermodel(five, ModalSystem.S4, 3))
        assertNull(Modal.findCountermodel(five, ModalSystem.S5, 3))
        val d = LogicParser.parse("□p → ◇p")
        assertNotNull(Modal.findCountermodel(d, ModalSystem.K, 2))
        assertNull(Modal.findCountermodel(d, ModalSystem.D, 3))
    }

    @Test fun modalModel() {
        val m = Modal.parseModel(2, "1-2, 2-2", "p: 2")
        val f = LogicParser.parse("□p")
        assertTrue(m.eval(f, 0)); assertTrue(m.eval(f, 1))
        assertFalse(m.eval(LogicParser.parse("p"), 0))
    }

    // ---------- HOL ----------
    @Test fun hol() {
        assertTrue(HOL.check(LogicParser.parse("∀x P(x) → P(a)"), 3).valid)
        assertFalse(HOL.check(LogicParser.parse("∃x P(x) → P(a)"), 3).valid)
        assertTrue(HOL.check(LogicParser.parse("∀P (P(a) → P(b)) → a = b"), 3).valid)  // Leibniz
        assertTrue(HOL.check(LogicParser.parse("∀p (p ∨ ¬p)"), 2).valid)
        assertTrue(HOL.check(LogicParser.parse("∃x ∀y R(x,y) → ∀y ∃x R(x,y)"), 3).valid)
        assertFalse(HOL.check(LogicParser.parse("∀y ∃x R(x,y) → ∃x ∀y R(x,y)"), 2).valid)
    }

    // ---------- Implikations-Planer ----------
    @Test fun implication() {
        val rules = Implication.parseRules("Regen -> nass\nnass & schnell -> Unfall")
        val res = Implication.run(rules, Implication.parseFacts("Regen, schnell"))
        assertTrue(res.has(Implication.Lit("Unfall", false)))
        assertEquals(4, Implication.plan(res, Implication.Lit("Unfall", false)).size)
        val res2 = Implication.run(rules, Implication.parseFacts("Regen"))
        assertEquals(setOf(Implication.Lit("schnell", false)), Implication.missing(res2, Implication.Lit("Unfall", false)))
        val res3 = Implication.run(Implication.parseRules("A -> B"), Implication.parseFacts("nicht B"))
        assertTrue(res3.has(Implication.Lit("A", true)))
    }

    // ---------- Schach ----------
    private fun perft(c: Chess, d: Int): Long {
        if (d == 0) return 1
        var n = 0L
        for (m in c.legalMoves()) { c.play(m); n += perft(c, d - 1); c.undoMove() }
        return n
    }

    @Test fun chessPerft() {
        val c = Chess()
        assertEquals(20L, perft(c, 1))
        assertEquals(400L, perft(c, 2))
        assertEquals(8902L, perft(c, 3))
    }

    @Test fun foolsMate() {
        val c = Chess()
        assertTrue(c.play(Chess.Move(53, 45)))   // f3
        assertTrue(c.play(Chess.Move(12, 28)))   // e5
        assertTrue(c.play(Chess.Move(54, 38)))   // g4
        assertTrue(c.play(Chess.Move(3, 39)))    // Dh4#
        assertEquals(Chess.State.CHECKMATE, c.state())
        assertEquals("Dh4#", c.notation.last())
    }
}
