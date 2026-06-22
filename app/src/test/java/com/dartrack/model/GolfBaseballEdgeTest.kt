package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADVERSARIAL edge cases for Golf and Baseball that the existing happy-path
 * tests don't cover:
 *  - Golf: an all-MISS 9-hole game finishes with every player tied at 45 strokes;
 *          repeated (reusable) undo unwinds the whole game back to the start;
 *          single player completes and is the sole winner.
 *  - Baseball: the runs = s + 2d + 3t mapping at the s+d+t == 3 boundary for
 *          every legal composition; an all-zero game ties everyone at 0; repeated
 *          undo unwinds back to the start; a max-runs (all triples) game.
 */
class GolfBaseballEdgeTest {

    private fun golf(vararg names: String) = GolfState.new(names.map { GamePlayer(it) })
    private fun baseball(vararg names: String) = BaseballState.new(names.map { GamePlayer(it) })

    // ============================================================ GOLF

    @Test
    fun golf_allMissGame_tiesEveryoneAt45() {
        var s = golf("A", "B", "C")
        repeat(GOLF_HOLES) {
            s = s.applyResult(GolfResult.MISS) // A
            s = s.applyResult(GolfResult.MISS) // B
            s = s.applyResult(GolfResult.MISS) // C
        }
        assertTrue(s.isFinished)
        // 9 holes * 5 strokes = 45 for everyone.
        assertTrue(s.perPlayer.all { it.strokes == 45 }, "all miss -> 45 each")
        assertEquals(listOf(0, 1, 2), s.winnerIndices, "all tied -> all winners (lowest)")
    }

    @Test
    fun golf_singlePlayer_completesAndWinsAlone() {
        var s = golf("Solo")
        repeat(GOLF_HOLES) { s = s.applyResult(GolfResult.SINGLE) }
        assertTrue(s.isFinished)
        assertEquals(GOLF_HOLES, s.perPlayer[0].results.size)
        assertEquals(GOLF_HOLES * 3, s.perPlayer[0].strokes) // single = 3 strokes
        assertEquals(listOf(0), s.winnerIndices)
    }

    @Test
    fun golf_repeatedUndo_unwindsEntireGameToStart() {
        var s = golf("A", "B")
        // Play a mix of results across all holes, then undo every recorded result.
        val seq = listOf(
            GolfResult.TRIPLE, GolfResult.MISS, GolfResult.DOUBLE, GolfResult.SINGLE,
        )
        repeat(GOLF_HOLES) {
            s = s.applyResult(seq[it % seq.size])
            s = s.applyResult(seq[(it + 1) % seq.size])
        }
        assertTrue(s.isFinished)
        val totalResults = s.perPlayer.sumOf { it.results.size }
        assertEquals(GOLF_HOLES * 2, totalResults)
        // Undo every single result; the game must return to a pristine start.
        repeat(totalResults) { s = s.undoLast() }
        assertEquals(0, s.currentHole, "back to hole 0")
        assertEquals(0, s.currentPlayerIndex, "back to first seat")
        assertFalse(s.isFinished, "no winner after full unwind")
        assertTrue(s.perPlayer.all { it.results.isEmpty() && it.strokes == 0 }, "all cleared")
        // One more undo on an empty game is a harmless no-op.
        assertEquals(s, s.undoLast())
    }

    // ============================================================ BASEBALL

    @Test
    fun baseball_runsFormula_holdsForEveryBoundaryComposition() {
        // Every legal (s,d,t) with s+d+t == 3 must yield runs = s + 2d + 3t.
        for (sgl in 0..3) for (dbl in 0..3) for (trp in 0..3) {
            if (sgl + dbl + trp != 3) continue
            val st = baseball("A").applyTurn(sgl, dbl, trp)
            val expected = sgl * 1 + dbl * 2 + trp * 3
            assertEquals(
                expected, st.perPlayer[0].total,
                "runs for (s=$sgl,d=$dbl,t=$trp) should be $expected",
            )
        }
    }

    @Test
    fun baseball_maxRunsGame_allTriples() {
        // 3 triples per inning = 9 runs/inning, 9 innings = 81 runs.
        var s = baseball("Slugger")
        repeat(BASEBALL_INNINGS) { s = s.applyTurn(0, 0, 3) }
        assertTrue(s.isFinished)
        assertEquals(9 * BASEBALL_INNINGS, s.perPlayer[0].total, "max 81 runs")
        assertEquals(listOf(0), s.winnerIndices)
    }

    @Test
    fun baseball_allZeroGame_tiesEveryoneAtZero() {
        var s = baseball("A", "B", "C")
        repeat(BASEBALL_INNINGS) {
            s = s.applyTurn(0, 0, 0)
            s = s.applyTurn(0, 0, 0)
            s = s.applyTurn(0, 0, 0)
        }
        assertTrue(s.isFinished)
        assertTrue(s.perPlayer.all { it.total == 0 })
        assertEquals(listOf(0, 1, 2), s.winnerIndices, "all 0 -> everyone is a top scorer")
    }

    @Test
    fun baseball_exactlyThreeDarts_isAccepted_fourIsRejected() {
        // Boundary: s+d+t == 3 is fine; == 4 throws.
        baseball("A").applyTurn(3, 0, 0) // ok
        baseball("A").applyTurn(1, 1, 1) // ok (==3)
        var threw = false
        try {
            baseball("A").applyTurn(1, 1, 2) // == 4
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "s+d+t == 4 must be rejected")
    }

    @Test
    fun baseball_repeatedUndo_unwindsEntireGameToStart() {
        var s = baseball("A", "B")
        repeat(BASEBALL_INNINGS) {
            s = s.applyTurn(1, 1, 0) // A: 3 runs
            s = s.applyTurn(0, 0, 1) // B: 3 runs
        }
        assertTrue(s.isFinished)
        val totalInnings = s.perPlayer.sumOf { it.innings.size }
        assertEquals(BASEBALL_INNINGS * 2, totalInnings)
        repeat(totalInnings) { s = s.undoLast() }
        assertEquals(0, s.currentInning, "back to inning 0")
        assertEquals(0, s.currentPlayerIndex)
        assertFalse(s.isFinished)
        assertTrue(s.perPlayer.all { it.innings.isEmpty() && it.total == 0 })
        assertEquals(s, s.undoLast(), "undo on empty is a no-op")
    }

    @Test
    fun baseball_jsonRoundTrip_finishedTieState() {
        val json = GameJson.format
        var state = baseball("Alice", "Bob")
        repeat(BASEBALL_INNINGS) {
            state = state.applyTurn(2, 0, 0)
            state = state.applyTurn(2, 0, 0)
        }
        assertTrue(state.isFinished)
        val record = GameRecord("bb-tie", state.toMode(), 1L, 2L, state)
        val text = json.encodeToString(GameRecord.serializer(), record)
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        assertEquals(listOf(0, 1), (decoded.state as BaseballState).winnerIndices)
    }
}
