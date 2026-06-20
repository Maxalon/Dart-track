package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.X01PlayerState
import com.dartrack.model.X01State
import com.dartrack.model.X01Turn
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Worked examples for [playerStats], which aggregates per-player stats keyed by
 * the stable player id. Each test builds X01 games by hand with known per-turn
 * totals so the expected metric values can be computed on paper.
 */
class PlayerStatsTest {

    private val DELTA = 1e-9

    /** Build an X01Turn; bust defaults to false (pass it explicitly per task). */
    private fun turn(scoreBefore: Int, entered: Int, finished: Boolean = false, bust: Boolean = false) =
        X01Turn(scoreBefore = scoreBefore, entered = entered, bust = bust, finished = finished)

    /**
     * Wrap a single id-keyed player's turns in an X01 game record. The player's
     * seat carries the [playerId]; an optional [otherId] adds a second seat so we
     * can test that the target player's own turns are isolated.
     */
    private fun x01Record(
        recId: String,
        playerId: String,
        playerName: String,
        turns: List<X01Turn>,
        startScore: Int = 501,
        won: Boolean = false,
    ): GameRecord {
        val player = GamePlayer(name = playerName, id = playerId)
        val state = X01State(
            players = listOf(player),
            perPlayer = listOf(X01PlayerState(player, turns)),
            startScore = startScore,
            winnerIndices = if (won) listOf(0) else emptyList(),
        )
        return GameRecord(
            id = recId,
            mode = GameMode.X01,
            createdAtEpochMs = 0L,
            updatedAtEpochMs = 0L,
            state = state,
        )
    }

    @Test
    fun winPercentage_acrossGames() {
        val won = x01Record(
            "r1", "p-1", "Alice",
            listOf(turn(40, 40, finished = true, bust = false)),
            startScore = 40, won = true,
        )
        val lost = x01Record(
            "r2", "p-1", "Alice",
            listOf(turn(40, 20, finished = false, bust = false)),
            startScore = 40, won = false,
        )
        val stats = playerStats("p-1", listOf(won, lost))
        assertEquals(2, stats.gamesPlayed, "two games for p-1")
        assertEquals(1, stats.gamesWon, "won one")
        assertEquals(0.5, stats.winPct, DELTA, "win % is 1/2")
    }

    @Test
    fun nonParticipantGamesAreExcluded() {
        // A game where p-1 is NOT a seat (only p-2 plays) must not be counted.
        val mine = x01Record(
            "r1", "p-1", "Alice",
            listOf(turn(40, 40, finished = true, bust = false)),
            startScore = 40, won = true,
        )
        val notMine = x01Record(
            "r2", "p-2", "Bob",
            listOf(turn(40, 40, finished = true, bust = false)),
            startScore = 40, won = true,
        )
        val stats = playerStats("p-1", listOf(mine, notMine))
        assertEquals(1, stats.gamesPlayed, "only the game p-1 is a seat in")
        assertEquals(1, stats.gamesWon, "won the one game")
        assertEquals(1, stats.x01GamesPlayed, "one X01 game")
    }

    @Test
    fun threeDartAverage_singleLeg() {
        // 501 start, 100,140,100,100,61 finish = 501 pts over 15 darts.
        // 3-dart avg = 501 * 3 / 15 = 100.2
        val turns = listOf(
            turn(501, 100, finished = false, bust = false),
            turn(401, 140, finished = false, bust = false),
            turn(261, 100, finished = false, bust = false),
            turn(161, 100, finished = false, bust = false),
            turn(61, 61, finished = true, bust = false),
        )
        val stats = playerStats(
            "p-1",
            listOf(x01Record("r1", "p-1", "Alice", turns, won = true)),
        )
        assertEquals(100.2, stats.x01ThreeDartAvg, 1e-6, "3-dart average")
    }

    @Test
    fun tonPlusAnd180Counts() {
        // Turns: 180, 180, 140, 100 (busted, scores 0), 99, 60.
        //   180s = 2, 140+ = 3 (180,180,140), 100+ = 3 (180,180,140); the busted
        //   100 does not count.
        val turns = listOf(
            turn(501, 180, finished = false, bust = false),
            turn(321, 180, finished = false, bust = false),
            turn(141, 140, finished = false, bust = false),
            turn(1, 100, finished = false, bust = true),
            turn(1, 99, finished = false, bust = true),
            turn(1, 60, finished = false, bust = true),
        )
        val stats = playerStats(
            "p-1",
            listOf(x01Record("r1", "p-1", "Alice", turns)),
        )
        assertEquals(2, stats.x01OneEighties, "two 180s")
        assertEquals(3, stats.x01OneFortyPlus, "three 140+")
        assertEquals(3, stats.x01TonPlus, "three 100+ (busted 100 excluded)")
    }

