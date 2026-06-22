package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Baseball: 9 innings targeting numbers 1..9, per-turn singles/doubles/
 * triples entry, runs = s + 2d + 3t (NO inning multiplier, NO instant win),
 * lockstep inning advance, winner = highest total after inning 9 (ties allowed),
 * undo within / across inning boundaries, require non-empty, and JSON round-trip.
 */
class BaseballTest {

    private fun game(vararg names: String) =
        BaseballState.new(names.map { GamePlayer(it) })

    @Test
    fun constants_areExpected() {
        assertEquals(9, BASEBALL_INNINGS)
        assertEquals(3, BASEBALL_MAX_DARTS)
    }

    @Test
    fun startState_zeroTotals_firstPlayerOnNumber1() {
        val s = game("A", "B")
        assertEquals(0, s.perPlayer[0].total)
        assertEquals(0, s.perPlayer[1].total)
        assertEquals(1, s.currentTarget(0))
        assertEquals(0, s.currentPlayerIndex)
        assertFalse(s.isFinished)
    }

    @Test
    fun runsHaveNoInningMultiplier() {
        // Single player: each turn advances the inning. Two singles in inning 1
        // and again in inning 2 add the SAME runs (no multiplier): 2 each.
        var s = game("A")
        s = s.applyTurn(2, 0, 0)
        assertEquals(2, s.perPlayer[0].total)
        assertEquals(1, s.currentInning)
        s = s.applyTurn(2, 0, 0)
        assertEquals(4, s.perPlayer[0].total, "no inning multiplier -> +2 again")
        assertEquals(3, s.currentTarget(0))
    }

    @Test
    fun runsScoring_mixesSinglesDoublesTriples() {
        // Inning 1: 1 single + 1 double + 1 triple = 1 + 2 + 3 = 6 runs.
        val s = game("A").applyTurn(1, 1, 1)
        assertEquals(6, s.perPlayer[0].total)
        // No instant win even with s+d+t all present (Baseball, not Shanghai).
        assertFalse(s.isFinished, "Baseball has no instant win")
        assertEquals(1, s.currentInning)
    }

    @Test
    fun inningAdvancesOnlyAfterLastPlayer() {
        var s = game("A", "B")
        assertEquals(0, s.currentInning)
        s = s.applyTurn(1, 0, 0) // A, still inning 0
        assertEquals(0, s.currentInning)
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(1, 0, 0) // B wraps -> inning 1
        assertEquals(1, s.currentInning)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(2, s.currentTarget(0))
    }

    @Test
    fun notFinished_beforeInning9() {
        var s = game("A", "B")
        repeat(BASEBALL_INNINGS - 1) {
            s = s.applyTurn(1, 0, 0)
            s = s.applyTurn(1, 0, 0)
        }
        assertEquals(BASEBALL_INNINGS - 1, s.currentInning)
        assertFalse(s.isFinished)
    }

    @Test
    fun gameEndsAfterInning9_highestTotalWins() {
        var s = game("A", "B")
        // A hits 3 singles every inning, B hits 1 single every inning, all 9.
        repeat(BASEBALL_INNINGS) {
            s = s.applyTurn(3, 0, 0) // A: +3
            s = s.applyTurn(1, 0, 0) // B: +1
        }
        assertTrue(s.isFinished, "game finishes after inning 9")
        assertEquals(BASEBALL_INNINGS, s.perPlayer[0].innings.size, "A threw 9 innings")
        assertEquals(BASEBALL_INNINGS * 3, s.perPlayer[0].darts, "darts = turns * 3")
        // A total = 9 * 3 = 27; B total = 9 * 1 = 9.
        assertEquals(27, s.perPlayer[0].total)
        assertEquals(9, s.perPlayer[1].total)
        assertEquals(listOf(0), s.winnerIndices, "highest total wins")
    }

    @Test
    fun tieRecordsBothWinners() {
        var s = game("A", "B")
        repeat(BASEBALL_INNINGS) {
            s = s.applyTurn(2, 0, 0) // A
            s = s.applyTurn(2, 0, 0) // B
        }
        assertTrue(s.isFinished)
        assertEquals(listOf(0, 1), s.winnerIndices, "ties record all top players")
        assertEquals(s.perPlayer[0].total, s.perPlayer[1].total)
    }

    @Test
    fun applyTurn_noOpAfterFinished() {
        var s = game("A")
        repeat(BASEBALL_INNINGS) { s = s.applyTurn(1, 0, 0) }
        assertTrue(s.isFinished)
        assertEquals(s, s.applyTurn(3, 0, 0))
    }

    @Test
    fun applyTurn_rejectsTooManyDarts() {
        val s = game("A")
        var threw = false
        try {
            s.applyTurn(2, 2, 0) // 4 darts
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "s + d + t > 3 must be rejected")
    }

    @Test
    fun applyTurn_rejectsNegative() {
        val s = game("A")
        var threw = false
        try {
            s.applyTurn(-1, 0, 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "negative dart counts must be rejected")
    }

    @Test
    fun new_rejectsEmptyPlayers() {
        var threw = false
        try {
            BaseballState.new(emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "an empty player list must be rejected")
    }

    // ----------------------------------------------------------------- undo

    @Test
    fun undoLast_revertsWithinInning() {
        var s = game("A", "B")
        s = s.applyTurn(1, 0, 0) // A inn1: +1
        s = s.applyTurn(3, 0, 0) // B inn1: +3
        val undone = s.undoLast() // reverts B
        assertEquals(1, undone.currentPlayerIndex)
        assertEquals(0, undone.currentInning)
        assertTrue(undone.perPlayer[1].innings.isEmpty())
        assertEquals(1, undone.perPlayer[0].total)
        assertEquals(0, undone.perPlayer[1].total)
    }

    @Test
    fun undoLast_revertsAcrossInningBoundary() {
        var s = game("A", "B")
        s = s.applyTurn(1, 0, 0) // A inn1
        s = s.applyTurn(1, 0, 0) // B inn1 -> inning 1
        assertEquals(1, s.currentInning)
        s = s.applyTurn(2, 0, 0) // A inn2 (+2)
        val undone = s.undoLast() // reverts A's inn2 -> back to inning 1, player A
        assertEquals(0, undone.currentPlayerIndex)
        assertEquals(1, undone.currentInning)
        assertEquals(1, undone.perPlayer[0].innings.size)
        assertEquals(1, undone.perPlayer[0].total) // only inn1 single remains
    }

    @Test
    fun undoLast_unwindsFinish() {
        var s = game("A", "B")
        repeat(BASEBALL_INNINGS) {
            s = s.applyTurn(2, 0, 0) // A
            s = s.applyTurn(1, 0, 0) // B
        }
        assertTrue(s.isFinished)
        val undone = s.undoLast() // reverts B's final inning
        assertFalse(undone.isFinished, "finish undone")
        assertTrue(undone.winnerIndices.isEmpty())
        assertEquals(1, undone.currentPlayerIndex)
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
        state = state.applyTurn(1, 1, 0) // Alice
        state = state.applyTurn(0, 0, 1) // Bob -> inning 1
        val record = GameRecord(
            id = "baseball-1",
            mode = state.toMode(),
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        assertTrue(text.contains("\"type\":\"baseball\""), "expected serial name: $text")
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        assertEquals(GameMode.BASEBALL, decoded.mode)
    }
}
