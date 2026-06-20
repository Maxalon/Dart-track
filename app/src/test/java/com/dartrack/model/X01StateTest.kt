package com.dartrack.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for X01 game logic: bust rules, turn rotation, winner recording,
 * undoLast, and X01Stats. All deterministic; no randomness or IO.
 */
class X01StateTest {

    private fun players(vararg names: String) = names.map { GamePlayer(it) }

    private fun game(
        start: Int = 501,
        doubleOut: Boolean = true,
        vararg names: String,
    ) = X01State.new(players(*names), startScore = start, doubleOut = doubleOut)

    // ---------------------------------------------------------------- bust rules

    @Test
    fun bust_whenScoreGoesBelowZero() {
        // start 50, enter 60 -> after = -10 -> bust
        val s = game(start = 50, doubleOut = false, "A", "B").applyTurn(60)
        val turn = s.perPlayer[0].turns.single()
        assertTrue(turn.bust, "going below 0 must bust")
        assertFalse(turn.finished)
        // bust discards the score; player stays at start
        assertEquals(50, s.scoreFor(0))
        assertEquals(0, turn.applied)
    }

    @Test
    fun bust_doubleOut_scoreEqualsOne() {
        // start 41, enter 40 -> after = 1 -> bust under double-out
        val s = game(start = 41, doubleOut = true, "A", "B").applyTurn(40)
        val turn = s.perPlayer[0].turns.single()
        assertTrue(turn.bust, "leaving 1 must bust under double-out")
        assertEquals(41, s.scoreFor(0))
    }

    @Test
    fun bust_doubleOut_reachingZeroWithoutFinishedOnDouble() {
        // start 40, enter 40 -> after = 0 but finishedOnDouble defaults to false
        // under double-out, so this is a bust, not a win.
        val s = game(start = 40, doubleOut = true, "A", "B").applyTurn(40)
        val turn = s.perPlayer[0].turns.single()
        assertTrue(turn.bust, "reaching 0 w/o finishedOnDouble flag must bust under double-out")
        assertFalse(turn.finished)
        assertTrue(s.winnerIndices.isEmpty())
        assertEquals(40, s.scoreFor(0))
    }

    @Test
    fun finish_doubleOut_reachingZeroWithFinishedOnDouble() {
        val s = game(start = 40, doubleOut = true, "A", "B")
            .applyTurn(40, finishedOnDouble = true)
        val turn = s.perPlayer[0].turns.single()
        assertFalse(turn.bust)
        assertTrue(turn.finished, "reaching 0 with finishedOnDouble must finish")
        assertEquals(listOf(0), s.winnerIndices)
        assertTrue(s.isFinished)
        assertEquals(0, s.scoreFor(0))
    }

    @Test
    fun finish_singleOut_reachingZeroByDefault() {
        // doubleOut = false -> finishedOnDouble default is true -> reaching 0 wins
        val s = game(start = 40, doubleOut = false, "A", "B").applyTurn(40)
        val turn = s.perPlayer[0].turns.single()
        assertFalse(turn.bust)
        assertTrue(turn.finished)
        assertEquals(listOf(0), s.winnerIndices)
    }

    @Test
    fun singleOut_leavingOneIsNotBust() {
        // doubleOut off: leaving 1 is legal
        val s = game(start = 41, doubleOut = false, "A", "B").applyTurn(40)
        val turn = s.perPlayer[0].turns.single()
        assertFalse(turn.bust)
        assertEquals(1, s.scoreFor(0))
    }

    @Test
    fun normalTurn_subtractsScore() {
        val s = game(start = 501, doubleOut = true, "A", "B").applyTurn(60)
        assertEquals(441, s.scoreFor(0))
        assertFalse(s.perPlayer[0].turns.single().bust)
    }

    // ------------------------------------------------------------- turn rotation

    @Test
    fun turnRotation_advancesToNextPlayer() {
        var s = game(start = 501, "A", "B", "C")
        assertEquals(0, s.currentPlayerIndex)
        s = s.applyTurn(60)
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(60)
        assertEquals(2, s.currentPlayerIndex)
        s = s.applyTurn(60)
        assertEquals(0, s.currentPlayerIndex, "wraps back to first player")
    }

    @Test
    fun turnRotation_bustStillAdvances() {
        var s = game(start = 50, doubleOut = false, "A", "B")
        s = s.applyTurn(60) // A busts
        assertEquals(1, s.currentPlayerIndex, "bust still passes the turn")
    }

    @Test
    fun finish_keepsCurrentPlayerIndexOnWinner() {
        val s = game(start = 40, doubleOut = false, "A", "B").applyTurn(40)
        // on finish, currentPlayerIndex stays on the winner
        assertEquals(0, s.currentPlayerIndex)
    }

