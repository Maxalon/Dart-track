package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.X01PlayerState
import com.dartrack.model.X01State
import com.dartrack.model.X01Turn
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Worked examples for the X01 deep statistics added in [StatsAggregator].
 *
 * Each test builds an X01 game by hand with known per-turn totals so the
 * expected metric values can be computed on paper and asserted exactly.
 */
class StatsTest {

    private val DELTA = 1e-9

    /** Build an X01Turn, deriving bust from a below-0 finish for convenience. */
    private fun turn(scoreBefore: Int, entered: Int, finished: Boolean = false, bust: Boolean = false) =
        X01Turn(scoreBefore = scoreBefore, entered = entered, bust = bust, finished = finished)

    /** Wrap a single player's turns in a finished/in-progress X01 game record. */
    private fun x01Record(
        name: String,
        turns: List<X01Turn>,
        startScore: Int = 501,
        won: Boolean = false,
    ): GameRecord {
        val player = GamePlayer(name)
        val state = X01State(
            players = listOf(player),
            perPlayer = listOf(X01PlayerState(player, turns)),
            startScore = startScore,
            winnerIndices = if (won) listOf(0) else emptyList(),
        )
        return GameRecord(
            id = "rec-$name",
            mode = GameMode.X01,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
            state = state,
        )
    }

    @Test
    fun scoringBuckets_180s_140plus_100plus() {
        // Turns: 180, 180, 140, 100, 99, 60.
        //   180s     = 2 (the two 180 turns)
        //   140+     = 3 (180,180,140)
        //   100+     = 4 (180,180,140,100)
        val turns = listOf(
            turn(501, 180),
            turn(321, 180),
            turn(141, 140), // leaves 1; not a real finish here, just scoring
            turn(1, 100, bust = true),
            turn(1, 99, bust = true),
            turn(1, 60, bust = true),
        )
        val stats = StatsAggregator.statsFor("A", listOf(x01Record("A", turns)))

        assertEquals(2, stats.x01OneEighties)
        assertEquals(3, stats.x01OneFortyPlus)
        // Busted 100 turn does NOT count toward 100+ (it scored 0).
        assertEquals(3, stats.x01TonPlus)
    }

    @Test
    fun bustedHighTurnDoesNotCountInBuckets() {
        val turns = listOf(
            turn(170, 180, bust = true), // overshoot -> bust, scores 0
            turn(170, 50),
        )
        val stats = StatsAggregator.statsFor("A", listOf(x01Record("A", turns)))
        assertEquals(0, stats.x01OneEighties)
        assertEquals(0, stats.x01OneFortyPlus)
        assertEquals(0, stats.x01TonPlus)
    }

    @Test
    fun firstNineAverage_singleLeg() {
        // First three turns: 100, 140, 60 -> 300 points over 9 darts.
        // first-9 avg (3-dart) = 300 * 3 / 9 = 100.0
        val turns = listOf(
            turn(501, 100),
            turn(401, 140),
            turn(261, 60),
            turn(201, 41), // 4th turn, ignored by first-9
        )
        val stats = StatsAggregator.statsFor("A", listOf(x01Record("A", turns)))
        assertEquals(100.0, stats.x01FirstNineAvg, DELTA)
    }

    @Test
    fun firstNineAverage_acrossLegs_isDartWeighted() {
        // Leg 1 first-9: 60,60,60 = 180 pts / 9 darts.
        // Leg 2 first-9: 100,100,100 = 300 pts / 9 darts.
        // Combined: 480 pts over 18 darts -> 480*3/18 = 80.0
        val leg1 = x01Record("A", listOf(turn(501, 60), turn(441, 60), turn(381, 60)))
        val leg2 = x01Record("A", listOf(turn(501, 100), turn(401, 100), turn(301, 100)))
        val stats = StatsAggregator.statsFor("A", listOf(leg1, leg2))
        assertEquals(80.0, stats.x01FirstNineAvg, DELTA)
    }

    @Test
    fun firstNineAverage_legWithFewerThanThreeTurns() {
        // Only 2 turns: 100, 90 -> 190 pts over 6 darts -> 190*3/6 = 95.0
        val turns = listOf(turn(501, 100), turn(401, 90))
        val stats = StatsAggregator.statsFor("A", listOf(x01Record("A", turns)))
        assertEquals(95.0, stats.x01FirstNineAvg, DELTA)
    }

    @Test
    fun checkoutPercentage_definition() {
        // Opportunities = turns with scoreBefore <= 170.
        // Hits = turns flagged finished.
        // Turns: 180 (before 501, not opp), 161 (before 321, not opp),
        //        100 (before 160 -> opp), 60 finish (before 60 -> opp, hit).
        // opportunities = 2, hits = 1 -> 50%.
        val turns = listOf(
            turn(501, 180),
            turn(321, 161),
            turn(160, 100),
            turn(60, 60, finished = true),
        )
        val stats = StatsAggregator.statsFor("A", listOf(x01Record("A", turns, won = true)))
        assertEquals(1, stats.x01CheckoutHits)
        assertEquals(2, stats.x01CheckoutAttempts)
        assertEquals(0.5, stats.x01CheckoutPct, DELTA)
    }

