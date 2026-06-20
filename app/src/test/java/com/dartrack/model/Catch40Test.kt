package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Catch 40: a catch-or-stay doubles ladder. Each player starts on
 * D20 (value 40) and ladders DOWN to D1. A turn enters 0..3 hits at the current
 * double: hits >= 1 "catches" it (score += 2 * doubleNumber, advance down),
 * hits == 0 "stays" (no score, no advance). Catching D1 finishes a player. The
 * game is lockstep, capped at CATCH40_MAX_TURNS turns per player, and ends when
 * everyone is finished or out of turns; highest score wins (ties allowed).
 *
 * NOTE: assertEquals here is kotlin.test.assertEquals, where the optional
 * message is the LAST argument. All defaulted helper params are passed by name
 * so no string ever binds to a non-vararg parameter.
 */
class Catch40Test {

    private fun game(vararg names: String) =
        Catch40State.new(names.map { GamePlayer(it) })

    @Test
    fun constants_areExpected() {
        assertEquals(20, CATCH40_START_DOUBLE)
        assertEquals(3, CATCH40_MAX_HITS)
        assertEquals(20, CATCH40_MAX_TURNS)
    }

    @Test
    fun startState_zeroScore_onD20() {
        val s = game("A", "B")
        assertEquals(0, s.perPlayer[0].score)
        assertEquals(0, s.perPlayer[1].score)
        assertEquals(20, s.currentDouble(0))
        assertEquals(40, s.currentDoubleValue(0))
        assertEquals(0, s.currentPlayerIndex)
        assertFalse(s.isFinished)
    }

    @Test
    fun catch_addsDoubleValue_andAdvancesDown() {
        // Single player: catching D20 scores 40 and moves to D19.
        var s = game("A")
        s = s.applyTurn(1)
        assertEquals(40, s.perPlayer[0].score)
        assertEquals(19, s.currentDouble(0))
        assertEquals(38, s.currentDoubleValue(0))
        assertFalse(s.perPlayer[0].finished)
        // hits count does not multiply the score: 3 hits on D19 still adds 38.
        s = s.applyTurn(3)
        assertEquals(78, s.perPlayer[0].score) // 40 + 38
        assertEquals(18, s.currentDouble(0))
    }

    @Test
    fun miss_staysOnSameDouble_noScore() {
        var s = game("A")
        s = s.applyTurn(0)
        assertEquals(0, s.perPlayer[0].score)
        assertEquals(20, s.currentDouble(0), "miss keeps the player on D20")
        assertEquals(1, s.perPlayer[0].turns.size, "the miss still consumes a turn")
        assertFalse(s.isFinished)
        // A subsequent catch then advances normally.
        s = s.applyTurn(1)
        assertEquals(40, s.perPlayer[0].score)
        assertEquals(19, s.currentDouble(0))
    }

    @Test
    fun catchingD1_finishesPlayerAndGame() {
        // Single player catches all 20 doubles D20..D1.
        var s = game("A")
        repeat(CATCH40_START_DOUBLE) { s = s.applyTurn(1) }
        assertTrue(s.perPlayer[0].finished, "caught D1 -> finished the ladder")
        assertTrue(s.isFinished, "only player finished -> game over")
        // Score = sum of 2*n for n = 20..1 = 2 * (20+...+1) = 2 * 210 = 420.
        assertEquals(420, s.perPlayer[0].score)
        assertEquals(CATCH40_START_DOUBLE, s.perPlayer[0].turns.size)
        assertEquals(listOf(0), s.winnerIndices)
    }

    @Test
    fun notFinished_beforeEveryoneDone() {
        // Two players, A catches once, B catches once: still mid-game.
        var s = game("A", "B")
        s = s.applyTurn(1) // A
        assertEquals(1, s.currentPlayerIndex, "lockstep -> B is next")
        assertFalse(s.isFinished)
        s = s.applyTurn(1) // B
        assertEquals(0, s.currentPlayerIndex, "wraps back to A")
        assertFalse(s.isFinished)
        assertEquals(40, s.perPlayer[0].score)
        assertEquals(40, s.perPlayer[1].score)
    }

    @Test
    fun gameEndsAfterMaxTurns_evenWithoutFinishing() {
        // Single player misses every turn: never finishes the ladder but the
        // CATCH40_MAX_TURNS cap ends the game.
        var s = game("A")
        repeat(CATCH40_MAX_TURNS) { s = s.applyTurn(0) }
        assertTrue(s.isFinished, "cap reached -> game over")
        assertFalse(s.perPlayer[0].finished, "ladder never caught")
        assertEquals(0, s.perPlayer[0].score)
        assertEquals(CATCH40_MAX_TURNS, s.perPlayer[0].turns.size)
        assertEquals(20, s.currentDouble(0), "still stuck on D20")
        assertEquals(listOf(0), s.winnerIndices)
    }

