package com.dartrack.data

import com.dartrack.model.X01State
import com.dartrack.model.X01Stats

/**
 * One data point on a player's 3-dart-average trend: the time the game was
 * played ([timeMs], from the record's [GameRecord.createdAtEpochMs]) and that
 * player's 3-dart average in that single game.
 */
data class TrendPoint(
    val timeMs: Long,
    val threeDartAvg: Double,
)

/**
 * Pure, allocation-light helpers for building a per-player time series of the
 * X01 3-dart average. Deliberately kept out of [StatsAggregator] so it can
 * evolve independently and be unit-tested in isolation.
 */
object TrendStats {

    /**
     * Builds the chronological 3-dart-average trend for [playerName].
     *
     * Filters to FINISHED X01 games that include the player, computes that
     * player's 3-dart average for each such game (one point per game) using the
     * game's own start score, and orders the points oldest-to-newest by the
     * record's creation time. Games where the player threw zero darts are
     * skipped (no meaningful average). Returns an empty list if the player has
     * no qualifying games.
     */
    fun threeDartAverageTrend(
        records: List<GameRecord>,
        playerName: String,
    ): List<TrendPoint> {
        val points = ArrayList<TrendPoint>()
        for (r in records) {
            if (!r.isFinished) continue
            val state = r.state as? X01State ?: continue
            val idx = state.players.indexOfFirst { it.name == playerName }
            if (idx < 0) continue
            val ps = state.perPlayer[idx]
            // Skip games the player did not actually throw in; the average would
            // be 0.0 and would distort the trend line.
            if (ps.turns.isEmpty()) continue
            val avg = X01Stats.threeDartAverage(ps, state.startScore)
            points.add(TrendPoint(timeMs = r.createdAtEpochMs, threeDartAvg = avg))
        }
        // Order oldest -> newest so the chart reads left (past) to right (now).
        // The repository hands lists sorted by updatedAt desc, so an explicit
        // sort by the creation time is required for correctness.
        points.sortBy { it.timeMs }
        return points
    }
}
