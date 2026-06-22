package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Checkout Trainer: a finishing drill over a ladder of double-out
 * checkout targets. Every player attempts the SAME ordered targets, round-robin
 * BY TARGET (round R = every seat attempts targets[R] once, in order, then the
 * ladder advances). Each attempt is HIT (with 1..3 darts used) or MISS. Score =
 * hits; winner = most hits, tie-break fewest darts on hits (remaining ties ->
 * multiple winners). Undo replays from scratch. Also covers the require guards,
 * the dart-range guard, that every default target is a finishable checkout, and
 * JSON round-trip.
 *
 * NOTE: assertEquals here is kotlin.test.assertEquals, where the optional message
 * is the LAST argument.
 */
class CheckoutTrainerTest {

    private fun game(vararg names: String, targets: List<Int> = DEFAULT_TARGETS) =
        CheckoutTrainerState.new(names.map { GamePlayer(it) }, targets)

    @Test
    fun constants_areExpected() {
        assertEquals(3, CHECKOUT_TRAINER_MAX_DARTS)
        assertEquals(listOf(40, 60, 80, 100, 120, 140, 160, 170), DEFAULT_TARGETS)
    }

    @Test
    fun startState_zeroHits_firstPlayerOnFirstTarget() {
        val s = game("A", "B")
        assertEquals(0, s.perPlayer[0].hits)
        assertEquals(0, s.perPlayer[1].hits)
        assertEquals(0, s.currentTargetIndex)
        assertEquals(40, s.currentTarget)
        assertEquals(0, s.currentPlayerIndex)
        assertFalse(s.isFinished)
    }

    // ------------------------------------------------------------ scoring

    @Test
    fun hitIncrementsScore_missDoesNot() {
        // Single player so each attempt advances to the next target.
        var s = game("A", targets = listOf(40, 60, 80))
        s = s.applyAttempt(hit = true, darts = 2)  // hit target 40
        assertEquals(1, s.perPlayer[0].hits)
        assertEquals(2, s.perPlayer[0].dartsOnHits)
        s = s.applyAttempt(hit = false, darts = 3) // miss target 60
        assertEquals(1, s.perPlayer[0].hits, "a miss does not increment hits")
        // darts on a miss are not tracked.
        assertEquals(2, s.perPlayer[0].dartsOnHits)
        assertEquals(2, s.perPlayer[0].attempts.size)
    }

    @Test
    fun missRecordsZeroDarts_evenWhenDartsPassed() {
        val s = game("A", targets = listOf(40)).applyAttempt(hit = false, darts = 3)
        assertEquals(0, s.perPlayer[0].attempts[0].darts, "miss normalises darts to 0")
        assertFalse(s.perPlayer[0].attempts[0].hit)
    }

    // ------------------------------------------------- round-robin rotation

    @Test
    fun targetAdvancesOnlyAfterLastSeat() {
        var s = game("A", "B", targets = listOf(40, 60))
        assertEquals(0, s.currentTargetIndex)
        s = s.applyAttempt(hit = true, darts = 1) // A on 40, still target 0
        assertEquals(0, s.currentTargetIndex)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(40, s.currentTarget, "B still on target 40")
        s = s.applyAttempt(hit = false, darts = 0) // B on 40 -> advance to target 60
        assertEquals(1, s.currentTargetIndex)
        assertEquals(0, s.currentPlayerIndex, "back to seat 0 on the new target")
        assertEquals(60, s.currentTarget)
    }

    @Test
    fun seatRotationWrapsInOrder() {
        var s = game("A", "B", "C", targets = listOf(40, 60))
        s = s.applyAttempt(hit = true, darts = 1)  // A
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyAttempt(hit = true, darts = 1)  // B
        assertEquals(2, s.currentPlayerIndex)
        s = s.applyAttempt(hit = true, darts = 1)  // C wraps -> next target, seat 0
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(1, s.currentTargetIndex)
    }

    // ----------------------------------------------------------- finishing

    @Test
    fun gameFinishesAfterLastTarget() {
        var s = game("A", "B", targets = listOf(40, 60))
        assertFalse(s.isFinished)
        s = s.applyAttempt(hit = true, darts = 1)  // A 40
        s = s.applyAttempt(hit = true, darts = 1)  // B 40 -> target 60
        assertFalse(s.isFinished, "not finished mid-ladder")
        s = s.applyAttempt(hit = true, darts = 1)  // A 60
        assertFalse(s.isFinished)
        s = s.applyAttempt(hit = true, darts = 1)  // B 60 (last seat, last target)
        assertTrue(s.isFinished, "finishes after the final target's round")
        // Each player attempted both targets exactly once.
        assertEquals(2, s.perPlayer[0].attempts.size)
        assertEquals(2, s.perPlayer[1].attempts.size)
    }

