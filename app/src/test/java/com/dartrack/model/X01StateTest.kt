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

    // ---------------------------------------------------------------- match play

    private fun match(
        start: Int = 501,
        doubleOut: Boolean = false,
        legsToWin: Int = 3,
        vararg names: String,
    ) = X01State.new(players(*names), startScore = start, doubleOut = doubleOut,
        legsToWin = legsToWin)

    /** Win the leg for whoever is currently to throw (single-out, start small). */
    private fun winLegForCurrent(s: X01State): X01State =
        s.applyTurn(s.currentPlayerScore())

    @Test
    fun singleLeg_isDefault_andBehavesAsBefore() {
        val s = game(start = 40, doubleOut = false, "A", "B")
        assertEquals(1, s.legsToWin)
        assertFalse(s.isMatch)
        val won = s.applyTurn(40)
        assertTrue(won.isFinished, "single leg finishes the game")
        assertEquals(listOf(0), won.winnerIndices)
        assertEquals(1, won.completedLegs.size)
        assertEquals(1, won.legsWonBy(0))
    }

    @Test
    fun match_firstLegWin_doesNotEndMatch_andStartsNextLeg() {
        var s = match(start = 40, legsToWin = 3, "A", "B")
        s = s.applyTurn(40) // A wins leg 1
        assertFalse(s.isFinished, "match not over after one leg of three")
        assertEquals(1, s.legsWonBy(0))
        assertEquals(0, s.legsWonBy(1))
        assertEquals(1, s.completedLegs.size)
        // Next leg reset: both back to start; B (rotated starter) throws first.
        assertEquals(40, s.scoreFor(0))
        assertEquals(40, s.scoreFor(1))
        assertEquals(1, s.startingPlayerIndex)
        assertEquals(1, s.currentPlayerIndex)
        assertTrue(s.perPlayer.all { it.turns.isEmpty() })
    }

    @Test
    fun match_winsWhenReachingLegsToWin() {
        // first to 2; A wins leg1, then leg2 (A starts leg1, B starts leg2).
        var s = match(start = 40, legsToWin = 2, "A", "B")
        s = s.applyTurn(40)          // A wins leg 1, B to throw leg 2
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(0)           // B throws, no score
        assertEquals(0, s.currentPlayerIndex)
        s = s.applyTurn(40)          // A wins leg 2 -> match
        assertTrue(s.isFinished)
        assertEquals(listOf(0), s.winnerIndices)
        assertEquals(2, s.legsWonBy(0))
        assertEquals(2, s.completedLegs.size)
        assertEquals(0, s.currentPlayerIndex, "cursor stays on match winner")
    }

    @Test
    fun match_undoAcrossLegBoundary_restoresPreviousLeg() {
        var s = match(start = 40, legsToWin = 3, "A", "B")
        s = s.applyTurn(40) // A wins leg 1 -> leg 2 started, B to throw
        assertEquals(1, s.completedLegs.size)
        assertEquals(1, s.currentPlayerIndex)

        val undone = s.undoLast()
        // Leg 1 restored as in-progress with A holding the winning turn.
        assertEquals(0, undone.completedLegs.size)
        assertEquals(0, undone.legsWonBy(0))
        assertEquals(0, undone.currentPlayerIndex, "cursor back on the winner")
        assertEquals(0, undone.startingPlayerIndex, "starter rolled back")
        assertFalse(undone.isFinished)
        // The winning turn is back in perPlayer (score reflects the checkout).
        assertEquals(0, undone.scoreFor(0))
        assertEquals(1, undone.perPlayer[0].turns.size)
        assertTrue(undone.perPlayer[0].turns.single().finished)
    }

    @Test
    fun match_undoAfterMatchWin_clearsMatchAndReopensFinalLeg() {
        var s = match(start = 40, legsToWin = 2, "A", "B")
        s = s.applyTurn(40)  // A wins leg 1
        s = s.applyTurn(0)   // B throws leg 2
        s = s.applyTurn(40)  // A wins leg 2 -> match over
        assertTrue(s.isFinished)
        assertEquals(2, s.completedLegs.size)

        val undone = s.undoLast()
        assertFalse(undone.isFinished, "match win cleared")
        assertTrue(undone.winnerIndices.isEmpty())
        assertEquals(1, undone.legsWonBy(0), "decremented back to one leg")
        assertEquals(1, undone.completedLegs.size)
        assertEquals(0, undone.currentPlayerIndex)
        // A is back to needing the final checkout (winning turn dropped).
        assertEquals(40, undone.scoreFor(0))
        assertTrue(undone.perPlayer[0].turns.isEmpty())
    }

    @Test
    fun match_normalUndoWithinLeg_stillWorks() {
        var s = match(start = 501, legsToWin = 3, "A", "B")
        s = s.applyTurn(60) // A
        s = s.applyTurn(45) // B
        val undone = s.undoLast()
        assertEquals(1, undone.currentPlayerIndex)
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        assertEquals(441, undone.scoreFor(0))
        assertEquals(0, undone.completedLegs.size, "no leg boundary crossed")
    }

    @Test
    fun match_stats_aggregateAcrossLegs() {
        // Small start so realistic 0..180 turns can finish a leg.
        // A finishes each won leg with 120 (highest turn / checkout = 120).
        var s = match(start = 120, legsToWin = 2, "A", "B")
        // leg 1: A 120 (single-out finish). A starts leg 1.
        s = s.applyTurn(120)
        assertEquals(1, s.legsWonBy(0))
        // leg 2: B starts. B 0, A 120 finish -> A wins match.
        s = s.applyTurn(0)   // B
        s = s.applyTurn(120) // A wins match
        assertTrue(s.isFinished)
        val legs = s.allLegStatesFor(0)
        assertEquals(2, legs.size)
        // Each leg: 120 points scored; total across both = 240.
        assertEquals(240, X01Stats.pointsScored(legs, 120))
        assertEquals(120, X01Stats.highestTurn(legs))
        assertEquals(120, X01Stats.highestCheckout(legs))
    }

    // ------------------------------------------------------------------- sets

    private fun setMatch(
        start: Int = 501,
        doubleOut: Boolean = false,
        legsToWin: Int = 2,
        setsToWin: Int = 2,
        vararg names: String,
    ) = X01State.new(players(*names), startScore = start, doubleOut = doubleOut,
        legsToWin = legsToWin, setsToWin = setsToWin)

    @Test
    fun singleSet_isDefault_andIdenticalToLegsOnly() {
        // Default setsToWin = 1: behaves exactly like a legs-only first-to-2 match.
        val s = match(start = 40, legsToWin = 2, "A", "B")
        assertEquals(1, s.setsToWin)
        assertEquals(0, s.setsWonBy(0))
        assertEquals(0, s.setsWonBy(1))
        // isMatch driven by legs as before.
        assertTrue(s.isMatch)

        var g = s
        g = g.applyTurn(40)      // A wins leg 1
        assertFalse(g.isFinished)
        assertEquals(1, g.legsWonBy(0))
        assertEquals(0, g.setsWonBy(0), "no sets layer: setWins stay zero")
        g = g.applyTurn(0)       // B
        g = g.applyTurn(40)      // A wins leg 2 -> match (single set)
        assertTrue(g.isFinished)
        assertEquals(listOf(0), g.winnerIndices)
        assertEquals(2, g.legsWonBy(0))
        assertEquals(0, g.setsWonBy(0), "single-set match never increments setWins")
        assertEquals(2, g.completedLegs.size)
    }

    @Test
    fun setsToWin_makesIsMatchTrueEvenWithSingleLeg() {
        val s = X01State.new(players("A", "B"), startScore = 40, doubleOut = false,
            legsToWin = 1, setsToWin = 3)
        assertTrue(s.isMatch, "multi-set is a match even at one leg per set")
    }

    /** Drive A (index 0) to check out the current leg with a 40; if B is up,
     *  B throws 0 first so the cursor returns to A. Single-out, start 40. */
    private fun aWinsLeg(s0: X01State): X01State {
        var s = s0
        if (s.currentPlayerIndex != 0) s = s.applyTurn(0)
        return s.applyTurn(40)
    }

    @Test
    fun twoSetMatch_setRollover_resetsLegsAndIncrementsSets() {
        // first-to-2 legs per set, first-to-2 sets. Single-out, start 40 so
        // every checkout is a single 40.
        var s = setMatch(start = 40, legsToWin = 2, setsToWin = 2, "A", "B")
        // Set 1: A wins both legs.
        s = s.applyTurn(40)              // leg1: A (A started)
        assertEquals(1, s.legsWonBy(0))
        assertEquals(1, s.currentPlayerIndex, "B starts leg 2")
        s = s.applyTurn(0)               // B throws
        s = s.applyTurn(40)              // leg2: A wins -> SET 1 to A
        assertFalse(s.isFinished, "match not over: A has 1 set of 2")
        assertEquals(1, s.setsWonBy(0))
        assertEquals(0, s.setsWonBy(1))
        assertEquals(0, s.legsWonBy(0), "legs reset at set rollover")
        assertEquals(0, s.legsWonBy(1))
        assertEquals(2, s.completedLegs.size, "completedLegs keep accumulating")
        // New set: fresh leg, scores reset.
        assertTrue(s.perPlayer.all { it.turns.isEmpty() })
        assertEquals(40, s.scoreFor(0))
        assertEquals(40, s.scoreFor(1))

        // Set 2: A wins both legs again -> match over.
        s = aWinsLeg(s)                  // S2 L1: A
        assertEquals(1, s.legsWonBy(0))
        assertEquals(1, s.setsWonBy(0), "still 1 set; only 1 leg into set 2")
        assertFalse(s.isFinished)
        s = aWinsLeg(s)                  // S2 L2: A -> match
        assertTrue(s.isFinished, "A wins set 2 -> match over")
        assertEquals(listOf(0), s.winnerIndices)
        assertEquals(2, s.setsWonBy(0))
        assertEquals(0, s.setsWonBy(1))
        assertEquals(4, s.completedLegs.size)
    }

    /** Symmetric helper: drive B (index 1) to check out the current leg. */
    private fun bWinsLeg(s0: X01State): X01State {
        var s = s0
        if (s.currentPlayerIndex != 1) s = s.applyTurn(0)
        return s.applyTurn(40)
    }

    @Test
    fun twoSetMatch_splitSets_thirdSetDecides() {
        // first-to-2 legs/set, first-to-2 sets. A takes set 1, B takes set 2,
        // A takes set 3 -> A wins 2-1 in sets.
        var s = setMatch(start = 40, legsToWin = 2, setsToWin = 2, "A", "B")
        // Set 1 -> A (A wins L1 & L2)
        s = aWinsLeg(s); s = aWinsLeg(s)
        assertEquals(1, s.setsWonBy(0)); assertEquals(0, s.setsWonBy(1))
        // Set 2 -> B (B wins L1 & L2)
        s = bWinsLeg(s); s = bWinsLeg(s)
        assertFalse(s.isFinished)
        assertEquals(1, s.setsWonBy(0)); assertEquals(1, s.setsWonBy(1))
        assertEquals(0, s.legsWonBy(0)); assertEquals(0, s.legsWonBy(1))
        // Set 3 (decider) -> A wins -> match.
        s = aWinsLeg(s); s = aWinsLeg(s)
        assertTrue(s.isFinished)
        assertEquals(listOf(0), s.winnerIndices)
        assertEquals(2, s.setsWonBy(0)); assertEquals(1, s.setsWonBy(1))
    }

    @Test
    fun setMatch_undoAcrossSetBoundary_restoresPriorSet() {
        // After A wins set 1 (2 legs), undo should reopen the set-deciding leg
        // with the set un-won and legs back at the pre-checkout tally.
        var s = setMatch(start = 40, legsToWin = 2, setsToWin = 2, "A", "B")
        s = s.applyTurn(40)                       // S1 L1: A (legWins A=1)
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(0)                        // B throws L2
        s = s.applyTurn(40)                       // S1 L2: A -> SET 1 won by A
        assertEquals(1, s.setsWonBy(0))
        assertEquals(0, s.legsWonBy(0), "legs reset after set")
        assertEquals(2, s.completedLegs.size)
        assertTrue(s.perPlayer.all { it.turns.isEmpty() }, "fresh leg for set 2")

        val undone = s.undoLast()
        // Set 1 reopened: A back to 1 leg (the L1 win), the set un-won, the
        // deciding L2 restored as an in-progress leg with A on the checkout.
        assertEquals(0, undone.setsWonBy(0), "set win reversed")
        assertEquals(0, undone.setsWonBy(1))
        assertEquals(1, undone.legsWonBy(0), "back to A having 1 leg in the set")
        assertEquals(0, undone.legsWonBy(1))
        assertEquals(1, undone.completedLegs.size, "deciding leg popped")
        assertFalse(undone.isFinished)
        assertEquals(0, undone.currentPlayerIndex, "cursor on the leg winner (A)")
        // The L2 winning turn is restored on the board.
        assertEquals(0, undone.scoreFor(0))
        assertTrue(undone.perPlayer[0].turns.last().finished)
        // Starter rolled back from the set-2 starter to the deciding leg's starter.
        assertEquals(1, undone.startingPlayerIndex)
    }

    @Test
    fun setMatch_undoAcrossLegBoundaryWithinSet() {
        // Undo crossing a plain leg boundary inside a set must NOT touch setWins.
        var s = setMatch(start = 40, legsToWin = 2, setsToWin = 2, "A", "B")
        s = s.applyTurn(40)                       // S1 L1: A wins, leg2 started
        assertEquals(1, s.legsWonBy(0))
        assertEquals(0, s.setsWonBy(0))
        assertEquals(1, s.completedLegs.size)
        assertTrue(s.perPlayer.all { it.turns.isEmpty() })

        val undone = s.undoLast()
        assertEquals(0, undone.completedLegs.size)
        assertEquals(0, undone.legsWonBy(0), "leg win reversed within set")
        assertEquals(0, undone.setsWonBy(0), "sets untouched")
        assertEquals(0, undone.currentPlayerIndex)
        assertEquals(0, undone.startingPlayerIndex)
        assertFalse(undone.isFinished)
        assertEquals(0, undone.scoreFor(0), "A's checkout restored")
        assertTrue(undone.perPlayer[0].turns.single().finished)
    }

    @Test
    fun setMatch_undoMatchDecidingCheckout_reopensFinalSetAndLeg() {
        var s = setMatch(start = 40, legsToWin = 2, setsToWin = 2, "A", "B")
        // Set 1 -> A
        s = aWinsLeg(s); s = aWinsLeg(s)
        assertEquals(1, s.setsWonBy(0))
        // Set 2 -> A wins both legs -> match.
        s = aWinsLeg(s); s = aWinsLeg(s)
        assertTrue(s.isFinished)
        assertEquals(2, s.setsWonBy(0))
        val completedBefore = s.completedLegs.size

        val undone = s.undoLast()
        assertFalse(undone.isFinished, "match win cleared")
        assertTrue(undone.winnerIndices.isEmpty())
        assertEquals(1, undone.setsWonBy(0), "deciding set reversed to 1")
        assertEquals(1, undone.legsWonBy(0), "A back to 1 leg in the final set")
        assertEquals(completedBefore - 1, undone.completedLegs.size)
        assertEquals(0, undone.currentPlayerIndex)
        // A is back needing the final checkout (winning turn dropped from board).
        assertEquals(40, undone.scoreFor(0))
        assertTrue(undone.perPlayer[0].turns.isEmpty())
    }

    @Test
    fun setMatch_statsAggregateAcrossAllLegsOfAllSets() {
        // Two sets, each two legs A wins with 120. completedLegs accumulate across
        // sets, so allLegStatesFor sees every leg of every set.
        var s = setMatch(start = 120, legsToWin = 2, setsToWin = 2, "A", "B")
        fun aLeg120(st: X01State): X01State {
            var x = st
            if (x.currentPlayerIndex != 0) x = x.applyTurn(0)
            return x.applyTurn(120)
        }
        s = aLeg120(s); s = aLeg120(s)                // S1: A wins both -> set1
        s = aLeg120(s); s = aLeg120(s)                // S2: A wins both -> match
        assertTrue(s.isFinished)
        val legs = s.allLegStatesFor(0)
        assertEquals(4, legs.size, "4 legs across 2 sets, deciding leg not doubled")
        assertEquals(480, X01Stats.pointsScored(legs, 120))
        assertEquals(120, X01Stats.highestCheckout(legs))
    }

    @Test
    fun singleLeg_finishedViaApplyTurn_countsLegOnce() {
        // Regression: when a game finishes, applyTurn stores the deciding leg in
        // BOTH completedLegs and the live perPlayer (the duplication undoLast
        // relies on). allLegStatesFor must de-duplicate so stats count the leg
        // once — here a single 180, not two. Building the finished state via
        // applyTurn (not the constructor) is what exposes the bug.
        val finished = game(start = 180, doubleOut = false, "A", "B").applyTurn(180)
        assertTrue(finished.isFinished)
        assertEquals(1, finished.completedLegs.size)
        val legs = finished.allLegStatesFor(0)
        assertEquals(1, legs.size, "deciding leg must not be counted twice")
        assertEquals(180, X01Stats.pointsScored(legs, 180))
        assertEquals(180, X01Stats.highestTurn(legs))
    }
}
