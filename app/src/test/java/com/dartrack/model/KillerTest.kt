package com.dartrack.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Killer: 2..4 players each assigned a distinct board number (20 - seat),
 * starting with [startLives] lives and not a killer. Hitting your own double makes
 * you a killer; once a killer you drain opponents' lives (and self-kill if you hit
 * your own number again on a later turn). Last player standing wins. Covers
 * promotion, damage gating, self-kill semantics, multi-hit turns, validation,
 * elimination + turn skipping, full games, and undo-by-replay.
 */
class KillerTest {

    private fun game(startLives: Int = KILLER_DEFAULT_LIVES, vararg names: String) =
        KillerState.new(names.map { GamePlayer(it) }, startLives)

    // ----------------------------------------------------------------- new()

    @Test
    fun new_assignsDistinctDescendingNumbers_fullLives_noKillers() {
        val s = game(3, "A", "B", "C", "D")
        assertEquals(listOf(20, 19, 18, 17), s.perPlayer.map { it.number })
        assertEquals(20, s.assignedNumber(0))
        assertEquals(17, s.assignedNumber(3))
        assertTrue(s.perPlayer.all { it.lives == 3 })
        assertTrue(s.perPlayer.none { it.isKiller })
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(4, s.aliveCount)
        assertFalse(s.isFinished)
        assertEquals(3, s.startLives)
    }

    @Test
    fun new_defaultStartLivesIsThree() {
        val s = KillerState.new(listOf(GamePlayer("A"), GamePlayer("B")))
        assertEquals(3, s.startLives)
        assertTrue(s.perPlayer.all { it.lives == 3 })
    }

    @Test
    fun new_supportsCustomStartLives() {
        val s = game(5, "A", "B")
        assertTrue(s.perPlayer.all { it.lives == 5 })
    }

    @Test
    fun new_rejectsTooFewPlayers() {
        assertFailsWith<IllegalArgumentException> {
            KillerState.new(listOf(GamePlayer("A")))
        }
    }

    @Test
    fun new_rejectsTooManyPlayers() {
        assertFailsWith<IllegalArgumentException> {
            KillerState.new((0..4).map { GamePlayer("P$it") })
        }
    }

    @Test
    fun new_rejectsStartLivesBelowOne() {
        assertFailsWith<IllegalArgumentException> { game(0, "A", "B") }
    }

    // ------------------------------------------------------- becoming a killer

    @Test
    fun hittingOwnDouble_becomesKiller_noSelfDamage() {
        var s = game(3, "A", "B")
        s = s.applyTurn(listOf(0)) // A hits own number -> becomes killer
        assertTrue(s.perPlayer[0].isKiller)
        assertEquals(3, s.perPlayer[0].lives, "no self-damage on the promoting dart")
        assertEquals(1, s.currentPlayerIndex)
    }

    @Test
    fun nonKiller_hittingOpponent_doesNothing() {
        var s = game(3, "A", "B")
        s = s.applyTurn(listOf(1)) // A (not a killer) hits B's number -> no effect
        assertFalse(s.perPlayer[0].isKiller)
        assertEquals(3, s.perPlayer[1].lives, "no damage before becoming a killer")
        assertEquals(1, s.currentPlayerIndex)
    }

    @Test
    fun killer_hittingOpponent_removesOneLife() {
        var s = game(3, "A", "B")
        s = s.applyTurn(listOf(0)) // A -> killer
        s = s.applyTurn(listOf(0)) // B -> killer
        s = s.applyTurn(listOf(1)) // A (killer) hits B -> B loses 1
        assertEquals(2, s.perPlayer[1].lives)
        assertTrue(s.perPlayer[0].isKiller)
    }

    @Test
    fun becomeKillerThenHitOpponent_sameTurn_damagesOpponent() {
        var s = game(3, "A", "B")
        // A becomes killer (hit own) THEN hits B in the same turn -> B loses 1.
        s = s.applyTurn(listOf(0, 1))
        assertTrue(s.perPlayer[0].isKiller)
        assertEquals(2, s.perPlayer[1].lives)
    }

    // --------------------------------------------------------------- self-kill