    @Test
    fun scoreBandDistribution_countsAndPercentages() {
        // Six visits: 180, 100, 60, 19, 0 (literal), 45-bust (counts as no-score).
        //   total      = 6
        //   no-score   = 2 (the literal 0 and the busted turn)
        //   1-19       = 1 (19)
        //   20+        = 3 (180,100,60)
        //   40+        = 3 (180,100,60)
        //   60+        = 3 (180,100,60)
        //   80+        = 2 (180,100)
        //   100+       = 2 (180,100)
        //   120+       = 1 (180)
        //   140+       = 1 (180)
        //   160+       = 1 (180)
        //   180        = 1 (180)
        val turns = listOf(
            turn(501, 180, finished = false, bust = false),
            turn(321, 100, finished = false, bust = false),
            turn(221, 60, finished = false, bust = false),
            turn(161, 19, finished = false, bust = false),
            turn(142, 0, finished = false, bust = false),
            turn(142, 45, finished = false, bust = true),
        )
        val s = playerStats(
            "p-1",
            listOf(x01Record("r1", "p-1", "Alice", turns)),
        ).scoreBands

        assertEquals(6, s.total, "total visits")
        assertEquals(2, s.noScore, "no-score count (literal 0 + bust)")
        assertEquals(1, s.b1to19, "1-19 count")
        assertEquals(3, s.b20Plus, "20+ count")
        assertEquals(3, s.b40Plus, "40+ count")
        assertEquals(3, s.b60Plus, "60+ count")
        assertEquals(2, s.b80Plus, "80+ count")
        assertEquals(2, s.b100Plus, "100+ count")
        assertEquals(1, s.b120Plus, "120+ count")
        assertEquals(1, s.b140Plus, "140+ count")
        assertEquals(1, s.b160Plus, "160+ count")
        assertEquals(1, s.b180, "exactly-180 count")

        // Percentages = band count / total.
        assertEquals(2.0 / 6.0, s.pctNoScore, DELTA, "no-score %")
        assertEquals(3.0 / 6.0, s.pct20Plus, DELTA, "20+ %")
        assertEquals(1.0 / 6.0, s.pct180, DELTA, "180 %")
    }

    @Test
    fun scoreBandDistribution_emptyIsZeroPct() {
        val stats = playerStats("p-x", emptyList())
        val s = stats.scoreBands
        assertEquals(0, s.total, "no visits")
        assertEquals(0.0, s.pctNoScore, DELTA, "zero pct when empty")
        assertEquals(0.0, s.pct180, DELTA, "zero pct when empty")
    }

    @Test
    fun blankIdMatchesNothing() {
        // A game seat with a blank id must not be matched by a blank query.
        val rec = x01Record(
            "r1", "", "Anon",
            listOf(turn(40, 40, finished = true, bust = false)),
            startScore = 40, won = true,
        )
        val stats = playerStats("", listOf(rec))
        assertEquals(0, stats.gamesPlayed, "blank id matches nothing")
        assertEquals(0.0, stats.winPct, DELTA, "no games -> 0 win %")
    }

    @Test
    fun checkoutPercentage_definition() {
        // Opportunities = turns with scoreBefore <= 170; hits = finished turns.
        // 180 (before 501, not opp), 161 (before 321, not opp),
        // 100 (before 160 -> opp), 60 finish (before 60 -> opp, hit).
        // opportunities = 2, hits = 1 -> 50%.
        val turns = listOf(
            turn(501, 180, finished = false, bust = false),
            turn(321, 161, finished = false, bust = false),
            turn(160, 100, finished = false, bust = false),
            turn(60, 60, finished = true, bust = false),
        )
        val stats = playerStats(
            "p-1",
            listOf(x01Record("r1", "p-1", "Alice", turns, won = true)),
        )
        assertEquals(1, stats.x01CheckoutHits, "one checkout hit")
        assertEquals(2, stats.x01CheckoutAttempts, "two opportunities")
        assertEquals(0.5, stats.x01CheckoutPct, DELTA, "checkout % = 1/2")
    }
}
