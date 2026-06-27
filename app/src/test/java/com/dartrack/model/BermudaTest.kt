package com.dartrack.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for Bermuda (Treasure Island): 12 fixed rounds/targets, halving on a
 * zero score (floor), normal accumulation, and final winner = highest total.
 */
class BermudaTest {

    private fun game(vararg names: String) =
        BermudaState.new(names.map { GamePlayer(it) })

    // ------------------------------------------------------------------ new()

    @Test
    fun new_seedsPlayersRoundZeroNotFinished() {
        val s = game("A", "B")
        assertEquals(2, s.perPlayer.size)
        assertEquals("A", s.perPlayer[0].player.name)
        assertEquals("B", s.perPlayer[1].player.name)
        assertEquals(0, s.currentRound)
        assertEquals(0, s.currentPlayerIndex)
        assertTrue(!s.isFinished)
        assertEquals(0, s.perPlayer[0].total)
    }

    @Test
    fun new_rejectsEmptyPlayerList() {
        assertFailsWith<IllegalArgumentException> {
            BermudaState.new(emptyList())
        }
    }

    // ---------------------------------------------------------------- targets

    @Test
    fun rounds_areTheTwelveFixedTargets() {
        assertEquals(12, BERMUDA_ROUNDS.size)
        assertEquals(
            listOf(
                BermudaTarget.Number(12),
                BermudaTarget.Number(13),
                BermudaTarget.Number(14),
                BermudaTarget.AnyDouble,
                BermudaTarget.Number(15),
                BermudaTarget.Number(16),
                BermudaTarget.Number(17),
                BermudaTarget.AnyTriple,
                BermudaTarget.Number(18),
                BermudaTarget.Number(19),
                BermudaTarget.Number(20),
                BermudaTarget.Bullseye,
            ),
            BERMUDA_ROUNDS,
        )
    }

    @Test
    fun rounds_haveExpectedLabels() {
        assertEquals(
            listOf(
                "12", "13", "14", "Any Double", "15", "16", "17",
                "Any Triple", "18", "19", "20", "Bull",
            ),
            BERMUDA_ROUNDS.map { it.label },
        )
    }

    @Test
    fun currentTarget_followsCurrentRound() {
        val s = game("A")
        assertEquals(BermudaTarget.Number(12), s.currentTarget())
        val s2 = s.applyTurn(10) // round advances after single player
        assertEquals(BermudaTarget.Number(13), s2.currentTarget())
    }

    @Test
    fun currentTarget_specialRounds() {
        var s = game("A")
        // advance to round 3 (AnyDouble)
        repeat(3) { s = s.applyTurn(10) }
        assertEquals(BermudaTarget.AnyDouble, s.currentTarget())
        // advance to round 7 (AnyTriple)
        repeat(4) { s = s.applyTurn(10) }
        assertEquals(BermudaTarget.AnyTriple, s.currentTarget())
        // advance to round 11 (Bullseye)
        repeat(4) { s = s.applyTurn(10) }
        assertEquals(BermudaTarget.Bullseye, s.currentTarget())
    }

    @Test
    fun currentTarget_nullWhenFinished() {
        var s = game("A")
        repeat(BERMUDA_ROUNDS.size) { s = s.applyTurn(10) }
        assertTrue(s.isFinished)
        assertEquals(null, s.currentTarget())
    }

    // ------------------------------------------------------------- scoring

    @Test
    fun accumulation_normalAdds() {
        var s = game("A")
        s = s.applyTurn(12) // r0 -> 12
        s = s.applyTurn(13) // r1 -> 25
        assertEquals(25, s.perPlayer[0].total)
    }

    @Test
    fun halving_onZeroFloorsEvenTotal() {
        var s = game("A")
        s = s.applyTurn(46) // -> 46 (even)
        s = s.applyTurn(0)  // halves: 46/2 = 23
        assertEquals(23, s.perPlayer[0].total)
    }

    @Test
    fun halving_onZeroFloorsOddTotal() {
        var s = game("A")
        s = s.applyTurn(45) // -> 45 (odd)
        s = s.applyTurn(0)  // halves: 45/2 = 22 (floor)
        assertEquals(22, s.perPlayer[0].total)
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

    // -------------------------------------------------------- turn rotation

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

    // --------------------------------------------------------------- winner

    @Test
    fun winner_highestTotalAfterTwelveRounds() {
        var s = game("A", "B")
        repeat(BERMUDA_ROUNDS.size) {
            s = s.applyTurn(10) // A
            s = s.applyTurn(5)  // B
        }
        assertTrue(s.isFinished, "game finishes after 12 rounds")
        assertEquals(120, s.perPlayer[0].total)
        assertEquals(60, s.perPlayer[1].total)
        assertEquals(listOf(0), s.winnerIndices, "highest total wins")
    }

    @Test
    fun winner_tieRecordsBothWinners() {
        var s = game("A", "B")
        repeat(BERMUDA_ROUNDS.size) {
            s = s.applyTurn(10)
            s = s.applyTurn(10)
        }
        assertTrue(s.isFinished)
        assertEquals(listOf(0, 1), s.winnerIndices, "ties record all top players")
    }

    @Test
    fun notFinished_beforeTwelveRounds() {
        var s = game("A", "B")
        repeat(BERMUDA_ROUNDS.size - 1) {
            s = s.applyTurn(10)
            s = s.applyTurn(10)
        }
        assertEquals(11, s.currentRound)
        assertTrue(!s.isFinished)
    }

    @Test
    fun applyTurn_noOpAfterFinished() {
        var s = game("A")
        repeat(BERMUDA_ROUNDS.size) { s = s.applyTurn(10) }
        assertTrue(s.isFinished)
        val again = s.applyTurn(20)
        assertEquals(s, again)
    }

    @Test
    fun applyTurn_rejectsNegativePoints() {
        val s = game("A")
        assertFailsWith<IllegalArgumentException> {
            s.applyTurn(-1)
        }
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

    @Test
    fun undoLast_afterFinish_clearsWinners() {
        var s = game("A")
        repeat(BERMUDA_ROUNDS.size) { s = s.applyTurn(10) }
        assertTrue(s.isFinished)
        val undone = s.undoLast()
        assertTrue(!undone.isFinished)
        assertEquals(emptyList(), undone.winnerIndices)
        // back to the last round, player A, with the last entry removed
        assertEquals(11, undone.currentRound)
        assertEquals(0, undone.currentPlayerIndex)
        assertEquals(11, undone.perPlayer[0].rounds.size)
    }
}