    @Test
    fun alreadyKiller_hittingOwn_selfKills() {
        var s = game(3, "A", "B")
        s = s.applyTurn(listOf(0)) // A -> killer
        s = s.applyTurn(emptyList()) // B misses
        s = s.applyTurn(listOf(0)) // A was already a killer, hits own -> self-kill
        assertEquals(2, s.perPlayer[0].lives)
        assertTrue(s.perPlayer[0].isKiller, "still a killer after self-kill")
    }

    @Test
    fun becomeKiller_thenHitOwnAgainSameTurn_noSelfDamage() {
        var s = game(3, "A", "B")
        // A hits own twice in one turn: first promotes, second is redundant (no damage).
        s = s.applyTurn(listOf(0, 0))
        assertTrue(s.perPlayer[0].isKiller)
        assertEquals(3, s.perPlayer[0].lives, "no self-damage on the turn you become killer")
    }

    @Test
    fun alreadyKiller_doubleOwnHit_selfKillsOnceEach() {
        var s = game(5, "A", "B")
        s = s.applyTurn(listOf(0))   // A -> killer
        s = s.applyTurn(emptyList()) // B
        s = s.applyTurn(listOf(0, 0)) // A already killer: two own hits -> lose 2
        assertEquals(3, s.perPlayer[0].lives)
    }

    // ------------------------------------------------------------- multi-hit

    @Test
    fun killer_doubleOpponentHit_removesTwoLives() {
        var s = game(5, "A", "B")
        s = s.applyTurn(listOf(0)) // A -> killer
        s = s.applyTurn(emptyList())
        s = s.applyTurn(listOf(1, 1)) // A hits B twice -> B loses 2
        assertEquals(3, s.perPlayer[1].lives)
    }

    @Test
    fun applyTurn_rejectsTooManyHits() {
        val s = game(3, "A", "B")
        assertFailsWith<IllegalArgumentException> { s.applyTurn(listOf(1, 1, 1, 1)) }
    }

    @Test
    fun applyTurn_rejectsBadIndex() {
        val s = game(3, "A", "B")
        assertFailsWith<IllegalArgumentException> { s.applyTurn(listOf(2)) }
        assertFailsWith<IllegalArgumentException> { s.applyTurn(listOf(-1)) }
    }

    @Test
    fun emptyHits_isValidMiss_justAdvances() {
        var s = game(3, "A", "B")
        s = s.applyTurn(emptyList())
        assertEquals(1, s.currentPlayerIndex)
        assertTrue(s.perPlayer.all { it.lives == 3 })
    }

    // ------------------------------------------------------------ elimination

    @Test
    fun reducingToZero_eliminates_andTurnSkips() {
        var s = game(1, "A", "B", "C")
        // A becomes killer, then we want A to knock out B so the turn order skips B.
        s = s.applyTurn(listOf(0))      // A(20) -> killer
        s = s.applyTurn(emptyList())    // B
        s = s.applyTurn(emptyList())    // C
        s = s.applyTurn(listOf(1))      // A kills B (B had 1 life) -> B eliminated
        assertTrue(s.isEliminated(1))
        assertEquals(0, s.perPlayer[1].lives)
        // Next alive after A is C (B is skipped).
        assertEquals(2, s.currentPlayerIndex)
        assertEquals(2, s.aliveCount)
        assertFalse(s.isFinished)
    }

    @Test
    fun livesNeverGoNegative_andHittingDeadOpponentNoUnderflow() {
        var s = game(1, "A", "B", "C")
        s = s.applyTurn(listOf(0)) // A -> killer
        s = s.applyTurn(emptyList())
        s = s.applyTurn(emptyList())
        s = s.applyTurn(listOf(1)) // A kills B -> B at 0
        assertEquals(0, s.perPlayer[1].lives)
        // C alive; C becomes killer then hits the already-dead B: no underflow.
        s = s.applyTurn(listOf(2)) // C -> killer
        s = s.applyTurn(listOf(1)) // A hits... wait, recompute order below
        // After C became killer, turn went to A; here A hits B (already 0): stays 0.
        assertEquals(0, s.perPlayer[1].lives, "dead opponent stays at 0, no underflow")
    }

    // ------------------------------------------------------------------- win

