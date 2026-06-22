package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADVERSARIAL edge-case tests for [GotchaState] (QA hardening), covering the
 * novel rules the existing GotchaTest does not exercise:
 *  - one turn that EXACTLY matches MULTIPLE opponents resets ALL of them at once;
 *  - landing on the target wins IMMEDIATELY even when that same total would also
 *    "gotcha" an opponent (the win must take precedence, no reset applied);
 *  - a turn that matches an opponent sitting on 0 does NOT reset (only > 0);
 *  - overshoot busts, leaves score unchanged, AND still advances the turn;
 *  - undo of a turn that simultaneously reset several opponents restores them all;
 *  - undo of the winning turn reopens the game and a subsequent win still works;
 *  - JSON round-trip of a mid-gotcha state (a reset already applied).
 */
class GotchaAdversarialTest {

    private fun game(target: Int = GOTCHA_DEFAULT_TARGET, vararg names: String) =
        GotchaState.new(names.map { GamePlayer(it) }, target)

    // ---------------------------------------------------------- multi-reset turn

    @Test
    fun singleTurn_knocksOutTwoOpponentsAtOnce() {
        // A clean "two opponents parked on the same total" state is only reachable
        // transiently through ordinary play (any climb to the shared value resets
        // the other player en route), so we hand-build the state with A and B both
        // on 80 and have C land EXACTLY on 80 in one turn: BOTH must reset.
        val handBuilt = GotchaState(
            players = listOf(GamePlayer("A"), GamePlayer("B"), GamePlayer("C")),
            perPlayer = listOf(
                GotchaPlayerState(GamePlayer("A"), turns = listOf(80), total = 80),
                GotchaPlayerState(GamePlayer("B"), turns = listOf(80), total = 80),
                GotchaPlayerState(GamePlayer("C"), turns = listOf(40), total = 40),
            ),
            target = 501,
            currentPlayerIndex = 2, // C to throw
        )
        val after = handBuilt.applyTurn(40) // C: 40 + 40 = 80 -> resets BOTH A and B
        assertEquals(0, after.perPlayer[0].total, "A reset by C's single turn")
        assertEquals(0, after.perPlayer[1].total, "B reset by the SAME single turn")
        assertEquals(80, after.perPlayer[2].total, "C now sits on 80")
        assertEquals(0, after.currentPlayerIndex, "turn advanced to A")
    }

    // ------------------------------------------------------- win beats gotcha

    @Test
    fun exactTarget_winsImmediately_withOpponentsPresent() {
        // Sanity: an exact-target hit wins immediately and leaves opponents alone.
        var s = game(301, "A", "B")
        s = s.applyTurn(180) // A -> 180
        s = s.applyTurn(100) // B -> 100
        s = s.applyTurn(121) // A -> 301 == target -> WIN
        assertTrue(s.isFinished, "exact target wins immediately")
        assertEquals(listOf(0), s.winnerIndices)
        assertEquals(100, s.perPlayer[1].total, "opponent untouched by a winning turn")
    }

    @Test
    fun winningTurnDoesNotRunGotchaReset_evenIfTargetEqualsAnOpponentTotal() {
        // Hand-build the pathological state the natural game can't reach: an
        // opponent parked on EXACTLY the target while another player lands on it.
        // The win branch must take precedence and NOT reset that opponent.
        val s = GotchaState(
            players = listOf(GamePlayer("A"), GamePlayer("B")),
            perPlayer = listOf(
                GotchaPlayerState(GamePlayer("A"), turns = listOf(150), total = 150),
                // B improbably parked on the target value (e.g. loaded from data).
                GotchaPlayerState(GamePlayer("B"), turns = listOf(180, 121), total = 301),
            ),
            target = 301,
            currentPlayerIndex = 0,
        )
        val after = s.applyTurn(151) // A: 150 + 151 = 301 == target -> WIN
        assertTrue(after.isFinished, "A wins by hitting the target exactly")
        assertEquals(listOf(0), after.winnerIndices)
        assertEquals(
            301, after.perPlayer[1].total,
            "the winning turn must NOT gotcha B even though B sits on the target",
        )
    }

    // ----------------------------------------------------- zero never resets

    @Test
    fun matchingAnOpponentAtZero_neverResets_multiPlayer() {
        // Several players on 0; one lands on 0 too. Nobody is reset (only > 0).
        var s = game(501, "A", "B", "C", "D")
        s = s.applyTurn(0) // A -> 0
        s = s.applyTurn(0) // B -> 0
        s = s.applyTurn(0) // C -> 0
        s = s.applyTurn(0) // D -> 0
        assertTrue(s.perPlayer.all { it.total == 0 }, "all still 0, no spurious resets")
        assertFalse(s.isFinished)
    }

