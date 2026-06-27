package com.dartrack.feedback

import com.dartrack.model.GamePlayer
import com.dartrack.model.KillerState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for the pure state-transition -> FeedbackEvent mapping. */
class FeedbackEventsTest {

    private fun players(vararg names: String) = names.map { GamePlayer(it) }

    @Test
    fun nullOldOrUnchanged_isNull() {
        val s = X01State.new(players("A", "B"), 501, false, 1, 1)
        assertNull(feedbackEventFor(null, s))
        assertNull(feedbackEventFor(s, s))
    }

    @Test
    fun x01_normalTurn_isScoreConfirm() {
        val s = X01State.new(players("A", "B"), 501, false, 1, 1)
        val next = s.applyTurn(60)
        assertEquals(FeedbackEvent.SCORE_CONFIRM, feedbackEventFor(s, next))
    }

    @Test
    fun x01_oneEighty_isBigScore() {
        val s = X01State.new(players("A", "B"), 501, false, 1, 1)
        val next = s.applyTurn(180)
        assertEquals(FeedbackEvent.BIG_SCORE, feedbackEventFor(s, next))
    }

    @Test
    fun x01_bust_isBust() {
        // 101 with no double-out: entering 120 takes the score below 0 -> bust.
        val s = X01State.new(players("A", "B"), 101, false, 1, 1)
        val next = s.applyTurn(120)
        assertEquals(FeedbackEvent.BUST, feedbackEventFor(s, next))
    }

    @Test
    fun x01_singleLegFinish_isGameWin() {
        val s = X01State.new(players("A", "B"), 101, false, 1, 1)
        val next = s.applyTurn(101) // checkout
        assertEquals(FeedbackEvent.GAME_WIN, feedbackEventFor(s, next))
    }

    @Test
    fun x01_legWinNotMatch_isLegWin() {
        // First-to-3 legs: winning one leg completes a leg without ending the match.
        val s = X01State.new(players("A", "B"), 101, false, 3, 1)
        val next = s.applyTurn(101)
        assertEquals(FeedbackEvent.LEG_WIN, feedbackEventFor(s, next))
    }

    @Test
    fun killer_arming_isScoreConfirm() {
        val s = KillerState.new(players("A", "B"), 3)
        val next = s.applyTurn(listOf(0)) // A arms, no lives lost
        assertEquals(FeedbackEvent.SCORE_CONFIRM, feedbackEventFor(s, next))
    }

    @Test
    fun killer_damageWithoutFinish_isLifeLost() {
        var s = KillerState.new(players("A", "B"), 3)
        s = s.applyTurn(listOf(0))       // A arms
        val beforeHit = s.applyTurn(emptyList()) // B does nothing
        val afterHit = beforeHit.applyTurn(listOf(1)) // A hits B: 3 -> 2, not finished
        assertEquals(FeedbackEvent.LIFE_LOST, feedbackEventFor(beforeHit, afterHit))
    }

    @Test
    fun killer_finishingHit_isGameWin() {
        var s = KillerState.new(players("A", "B"), 1)
        s = s.applyTurn(listOf(0))       // A arms
        val preWin = s.applyTurn(emptyList()) // B
        val win = preWin.applyTurn(listOf(1)) // A kills B's last life -> win
        assertEquals(FeedbackEvent.GAME_WIN, feedbackEventFor(preWin, win))
    }

    @Test
    fun genericMode_finish_isGameWin_elseConfirm() {
        val s = ShanghaiState.new(players("A", "B"))
        val confirm = s.applyTurn(1, 0, 0) // 1 single, no instant win
        assertEquals(FeedbackEvent.SCORE_CONFIRM, feedbackEventFor(s, confirm))
        val instantWin = s.applyTurn(1, 1, 1) // Shanghai! instant win
        assertEquals(FeedbackEvent.GAME_WIN, feedbackEventFor(s, instantWin))
    }
}
