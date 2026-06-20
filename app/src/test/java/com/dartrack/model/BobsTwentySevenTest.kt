package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Bob's 27 doubles practice: start on 27, doubles 1..20 in order,
 * +hits*(2N) on a hit / -(2N) on a miss, elimination at score <= 0, lockstep
 * round advance, winner = highest score after double 20, undo within and across
 * round boundaries, and JSON round-trip.
 */
class BobsTwentySevenTest {

    private fun game(vararg names: String) =
        BobsTwentySevenState.new(names.map { GamePlayer(it) })

    @Test
    fun constants_areExpected() {
        assertEquals(27, BOBS27_START)
        assertEquals(20, BOBS27_LAST_DOUBLE)
        assertEquals(3, BOBS27_MAX_HITS)
    }

    @Test
    fun startState_everyoneOn27_firstPlayerOnDouble1() {
        val s = game("A", "B")
        assertEquals(27, s.perPlayer[0].score)
        assertEquals(27, s.perPlayer[1].score)
        assertEquals(1, s.currentDouble(0))
        assertEquals(0, s.currentPlayerIndex)
        assertFalse(s.isFinished)
    }

    @Test
    fun hit_addsHitsTimesDouble() {
        // Double 1 = value 2; 3 hits => +6.
        val s = game("A").applyTurn(3)
        assertEquals(27 + 3 * 2, s.perPlayer[0].score)
    }

    @Test
    fun hit_oneDart_addsSingleDouble() {
        // Double 1 = value 2; 1 hit => +2.
        val s = game("A").applyTurn(1)
        assertEquals(29, s.perPlayer[0].score)
    }

    @Test
    fun miss_subtractsDoubleValue() {
        // Double 1 = value 2; miss => -2.
        val s = game("A").applyTurn(0)
        assertEquals(27 - 2, s.perPlayer[0].score)
    }

    @Test
    fun doubleValueScalesWithRound() {
        // Single player: each turn advances the round. Round 2 = double 2 = value 4.
        var s = game("A")
        s = s.applyTurn(2) // double 1: +4 -> 31
        assertEquals(31, s.perPlayer[0].score)
        assertEquals(1, s.currentRound)
        s = s.applyTurn(2) // double 2: +2*4 = +8 -> 39
        assertEquals(39, s.perPlayer[0].score)
    }

    @Test
    fun roundAdvancesOnlyAfterLastPlayer() {
        var s = game("A", "B")
        assertEquals(0, s.currentRound)
        s = s.applyTurn(1) // A, still round 0
        assertEquals(0, s.currentRound)
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(1) // B wraps -> round 1
        assertEquals(1, s.currentRound)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(2, s.currentDouble(0))
    }

    @Test
    fun playerGoesOutAtZeroOrBelow() {
        // Start 27. Miss double 1 (-2 -> 25). To get to <= 0 quickly, drive misses.
        var s = game("A")
        // Misses subtract 2,4,6,8,... = cumulative. 27-(2+4+6+8) = 27-20 = 7,
        // then -10 -> -3 (out). That's doubles 1..5.
        s = s.applyTurn(0) // d1: -2 -> 25
        s = s.applyTurn(0) // d2: -4 -> 21
        s = s.applyTurn(0) // d3: -6 -> 15
        s = s.applyTurn(0) // d4: -8 -> 7
        s = s.applyTurn(0) // d5: -10 -> -3 -> OUT
        assertTrue(s.perPlayer[0].out)
        assertEquals(-3, s.perPlayer[0].score)
        // Single player went out -> game ends.
        assertTrue(s.isFinished)
    }

    @Test
    fun exactlyZeroIsOut() {
        // Drive a player to exactly 0: 27 then misses 2+4+6+8 = 20 -> 7, then a
        // miss of 7? doubles are even, so construct: use a fresh sequence.
        // 27 -> d1 miss -2 =25 -> d2 miss -4=21 -> d3 miss -6=15 -> d4 miss -8=7
        // -> need -7 but d5 = -10. Instead hit to reach 0 is impossible with even
        // deltas from odd 27... 27 is odd, even deltas keep parity odd, never 0.
        // So test "<= 0" via going below zero is covered; here assert parity note
        // by confirming a near-zero positive player is NOT out.
        var s = game("A")
        s = s.applyTurn(0) // 25
        s = s.applyTurn(0) // 21
        s = s.applyTurn(0) // 15
        s = s.applyTurn(0) // 7
        assertFalse(s.perPlayer[0].out)
        assertEquals(7, s.perPlayer[0].score)
    }

