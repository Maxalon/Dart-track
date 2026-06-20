package com.dartrack.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Half-It: fixed rounds/targets, halving on a zero score (floor),
 * normal accumulation, and final winner = highest total.
 */
class HalfItStateTest {

    private fun game(vararg names: String) =
        HalfItState.new(names.map { GamePlayer(it) })

    @Test
    fun rounds_areTheNineFixedTargets() {
        assertEquals(9, HALF_IT_ROUNDS.size)
        assertEquals(
            listOf(
                HalfItTarget.Number(15),
                HalfItTarget.Number(16),
                HalfItTarget.AnyDouble,
                HalfItTarget.Number(17),
                HalfItTarget.Number(18),
                HalfItTarget.AnyTriple,
                HalfItTarget.Number(19),
                HalfItTarget.Number(20),
                HalfItTarget.Bullseye,
            ),
            HALF_IT_ROUNDS,
        )
    }

    @Test
    fun currentTarget_followsCurrentRound() {
        val s = game("A")
        assertEquals(HalfItTarget.Number(15), s.currentTarget())
        val s2 = s.applyTurn(10) // round advances after single player
        assertEquals(HalfItTarget.Number(16), s2.currentTarget())
    }

    @Test
    fun accumulation_normalAdds() {
        var s = game("A")
        s = s.applyTurn(15) // r0 -> 15
        s = s.applyTurn(16) // r1 -> 31
        assertEquals(31, s.perPlayer[0].total)
    }

    @Test
    fun halving_onZeroFloorsTotal() {
        var s = game("A")
        s = s.applyTurn(15) // -> 15
        s = s.applyTurn(0)  // halves: 15/2 = 7 (integer floor)
        assertEquals(7, s.perPlayer[0].total)
    }

    @Test
    fun halving_fromZeroStaysZero() {
        var s = game("A")
        s = s.applyTurn(0) // 0/2 = 0
        assertEquals(0, s.perPlayer[0].total)
    }

    @Test
    fun halving_recordsZeroPointsAndHalvedTotal() {
        var s = game("A")
        s = s.applyTurn(21) // -> 21
        s = s.applyTurn(0)  // -> 10
        val entry = s.perPlayer[0].rounds.last()
        assertEquals(0, entry.pointsScored)
        assertEquals(10, entry.totalAfter)
    }

    @Test
    fun turnRotation_advancesRoundOnlyAfterLastPlayer() {
        var s = game("A", "B")
        assertEquals(0, s.currentRound)
        s = s.applyTurn(10) // A, round stays 0, now B
        assertEquals(0, s.currentRound)
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(10) // B, wraps -> round advances to 1
        assertEquals(1, s.currentRound)
        assertEquals(0, s.currentPlayerIndex)
    }

    @Test
    fun winner_highestTotalAfterNineRounds() {
        var s = game("A", "B")
        // Play all 9 rounds. A scores 10 each round, B scores 5 each round.
        repeat(HALF_IT_ROUNDS.size) {
            s = s.applyTurn(10) // A
            s = s.applyTurn(5)  // B
        }
        assertTrue(s.isFinished, "game finishes after 9 rounds")
        assertEquals(90, s.perPlayer[0].total)
        assertEquals(45, s.perPlayer[1].total)
        assertEquals(listOf(0), s.winnerIndices, "highest total wins")
    }

    @Test
    fun winner_tieRecordsBothWinners() {
        var s = game("A", "B")
        repeat(HALF_IT_ROUNDS.size) {
            s = s.applyTurn(10)
            s = s.applyTurn(10)
        }
        assertTrue(s.isFinished)
        assertEquals(listOf(0, 1), s.winnerIndices, "ties record all top players")
    }

    @Test
    fun notFinished_beforeNineRounds() {
        var s = game("A", "B")
        repeat(HALF_IT_ROUNDS.size - 1) {
            s = s.applyTurn(10)
            s = s.applyTurn(10)
        }
        // 8 full rounds done, round index 8, not yet >= 9
        assertEquals(8, s.currentRound)
        assertTrue(!s.isFinished)
    }

    @Test
    fun applyTurn_noOpAfterFinished() {
        var s = game("A")
        repeat(HALF_IT_ROUNDS.size) { s = s.applyTurn(10) }
        assertTrue(s.isFinished)
        val again = s.applyTurn(20)
        assertEquals(s, again)
    }

    // ----------------------------------------------------------------- undoLast

    @Test
    fun undoLast_revertsWithinRound() {
        var s = game("A", "B")
        s = s.applyTurn(10) // A
        s = s.applyTurn(20) // B
        val undone = s.undoLast() // reverts B
        assertEquals(1, undone.currentPlayerIndex)
        assertEquals(0, undone.currentRound)
        assertTrue(undone.perPlayer[1].rounds.isEmpty())
        assertEquals(10, undone.perPlayer[0].total)
    }

    @Test
    fun undoLast_revertsAcrossRoundBoundary() {
        var s = game("A", "B")
        s = s.applyTurn(10) // A r0
        s = s.applyTurn(20) // B r0 -> advances to r1
        assertEquals(1, s.currentRound)
        s = s.applyTurn(5)  // A r1
        val undone = s.undoLast() // reverts A's r1 turn, back to r1, player A
        assertEquals(0, undone.currentPlayerIndex)
        assertEquals(1, undone.currentRound)
        assertEquals(10, undone.perPlayer[0].total)
    }

    @Test
    fun undoLast_atStart_isNoOp() {
        val s = game("A", "B")
        assertEquals(s, s.undoLast())
    }
}
