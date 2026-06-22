package com.dartrack.model.bot

import com.dartrack.model.Checkout
import com.dartrack.model.GamePlayer
import com.dartrack.model.X01State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADVERSARIAL QA tests that drive every [DartsBot.x01Visit] output straight
 * through the real [X01State.applyTurn] engine across a large seed x remaining
 * sweep, asserting the engine NEVER rejects a bot visit and that, when the bot
 * "finishes", the engine agrees the leg is won and the finishing total equals
 * `remaining` exactly. Also pins the double-out tiny-finish contract: from a
 * remaining of 2 the only legal finishing visit is exactly 2.
 */
class DartsBotEngineDriveTest {

    private val players = listOf(GamePlayer("Bot"), GamePlayer("Human"))

    /** Feed a single visit into the engine from a fresh leg of `remaining`. */
    private fun engineAfter(remaining: Int, doubleOut: Boolean, v: Int): X01State {
        // For double-out, the bot only ever returns `remaining` as a genuine
        // checkout, so flag the finish exactly when it claims one.
        val finish = v == remaining && Checkout.suggest(remaining, doubleOut).isNotEmpty()
        return X01State.new(players, startScore = remaining, doubleOut = doubleOut)
            .applyTurn(v, finishedOnDouble = finish)
    }

    // ----------------------------------------------- engine accepts everything

    @Test
    fun everyVisit_acrossSeedsAndRemainings_isAcceptedByTheEngine() {
        // A genuinely large sweep: many seeds x many remainings x both out-modes.
        // The single hard invariant: applyTurn requires 0..180, and the bot must
        // never produce anything else, nor a value the engine treats as illegal.
        for (level in BotLevel.entries) {
            for (seed in 0L until 12L) {
                val bot = DartsBot(level, seed = seed)
                for (rem in 2..170) {
                    for (doubleOut in listOf(true, false)) {
                        val v = bot.x01Visit(rem, doubleOut)
                        assertTrue(
                            v in 0..180,
                            "engine would reject $v (level=$level seed=$seed rem=$rem DO=$doubleOut)",
                        )
                        assertTrue(
                            v !in DartsBot.FORBIDDEN_VISIT_TOTALS,
                            "bot emitted a forbidden total $v (rem=$rem DO=$doubleOut)",
                        )
                        // The engine must not throw on it.
                        engineAfter(rem, doubleOut, v)
                    }
                }
            }
        }
    }

    @Test
    fun whenBotFinishes_theEngineConfirmsTheWin_andTotalEqualsRemaining() {
        // For finishable remainings, gather some genuine finishes and prove each
        // is exactly `remaining` AND wins per the engine. Also prove the bot DOES
        // sometimes finish from a finishable spot (it isn't trivially never).
        val finishable = listOf(40, 32, 36, 50, 60, 100, 120, 170, 24, 8)
        var totalFinishes = 0
        for (rem in finishable) {
            assertTrue(Checkout.suggest(rem, doubleOut = true).isNotEmpty(), "$rem must be finishable")
            val bot = DartsBot(BotLevel.PRO, seed = 4242L)
            var finishes = 0
            repeat(3000) {
                val v = bot.x01Visit(rem, doubleOut = true)
                if (v == rem) {
                    finishes++
                    val st = engineAfter(rem, doubleOut = true, v)
                    assertTrue(st.isFinished, "finish from $rem must win (v=$v)")
                    assertEquals(listOf(0), st.winnerIndices)
                    assertEquals(0, st.scoreFor(0), "a finish lands the player on exactly 0")
                }
            }
            assertTrue(finishes > 0, "PRO should finish $rem at least once in 3000 visits")
            totalFinishes += finishes
        }
        assertTrue(totalFinishes > 0)
    }

    @Test
    fun fromRemainingTwo_doubleOut_onlyLegalFinishingVisitIsExactlyTwo() {
        // From 2 (D1) the sole 1-visit checkout is hitting the 2 itself. Any other
        // value the bot returns must NOT be accepted as a finish by the engine:
        //  - v > 2  -> busts (score below 0),
        //  - v == 1 -> busts (leaves 1, illegal under double-out),
        //  - v == 0 -> a legal no-score, leaves 2 (not finished).
        assertTrue(Checkout.suggest(2, doubleOut = true).isNotEmpty(), "2 is finishable")
        for (seed in 0L until 40L) {
            val bot = DartsBot(BotLevel.PRO, seed = seed)
            repeat(500) {
                val v = bot.x01Visit(remaining = 2, doubleOut = true)
                assertTrue(v in 0..180, "out of range $v")
                if (v == 2) {
                    val st = engineAfter(2, doubleOut = true, v)
                    assertTrue(st.isFinished, "exactly 2 must finish from 2")
                } else {
                    // Anything other than 2 must never read as a finish from 2.
                    val st = X01State.new(players, startScore = 2, doubleOut = true)
                        .applyTurn(v, finishedOnDouble = true) // even if it CLAIMS a finish
                    assertTrue(
                        !st.isFinished,
                        "from 2, a visit of $v must never finish under double-out",
                    )
                }
            }
        }
    }

    @Test
    fun fromRemainingTwo_botActuallyFinishesSometimes() {
        // Guard against the contract being satisfied by "never finish": a PRO must
        // close a 2 at least occasionally.
        val bot = DartsBot(BotLevel.PRO, seed = 9L)
        var hits = 0
        repeat(8000) { if (bot.x01Visit(remaining = 2, doubleOut = true) == 2) hits++ }
        assertTrue(hits > 0, "PRO should finish a 2 at least once in 8000 tries (got $hits)")
    }

    @Test
    fun determinismBySeed_holdsUnderTheEngineDrive() {
        // Two bots, same seed, identical remaining stream -> identical engine
        // outcomes turn for turn.
        val remStream = (0 until 300).map { listOf(501, 2, 40, 170, 100, 61, 32, 24, 8, 160)[it % 10] }
        val a = DartsBot(BotLevel.HARD, seed = 77L)
        val b = DartsBot(BotLevel.HARD, seed = 77L)
        for (rem in remStream) {
            val va = a.x01Visit(rem, doubleOut = true)
            val vb = b.x01Visit(rem, doubleOut = true)
            assertEquals(va, vb, "same seed must produce identical visit for rem=$rem")
            assertEquals(
                engineAfter(rem, true, va).isFinished,
                engineAfter(rem, true, vb).isFinished,
                "engine outcome must also match for rem=$rem",
            )
        }
    }
}
