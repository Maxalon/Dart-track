package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Count-Up: a fixed-[ROUNDS] high-total practice game. Each turn the
 * active player enters a single 3-dart total in 0..180 that accumulates to their
 * score; turns are lockstep and a round completes once every player has thrown.
 * After all rounds the highest cumulative total wins (ties allowed). Covers
 * accumulation, turn/round rotation, finishing, winner selection incl. a tie,
 * undo across a round boundary, the empty-players guard, and JSON round-trip.
 */
class CountUpTest {

    private fun game(vararg names: String) =
        CountUpState.new(names.map { GamePlayer(it) })

    @Test
    fun constants_areExpected() {
        assertEquals(8, ROUNDS)
    }

    @Test
    fun startState_zeroTotals_firstPlayerOnRound1() {
        val s = game("A", "B")
        assertEquals(0, s.perPlayer[0].total)
        assertEquals(0, s.perPlayer[1].total)
        assertEquals(1, s.currentRoundNumber(0))
        assertEquals(0, s.currentRound)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(ROUNDS, s.rounds)
        assertFalse(s.isFinished)
    }

    @Test
    fun scoresAccumulateAcrossTurns() {
        // Single player: each turn advances the round and adds to the total.
        var s = game("A")
        s = s.applyTurn(60)
        assertEquals(60, s.perPlayer[0].total)
        assertEquals(1, s.currentRound)
        s = s.applyTurn(100)
        assertEquals(160, s.perPlayer[0].total)
        // After two rounds the player is now on round 3.
        assertEquals(3, s.currentRoundNumber(0))
        assertEquals(2, s.perPlayer[0].turns.size)
        assertEquals(6, s.perPlayer[0].darts, "darts = turns * 3")
    }

    @Test
    fun roundAdvancesOnlyAfterLastPlayer() {
        var s = game("A", "B")
        assertEquals(0, s.currentRound)
        s = s.applyTurn(40) // A, still round 0
        assertEquals(0, s.currentRound)
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(50) // B wraps -> round 1
        assertEquals(1, s.currentRound)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(2, s.currentRoundNumber(0))
        assertEquals(40, s.perPlayer[0].total)
        assertEquals(50, s.perPlayer[1].total)
    }

    @Test
    fun notFinished_beforeAllRounds() {
        var s = game("A", "B")
        repeat(ROUNDS - 1) {
            s = s.applyTurn(20)
            s = s.applyTurn(20)
        }
        assertEquals(ROUNDS - 1, s.currentRound)
        assertFalse(s.isFinished)
    }

    @Test
    fun gameEndsAfterAllRounds_highestTotalWins() {
        var s = game("A", "B")
        // A scores 60 every round, B scores 20 every round, all 8 rounds.
        repeat(ROUNDS) {
            s = s.applyTurn(60) // A
            s = s.applyTurn(20) // B
        }
        assertTrue(s.isFinished, "game finishes after the final round")
        assertEquals(ROUNDS, s.perPlayer[0].turns.size, "A threw 8 turns")
        assertEquals(ROUNDS * 3, s.perPlayer[0].darts, "darts = turns * 3")
        assertEquals(ROUNDS * 60, s.perPlayer[0].total) // 480
        assertEquals(ROUNDS * 20, s.perPlayer[1].total) // 160
        assertTrue(s.perPlayer[0].total > s.perPlayer[1].total)
        assertEquals(listOf(0), s.winnerIndices, "highest total wins")
    }

    @Test
    fun tieRecordsBothWinners() {
        var s = game("A", "B")
        repeat(ROUNDS) {
            s = s.applyTurn(45) // A
            s = s.applyTurn(45) // B
        }
        assertTrue(s.isFinished)
        assertEquals(s.perPlayer[0].total, s.perPlayer[1].total)
        assertEquals(listOf(0, 1), s.winnerIndices, "ties record all top players")
    }

    @Test
    fun applyTurn_noOpAfterFinished() {
        var s = game("A")
        repeat(ROUNDS) { s = s.applyTurn(50) }
        assertTrue(s.isFinished)
        assertEquals(s, s.applyTurn(60))
    }

    @Test
    fun applyTurn_rejectsAboveMax() {
        val s = game("A")
        var threw = false
        try {
            s.applyTurn(181)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "total > 180 must be rejected")
    }

    @Test
    fun applyTurn_rejectsNegative() {
        val s = game("A")
        var threw = false
        try {
            s.applyTurn(-1)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "negative total must be rejected")
    }

    @Test
    fun new_rejectsEmptyPlayers() {
        var threw = false
        try {
            CountUpState.new(emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "empty players must be rejected")
    }

    // ----------------------------------------------------------------- undo

    @Test
    fun undoLast_revertsWithinRound() {
        var s = game("A", "B")
        s = s.applyTurn(40) // A r1
        s = s.applyTurn(60) // B r1
        val undone = s.undoLast() // reverts B
        assertEquals(1, undone.currentPlayerIndex)
        assertEquals(0, undone.currentRound)
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        assertEquals(40, undone.perPlayer[0].total)
        assertEquals(0, undone.perPlayer[1].total)
    }

    @Test
    fun undoLast_revertsAcrossRoundBoundary() {
        var s = game("A", "B")
        s = s.applyTurn(40) // A r1
        s = s.applyTurn(50) // B r1 -> round 1
        assertEquals(1, s.currentRound)
        s = s.applyTurn(60) // A r2
        val undone = s.undoLast() // reverts A's r2 turn -> back to round 1, player A
        assertEquals(0, undone.currentPlayerIndex)
        assertEquals(1, undone.currentRound)
        assertEquals(1, undone.perPlayer[0].turns.size)
        assertEquals(40, undone.perPlayer[0].total) // only r1 turn remains
        assertEquals(50, undone.perPlayer[1].total)
    }

    @Test
    fun undoLast_unwindsFinish() {
        var s = game("A", "B")
        // Play out the whole game.
        repeat(ROUNDS) {
            s = s.applyTurn(60) // A
            s = s.applyTurn(20) // B
        }
        assertTrue(s.isFinished)
        assertEquals(listOf(0), s.winnerIndices)
        val undone = s.undoLast() // reverts B's final turn
        assertFalse(undone.isFinished, "finish undone")
        assertTrue(undone.winnerIndices.isEmpty())
        // Back to B's turn in the final round (A already threw it).
        assertEquals(1, undone.currentPlayerIndex)
        assertEquals(ROUNDS - 1, undone.currentRound)
        assertEquals(ROUNDS - 1, undone.perPlayer[1].turns.size, "B's last turn removed")
        assertEquals(ROUNDS * 60, undone.perPlayer[0].total, "A untouched")
    }

    @Test
    fun undoLast_atStart_isNoOp() {
        val s = game("A", "B")
        assertEquals(s, s.undoLast())
    }

    // ----------------------------------------------------------------- JSON

    @Test
    fun jsonRoundTrip_preservesState() {
        val json = GameJson.format
        var state = game("Alice", "Bob")
        state = state.applyTurn(57) // Alice
        state = state.applyTurn(100) // Bob -> round 1
        val record = GameRecord(
            id = "countup-1",
            mode = state.toMode(),
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        assertTrue(text.contains("\"type\":\"count_up\""), "expected serial name: $text")
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        assertEquals(GameMode.COUNT_UP, decoded.mode)
    }
}
