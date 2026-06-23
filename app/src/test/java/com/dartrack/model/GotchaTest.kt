package com.dartrack.model

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.data.toMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for Gotcha: race to EXACTLY a target (301 default, 501 allowed) from 0,
 * numpad-style 0..180 turn totals. Overshoot busts (turn scores 0, player
 * stays); an exact hit wins immediately; landing on another player's CURRENT
 * (>0) total knocks that player back to 0 ("gotcha"). Target validation, undo
 * (including undoing a gotcha reset), and JSON round-trip.
 */
class GotchaTest {

    private fun game(target: Int = GOTCHA_DEFAULT_TARGET, vararg names: String) =
        GotchaState.new(names.map { GamePlayer(it) }, target)

    @Test
    fun constants_areExpected() {
        assertEquals(301, GOTCHA_DEFAULT_TARGET)
        assertTrue(301 in GOTCHA_ALLOWED_TARGETS)
        assertTrue(501 in GOTCHA_ALLOWED_TARGETS)
    }

    @Test
    fun startState_zeroTotals_defaultTarget() {
        val s = game(names = arrayOf("A", "B"))
        assertEquals(0, s.perPlayer[0].total)
        assertEquals(0, s.perPlayer[1].total)
        assertEquals(301, s.target)
        assertEquals(301, s.remainingFor(0))
        assertEquals(0, s.currentPlayerIndex)
        assertFalse(s.isFinished)
    }

    @Test
    fun normalTurn_addsTotal_andAdvances() {
        var s = game(names = arrayOf("A", "B"))
        s = s.applyTurn(100) // A -> 100
        assertEquals(100, s.perPlayer[0].total)
        assertEquals(1, s.currentPlayerIndex)
        s = s.applyTurn(60) // B -> 60
        assertEquals(60, s.perPlayer[1].total)
        assertEquals(0, s.currentPlayerIndex)
    }

    @Test
    fun exactTarget_winsImmediately() {
        // Single player marches to exactly 301: 180 + 121 = 301.
        var s = game(names = arrayOf("A"))
        s = s.applyTurn(180)
        assertFalse(s.isFinished)
        s = s.applyTurn(121)
        assertTrue(s.isFinished, "landing exactly on target wins")
        assertEquals(listOf(0), s.winnerIndices)
        assertEquals(301, s.perPlayer[0].total)
    }

    @Test
    fun overshoot_busts_andStays() {
        var s = game(301, "A", "B")
        s = s.applyTurn(180) // A -> 180
        s = s.applyTurn(0)   // B -> 0 (skip)
        s = s.applyTurn(180) // A: 180 + 180 = 360 > 301 -> BUST, stays at 180
        assertFalse(s.isFinished, "a bust does not win")
        assertEquals(180, s.perPlayer[0].total, "busted turn keeps the old total")
        // The busted turn is still recorded (darts count stays honest).
        assertEquals(2, s.perPlayer[0].turns.size)
        assertEquals(6, s.perPlayer[0].darts)
        // Turn passed on as normal.
        assertEquals(1, s.currentPlayerIndex)
    }

    @Test
    fun gotcha_resetsEqualOpponentToZero() {
        var s = game(301, "A", "B")
        s = s.applyTurn(100) // A -> 100
        s = s.applyTurn(100) // B -> 100, equals A's current 100 -> A reset to 0
        assertEquals(0, s.perPlayer[0].total, "A knocked back to 0 by the gotcha")
        assertEquals(100, s.perPlayer[1].total, "B keeps their 100")
        assertFalse(s.isFinished)
    }

    @Test
    fun gotcha_doesNotResetZeroOpponents() {
        // B sitting on 0 is NOT reset when A also lands on 0 (only >0 totals reset).
        var s = game(301, "A", "B")
        s = s.applyTurn(0) // A -> 0 (B is also 0)
        assertEquals(0, s.perPlayer[0].total)
        assertEquals(0, s.perPlayer[1].total)
        // Both still 0, no spurious reset, game continues.
        assertFalse(s.isFinished)
        assertEquals(1, s.currentPlayerIndex)
    }

