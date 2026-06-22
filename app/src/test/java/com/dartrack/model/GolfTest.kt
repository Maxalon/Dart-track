package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Golf: 9 holes, one best-dart "stroke" result per hole
 * (Triple=1, Double=2, Single=3, Miss=5), round-robin BY HOLE like Shanghai,
 * winner = LOWEST total strokes after hole 9 (ties allowed), undo within /
 * across hole boundaries, require non-empty, and JSON round-trip.
 */
class GolfTest {

    private fun game(vararg names: String) =
        GolfState.new(names.map { GamePlayer(it) })

    @Test
    fun constants_areExpected() {
        assertEquals(9, GOLF_HOLES)
    }

    @Test
    fun strokeMapping_matchesGolfScoring() {
        assertEquals(1, GolfResult.TRIPLE.strokes)
        assertEquals(2, GolfResult.DOUBLE.strokes)
        assertEquals(3, GolfResult.SINGLE.strokes)
        assertEquals(5, GolfResult.MISS.strokes)
    }

    @Test
    fun startState_zeroStrokes_firstPlayerOnHole1() {
        val s = game("A", "B")
        assertEquals(0, s.perPlayer[0].strokes)
        assertEquals(0, s.perPlayer[1].strokes)
        assertEquals(1, s.currentHoleNumber(0))
        assertEquals(0, s.currentPlayerIndex)
        assertFalse(s.isFinished)
    }

    @Test
    fun applyResult_accumulatesStrokes_andAdvancesHole() {
        var s = game("A")
        s = s.applyResult(GolfResult.TRIPLE) // +1 stroke
        assertEquals(1, s.perPlayer[0].strokes)
        assertEquals(1, s.currentHole)
        s = s.applyResult(GolfResult.MISS) // +5 strokes
        assertEquals(6, s.perPlayer[0].strokes)
        assertEquals(3, s.currentHoleNumber(0))
    }

    @Test
    fun holeAdvancesOnlyAfterLastPlayer() {
        var s = game("A", "B")
        assertEquals(0, s.currentHole)
        s = s.applyResult(GolfResult.SINGLE) // A, still hole 0
        assertEquals(0, s.currentHole)
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyResult(GolfResult.SINGLE) // B wraps -> hole 1
        assertEquals(1, s.currentHole)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(2, s.currentHoleNumber(0))
    }

    @Test
    fun notFinished_beforeHole9() {
        var s = game("A", "B")
        repeat(GOLF_HOLES - 1) {
            s = s.applyResult(GolfResult.SINGLE)
            s = s.applyResult(GolfResult.SINGLE)
        }
        assertEquals(GOLF_HOLES - 1, s.currentHole)
        assertFalse(s.isFinished)
    }

    @Test
    fun gameEndsAfterHole9_lowestStrokesWins() {
        var s = game("A", "B")
        // A triples every hole (1 stroke), B misses every hole (5 strokes).
        repeat(GOLF_HOLES) {
            s = s.applyResult(GolfResult.TRIPLE) // A: +1
            s = s.applyResult(GolfResult.MISS)   // B: +5
        }
        assertTrue(s.isFinished, "game finishes after hole 9")
        assertEquals(GOLF_HOLES, s.perPlayer[0].results.size, "A played 9 holes")
        // A strokes = 9 * 1 = 9; B strokes = 9 * 5 = 45.
        assertEquals(9, s.perPlayer[0].strokes)
        assertEquals(45, s.perPlayer[1].strokes)
        assertTrue(s.perPlayer[0].strokes < s.perPlayer[1].strokes)
        assertEquals(listOf(0), s.winnerIndices, "LOWEST strokes wins")
    }

    @Test
    fun tieRecordsBothWinners() {
        var s = game("A", "B")
        repeat(GOLF_HOLES) {
            s = s.applyResult(GolfResult.DOUBLE) // A
            s = s.applyResult(GolfResult.DOUBLE) // B
        }
        assertTrue(s.isFinished)
        assertEquals(listOf(0, 1), s.winnerIndices, "ties record all lowest players")
        assertEquals(s.perPlayer[0].strokes, s.perPlayer[1].strokes)
    }

    @Test
    fun applyResult_noOpAfterFinished() {
        var s = game("A")
        repeat(GOLF_HOLES) { s = s.applyResult(GolfResult.SINGLE) }
        assertTrue(s.isFinished)
        assertEquals(s, s.applyResult(GolfResult.TRIPLE))
    }

    @Test
    fun new_rejectsEmptyPlayers() {
        var threw = false
        try {
            GolfState.new(emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "an empty player list must be rejected")
    }

    // ----------------------------------------------------------------- undo

    @Test
    fun undoLast_revertsWithinHole() {
        var s = game("A", "B")
        s = s.applyResult(GolfResult.TRIPLE) // A hole1: +1
        s = s.applyResult(GolfResult.MISS)   // B hole1: +5
        val undone = s.undoLast() // reverts B
        assertEquals(1, undone.currentPlayerIndex)
        assertEquals(0, undone.currentHole)
        assertTrue(undone.perPlayer[1].results.isEmpty())
        assertEquals(1, undone.perPlayer[0].strokes)
        assertEquals(0, undone.perPlayer[1].strokes)
    }

    @Test
    fun undoLast_revertsAcrossHoleBoundary() {
        var s = game("A", "B")
        s = s.applyResult(GolfResult.SINGLE) // A hole1 (+3)
        s = s.applyResult(GolfResult.SINGLE) // B hole1 -> hole 1
        assertEquals(1, s.currentHole)
        s = s.applyResult(GolfResult.DOUBLE) // A hole2 (+2)
        val undone = s.undoLast() // reverts A's hole2 -> back to hole 1, player A
        assertEquals(0, undone.currentPlayerIndex)
        assertEquals(1, undone.currentHole)
        assertEquals(1, undone.perPlayer[0].results.size)
        assertEquals(3, undone.perPlayer[0].strokes) // only hole1 single remains
    }

    @Test
    fun undoLast_unwindsFinish() {
        var s = game("A", "B")
        repeat(GOLF_HOLES) {
            s = s.applyResult(GolfResult.SINGLE)
            s = s.applyResult(GolfResult.DOUBLE)
        }
        assertTrue(s.isFinished)
        val undone = s.undoLast()
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
        state = state.applyResult(GolfResult.TRIPLE) // Alice
        state = state.applyResult(GolfResult.MISS)   // Bob -> hole 1
        val record = GameRecord(
            id = "golf-1",
            mode = state.toMode(),
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        assertTrue(text.contains("\"type\":\"golf\""), "expected serial name: $text")
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        assertEquals(GameMode.GOLF, decoded.mode)
    }
}
