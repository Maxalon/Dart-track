package com.dartrack.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Cricket logic: mark accumulation, closing at 3 marks,
 * scoring while an opponent is still open, and win conditions.
 */
class CricketStateTest {

    private fun game(vararg names: String) =
        CricketState.new(names.map { GamePlayer(it) })

    // ------------------------------------------------------ marks / accumulation

    @Test
    fun marks_accumulateAcrossTurns() {
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 2)) // A
        s = s.applyTurn(mapOf(20 to 1)) // B
        s = s.applyTurn(mapOf(20 to 1)) // A -> 3 marks on 20
        val a = s.perPlayer[0]
        assertEquals(3, a.cumulativeMarks()[20])
        assertTrue(a.isClosed(20), "3 marks closes a target")
        assertFalse(a.isClosed(19))
    }

    @Test
    fun applyTurn_dropsZeroMarkEntries() {
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 2, 19 to 0))
        // zero-valued entries are filtered out of the stored turn
        assertEquals(mapOf(20 to 2), s.perPlayer[0].turns.single().marksByTarget)
    }

    @Test
    fun closing_requiresThreeMarks() {
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 2)) // A: 2 marks, not closed
        assertFalse(s.perPlayer[0].isClosed(20))
    }

    @Test
    fun turnRotation_advances() {
        var s = game("A", "B")
        assertEquals(0, s.currentPlayerIndex)
        s = s.applyTurn(mapOf(20 to 1))
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(mapOf(20 to 1))
        assertEquals(0, s.currentPlayerIndex)
    }

    // ------------------------------------------------------------------ scoring

    @Test
    fun scoring_pointsWhileOpponentOpen() {
        // A closes 20 (3 marks) then hits 2 more marks -> 2 * 20 = 40 points
        // while B has NOT closed 20.
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 3)) // A closes 20
        s = s.applyTurn(mapOf(19 to 1)) // B
        s = s.applyTurn(mapOf(20 to 2)) // A: 5 total -> 2 extra * 20 = 40
        assertEquals(40, s.scoreFor(0))
        assertEquals(0, s.scoreFor(1))
    }

    @Test
    fun scoring_noPointsOnceAllOpponentsClosed() {
        // Both close 20; A then hits extra marks on 20 -> no points because
        // the only opponent (B) has also closed 20.
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 3)) // A closes 20
        s = s.applyTurn(mapOf(20 to 3)) // B closes 20
        s = s.applyTurn(mapOf(20 to 3)) // A: 6 total, but B closed -> 0 points
        assertEquals(0, s.scoreFor(0))
    }

    @Test
    fun scoring_marksAtExactlyThreeScoreNothing() {
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 3)) // exactly closed, no extra
        s = s.applyTurn(mapOf(19 to 1))
        assertEquals(0, s.scoreFor(0))
    }

    @Test
    fun scoring_concreteTwoPlayerScenario() {
        // Deterministic full scenario with exact expected points.
        var s = game("A", "B")
        // Turn 1 A: triple 20 -> 3 marks, closes 20, 0 points
        s = s.applyTurn(mapOf(20 to 3))
        // Turn 1 B: triple 19 -> closes 19, 0 points
        s = s.applyTurn(mapOf(19 to 3))
        // Turn 2 A: triple 20 again -> 6 marks on 20, B has not closed 20 -> 3*20=60
        s = s.applyTurn(mapOf(20 to 3))
        // Turn 2 B: triple 19 again -> 6 marks on 19, A has not closed 19 -> 3*19=57
        s = s.applyTurn(mapOf(19 to 3))
        assertEquals(60, s.scoreFor(0))
        assertEquals(57, s.scoreFor(1))
        // Turn 3 A: single 19 x? Hit 20 once more -> 7 marks -> 4*20 = 80
        s = s.applyTurn(mapOf(20 to 1))
        assertEquals(80, s.scoreFor(0))
    }

    // ------------------------------------------------------------- win condition

    @Test
    fun win_whenAllClosedAndLeads() {
        // 2 players, A closes everything and scores; A should win once all closed.
        var s = game("A", "B")
        // Give A all 7 targets closed across turns, interleaving B no-ops.
        for (t in CRICKET_TARGETS) {
            s = s.applyTurn(mapOf(t to 3)) // A closes target t
            if (!s.isFinished) s = s.applyTurn(emptyMap()) // B passes
        }
        assertTrue(s.isFinished, "A closed all targets and leads -> win")
        assertEquals(listOf(0), s.winnerIndices)
    }

    @Test
    fun win_blockedWhenAllClosedButTrailing() {
        // A closes all targets but B has more points on a shared open target,
        // so A does NOT win until A leads or ties.
        var s = game("A", "B")
        // B first piles points on 15 (close + extra) while A leaves 15 open.
        s = s.applyTurn(mapOf(20 to 3)) // A close 20
        s = s.applyTurn(mapOf(15 to 3)) // B close 15
        s = s.applyTurn(mapOf(19 to 3)) // A close 19
        s = s.applyTurn(mapOf(15 to 3)) // B 6 marks on 15 -> 3*15=45 pts (A open on 15)
        assertEquals(45, s.scoreFor(1))
        s = s.applyTurn(mapOf(18 to 3)) // A close 18
        s = s.applyTurn(emptyMap())     // B pass
        s = s.applyTurn(mapOf(17 to 3)) // A close 17
        s = s.applyTurn(emptyMap())     // B pass
        s = s.applyTurn(mapOf(16 to 3)) // A close 16
        s = s.applyTurn(emptyMap())     // B pass
        s = s.applyTurn(mapOf(25 to 3)) // A close 25 -- now A closed all but 15
        s = s.applyTurn(emptyMap())     // B pass
        // A still has 15 open; close it but A has 0 pts vs B's 45 -> NOT a win
        s = s.applyTurn(mapOf(15 to 3)) // A closes 15, all closed, score 0 < 45
        assertFalse(s.isFinished, "all closed but trailing on points -> no win yet")
        assertEquals(0, s.scoreFor(0))
        assertEquals(45, s.scoreFor(1))
    }

    @Test
    fun win_tieCounts() {
        // Equal scores (both 0) with A closing all -> A wins on tie ("<=").
        var s = game("A", "B")
        for (t in CRICKET_TARGETS) {
            s = s.applyTurn(mapOf(t to 3)) // A closes target t (0 points, B open)
            // but scoring kicks in: A would score on extra marks only; 3 marks = closed, 0 pts
            if (!s.isFinished) s = s.applyTurn(emptyMap())
        }
        // A scored 0 (only ever 3 marks each), B scored 0 -> tie, A wins
        assertEquals(0, s.scoreFor(0))
        assertEquals(0, s.scoreFor(1))
        assertTrue(s.isFinished)
        assertEquals(listOf(0), s.winnerIndices)
    }

    @Test
    fun applyTurn_noOpAfterFinished() {
        var s = game("A", "B")
        for (t in CRICKET_TARGETS) {
            s = s.applyTurn(mapOf(t to 3))
            if (!s.isFinished) s = s.applyTurn(emptyMap())
        }
        assertTrue(s.isFinished)
        val again = s.applyTurn(mapOf(20 to 3))
        assertEquals(s, again)
    }

    @Test
    fun finish_keepsCurrentPlayerOnWinner() {
        var s = game("A", "B")
        for (t in CRICKET_TARGETS) {
            s = s.applyTurn(mapOf(t to 3))
            if (!s.isFinished) s = s.applyTurn(emptyMap())
        }
        assertEquals(0, s.currentPlayerIndex)
    }

    // ----------------------------------------------------------------- undoLast

    @Test
    fun undoLast_revertsLastTurn() {
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 2)) // A
        s = s.applyTurn(mapOf(19 to 2)) // B
        val undone = s.undoLast() // reverts B
        assertEquals(1, undone.currentPlayerIndex)
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        assertEquals(2, undone.perPlayer[0].cumulativeMarks()[20])
    }

    @Test
    fun undoLast_noTurns_isNoOp() {
        val s = game("A", "B")
        assertEquals(s, s.undoLast())
    }
}