    @Test
    fun applyAttempt_noOpAfterFinished() {
        var s = game("A", targets = listOf(40))
        s = s.applyAttempt(hit = true, darts = 1)
        assertTrue(s.isFinished)
        assertEquals(s, s.applyAttempt(hit = true, darts = 2))
    }

    // -------------------------------------------------------------- winner

    @Test
    fun winnerByMostHits() {
        var s = game("A", "B", targets = listOf(40, 60))
        s = s.applyAttempt(hit = true, darts = 1)  // A 40 hit
        s = s.applyAttempt(hit = false, darts = 0) // B 40 miss
        s = s.applyAttempt(hit = true, darts = 1)  // A 60 hit
        s = s.applyAttempt(hit = false, darts = 0) // B 60 miss
        assertTrue(s.isFinished)
        assertEquals(2, s.perPlayer[0].hits)
        assertEquals(0, s.perPlayer[1].hits)
        assertEquals(listOf(0), s.winnerIndices, "most hits wins")
    }

    @Test
    fun tieBreakByFewestDartsOnHits() {
        // Both players hit both targets, but A used fewer darts -> A wins.
        var s = game("A", "B", targets = listOf(40, 60))
        s = s.applyAttempt(hit = true, darts = 1)  // A 40, 1 dart
        s = s.applyAttempt(hit = true, darts = 3)  // B 40, 3 darts
        s = s.applyAttempt(hit = true, darts = 1)  // A 60, 1 dart
        s = s.applyAttempt(hit = true, darts = 3)  // B 60, 3 darts
        assertTrue(s.isFinished)
        assertEquals(s.perPlayer[0].hits, s.perPlayer[1].hits, "equal hits")
        assertEquals(2, s.perPlayer[0].dartsOnHits)
        assertEquals(6, s.perPlayer[1].dartsOnHits)
        assertEquals(listOf(0), s.winnerIndices, "tie broken by fewest darts on hits")
    }

    @Test
    fun plainTie_recordsMultipleWinners() {
        // Equal hits AND equal darts on hits -> both win.
        var s = game("A", "B", targets = listOf(40, 60))
        s = s.applyAttempt(hit = true, darts = 2)  // A 40
        s = s.applyAttempt(hit = true, darts = 2)  // B 40
        s = s.applyAttempt(hit = true, darts = 2)  // A 60
        s = s.applyAttempt(hit = true, darts = 2)  // B 60
        assertTrue(s.isFinished)
        assertEquals(s.perPlayer[0].dartsOnHits, s.perPlayer[1].dartsOnHits)
        assertEquals(listOf(0, 1), s.winnerIndices, "full tie records all top players")
    }

    @Test
    fun allMisses_tieOnZeroHits() {
        var s = game("A", "B", targets = listOf(40))
        s = s.applyAttempt(hit = false, darts = 0) // A
        s = s.applyAttempt(hit = false, darts = 0) // B
        assertTrue(s.isFinished)
        assertEquals(0, s.perPlayer[0].hits)
        assertEquals(0, s.perPlayer[1].hits)
        // 0 hits each, 0 darts on hits each -> both "win" the wooden spoon.
        assertEquals(listOf(0, 1), s.winnerIndices)
    }

    // ---------------------------------------------------------------- undo

    @Test
    fun undoLast_revertsWithinTarget() {
        var s = game("A", "B", targets = listOf(40, 60))
        s = s.applyAttempt(hit = true, darts = 1)  // A 40
        s = s.applyAttempt(hit = true, darts = 2)  // B 40 -> advance to 60
        val undone = s.undoLast()                  // reverts B's attempt
        assertEquals(1, undone.currentPlayerIndex)
        assertEquals(0, undone.currentTargetIndex)
        assertEquals(60.let { 40 }, undone.currentTarget, "back on target 40")
        assertTrue(undone.perPlayer[1].attempts.isEmpty())
        assertEquals(1, undone.perPlayer[0].hits)
    }