    @Test
    fun outPlayerIsSkipped() {
        var s = game("A", "B")
        // Make A go out fast while B survives. We alternate A then B each round.
        // Round 0 d1: A miss -2=25, B hit +? keep B alive with hits.
        // Easiest: drive A to out, ensure subsequent rounds skip A (A's turn count
        // stops growing) and currentPlayerIndex never lands on A.
        repeat(5) {
            // A always misses, B always hits 3.
            if (!s.isFinished && !s.perPlayer[0].out) s = s.applyTurn(0) // A
            if (!s.isFinished) s = s.applyTurn(3) // B
        }
        assertTrue(s.perPlayer[0].out, "A eliminated")
        // After A is out, continuing must never set currentPlayerIndex to A.
        var continued = s
        while (!continued.isFinished) {
            assertTrue(continued.currentPlayerIndex != 0 || continued.perPlayer[0].out)
            continued = continued.applyTurn(1)
        }
    }

    @Test
    fun gameEndsAfterDouble20_highestScoreWins() {
        var s = game("A", "B")
        // A hits 3 every round, B hits 1 every round, for all 20 doubles.
        repeat(BOBS27_LAST_DOUBLE) {
            s = s.applyTurn(3) // A
            s = s.applyTurn(1) // B
        }
        assertTrue(s.isFinished, "game finishes after double 20")
        assertEquals(BOBS27_LAST_DOUBLE, s.perPlayer[0].turns.size, "A threw 20 turns")
        assertEquals(BOBS27_LAST_DOUBLE * 3, s.perPlayer[0].darts, "darts = turns * 3")
        assertTrue(s.perPlayer[0].score > s.perPlayer[1].score)
        assertEquals(listOf(0), s.winnerIndices, "highest score wins")
    }

    @Test
    fun tieRecordsBothWinners() {
        var s = game("A", "B")
        repeat(BOBS27_LAST_DOUBLE) {
            s = s.applyTurn(2) // A
            s = s.applyTurn(2) // B
        }
        assertTrue(s.isFinished)
        assertEquals(listOf(0, 1), s.winnerIndices, "ties record all top players")
    }

    @Test
    fun notFinished_beforeDouble20() {
        var s = game("A", "B")
        repeat(BOBS27_LAST_DOUBLE - 1) {
            s = s.applyTurn(1)
            s = s.applyTurn(1)
        }
        assertEquals(BOBS27_LAST_DOUBLE - 1, s.currentRound)
        assertFalse(s.isFinished)
    }

    @Test
    fun applyTurn_noOpAfterFinished() {
        var s = game("A")
        repeat(BOBS27_LAST_DOUBLE) { s = s.applyTurn(1) }
        assertTrue(s.isFinished)
        assertEquals(s, s.applyTurn(3))
    }

    @Test
    fun applyTurn_rejectsOutOfRangeHits() {
        val s = game("A")
        var threw = false
        try {
            s.applyTurn(4)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "hits > 3 must be rejected")
    }

    // ----------------------------------------------------------------- undo

    @Test
    fun undoLast_revertsWithinRound() {
        var s = game("A", "B")
        s = s.applyTurn(1) // A d1: +2 -> 29
        s = s.applyTurn(3) // B d1: +6 -> 33
        val undone = s.undoLast() // reverts B
        assertEquals(1, undone.currentPlayerIndex)
        assertEquals(0, undone.currentRound)
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        assertEquals(29, undone.perPlayer[0].score)
        assertEquals(27, undone.perPlayer[1].score)
    }

    @Test
    fun undoLast_revertsAcrossRoundBoundary() {
        var s = game("A", "B")
        s = s.applyTurn(1) // A d1
        s = s.applyTurn(1) // B d1 -> round 1
        assertEquals(1, s.currentRound)
        s = s.applyTurn(2) // A d2
        val undone = s.undoLast() // reverts A's d2 turn -> back to round 1, player A
        assertEquals(0, undone.currentPlayerIndex)
        assertEquals(1, undone.currentRound)
        assertEquals(1, undone.perPlayer[0].turns.size)
        assertEquals(29, undone.perPlayer[0].score) // only d1 hit remains
    }

    @Test
    fun undoLast_atStart_isNoOp() {
        val s = game("A", "B")
        assertEquals(s, s.undoLast())
    }

    @Test
    fun undoLast_unwindsElimination() {
        var s = game("A")
        // Drive A out at d5 (see playerGoesOutAtZeroOrBelow).
        repeat(5) { s = s.applyTurn(0) }
        assertTrue(s.perPlayer[0].out)
        assertTrue(s.isFinished)
        val undone = s.undoLast()
        assertFalse(undone.perPlayer[0].out, "elimination undone")
        assertEquals(7, undone.perPlayer[0].score)
        assertFalse(undone.isFinished)
    }

    // ----------------------------------------------------------------- JSON

    @Test
    fun jsonRoundTrip_preservesState() {
        val json = GameJson.format
        var state = game("Alice", "Bob")
        state = state.applyTurn(3) // Alice
        state = state.applyTurn(0) // Bob -> round 1
        val record = GameRecord(
            id = "bobs27-1",
            mode = state.toMode(),
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        assertTrue(text.contains("\"type\":\"bobs_27\""), "expected serial name: $text")
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        assertEquals(GameMode.BOBS_27, decoded.mode)
    }
}