    @Test
    fun gotcha_appliesToMultipleOpponents() {
        var s = game(501, "A", "B", "C")
        s = s.applyTurn(120) // A -> 120
        s = s.applyTurn(120) // B -> 120 (gotchas A back to 0)
        assertEquals(0, s.perPlayer[0].total)
        assertEquals(120, s.perPlayer[1].total)
        // Now C lands on 120 too -> resets B (A is already 0, untouched).
        s = s.applyTurn(120) // C -> 120 (gotchas B back to 0)
        assertEquals(0, s.perPlayer[0].total)
        assertEquals(0, s.perPlayer[1].total)
        assertEquals(120, s.perPlayer[2].total)
    }

    @Test
    fun new_rejectsBadTarget() {
        var threw = false
        try {
            GotchaState.new(listOf(GamePlayer("A")), target = 401)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "a target outside the allowed set must be rejected")
    }

    @Test
    fun new_acceptsFiveOhOne() {
        val s = GotchaState.new(listOf(GamePlayer("A")), target = 501)
        assertEquals(501, s.target)
    }

    @Test
    fun new_rejectsEmptyPlayers() {
        var threw = false
        try {
            GotchaState.new(emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "an empty player list must be rejected")
    }

    @Test
    fun applyTurn_rejectsOutOfRange() {
        val s = game(names = arrayOf("A"))
        var threw = false
        try {
            s.applyTurn(181)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "a total above 180 must be rejected")
    }

    @Test
    fun applyTurn_noOpAfterFinished() {
        var s = game(names = arrayOf("A"))
        s = s.applyTurn(180)
        s = s.applyTurn(121) // win
        assertTrue(s.isFinished)
        assertEquals(s, s.applyTurn(10))
    }

    // ----------------------------------------------------------------- undo

    @Test
    fun undoLast_revertsNormalTurn() {
        var s = game(301, "A", "B")
        s = s.applyTurn(100) // A -> 100
        s = s.applyTurn(50)  // B -> 50
        val undone = s.undoLast() // reverts B
        assertEquals(0, undone.perPlayer[1].total)
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        assertEquals(100, undone.perPlayer[0].total)
        assertEquals(1, undone.currentPlayerIndex)
    }

    @Test
    fun undoLast_unwindsGotchaReset() {
        var s = game(301, "A", "B")
        s = s.applyTurn(100) // A -> 100
        s = s.applyTurn(100) // B -> 100, gotchas A back to 0
        assertEquals(0, s.perPlayer[0].total)
        // Undo B's gotcha turn: A must be RESTORED to 100 (replay re-runs A only).
        val undone = s.undoLast()
        assertEquals(100, undone.perPlayer[0].total, "undo restores the gotcha'd player")
        assertEquals(0, undone.perPlayer[1].total)
        assertTrue(undone.perPlayer[1].turns.isEmpty())
        assertEquals(1, undone.currentPlayerIndex)
    }

    @Test
    fun undoLast_unwindsWin() {
        var s = game(names = arrayOf("A"))
        s = s.applyTurn(180)
        s = s.applyTurn(121) // win
        assertTrue(s.isFinished)
        val undone = s.undoLast()
        assertFalse(undone.isFinished, "win undone")
        assertTrue(undone.winnerIndices.isEmpty())
        assertEquals(180, undone.perPlayer[0].total)
    }

    @Test
    fun undoLast_atStart_isNoOp() {
        val s = game(names = arrayOf("A", "B"))
        assertEquals(s, s.undoLast())
    }

    // ----------------------------------------------------------------- JSON

    @Test
    fun jsonRoundTrip_preservesState() {
        val json = GameJson.format
        var state = game(501, "Alice", "Bob")
        state = state.applyTurn(140) // Alice -> 140
        state = state.applyTurn(60)  // Bob -> 60
        val record = GameRecord(
            id = "gotcha-1",
            mode = state.toMode(),
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        assertTrue(text.contains("\"type\":\"gotcha\""), "expected serial name: $text")
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        assertEquals(GameMode.GOTCHA, decoded.mode)
        assertEquals(501, decoded.state.let { (it as GotchaState).target })
    }
}
