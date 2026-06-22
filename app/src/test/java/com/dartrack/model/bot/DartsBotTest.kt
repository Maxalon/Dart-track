package com.dartrack.model.bot

import com.dartrack.model.Checkout
import com.dartrack.model.X01State
import com.dartrack.model.GamePlayer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the pure CPU-opponent simulator [DartsBot]. Everything is
 * deterministic via injected seeds, so the statistical assertions are stable
 * run-to-run. No Android, no IO, no global RNG.
 */
class DartsBotTest {

    // The mathematically-impossible 3-dart sums, UNION the classic bogey numbers
    // the spec calls out. A correct scoring model never emits any of these. We
    // assert against the bot's own published set so the contract can't drift.
    private val impossibleTotals = DartsBot.FORBIDDEN_VISIT_TOTALS

    private fun assertLegalTotal(v: Int, ctx: String) {
        assertTrue(v in 0..180, "$ctx: $v out of 0..180")
        assertTrue(v !in impossibleTotals, "$ctx: $v is an impossible 3-dart total")
    }

    // -------------------------------------------------------------- determinism

    @Test
    fun sameSeed_producesIdenticalCountUpSequence() {
        val a = DartsBot(BotLevel.HARD, seed = 42L)
        val b = DartsBot(BotLevel.HARD, seed = 42L)
        repeat(500) {
            assertEquals(a.countUpVisit(), b.countUpVisit(), "visit $it must match for equal seeds")
        }
    }

    @Test
    fun sameSeed_producesIdenticalX01Sequence() {
        val a = DartsBot(BotLevel.PRO, seed = 7L)
        val b = DartsBot(BotLevel.PRO, seed = 7L)
        // Mix finishable and unfinishable remainings.
        val remainings = listOf(501, 170, 40, 100, 60, 32, 50, 161, 240, 36)
        repeat(200) { i ->
            val rem = remainings[i % remainings.size]
            assertEquals(
                a.x01Visit(rem, doubleOut = true),
                b.x01Visit(rem, doubleOut = true),
                "x01 visit $i (rem=$rem) must match for equal seeds",
            )
        }
    }

    @Test
    fun differentSeeds_diverge() {
        val a = DartsBot(BotLevel.MEDIUM, seed = 1L)
        val b = DartsBot(BotLevel.MEDIUM, seed = 2L)
        val seqA = (0 until 200).map { a.countUpVisit() }
        val seqB = (0 until 200).map { b.countUpVisit() }
        assertTrue(seqA != seqB, "different seeds should not yield identical sequences")
    }

    @Test
    fun rngConstructor_matchesSeedConstructor() {
        val a = DartsBot(BotLevel.EASY, Random(99L))
        val b = DartsBot(BotLevel.EASY, seed = 99L)
        repeat(100) { assertEquals(a.countUpVisit(), b.countUpVisit()) }
    }

    // ---------------------------------------------------- legality of all totals

    @Test
    fun countUpVisits_areAlwaysLegalPossibleTotals() {
        for (level in BotLevel.entries) {
            val bot = DartsBot(level, seed = 123L)
            repeat(20_000) { assertLegalTotal(bot.countUpVisit(), "countUp/$level") }
        }
    }

    @Test
    fun x01Visits_areAlwaysLegalPossibleTotals_unfinishable() {
        // remaining well above any 3-dart finish -> pure scoring visits.
        for (level in BotLevel.entries) {
            val bot = DartsBot(level, seed = 321L)
            repeat(20_000) { assertLegalTotal(bot.x01Visit(remaining = 501, doubleOut = true), "x01-501/$level") }
        }
    }

    @Test
    fun x01Visits_areAlwaysLegalPossibleTotals_acrossManyRemainings() {
        val bot = DartsBot(BotLevel.PRO, seed = 555L)
        // Sweep every remaining from 2..170 (finishable & not), double-out & single-out.
        for (rem in 2..170) {
            repeat(60) {
                assertLegalTotal(bot.x01Visit(rem, doubleOut = true), "x01-DO/$rem")
                assertLegalTotal(bot.x01Visit(rem, doubleOut = false), "x01-SO/$rem")
            }
        }
    }

    // -------------------------------------------------------------- calibration

    @Test
    fun analyticAverage_matchesLevelTarget() {
        for (level in BotLevel.entries) {
            val bot = DartsBot(level, seed = 0L)
            val expected = level.threeDartAverage.toDouble()
            val got = bot.expectedThreeDartAverage()
            assertTrue(
                kotlin.math.abs(got - expected) <= 2.0,
                "$level analytic avg $got should be within 2 of ${level.threeDartAverage}",
            )
        }
    }

    @Test
    fun empiricalMean_isWithinToleranceOfLevelTarget() {
        for (level in BotLevel.entries) {
            val bot = DartsBot(level, seed = 2024L)
            val n = 5000
            var sum = 0L
            repeat(n) { sum += bot.countUpVisit() }
            val mean = sum.toDouble() / n
            assertTrue(
                kotlin.math.abs(mean - level.threeDartAverage) <= 6.0,
                "$level empirical mean $mean should be within 6 of ${level.threeDartAverage}",
            )
        }
    }

    @Test
    fun higherLevels_haveStrictlyHigherEmpiricalMean() {
        fun mean(level: BotLevel): Double {
            val bot = DartsBot(level, seed = 88L)
            var sum = 0L
            val n = 5000
            repeat(n) { sum += bot.countUpVisit() }
            return sum.toDouble() / n
        }
        val easy = mean(BotLevel.EASY)
        val medium = mean(BotLevel.MEDIUM)
        val hard = mean(BotLevel.HARD)
        val pro = mean(BotLevel.PRO)
        assertTrue(easy < medium, "EASY ($easy) < MEDIUM ($medium)")
        assertTrue(medium < hard, "MEDIUM ($medium) < HARD ($hard)")
        assertTrue(hard < pro, "HARD ($hard) < PRO ($pro)")
    }