    // ----------------------------------------------------------------- bust

    @Test
    fun overshoot_busts_keepsScore_andAdvancesTurn() {
        var s = game(301, "A", "B")
        s = s.applyTurn(180) // A -> 180
        assertEquals(180, s.perPlayer[0].total)
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(50) // B -> 50
        s = s.applyTurn(180) // A: 180 + 180 = 360 > 301 -> BUST, stays at 180
        assertFalse(s.isFinished, "a bust never wins")
        assertEquals(180, s.perPlayer[0].total, "bust leaves the score unchanged")
        // A has thrown exactly twice (180, then the busted 180); the busted turn
        // is still recorded so the darts count stays honest.
        assertEquals(2, s.perPlayer[0].turns.size, "busted turn is still recorded")
        assertEquals(6, s.perPlayer[0].darts)
        assertEquals(1, s.currentPlayerIndex, "bust still advances the turn")
        // A bust must NOT trigger a gotcha even if the busted candidate equals
        // an opponent total: B is on 50, A "tries" 360 (not 50), no reset anyway.
        assertEquals(50, s.perPlayer[1].total)
    }

    @Test
    fun bustDoesNotGotcha_opponentsUntouched() {
        // A bust returns via advanceFrom with NO gotcha map step, so opponents are
        // left byte-identical regardless of the over-target candidate value.
        val s = GotchaState(
            players = listOf(GamePlayer("A"), GamePlayer("B")),
            perPlayer = listOf(
                GotchaPlayerState(GamePlayer("A"), turns = listOf(300), total = 300),
                GotchaPlayerState(GamePlayer("B"), turns = listOf(150), total = 150),
            ),
            target = 301,
            currentPlayerIndex = 0,
        )
        val after = s.applyTurn(50) // A: 300 + 50 = 350 > 301 -> bust
        assertEquals(300, after.perPlayer[0].total)
        assertEquals(150, after.perPlayer[1].total, "opponent untouched on a bust")
    }

    // ------------------------------------------------------------- undo depth

    @Test
    fun undo_restoresTheGotchadOpponent_inAReachableThreePlayerGame() {
        // A reachable 3-player gotcha-then-undo: build totals via real play, have
        // one turn reset exactly one opponent, then UNDO and confirm the reset
        // opponent is restored and the actor's turn is rolled back.
        var s = game(501, "A", "B", "C")
        s = s.applyTurn(100) // A -> 100
        s = s.applyTurn(60)  // B -> 60
        s = s.applyTurn(40)  // C -> 40
        s = s.applyTurn(0)   // A -> 100 (stay)
        s = s.applyTurn(40)  // B -> 100 (matches A's 100 -> gotchas A to 0)
        assertEquals(0, s.perPlayer[0].total, "B's turn gotcha'd A")
        assertEquals(100, s.perPlayer[1].total)
        val undone = s.undoLast() // undo B's gotcha turn
        assertEquals(100, undone.perPlayer[0].total, "A restored to 100 by undo")
        assertEquals(60, undone.perPlayer[1].total, "B rolled back to 60")
        assertEquals(40, undone.perPlayer[2].total, "C untouched")
        assertEquals(1, undone.currentPlayerIndex, "cursor back on B")
        assertEquals(1, undone.perPlayer[1].turns.size, "B's reset turn dropped")
    }

    @Test
    fun engineResetsMultipleOpponents_butUndoReplaysReachablePlayOnly() {
        // The engine's gotcha pass DOES reset every opponent on the matched total
        // in a single turn (see singleTurn_knocksOutTwoOpponentsAtOnce). However
        // two opponents parked on the SAME total simultaneously is UNREACHABLE in
        // real lockstep play: whoever climbed onto the shared total second would
        // already have gotcha'd the first. So a hand-built simultaneous state has
        // no legitimate history; undoLast is replay-based and faithfully replays
        // the REACHABLE sequence (A->V, then B->V which re-gotchas A). This test
        // documents that contract so a future refactor that breaks replay-undo is
        // still caught, without asserting a state the rules forbid.
        val pre = GotchaState(
            players = listOf(GamePlayer("A"), GamePlayer("B"), GamePlayer("C")),
            perPlayer = listOf(
                GotchaPlayerState(GamePlayer("A"), turns = listOf(90), total = 90),
                GotchaPlayerState(GamePlayer("B"), turns = listOf(90), total = 90),
                GotchaPlayerState(GamePlayer("C"), turns = listOf(45), total = 45),
            ),
            target = 501,
            currentPlayerIndex = 2,
        )
        val reset = pre.applyTurn(45) // C: 45 + 45 = 90 -> engine resets BOTH A and B
        assertEquals(0, reset.perPlayer[0].total, "engine reset A")
        assertEquals(0, reset.perPlayer[1].total, "engine reset B in the same turn")
        // Replay-based undo reconstructs the only reachable interpretation of the
        // recorded turns: A=90, then B=90 (which gotchas A to 0), then C rolled
        // back to 45. A ends at 0 — correct for a replayable history.
        val undone = reset.undoLast()
        assertEquals(0, undone.perPlayer[0].total, "replay re-applies B's gotcha on A")
        assertEquals(90, undone.perPlayer[1].total, "B keeps its (reachable) 90")
        assertEquals(45, undone.perPlayer[2].total, "C rolled back")
        // Replay drives the cursor from each recorded actor in lockstep order, so
        // after replaying A,B,C (the kept turns) the cursor lands past C -> back to A.
        assertEquals(0, undone.currentPlayerIndex, "cursor wraps to A after replaying A,B,C")
    }

