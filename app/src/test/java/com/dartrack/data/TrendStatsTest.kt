package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.X01PlayerState
import com.dartrack.model.X01State
import com.dartrack.model.X01Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TrendStats.threeDartAverageTrend]: filtering to finished X01
 * games, computing one point per game, and chronological ordering.
 */
class TrendStatsTest {

    private val DELTA = 1e-9

    private fun turn(scoreBefore: Int, entered: Int, finished: Boolean = false, bust: Boolean = false) =
        X01Turn(scoreBefore = scoreBefore, entered = entered, bust = bust, finished = finished)

    private fun x01Record(
        id: String,
        name: String,
        turns: List<X01Turn>,
        startScore: Int = 501,
        won: Boolean = false,
        createdAt: Long = 0L,
        otherPlayers: List<String> = emptyList(),
    ): GameRecord {
        val player = GamePlayer(name)
        val players = listOf(player) + otherPlayers.map { GamePlayer(it) }
        val perPlayer = players.map { p ->
            X01PlayerState(p, if (p.name == name) turns else emptyList())
        }
        val state = X01State(
            players = players,
            perPlayer = perPlayer,
            startScore = startScore,
            winnerIndices = if (won) listOf(0) else emptyList(),
        )
        return GameRecord(
            id = id,
            mode = GameMode.X01,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = createdAt,
            state = state,
        )
    }

    @Test
    fun emptyWhenNoGames() {
        val trend = TrendStats.threeDartAverageTrend(emptyList(), "Alice")
        assertTrue(trend.isEmpty())
    }

    @Test
    fun emptyWhenPlayerNotInvolved() {
        val rec = x01Record("g1", "Bob", listOf(turn(501, 60, finished = false)), won = true)
        val trend = TrendStats.threeDartAverageTrend(listOf(rec), "Alice")
        assertTrue(trend.isEmpty())
    }

    @Test
    fun ignoresUnfinishedGames() {
        // Finished=false on the record => isFinished is false (no winner).
        val rec = x01Record("g1", "Alice", listOf(turn(501, 60)), won = false)
        val trend = TrendStats.threeDartAverageTrend(listOf(rec), "Alice")
        assertTrue(trend.isEmpty())
    }

    @Test
    fun onePointPerFinishedGameWithCorrectAverage() {
        // 3 turns => 9 darts, scored 501 - 0 = 501 points => avg = 501*3/9 = 167.0
        val turns = listOf(
            turn(501, 180),
            turn(321, 180),
            turn(141, 141, finished = true),
        )
        val rec = x01Record("g1", "Alice", turns, won = true, createdAt = 100L)
        val trend = TrendStats.threeDartAverageTrend(listOf(rec), "Alice")
        assertEquals(1, trend.size)
        assertEquals(100L, trend[0].timeMs)
        assertEquals(167.0, trend[0].threeDartAvg, DELTA)
    }

    @Test
    fun ordersChronologicallyByCreatedAt() {
        val older = x01Record(
            "old", "Alice",
            listOf(turn(501, 60), turn(441, 60)),
            won = true, createdAt = 1000L,
        )
        val newer = x01Record(
            "new", "Alice",
            listOf(turn(501, 100), turn(401, 100)),
            won = true, createdAt = 2000L,
        )
        // Pass newest-first (as the repo does) to prove we re-sort.
        val trend = TrendStats.threeDartAverageTrend(listOf(newer, older), "Alice")
        assertEquals(2, trend.size)
        assertEquals(1000L, trend[0].timeMs)
        assertEquals(2000L, trend[1].timeMs)
    }

    @Test
    fun skipsGamesWherePlayerThrewNoDarts() {
        val rec = x01Record("g1", "Alice", emptyList(), won = true)
        val trend = TrendStats.threeDartAverageTrend(listOf(rec), "Alice")
        assertTrue(trend.isEmpty())
    }
}