    @Test
    fun lastPlayerStanding_wins_andApplyTurnIsNoOp() {
        var s = game(1, "A", "B")
        s = s.applyTurn(listOf(0))   // A -> killer
        s = s.applyTurn(emptyList()) // B misses
        s = s.applyTurn(listOf(1))   // A kills B -> A wins
        assertTrue(s.isFinished)
        assertEquals(listOf(0), s.winnerIndices)
        assertEquals(0, s.perPlayer[1].lives)
        // applyTurn after finish is a no-op.
        assertEquals(s, s.applyTurn(listOf(0)))
    }

    @Test
    fun deterministic_twoPlayerGameToWin() {
        var s = game(3, "A", "B")
        s = s.applyTurn(listOf(0))       // A -> killer
        s = s.applyTurn(listOf(0))       // B -> killer
        s = s.applyTurn(listOf(1))       // A: B 3->2
        s = s.applyTurn(listOf(0))       // B: already killer, self-kill 3->2
        assertEquals(2, s.perPlayer[1].lives)
        assertEquals(3, s.perPlayer[0].lives)
        assertFalse(s.isFinished)
        s = s.applyTurn(listOf(1))       // A: B 2->1
        s = s.applyTurn(emptyList())     // B misses
        s = s.applyTurn(listOf(1))       // A: B 1->0 -> A wins
        assertTrue(s.isFinished)
        assertEquals(listOf(0), s.winnerIndices)
        assertEquals(0, s.perPlayer[1].lives)
        assertEquals(3, s.perPlayer[0].lives)
    }

    @Test
    fun fourPlayerGame_eliminationMidGame_skipsCorrectly() {
        var s = game(1, "A", "B", "C", "D")
        s = s.applyTurn(listOf(0))   // A -> killer (seat0=20)
        s = s.applyTurn(emptyList()) // B
        s = s.applyTurn(emptyList()) // C
        s = s.applyTurn(emptyList()) // D
        s = s.applyTurn(listOf(2))   // A kills C (index2) -> C out
        assertTrue(s.isEliminated(2))
        // After A, next alive is B (C not yet reached); D, then wrap skipping C.
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(emptyList()) // B
        assertEquals(3, s.currentPlayerIndex, "C is skipped; D is next")
        s = s.applyTurn(emptyList()) // D
        assertEquals(0, s.currentPlayerIndex, "wraps to A, skipping eliminated C")
        assertEquals(3, s.aliveCount)
    }

    @Test
    fun mutualElimination_endsAsDrawBetweenLastTwo_neverStuck() {
        // 2 players, 1 life each, both armed. A then self-kills (own) on the same
        // visit it drains B's last life (opp) -> both reach 0 at once -> draw.
        var s = game(1, "A", "B")
        s = s.applyTurn(listOf(0))   // A -> killer
        s = s.applyTurn(listOf(1))   // B -> killer
        // A is armed at turn start; [own, opp] = self-kill A to 0 AND kill B to 0.
        s = s.applyTurn(listOf(0, 1))
        assertEquals(0, s.perPlayer[0].lives)
        assertEquals(0, s.perPlayer[1].lives)
        assertEquals(0, s.aliveCount)
        assertTrue(s.isFinished, "a 0-alive board must still be a finished game, not stuck")
        assertEquals(listOf(0, 1), s.winnerIndices, "mutual elimination is a draw between both")
        assertEquals(s, s.applyTurn(listOf(0)), "finished game ignores further turns")
    }

    @Test
    fun mutualElimination_isUndoable() {
        var s = game(1, "A", "B")
        s = s.applyTurn(listOf(0))
        s = s.applyTurn(listOf(1))
        val preFinish = s
        s = s.applyTurn(listOf(0, 1))
        assertTrue(s.isFinished)
        val undone = s.undoLast()
        assertFalse(undone.isFinished, "draw undone")
        assertTrue(undone.winnerIndices.isEmpty())
        assertEquals(preFinish.perPlayer.map { it.lives }, undone.perPlayer.map { it.lives })
        assertEquals(1, undone.perPlayer[0].lives)
        assertEquals(1, undone.perPlayer[1].lives)
    }