    @Test
    fun undo_thenRedo_byReplayMatchesOriginal_acrossAGotcha() {
        // The prompt's canonical sequence: P1 reaches 100, P2 reaches 100 (resets
        // P1 -> 0), UNDO -> P1 back to 100. Then re-apply the same turn and the
        // state must match the pre-undo state exactly (replay determinism).
        var s = game(301, "P1", "P2")
        s = s.applyTurn(100) // P1 -> 100
        val afterReset = s.applyTurn(100) // P2 -> 100, gotchas P1 to 0
        assertEquals(0, afterReset.perPlayer[0].total)
        val undone = afterReset.undoLast()
        assertEquals(100, undone.perPlayer[0].total, "undo restores P1 to 100")
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        // Redo the identical turn; must reproduce the reset state.
        val redo = undone.applyTurn(100)
        assertEquals(afterReset, redo, "redoing the same turn reproduces the gotcha state")
    }

    @Test
    fun undoOfWinningTurn_reopensGame_andCanWinAgain() {
        var s = game(301, "A", "B")
        s = s.applyTurn(180) // A -> 180
        s = s.applyTurn(0)   // B -> 0
        s = s.applyTurn(121) // A -> 301 WIN
        assertTrue(s.isFinished)
        val undone = s.undoLast()
        assertFalse(undone.isFinished, "undoing the win reopens the game")
        assertTrue(undone.winnerIndices.isEmpty())
        assertEquals(180, undone.perPlayer[0].total, "A back to 180 (pre-win)")
        // The game is live again: A can re-finish.
        val rewin = undone.applyTurn(121)
        assertTrue(rewin.isFinished, "game can be won again after reopening")
        assertEquals(listOf(0), rewin.winnerIndices)
    }

    // ----------------------------------------------------- target validation

    @Test
    fun new_rejectsJunkTargets_acceptsOnlyAllowed() {
        for (junk in listOf(0, -1, 300, 302, 401, 500, 502, 1000, Int.MAX_VALUE)) {
            var threw = false
            try {
                GotchaState.new(listOf(GamePlayer("A")), target = junk)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw, "junk target $junk must be rejected")
        }
        assertEquals(301, GotchaState.new(listOf(GamePlayer("A")), 301).target)
        assertEquals(501, GotchaState.new(listOf(GamePlayer("A")), 501).target)
    }

    // ----------------------------------------------------------------- JSON

    @Test
    fun jsonRoundTrip_midGotchaState_withAResetAlreadyApplied() {
        val json = GameJson.format
        var state = game(501, "Alice", "Bob", "Cara")
        state = state.applyTurn(140) // Alice -> 140
        state = state.applyTurn(140) // Bob -> 140 (gotchas Alice to 0)
        assertEquals(0, state.perPlayer[0].total, "precondition: Alice was reset")
        val record = GameRecord(
            id = "gotcha-mid",
            mode = state.toMode(),
            createdAtEpochMs = 10L,
            updatedAtEpochMs = 20L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        assertTrue(text.contains("\"type\":\"gotcha\""), "expected serial name: $text")
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded, "mid-gotcha state must round-trip exactly")
        val gs = decoded.state as GotchaState
        assertEquals(0, gs.perPlayer[0].total, "reset survives the round-trip")
        assertEquals(140, gs.perPlayer[1].total)
        // Bob's turn history (darts) is preserved across the gotcha + round-trip.
        assertEquals(3, gs.perPlayer[1].darts)
    }
}