    @Test
    fun applyTurn_noOpAfterFinished() {
        val finished = game(start = 40, doubleOut = false, "A", "B").applyTurn(40)
        val again = finished.applyTurn(20)
        assertEquals(finished, again, "applyTurn after finish is a no-op")
    }

    // ---------------------------------------------------------------- undoLast

    @Test
    fun undoLast_revertsLastTurn() {
        var s = game(start = 501, "A", "B")
        s = s.applyTurn(60) // A
        s = s.applyTurn(45) // B
        // last actor was B (index 1)
        val undone = s.undoLast()
        assertEquals(1, undone.currentPlayerIndex, "undo points to the reverted player")
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        assertEquals(441, undone.scoreFor(0), "A's turn untouched")
        assertEquals(501, undone.scoreFor(1))
    }

    @Test
    fun undoLast_afterWin_revertsWinningTurnAndClearsWinner() {
        var s = game(start = 100, doubleOut = false, "A", "B")
        s = s.applyTurn(60) // A -> 40
        s = s.applyTurn(50) // B -> 50
        s = s.applyTurn(40) // A finishes
        assertTrue(s.isFinished)
        assertEquals(listOf(0), s.winnerIndices)

        val undone = s.undoLast()
        assertFalse(undone.isFinished, "win must be cleared after undo")
        assertTrue(undone.winnerIndices.isEmpty())
        assertEquals(0, undone.currentPlayerIndex, "back on the un-won player")
        assertEquals(40, undone.scoreFor(0), "winning turn reverted, A back to 40")
        assertEquals(1, undone.perPlayer[0].turns.size)
    }

    @Test
    fun undoLast_noTurns_isNoOp() {
        val s = game(start = 501, "A", "B")
        assertEquals(s, s.undoLast())
    }

    // ---------------------------------------------------------------- X01Stats

    @Test
    fun stats_pointsScored() {
        var s = game(start = 501, doubleOut = false, "A", "B")
        s = s.applyTurn(100) // A -> 401
        s = s.applyTurn(0)   // B
        s = s.applyTurn(80)  // A -> 321
        assertEquals(180, X01Stats.pointsScored(s.perPlayer[0], 501))
        assertEquals(0, X01Stats.pointsScored(s.perPlayer[1], 501))
    }

    @Test
    fun stats_pointsScored_noTurns_isZero() {
        val s = game(start = 501, "A", "B")
        assertEquals(0, X01Stats.pointsScored(s.perPlayer[0], 501))
    }

    @Test
    fun stats_threeDartAverage_countsBustAsThreeDartsZeroPoints() {
        var s = game(start = 100, doubleOut = false, "A", "B")
        s = s.applyTurn(60) // A: 60 in 3 darts -> 40
        s = s.applyTurn(0)  // B
        s = s.applyTurn(50) // A busts (40-50<0): 0 points, still 3 darts
        // A: 2 turns = 6 darts, 60 points -> avg = 60*3/6 = 30.0
        assertEquals(30.0, X01Stats.threeDartAverage(s.perPlayer[0], 100), 0.0001)
    }

    @Test
    fun stats_threeDartAverage_noTurns_isZero() {
        val s = game(start = 501, "A", "B")
        assertEquals(0.0, X01Stats.threeDartAverage(s.perPlayer[0], 501), 0.0)
    }

    @Test
    fun stats_highestTurn_excludesBusts() {
        var s = game(start = 200, doubleOut = false, "A", "B")
        s = s.applyTurn(100) // A -> 100
        s = s.applyTurn(0)   // B
        s = s.applyTurn(140) // A busts (100-140<0) entered 140 but busted
        s = s.applyTurn(0)   // B
        s = s.applyTurn(60)  // A -> 40
        // highest non-bust entered = 100 (the busted 140 is excluded)
        assertEquals(100, X01Stats.highestTurn(s.perPlayer[0]))
    }

    @Test
    fun stats_highestTurn_noTurns_isZero() {
        val s = game(start = 501, "A", "B")
        assertEquals(0, X01Stats.highestTurn(s.perPlayer[0]))
    }

    @Test
    fun stats_checkout_isFinishingTurnEntered() {
        val s = game(start = 40, doubleOut = false, "A", "B").applyTurn(40)
        assertEquals(40, X01Stats.checkout(s.perPlayer[0]))
    }

    @Test
    fun stats_checkout_nullWhenNotFinished() {
        val s = game(start = 501, "A", "B").applyTurn(60)
        assertEquals(null, X01Stats.checkout(s.perPlayer[0]))
    }
}