    // ------------------------------------------------------------------ undo

    @Test
    fun undoLast_atStart_isNoOp() {
        val s = game(3, "A", "B")
        assertEquals(s, s.undoLast())
    }

    @Test
    fun undoLast_revertsStepByStep() {
        val s0 = game(3, "A", "B")
        val s1 = s0.applyTurn(listOf(0))       // A -> killer
        val s2 = s1.applyTurn(listOf(0))       // B -> killer
        val s3 = s2.applyTurn(listOf(1))       // A: B 3->2

        // Undo s3 -> back to s2 derived fields.
        val u3 = s3.undoLast()
        assertEquals(s2.perPlayer.map { it.lives }, u3.perPlayer.map { it.lives })
        assertEquals(s2.perPlayer.map { it.isKiller }, u3.perPlayer.map { it.isKiller })
        assertEquals(s2.currentPlayerIndex, u3.currentPlayerIndex)
        assertEquals(s2.winnerIndices, u3.winnerIndices)
        assertEquals(s2.turns, u3.turns)

        // Undo again -> back to s1.
        val u2 = u3.undoLast()
        assertEquals(s1.perPlayer.map { it.lives }, u2.perPlayer.map { it.lives })
        assertEquals(s1.perPlayer.map { it.isKiller }, u2.perPlayer.map { it.isKiller })
        assertEquals(s1.currentPlayerIndex, u2.currentPlayerIndex)

        // Undo again -> back to start.
        val u1 = u2.undoLast()
        assertEquals(s0.perPlayer.map { it.lives }, u1.perPlayer.map { it.lives })
        assertEquals(s0.perPlayer.map { it.isKiller }, u1.perPlayer.map { it.isKiller })
        assertEquals(0, u1.currentPlayerIndex)
        assertTrue(u1.turns.isEmpty())

        // Undo past the start is a no-op.
        assertEquals(u1, u1.undoLast())
    }

    @Test
    fun undoLast_unwindsCrossPlayerDamage() {
        var s = game(3, "A", "B")
        s = s.applyTurn(listOf(0)) // A -> killer
        s = s.applyTurn(listOf(0)) // B -> killer
        s = s.applyTurn(listOf(1)) // A damages B: 3 -> 2
        assertEquals(2, s.perPlayer[1].lives)
        val undone = s.undoLast()
        assertEquals(3, undone.perPlayer[1].lives, "undo restores the damaged opponent")
        assertTrue(undone.perPlayer[0].isKiller, "A's killer flag preserved through replay")
        assertEquals(0, undone.currentPlayerIndex, "turn returns to A")
    }

    @Test
    fun undoLast_afterWin_clearsWinnerAndRestoresPreFinish() {
        var s = game(1, "A", "B")
        s = s.applyTurn(listOf(0))   // A -> killer
        s = s.applyTurn(emptyList()) // B
        val preFinish = s
        s = s.applyTurn(listOf(1))   // A kills B -> win
        assertTrue(s.isFinished)
        val undone = s.undoLast()
        assertFalse(undone.isFinished, "win undone")
        assertTrue(undone.winnerIndices.isEmpty())
        assertEquals(preFinish.perPlayer.map { it.lives }, undone.perPlayer.map { it.lives })
        assertEquals(preFinish.currentPlayerIndex, undone.currentPlayerIndex)
        assertEquals(1, undone.perPlayer[1].lives)
    }

    @Test
    fun undoLast_unwindsElimination_restoresTurnOrder() {
        var s = game(1, "A", "B", "C")
        s = s.applyTurn(listOf(0))   // A -> killer
        s = s.applyTurn(emptyList()) // B
        s = s.applyTurn(emptyList()) // C
        s = s.applyTurn(listOf(1))   // A kills B -> B out, turn -> C
        assertTrue(s.isEliminated(1))
        assertEquals(2, s.currentPlayerIndex)
        val undone = s.undoLast()
        assertFalse(undone.isEliminated(1), "B restored to alive")
        assertEquals(1, undone.perPlayer[1].lives)
        assertEquals(3, undone.aliveCount)
        assertEquals(0, undone.currentPlayerIndex, "turn returns to A")
    }
}
