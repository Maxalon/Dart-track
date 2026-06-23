package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Cricket logic: mark accumulation, closing at 3 marks,
 * scoring while an opponent is still open, and win conditions.
 */
class CricketStateTest {

    private val json = GameJson.format

    private fun game(vararg names: String) =
        CricketState.new(names.map { GamePlayer(it) })

    // ------------------------------------------------------ marks / accumulation

    @Test
    fun marks_accumulateAcrossTurns() {
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 2)) // A
        s = s.applyTurn(mapOf(20 to 1)) // B
        s = s.applyTurn(mapOf(20 to 1)) // A -> 3 marks on 20
        val a = s.perPlayer[0]
        assertEquals(3, a.cumulativeMarks()[20])
        assertTrue(a.isClosed(20), "3 marks closes a target")
        assertFalse(a.isClosed(19))
    }

    @Test
    fun applyTurn_dropsZeroMarkEntries() {
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 2, 19 to 0))
        // zero-valued entries are filtered out of the stored turn
        assertEquals(mapOf(20 to 2), s.perPlayer[0].turns.single().marksByTarget)
    }

    @Test
    fun closing_requiresThreeMarks() {
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 2)) // A: 2 marks, not closed
        assertFalse(s.perPlayer[0].isClosed(20))
    }

    @Test
    fun turnRotation_advances() {
        var s = game("A", "B")
        assertEquals(0, s.currentPlayerIndex)
        s = s.applyTurn(mapOf(20 to 1))
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(mapOf(20 to 1))
        assertEquals(0, s.currentPlayerIndex)
    }

    // ------------------------------------------------------------------ scoring

    @Test
    fun scoring_pointsWhileOpponentOpen() {
        // A closes 20 (3 marks) then hits 2 more marks -> 2 * 20 = 40 points
        // while B has NOT closed 20.
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 3)) // A closes 20
        s = s.applyTurn(mapOf(19 to 1)) // B
        s = s.applyTurn(mapOf(20 to 2)) // A: 5 total -> 2 extra * 20 = 40
        assertEquals(40, s.scoreFor(0))
        assertEquals(0, s.scoreFor(1))
    }

    @Test
    fun scoring_noPointsOnceAllOpponentsClosed() {
        // Both close 20; A then hits extra marks on 20 -> no points because
        // the only opponent (B) has also closed 20.
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 3)) // A closes 20
        s = s.applyTurn(mapOf(20 to 3)) // B closes 20
        s = s.applyTurn(mapOf(20 to 3)) // A: 6 total, but B closed -> 0 points
        assertEquals(0, s.scoreFor(0))
    }

    @Test
    fun scoring_marksAtExactlyThreeScoreNothing() {
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 3)) // exactly closed, no extra
        s = s.applyTurn(mapOf(19 to 1))
        assertEquals(0, s.scoreFor(0))
    }

    @Test
    fun scoring_concreteTwoPlayerScenario() {
        // Deterministic full scenario with exact expected points.
        var s = game("A", "B")
        // Turn 1 A: triple 20 -> 3 marks, closes 20, 0 points
        s = s.applyTurn(mapOf(20 to 3))
        // Turn 1 B: triple 19 -> closes 19, 0 points
        s = s.applyTurn(mapOf(19 to 3))
        // Turn 2 A: triple 20 again -> 6 marks on 20, B has not closed 20 -> 3*20=60
        s = s.applyTurn(mapOf(20 to 3))
        // Turn 2 B: triple 19 again -> 6 marks on 19, A has not closed 19 -> 3*19=57
        s = s.applyTurn(mapOf(19 to 3))
        assertEquals(60, s.scoreFor(0))
        assertEquals(57, s.scoreFor(1))
        // Turn 3 A: single 19 x? Hit 20 once more -> 7 marks -> 4*20 = 80
        s = s.applyTurn(mapOf(20 to 1))
        assertEquals(80, s.scoreFor(0))
    }

    // ------------------------------------------------------------- win condition

    @Test
    fun win_whenAllClosedAndLeads() {
        // 2 players, A closes everything and scores; A should win once all closed.
        var s = game("A", "B")
        // Give A all 7 targets closed across turns, interleaving B no-ops.
        for (t in CRICKET_TARGETS) {
            s = s.applyTurn(mapOf(t to 3)) // A closes target t
            if (!s.isFinished) s = s.applyTurn(emptyMap()) // B passes
        }
        assertTrue(s.isFinished, "A closed all targets and leads -> win")
        assertEquals(listOf(0), s.winnerIndices)
    }

    @Test
    fun win_blockedWhenAllClosedButTrailing() {
        // "Closed all but trailing -> no win" needs THREE players: because
        // scoreFor() recomputes live and a target stops scoring once every
        // opponent has closed it, a 2-player game can never reach this state
        // (closing the last target zeroes the opponent's points on it). Here a
        // third player (C) keeps target 20 open, so B's 60 points persist even
        // after A closes 20. A closes all 7 targets but trails B -> A must NOT
        // win yet. State is built directly to set up the exact pre-win position.
        val a = CricketPlayerState(
            GamePlayer("A"),
            turns = listOf(20, 19, 18, 17, 16, 25).map { CricketTurn(mapOf(it to 3)) },
        ) // A has closed 6 targets; 15 is still open
        val b = CricketPlayerState(
            GamePlayer("B"),
            turns = listOf(CricketTurn(mapOf(20 to 3)), CricketTurn(mapOf(20 to 3))),
        ) // B: 6 marks on 20 -> (6-3)*20 = 60 points while an opponent keeps 20 open
        val c = CricketPlayerState(GamePlayer("C")) // C keeps every target open
        val s = CricketState(
            players = listOf(a.player, b.player, c.player),
            perPlayer = listOf(a, b, c),
            currentPlayerIndex = 0, // A to throw
        )
        // B is scoring 60 because C still has 20 open (A having closed 20 alone
        // is not enough to stop B's points).
        assertEquals(60, s.scoreFor(1))

        // A closes the last target (15). Now A has closed all 7 but scores 0,
        // trailing B's 60 -> the win must be blocked.
        val after = s.applyTurn(mapOf(15 to 3))
        assertTrue(after.perPlayer[0].hasClosedAll(), "A has closed all targets")
        assertFalse(after.isFinished, "all closed but trailing on points -> no win yet")
        assertEquals(0, after.scoreFor(0))
        assertEquals(60, after.scoreFor(1))
    }

    @Test
    fun win_tieCounts() {
        // Equal scores (both 0) with A closing all -> A wins on tie ("<=").
        var s = game("A", "B")
        for (t in CRICKET_TARGETS) {
            s = s.applyTurn(mapOf(t to 3)) // A closes target t (0 points, B open)
            // but scoring kicks in: A would score on extra marks only; 3 marks = closed, 0 pts
            if (!s.isFinished) s = s.applyTurn(emptyMap())
        }
        // A scored 0 (only ever 3 marks each), B scored 0 -> tie, A wins
        assertEquals(0, s.scoreFor(0))
        assertEquals(0, s.scoreFor(1))
        assertTrue(s.isFinished)
        assertEquals(listOf(0), s.winnerIndices)
    }

    @Test
    fun applyTurn_noOpAfterFinished() {
        var s = game("A", "B")
        for (t in CRICKET_TARGETS) {
            s = s.applyTurn(mapOf(t to 3))
            if (!s.isFinished) s = s.applyTurn(emptyMap())
        }
        assertTrue(s.isFinished)
        val again = s.applyTurn(mapOf(20 to 3))
        assertEquals(s, again)
    }

    @Test
    fun finish_keepsCurrentPlayerOnWinner() {
        var s = game("A", "B")
        for (t in CRICKET_TARGETS) {
            s = s.applyTurn(mapOf(t to 3))
            if (!s.isFinished) s = s.applyTurn(emptyMap())
        }
        assertEquals(0, s.currentPlayerIndex)
    }

    // ----------------------------------------------------------------- undoLast

    @Test
    fun undoLast_revertsLastTurn() {
        var s = game("A", "B")
        s = s.applyTurn(mapOf(20 to 2)) // A
        s = s.applyTurn(mapOf(19 to 2)) // B
        val undone = s.undoLast() // reverts B
        assertEquals(1, undone.currentPlayerIndex)
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        assertEquals(2, undone.perPlayer[0].cumulativeMarks()[20])
    }

    @Test
    fun undoLast_noTurns_isNoOp() {
        val s = game("A", "B")
        assertEquals(s, s.undoLast())
    }

    // ---------------------------------------------------------- cut-throat mode

    private fun cutThroatGame(vararg names: String) =
        CricketState.new(names.map { GamePlayer(it) }, cutThroat = true)

    @Test
    fun cutThroat_excessMarksPenalizeOpenOpponents_notThrower() {
        // A closes 20 then hits 2 extra marks while B (and C) are still open.
        // In cut-throat those 2*20 = 40 points are charged to EVERY opponent who
        // has not closed 20 — not to the thrower.
        var s = cutThroatGame("A", "B", "C")
        s = s.applyTurn(mapOf(20 to 3)) // A closes 20
        s = s.applyTurn(mapOf(19 to 1)) // B (leaves 20 open)
        s = s.applyTurn(mapOf(18 to 1)) // C (leaves 20 open)
        s = s.applyTurn(mapOf(20 to 2)) // A: 5 marks on 20 -> 2 extra * 20 = 40
        assertEquals(0, s.scoreFor(0), "the thrower is never penalised")
        assertEquals(40, s.scoreFor(1), "open opponent B is charged the excess")
        assertEquals(40, s.scoreFor(2), "open opponent C is charged the excess")
    }

    @Test
    fun cutThroat_noPenaltyToOpponentWhoClosed() {
        // B has also closed 20, so A's excess marks on 20 cannot be charged to B
        // (you only penalise opponents still OPEN on that number). C stays open.
        var s = cutThroatGame("A", "B", "C")
        s = s.applyTurn(mapOf(20 to 3)) // A closes 20
        s = s.applyTurn(mapOf(20 to 3)) // B closes 20
        s = s.applyTurn(emptyMap())     // C passes (20 still open)
        s = s.applyTurn(mapOf(20 to 2)) // A: 2 extra marks on 20
        assertEquals(0, s.scoreFor(1), "B already closed 20 -> no penalty")
        assertEquals(40, s.scoreFor(2), "C still open on 20 -> charged 2*20")
    }

    @Test
    fun cutThroat_win_whenAllClosedAndStrictlyLowest() {
        // Three players. A is about to close its last number (18). B is open on 20
        // and carries a penalty C handed out there, so B sits at 60 while A will
        // be 0. When A closes 18 it has closed-all AND is strictly below B -> wins.
        val a = CricketPlayerState(
            GamePlayer("A"),
            turns = listOf(19, 17, 16, 15, 25).map { CricketTurn(mapOf(it to 3)) },
        ) // A closed five; still OPEN on 20 and 18
        val b = CricketPlayerState(
            GamePlayer("B"),
            turns = listOf(CricketTurn(mapOf(19 to 3))), // B closed 19 only -> open on 20
        )
        val c = CricketPlayerState(
            GamePlayer("C"),
            turns = listOf(CricketTurn(mapOf(20 to 3)), CricketTurn(mapOf(20 to 3))),
        ) // C: 6 marks on 20 -> open players (A,B) charged (6-3)*20 = 60 each
        val s = CricketState(
            players = listOf(a.player, b.player, c.player),
            perPlayer = listOf(a, b, c),
            currentPlayerIndex = 0, // A to throw
            cutThroat = true,
        )
        assertEquals(60, s.scoreFor(0), "A still open on 20 -> charged 60")
        assertEquals(60, s.scoreFor(1), "B still open on 20 -> charged 60")
        // A closes 20 (drops its own 60) and 18 (its last) in one turn -> closed-all
        // with score 0, while B remains open on 20 and keeps the 60 penalty.
        val after = s.applyTurn(mapOf(20 to 3, 18 to 3))
        assertTrue(after.perPlayer[0].hasClosedAll(), "A has closed all targets")
        assertEquals(0, after.scoreFor(0), "A closed everything -> no open number to be penalised on")
        assertEquals(60, after.scoreFor(1), "B still open on 20 -> still carries 60")
        assertTrue(after.isFinished, "A closed all and is strictly lowest -> win")
        assertEquals(listOf(0), after.winnerIndices)
    }

    @Test
    fun cutThroat_closingAllZeroesYourScore_soClosingPlayerNeverTrails() {
        // Cut-throat invariant: you are only penalised on numbers you have NOT
        // closed, so a player who has closed ALL of them is open on nothing and
        // therefore always sits at the minimum possible score (0). This is the
        // cut-throat analogue of "leads/ties" — the closer can never be strictly
        // above an opponent. Built directly from a heavily-penalised position to
        // show the penalty fully evaporates the instant the last number closes.
        val a = CricketPlayerState(
            GamePlayer("A"),
            turns = listOf(20, 19, 18, 17, 16, 25).map { CricketTurn(mapOf(it to 3)) },
        ) // A closed six; 15 still open
        val b = CricketPlayerState(
            GamePlayer("B"),
            turns = listOf(CricketTurn(mapOf(15 to 3)), CricketTurn(mapOf(15 to 3))),
        ) // B: 6 marks on 15 -> A (open on 15) is charged (6-3)*15 = 45
        val s = CricketState(
            players = listOf(a.player, b.player),
            perPlayer = listOf(a, b),
            currentPlayerIndex = 0, // A to throw
            cutThroat = true,
        )
        assertEquals(45, s.scoreFor(0), "A open on 15 -> charged B's excess")
        // A closes 15 -> now closed-all; A is open on nothing -> score 0, and
        // wins as the lowest (B is also 0 here -> tie, allowed).
        val after = s.applyTurn(mapOf(15 to 3))
        assertTrue(after.perPlayer[0].hasClosedAll(), "A has closed all targets")
        assertEquals(0, after.scoreFor(0), "closing all leaves no open number to be penalised on")
        assertTrue(after.isFinished)
        assertEquals(listOf(0), after.winnerIndices)
    }

    @Test
    fun cutThroat_noWinWhileStillOpen_evenIfCurrentlyLowest() {
        // The win predicate is gated on hasClosedAll() FIRST. A player can be the
        // current low scorer yet not have closed everything -> no win. Here C is
        // open on everything and carries B's penalty; A is the lowest (0) but is
        // itself still open on 18, so closing nothing this turn must not win.
        val a = CricketPlayerState(
            GamePlayer("A"),
            turns = listOf(20, 19, 17, 16, 15, 25).map { CricketTurn(mapOf(it to 3)) },
        ) // A closed six; 18 still open -> A is NOT closed-all
        val b = CricketPlayerState(
            GamePlayer("B"),
            turns = listOf(CricketTurn(mapOf(18 to 3)), CricketTurn(mapOf(18 to 3))),
        ) // B: 6 marks on 18 -> open players (A,C) charged (6-3)*18 = 54
        val c = CricketPlayerState(GamePlayer("C")) // open on everything
        val s = CricketState(
            players = listOf(a.player, b.player, c.player),
            perPlayer = listOf(a, b, c),
            currentPlayerIndex = 0, // A to throw
            cutThroat = true,
        )
        assertEquals(54, s.scoreFor(0), "A open on 18 -> charged 54")
        // A throws but does NOT close 18 (hits 19 again, already closed -> no-op
        // for closing). A still open on 18 -> hasClosedAll() false -> no win even
        // though, were it closed, A would tie for lowest.
        val after = s.applyTurn(mapOf(19 to 1))
        assertFalse(after.perPlayer[0].hasClosedAll(), "A still open on 18")
        assertFalse(after.isFinished, "not closed-all -> cannot win regardless of score")
        assertTrue(after.winnerIndices.isEmpty())
    }

    @Test
    fun cutThroat_win_tieForLowestStillWins() {
        // A that closes all while TIED for the lowest score still wins: the
        // predicate is "<= every opponent" (ties allowed), mirroring standard
        // Cricket's "ties count". winnerIndices is a List<Int> so it can express
        // co-winners, but sequential play freezes on the first closer-and-low, so
        // the realistic outcome of a tie is the first finisher taking it.
        var s = cutThroatGame("A", "B")
        // A closes all 7 (one target per turn), B mirrors so both stay at 0.
        for (t in CRICKET_TARGETS) {
            s = s.applyTurn(mapOf(t to 3)) // A closes t (always 0 excess)
            if (!s.isFinished) s = s.applyTurn(mapOf(t to 3)) // B closes t too
        }
        assertEquals(0, s.scoreFor(0), "A never scored excess -> 0")
        assertEquals(0, s.scoreFor(1), "B tied at 0")
        assertTrue(s.isFinished, "A closed all and ties B for lowest -> tie does not block the win")
        assertEquals(listOf(0), s.winnerIndices, "A closed all first; tie still yields the win")
    }

    // -------------------------------------------------- standard-mode regression

    @Test
    fun standard_regression_excessMarksRewardThrower_highestWins() {
        // With cutThroat=false the original behaviour must be byte-for-byte:
        // excess marks add to the THROWER while an opponent is open, and the
        // higher score (with all closed) wins.
        var s = game("A", "B") // game() uses default cutThroat=false
        assertFalse(s.cutThroat, "default variant is standard Cricket")
        s = s.applyTurn(mapOf(20 to 3)) // A closes 20
        s = s.applyTurn(mapOf(19 to 1)) // B open on 20
        s = s.applyTurn(mapOf(20 to 2)) // A: 2 extra * 20 = 40 to A
        assertEquals(40, s.scoreFor(0), "thrower scores in standard")
        assertEquals(0, s.scoreFor(1), "opponent is not penalised in standard")
    }

    @Test
    fun standard_regression_winGoesToHighestWithAllClosed() {
        // A closes all 7 while leading -> A wins (highest), matching today.
        var s = game("A", "B")
        for (t in CRICKET_TARGETS) {
            s = s.applyTurn(mapOf(t to 3))
            if (!s.isFinished) s = s.applyTurn(emptyMap())
        }
        assertTrue(s.isFinished)
        assertEquals(listOf(0), s.winnerIndices)
    }

    // ----------------------------------------------------------------- JSON

    @Test
    fun jsonRoundTrip_cutThroatTrue_preservesFlag() {
        val json = GameJson.format
        var state = cutThroatGame("Alice", "Bob")
        state = state.applyTurn(mapOf(20 to 3)) // Alice closes 20
        state = state.applyTurn(mapOf(19 to 1)) // Bob
        val record = GameRecord(
            id = "cricket-ct-1",
            mode = state.toMode(),
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        assertTrue(text.contains("\"type\":\"cricket\""), "expected serial name: $text")
        assertTrue(text.contains("\"cutThroat\":true"), "flag should serialize: $text")
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        assertEquals(GameMode.CRICKET, decoded.mode)
        assertTrue((decoded.state as CricketState).cutThroat)
    }

    @Test
    fun legacyCricketJson_withoutCutThroatField_decodesToFalse() {
        // Simulates a game persisted BEFORE the cut-throat variant: no cutThroat
        // field. ignoreUnknownKeys + the default must decode it to standard.
        val legacy = """
            {"id":"old","mode":"CRICKET","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "state":{"type":"cricket",
               "players":[{"name":"Alice"},{"name":"Bob"}],
               "perPlayer":[{"player":{"name":"Alice"},"turns":[]},
                            {"player":{"name":"Bob"},"turns":[]}],
               "currentPlayerIndex":0,"winnerIndices":[]}}
        """.trimIndent()
        val decoded = json.decodeFromString(GameRecord.serializer(), legacy)
        val state = decoded.state as CricketState
        assertFalse(state.cutThroat, "legacy data defaults to standard Cricket")
        // Still fully playable as standard Cricket.
        val after = state.applyTurn(mapOf(20 to 3))
        assertTrue(after.perPlayer[0].isClosed(20))
    }
}
