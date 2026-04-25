package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.cricket.CricketState
import com.dartrack.model.halfit.HalfItState
import com.dartrack.model.x01.X01State
import com.dartrack.model.x01.X01Stats

data class PlayerStats(
    val name: String,
    val gamesPlayed: Int,
    val gamesWon: Int,
    // X01-specific
    val x01ThreeDartAvg: Double,
    val x01HighestTurn: Int,
    val x01HighestCheckout: Int,
    val x01GamesPlayed: Int,
    // Cricket
    val cricketGamesPlayed: Int,
    val cricketGamesWon: Int,
    // Half-It
    val halfItGamesPlayed: Int,
    val halfItHighScore: Int,
)

object StatsAggregator {

    fun aggregate(records: List<GameRecord>): List<PlayerStats> {
        // Collect every distinct player name across all records
        val names = records.flatMap { it.playerNames }.toSortedSet()
        return names.map { name -> statsFor(name, records) }
    }

    fun statsFor(name: String, records: List<GameRecord>): PlayerStats {
        var gamesPlayed = 0; var gamesWon = 0
        var x01Played = 0; var x01TotalPoints = 0L; var x01TotalDarts = 0L
        var x01HighTurn = 0; var x01HighCheckout = 0
        var cricketPlayed = 0; var cricketWon = 0
        var halfItPlayed = 0; var halfItHigh = 0

        for (r in records) {
            val idx = r.state.players.indexOfFirst { it.name == name }
            if (idx < 0) continue
            gamesPlayed++
            if (r.state.winnerIndices.contains(idx)) gamesWon++

            when (val s = r.state) {
                is X01State -> {
                    x01Played++
                    val ps = s.perPlayer[idx]
                    x01TotalPoints += X01Stats.pointsScored(ps, s.startScore).toLong()
                    x01TotalDarts += (ps.turns.size * 3).toLong()
                    x01HighTurn = maxOf(x01HighTurn, X01Stats.highestTurn(ps))
                    X01Stats.checkout(ps)?.let {
                        x01HighCheckout = maxOf(x01HighCheckout, it)
                    }
                }
                is CricketState -> {
                    cricketPlayed++
                    if (r.state.winnerIndices.contains(idx)) cricketWon++
                }
                is HalfItState -> {
                    halfItPlayed++
                    halfItHigh = maxOf(halfItHigh, s.perPlayer[idx].total)
                }
            }
        }

        val avg = if (x01TotalDarts == 0L) 0.0
                  else x01TotalPoints.toDouble() * 3.0 / x01TotalDarts
        return PlayerStats(
            name = name,
            gamesPlayed = gamesPlayed,
            gamesWon = gamesWon,
            x01ThreeDartAvg = avg,
            x01HighestTurn = x01HighTurn,
            x01HighestCheckout = x01HighCheckout,
            x01GamesPlayed = x01Played,
            cricketGamesPlayed = cricketPlayed,
            cricketGamesWon = cricketWon,
            halfItGamesPlayed = halfItPlayed,
            halfItHighScore = halfItHigh,
        )
    }
}