    @Test
    fun highestScoreWins() {
        // A catches every turn (finishes at 420); B misses every turn (stuck,
        // ends on the cap with 0). A wins.
        var s = game("A", "B")
        // Interleave lockstep. A finishes after 20 catches; B uses 20 misses.
        // Keep applying until the game ends.
        var guard = 0
        while (!s.isFinished && guard < 1000) {
            val idx = s.currentPlayerIndex
            s = s.applyTurn(if (idx == 0) 1 else 0)
            guard++
        }
        assertTrue(s.isFinished)
        assertEquals(420, s.perPlayer[0].score)
        assertEquals(0, s.perPlayer[1].score)
        assertTrue(s.perPlayer[0].finished)
        assertFalse(s.perPlayer[1].finished)
        assertEquals(listOf(0), s.winnerIndices, "highest score wins")
    }

    @Test
    fun tieRecordsBothWinners() {
        // Both players catch every turn -> both finish at 420.
        var s = game("A", "B")
        repeat(CATCH40_START_DOUBLE) {
            s = s.applyTurn(1) // A
            s = s.applyTurn(1) // B
        }
        assertTrue(s.isFinished)
        assertEquals(420, s.perPlayer[0].score)
        assertEquals(420, s.perPlayer[1].score)
        assertEquals(listOf(0, 1), s.winnerIndices, "ties record all top players")
    }

    @Test
    fun applyTurn_noOpAfterFinished() {
        var s = game("A")
        repeat(CATCH40_START_DOUBLE) { s = s.applyTurn(1) }
        assertTrue(s.isFinished)
        assertEquals(s, s.applyTurn(2))
    }

    @Test
    fun applyTurn_rejectsTooManyHits() {
        val s = game("A")
        var threw = false
        try {
            s.applyTurn(4)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "hits > 3 must be rejected")
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
        assertTrue(threw, "negative hits must be rejected")
    }

    // ----------------------------------------------------------------- undo

    @Test
    fun undoCatch_revertsScoreAndDouble() {
        var s = game("A", "B")
        s = s.applyTurn(1) // A catches D20 -> 40, on D19
        s = s.applyTurn(1) // B catches D20 -> 40, on D19
        val undone = s.undoLast() // reverts B
        assertEquals(1, undone.currentPlayerIndex, "back to B's turn")
        assertEquals(0, undone.perPlayer[1].score, "B's score reverted")
        assertEquals(20, undone.currentDouble(1), "B back on D20")
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        // A untouched.
        assertEquals(40, undone.perPlayer[0].score)
        assertEquals(19, undone.currentDouble(0))
    }

    @Test
    fun undoMiss_revertsTurnOnly() {
        var s = game("A")
        s = s.applyTurn(1) // catch D20 -> 40, on D19
        s = s.applyTurn(0) // miss on D19: stays
        assertEquals(40, s.perPlayer[0].score)
        assertEquals(19, s.currentDouble(0))
        assertEquals(2, s.perPlayer[0].turns.size)
        val undone = s.undoLast() // remove the miss
        assertEquals(40, undone.perPlayer[0].score, "score unchanged by undoing a miss")
        assertEquals(19, undone.currentDouble(0))
        assertEquals(1, undone.perPlayer[0].turns.size)
        assertFalse(undone.isFinished)
    }

    @Test
    fun undoAcrossFinish_reopensGame() {
        // Single player catches the whole ladder, then undo the finishing catch.
        var s = game("A")
        repeat(CATCH40_START_DOUBLE) { s = s.applyTurn(1) }
        assertTrue(s.isFinished)
        assertTrue(s.perPlayer[0].finished)
        val undone = s.undoLast()
        assertFalse(undone.isFinished, "finishing catch undone")
        assertTrue(undone.winnerIndices.isEmpty())
        assertFalse(undone.perPlayer[0].finished, "no longer finished")
        assertEquals(1, undone.currentDouble(0), "back on D1, about to catch it")
        assertEquals(CATCH40_START_DOUBLE - 1, undone.perPlayer[0].turns.size)
        // Score = 420 minus the D1 catch (value 2) = 418.
        assertEquals(418, undone.perPlayer[0].score)
        assertEquals(0, undone.currentPlayerIndex)
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
        state = state.applyTurn(1) // Alice catches D20
        state = state.applyTurn(0) // Bob misses D20
        val record = GameRecord(
            id = "catch40-1",
            mode = state.toMode(),
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        assertTrue(text.contains("\"type\":\"catch_40\""), "expected serial name: $text")
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        assertEquals(GameMode.CATCH_40, decoded.mode)
    }
}
