package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Shanghai: 7 rounds targeting numbers 1..7, per-turn singles/doubles/
 * triples entry, score = (s + 2d + 3t) * round, instant Shanghai win on s=d=t=1,
 * lockstep round advance, winner = highest total after round 7 (ties allowed),
 * undo within / across round boundaries and of an instant win, and JSON
 * round-trip.
 */
class ShanghaiTest {

    private fun game(vararg names: String) =
        ShanghaiState.new(names.map { GamePlayer(it) })

    @Test
    fun constants_areExpected() {
        assertEquals(7, SHANGHAI_ROUNDS)
        assertEquals(3, SHANGHAI_MAX_DARTS)
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
    fun scoreScalesWithRound() {
        // Single player: each (non-Shanghai) turn advances the round.
        // Round 1, two singles of 1 = (2*1)*1 = 2.
        var s = game("A")
        s = s.applyTurn(2, 0, 0)
        assertEquals(2, s.perPlayer[0].total)
        assertEquals(1, s.currentRound)
        // Round 2 target 2, two singles = (2*1)*2 = 4 -> total 6.
        s = s.applyTurn(2, 0, 0)
        assertEquals(6, s.perPlayer[0].total)
        assertEquals(2, s.currentTarget(0))
    }

    @Test
    fun multiDartTurn_mixesSinglesDoublesTriples() {
        // Round 1: 1 single + 2 doubles? exceeds 3? 1+2 = 3 darts ok.
        // (1*1 + 2*2 + 0*3) * 1 = 5.
        val s = game("A").applyTurn(1, 2, 0)
        assertEquals(5, s.perPlayer[0].total)
    }

    @Test
    fun doubleAndTripleScored_noShanghaiWithoutSingle() {
        // Round 1: 0 singles, 1 double, 1 triple = (0 + 2 + 3) * 1 = 5. Not a
        // Shanghai (needs a single too) -> game continues, round advances.
        val s = game("A").applyTurn(0, 1, 1)
        assertEquals(5, s.perPlayer[0].total)
        assertFalse(s.isFinished)
        assertEquals(1, s.currentRound)
    }

    @Test
    fun instantShanghaiWin() {
        // Round 1: 1 single + 1 double + 1 triple -> instant win regardless of score.
        val s = game("A", "B").applyTurn(1, 1, 1)
        assertTrue(s.isFinished, "S+D+T is an instant Shanghai win")
        assertEquals(listOf(0), s.winnerIndices)
        // Score still recorded: (1 + 2 + 3) * 1 = 6.
        assertEquals(6, s.perPlayer[0].total)
        // Second player never threw.
        assertTrue(s.perPlayer[1].turns.isEmpty())
    }

    @Test
    fun roundAdvancesOnlyAfterLastPlayer() {
        var s = game("A", "B")
        assertEquals(0, s.currentRound)
        s = s.applyTurn(1, 0, 0) // A, still round 0
        assertEquals(0, s.currentRound)
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(1, 0, 0) // B wraps -> round 1
        assertEquals(1, s.currentRound)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(2, s.currentTarget(0))
    }

    @Test
    fun notFinished_beforeRound7() {
        var s = game("A", "B")
        repeat(SHANGHAI_ROUNDS - 1) {
            s = s.applyTurn(1, 0, 0)
            s = s.applyTurn(1, 0, 0)
        }
        assertEquals(SHANGHAI_ROUNDS - 1, s.currentRound)
        assertFalse(s.isFinished)
    }

    @Test
    fun gameEndsAfterRound7_highestTotalWins() {
        var s = game("A", "B")
        // A hits 3 singles every round, B hits 1 single every round, all 7 rounds.
        repeat(SHANGHAI_ROUNDS) {
            s = s.applyTurn(3, 0, 0) // A
            s = s.applyTurn(1, 0, 0) // B
        }
        assertTrue(s.isFinished, "game finishes after round 7")
        assertEquals(SHANGHAI_ROUNDS, s.perPlayer[0].turns.size, "A threw 7 turns")
        assertEquals(SHANGHAI_ROUNDS * 3, s.perPlayer[0].darts, "darts = turns * 3")
        assertTrue(s.perPlayer[0].total > s.perPlayer[1].total)
        assertEquals(listOf(0), s.winnerIndices, "highest total wins")
        // A total = sum over R of 3*R = 3*(1+..+7) = 3*28 = 84.
        assertEquals(84, s.perPlayer[0].total)
    }

    @Test
    fun tieRecordsBothWinners() {
        var s = game("A", "B")
        repeat(SHANGHAI_ROUNDS) {
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
        repeat(SHANGHAI_ROUNDS) { s = s.applyTurn(1, 0, 0) }
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

    // ----------------------------------------------------------------- undo

    @Test
    fun undoLast_revertsWithinRound() {
        var s = game("A", "B")
        s = s.applyTurn(1, 0, 0) // A r1: +1
        s = s.applyTurn(3, 0, 0) // B r1: +3
        val undone = s.undoLast() // reverts B
        assertEquals(1, undone.currentPlayerIndex)
        assertEquals(0, undone.currentRound)
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        assertEquals(1, undone.perPlayer[0].total)
        assertEquals(0, undone.perPlayer[1].total)
    }

    @Test
    fun undoLast_revertsAcrossRoundBoundary() {
        var s = game("A", "B")
        s = s.applyTurn(1, 0, 0) // A r1
        s = s.applyTurn(1, 0, 0) // B r1 -> round 1
        assertEquals(1, s.currentRound)
        s = s.applyTurn(2, 0, 0) // A r2 (+2*2 = 4)
        val undone = s.undoLast() // reverts A's r2 turn -> back to round 1, player A
        assertEquals(0, undone.currentPlayerIndex)
        assertEquals(1, undone.currentRound)
        assertEquals(1, undone.perPlayer[0].turns.size)
        assertEquals(1, undone.perPlayer[0].total) // only r1 single remains
    }

    @Test
    fun undoLast_atStart_isNoOp() {
        val s = game("A", "B")
        assertEquals(s, s.undoLast())
    }

    @Test
    fun undoLast_unwindsInstantWin() {
        var s = game("A", "B")
        s = s.applyTurn(2, 0, 0) // A r1
        s = s.applyTurn(1, 1, 1) // B Shanghai -> instant win
        assertTrue(s.isFinished)
        assertEquals(listOf(1), s.winnerIndices)
        val undone = s.undoLast()
        assertFalse(undone.isFinished, "instant win undone")
        assertTrue(undone.winnerIndices.isEmpty())
        // Back to B's turn in round 1 (A already threw).
        assertEquals(1, undone.currentPlayerIndex)
        assertEquals(0, undone.currentRound)
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        assertEquals(2, undone.perPlayer[0].total)
    }

    // ----------------------------------------------------------------- JSON

    @Test
    fun jsonRoundTrip_preservesState() {
        val json = GameJson.format
        var state = game("Alice", "Bob")
        state = state.applyTurn(1, 1, 0) // Alice
        state = state.applyTurn(0, 0, 1) // Bob -> round 1
        val record = GameRecord(
            id = "shanghai-1",
            mode = state.toMode(),
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        assertTrue(text.contains("\"type\":\"shanghai\""), "expected serial name: $text")
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        assertEquals(GameMode.SHANGHAI, decoded.mode)
    }
}