    @Test
    fun undoLast_revertsAcrossTargetBoundary() {
        var s = game("A", "B", targets = listOf(40, 60))
        s = s.applyAttempt(hit = true, darts = 1)  // A 40
        s = s.applyAttempt(hit = true, darts = 1)  // B 40 -> advance to 60
        assertEquals(1, s.currentTargetIndex)
        s = s.applyAttempt(hit = true, darts = 2)  // A 60
        assertEquals(1, s.currentTargetIndex)
        assertEquals(1, s.currentPlayerIndex)
        val undone = s.undoLast()                  // reverts A's target-60 attempt
        assertEquals(0, undone.currentPlayerIndex, "back to seat 0 (A)")
        assertEquals(1, undone.currentTargetIndex, "still on target 60 (A re-throws)")
        assertEquals(60, undone.currentTarget)
        // A's one remaining attempt is the target-40 hit.
        assertEquals(1, undone.perPlayer[0].attempts.size)
        assertEquals(1, undone.perPlayer[0].hits)
    }

    @Test
    fun undoLast_unwindsFinish() {
        var s = game("A", "B", targets = listOf(40, 60))
        s = s.applyAttempt(hit = true, darts = 1)  // A 40
        s = s.applyAttempt(hit = true, darts = 1)  // B 40
        s = s.applyAttempt(hit = true, darts = 1)  // A 60
        s = s.applyAttempt(hit = true, darts = 1)  // B 60 -> finish
        assertTrue(s.isFinished)
        val undone = s.undoLast()
        assertFalse(undone.isFinished, "finish undone")
        assertTrue(undone.winnerIndices.isEmpty())
        // Back to B's attempt on the final target.
        assertEquals(1, undone.currentPlayerIndex)
        assertEquals(1, undone.currentTargetIndex)
        assertTrue(undone.perPlayer[1].attempts.size == 1)
    }

    @Test
    fun undoLast_reusable_backToStart() {
        var s = game("A", "B", targets = listOf(40, 60))
        s = s.applyAttempt(hit = true, darts = 1)
        s = s.applyAttempt(hit = false, darts = 0)
        s = s.undoLast().undoLast()
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(0, s.currentTargetIndex)
        assertTrue(s.perPlayer.all { it.attempts.isEmpty() })
    }

    @Test
    fun undoLast_atStart_isNoOp() {
        val s = game("A", "B")
        assertEquals(s, s.undoLast())
    }

    // ------------------------------------------------------------ requires

    @Test
    fun new_rejectsEmptyPlayers() {
        var threw = false
        try {
            CheckoutTrainerState.new(emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "empty player list must be rejected")
    }

    @Test
    fun new_rejectsEmptyTargets() {
        var threw = false
        try {
            CheckoutTrainerState.new(listOf(GamePlayer("A")), targets = emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "empty target list must be rejected")
    }

    @Test
    fun applyAttempt_rejectsDartsBelowRangeOnHit() {
        val s = game("A", targets = listOf(40))
        var threw = false
        try {
            s.applyAttempt(hit = true, darts = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "0 darts on a hit must be rejected")
    }

    @Test
    fun applyAttempt_rejectsDartsAboveRangeOnHit() {
        val s = game("A", targets = listOf(40))
        var threw = false
        try {
            s.applyAttempt(hit = true, darts = 4)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "4 darts on a hit must be rejected")
    }

    @Test
    fun applyAttempt_allowsAnyDartsOnMiss() {
        // A miss ignores darts entirely, so an out-of-range value must NOT throw.
        val s = game("A", targets = listOf(40))
        val after = s.applyAttempt(hit = false, darts = 99)
        assertEquals(0, after.perPlayer[0].attempts[0].darts)
    }

    // ----------------------------------------------------- checkout routes

    @Test
    fun everyDefaultTarget_hasACheckoutSuggestion() {
        for (target in DEFAULT_TARGETS) {
            val routes = Checkout.suggest(target, doubleOut = true)
            assertNotNull(routes.firstOrNull(), "target $target should have a route")
            assertTrue(routes.isNotEmpty(), "target $target should be finishable")
        }
    }

    // ----------------------------------------------------------------- JSON

    @Test
    fun jsonRoundTrip_preservesState() {
        val json = GameJson.format
        var state = game("Alice", "Bob", targets = listOf(40, 60, 80))
        state = state.applyAttempt(hit = true, darts = 2)  // Alice 40
        state = state.applyAttempt(hit = false, darts = 0) // Bob 40 -> target 60
        val record = GameRecord(
            id = "checkout-1",
            mode = state.toMode(),
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        assertTrue(
            text.contains("\"type\":\"checkout_trainer\""),
            "expected serial name: $text",
        )
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        assertEquals(GameMode.CHECKOUT_TRAINER, decoded.mode)
    }
}