    @Test
    fun believableSpread_occasionalBigScoresAndFrequentLowMisses() {
        // A PRO should hit some 180s/tons; an EASY should be frequently 26-or-less.
        val pro = DartsBot(BotLevel.PRO, seed = 13L)
        var tons = 0
        var maxes = 0
        val n = 10_000
        repeat(n) {
            val v = pro.countUpVisit()
            if (v >= 100) tons++
            if (v == 180) maxes++
        }
        assertTrue(tons > n / 4, "PRO should ton+ on a large share of visits (got $tons/$n)")
        assertTrue(maxes > 0, "PRO should hit at least one 180 in $n visits")

        val easy = DartsBot(BotLevel.EASY, seed = 13L)
        var low = 0
        repeat(n) { if (easy.countUpVisit() <= 26) low++ }
        assertTrue(low > n / 5, "EASY should frequently score 26-or-less (got $low/$n)")
    }

    // ----------------------------------------------------------------- checkouts

    @Test
    fun checkoutSuccessRate_increasesWithLevel_andStaysInOpenInterval() {
        fun successRate(level: BotLevel): Double {
            val bot = DartsBot(level, seed = 4242L)
            val trials = 8000
            var hits = 0
            repeat(trials) { if (bot.x01Visit(remaining = 40, doubleOut = true) == 40) hits++ }
            return hits.toDouble() / trials
        }
        val easy = successRate(BotLevel.EASY)
        val medium = successRate(BotLevel.MEDIUM)
        val hard = successRate(BotLevel.HARD)
        val pro = successRate(BotLevel.PRO)
        // Strictly inside (0,1) for every level.
        for ((lvl, r) in listOf("EASY" to easy, "MEDIUM" to medium, "HARD" to hard, "PRO" to pro)) {
            assertTrue(r > 0.0 && r < 1.0, "$lvl checkout rate $r must be in (0,1)")
        }
        // Monotonically increasing with skill.
        assertTrue(easy < medium, "EASY ($easy) < MEDIUM ($medium)")
        assertTrue(medium < hard, "MEDIUM ($medium) < HARD ($hard)")
        assertTrue(hard < pro, "HARD ($hard) < PRO ($pro)")
    }

    @Test
    fun checkoutReturnsExactRemaining_isAcceptedAsAFinishByEngine() {
        // When the bot "finishes", returning exactly remaining must be a legal
        // win the X01 engine accepts (with the double-out finish flag).
        val bot = DartsBot(BotLevel.PRO, seed = 5L)
        val players = listOf(GamePlayer("Bot"), GamePlayer("Human"))
        var sawFinish = false
        repeat(2000) {
            val v = bot.x01Visit(remaining = 40, doubleOut = true)
            if (v == 40) {
                sawFinish = true
                val state = X01State.new(players, startScore = 40, doubleOut = true)
                    .applyTurn(v, finishedOnDouble = true)
                assertTrue(state.isFinished, "returning exactly remaining must finish the leg")
                assertEquals(listOf(0), state.winnerIndices)
            }
        }
        assertTrue(sawFinish, "PRO should finish a 40 at least once in 2000 attempts")
    }

    @Test
    fun unfinishableRemaining_neverReturnsAnIllegalSingleVisitFinish() {
        // 169 is NOT a legal 3-dart double-out finish; the bot must never return a
        // value the engine would accept as a finish from there, and never an
        // impossible total. (It may legally score/bust.)
        assertTrue(Checkout.suggest(169, doubleOut = true).isEmpty(), "169 should be unfinishable")
        val players = listOf(GamePlayer("Bot"), GamePlayer("Human"))
        val bot = DartsBot(BotLevel.PRO, seed = 6L)
        repeat(5000) {
            val v = bot.x01Visit(remaining = 169, doubleOut = true)
            assertLegalTotal(v, "x01-169")
            // Feed it to the engine: from 169 it can never finish in one visit.
            val state = X01State.new(players, startScore = 169, doubleOut = true)
                .applyTurn(v, finishedOnDouble = true)
            assertTrue(!state.isFinished, "from 169 a single visit must never finish (got $v)")
        }
    }

    @Test
    fun x01Visit_neverExceeds180_evenForLargeRemaining() {
        val bot = DartsBot(BotLevel.PRO, seed = 31L)
        repeat(10_000) {
            val v = bot.x01Visit(remaining = 501, doubleOut = true)
            assertTrue(v in 0..180, "visit $v out of range")
        }
    }

    @Test
    fun appliedAcrossAWholeLeg_engineNeverRejectsABotVisit() {
        // End-to-end: let the bot play a full 501 leg against itself; the engine
        // must accept every visit (applyTurn requires 0..180) until someone wins.
        val players = listOf(GamePlayer("Bot"), GamePlayer("Human"))
        val bot = DartsBot(BotLevel.PRO, seed = 2718L)
        var state = X01State.new(players, startScore = 501, doubleOut = true)
        var guard = 0
        while (!state.isFinished && guard < 1000) {
            val rem = state.currentPlayerScore()
            val v = bot.x01Visit(rem, doubleOut = true)
            assertTrue(v in 0..180, "engine would reject $v")
            // If the bot returns exactly a finishable remaining, flag the finish.
            val isFinish = v == rem && Checkout.suggest(rem, doubleOut = true).isNotEmpty()
            state = state.applyTurn(v, finishedOnDouble = isFinish)
            guard++
        }
        assertTrue(state.isFinished, "a 501 leg between two PRO bots should finish within 1000 visits")
    }
}