    @Test
    fun checkoutPercentage_scoreBefore170IsAnOpportunity() {
        // scoreBefore exactly 170 is the boundary and counts as an opportunity.
        val turns = listOf(turn(170, 100))
        val stats = StatsAggregator.statsFor("A", listOf(x01Record("A", turns)))
        assertEquals(1, stats.x01CheckoutAttempts)
        assertEquals(0, stats.x01CheckoutHits)
        assertEquals(0.0, stats.x01CheckoutPct, DELTA)
    }

    @Test
    fun bestLegAndAvgDartsPerLeg() {
        // Leg 1 (won): 5 turns -> 15 darts.
        // Leg 2 (won): 4 turns -> 12 darts (the best/fewest).
        // Leg 3 (lost/unfinished): 6 turns -> 18 darts, not eligible for best leg.
        // avg darts/leg = (15 + 12 + 18) / 3 = 15.0
        val leg1 = x01Record(
            "A",
            listOf(turn(501, 100), turn(401, 100), turn(301, 100), turn(201, 100), turn(101, 101, finished = true)),
            won = true,
        )
        val leg2 = x01Record(
            "A",
            listOf(turn(501, 140), turn(361, 140), turn(221, 140), turn(81, 81, finished = true)),
            won = true,
        )
        val leg3 = x01Record(
            "A",
            listOf(turn(501, 60), turn(441, 60), turn(381, 60), turn(321, 60), turn(261, 60), turn(201, 60)),
            won = false,
        )
        val stats = StatsAggregator.statsFor("A", listOf(leg1, leg2, leg3))
        assertEquals(12, stats.x01BestLegDarts)
        assertEquals(15.0, stats.x01AvgDartsPerLeg, DELTA)
        assertEquals(3, stats.x01GamesPlayed)
    }

    @Test
    fun bestLegDartsZeroWhenNoWins() {
        val turns = listOf(turn(501, 100), turn(401, 100))
        val stats = StatsAggregator.statsFor("A", listOf(x01Record("A", turns, won = false)))
        assertEquals(0, stats.x01BestLegDarts)
    }

    @Test
    fun setsWon_accumulatesAcrossMatch() {
        // first-to-2 legs/set, first-to-2 sets. A wins both sets (4 legs) -> 2 sets.
        val players = listOf(GamePlayer("A"), GamePlayer("B"))
        var s = X01State.new(players, startScore = 40, doubleOut = false,
            legsToWin = 2, setsToWin = 2)
        fun aLeg(st: X01State): X01State {
            var x = st
            if (x.currentPlayerIndex != 0) x = x.applyTurn(0)
            return x.applyTurn(40)
        }
        s = aLeg(s); s = aLeg(s)   // set 1 to A
        s = aLeg(s); s = aLeg(s)   // set 2 to A -> match
        val record = GameRecord("setmatch", GameMode.X01, 0L, 0L, s)
        val statsA = StatsAggregator.statsFor("A", listOf(record))
        val statsB = StatsAggregator.statsFor("B", listOf(record))
        assertEquals(2, statsA.x01SetsWon)
        assertEquals(4, statsA.x01LegsWon, "all 4 legs counted across both sets")
        assertEquals(1, statsA.x01MatchesWon)
        assertEquals(0, statsB.x01SetsWon)
        assertEquals(0, statsB.x01LegsWon)
        assertEquals(0, statsB.x01MatchesWon)
    }

    @Test
    fun setsWon_isZeroForLegacySingleLegGames() {
        val turns = listOf(turn(40, 40, finished = true))
        val stats = StatsAggregator.statsFor("A",
            listOf(x01Record("A", turns, startScore = 40, won = true)))
        assertEquals(0, stats.x01SetsWon, "no sets layer -> zero sets won")
    }

    @Test
    fun existingMetricsStillWork() {
        // 501 start, scores 100,140,100,100,61 finish = 501 pts over 15 darts.
        // 3-dart avg = 501 * 3 / 15 = 100.2
        val turns = listOf(
            turn(501, 100),
            turn(401, 140),
            turn(261, 100),
            turn(161, 100),
            turn(61, 61, finished = true),
        )
        val stats = StatsAggregator.statsFor("A", listOf(x01Record("A", turns, won = true)))
        assertEquals(100.2, stats.x01ThreeDartAvg, 1e-6)
        assertEquals(140, stats.x01HighestTurn)
        assertEquals(61, stats.x01HighestCheckout)
        assertEquals(1, stats.gamesWon)
        assertEquals(1, stats.gamesPlayed)
    }
}
