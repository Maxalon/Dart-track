package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.AroundTheClockState
import com.dartrack.model.CricketState
import com.dartrack.model.HalfItState
import com.dartrack.model.X01State
import com.dartrack.model.X01Stats

data class PlayerStats(
    val name: String,
    val gamesPlayed: Int,
    val gamesWon: Int,
    // X01-specific
    val x01ThreeDartAvg: Double,
    val x01HighestTurn: Int,
    val x01HighestCheckout: Int,
    val x01GamesPlayed: Int,
    // X01 deep metrics
    val x01OneEighties: Int,
    /** Turns scoring 100+ (inclusive). Includes the 140+ and 180 turns too. */
    val x01TonPlus: Int,
    /** Turns scoring 140..180 (inclusive). Includes the 180 turns too. */
    val x01OneFortyPlus: Int,
    /** Average 3-dart score over the first 9 darts (first 3 turns) of each leg. */
    val x01FirstNineAvg: Double,
    /** Successful checkouts / checkout opportunities, as a fraction 0.0..1.0. */
    val x01CheckoutPct: Double,
    val x01CheckoutHits: Int,
    val x01CheckoutAttempts: Int,
    /** Fewest darts to finish a leg, among legs this player won. 0 if none. */
    val x01BestLegDarts: Int,
    /** Average darts thrown per X01 leg played. 0.0 if none. */
    val x01AvgDartsPerLeg: Double,
    /** Total X01 legs won (across all completed legs + finished current legs). */
    val x01LegsWon: Int,
    /** X01 matches (games) won. */
    val x01MatchesWon: Int,
    // Cricket
    val cricketGamesPlayed: Int,
    val cricketGamesWon: Int,
    // Half-It
    val halfItGamesPlayed: Int,
    val halfItHighScore: Int,
)

object StatsAggregator {

    /*
     * Definitions for the X01 deep metrics (read carefully — these are
     * approximations forced by the fact that we only persist a single 0..180
     * 3-dart total per turn, never individual darts):
     *
     *  - First-9 average: over each leg's first three confirmed turns (= first
     *    9 darts), sum the points actually scored (busts contribute 0 points but
     *    still 9 darts) and divide by darts thrown, x3. Across legs this is a
     *    dart-weighted mean, so a player with fewer than 3 turns in a leg simply
     *    contributes fewer darts. Formula: firstNinePoints * 3 / firstNineDarts.
     *
     *  - Checkout %: hits / opportunities.
     *      opportunity = any turn the player threw from a finishable position,
     *        approximated as scoreBefore <= 170 (the highest checkout under
     *        double-out). We CANNOT see whether the player actually had a dart
     *        at a double, so this over-counts opportunities (e.g. a turn from
     *        120 that ends on 40 was likely a setup turn, not a real attempt).
     *        It is therefore a conservative (pessimistic) checkout %.
     *      hit = a turn flagged finished (the leg-winning checkout).
     *    Each leg can contribute at most one hit but several opportunities, so
     *    this number trends low. It is intended as a relative/trend figure, not
     *    a precise PDC-style checkout percentage.
     */
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

        // X01 deep-metric accumulators.
        var oneEighties = 0
        var tonPlus = 0
        var oneFortyPlus = 0
        // First-9 average is computed per leg as (first-9 points * 3 / first-9 darts),
        // then averaged across legs that actually had at least one of their first
        // three turns. We accumulate the raw points/darts across legs so the overall
        // figure is a true dart-weighted average (matches how avg is normally read).
        var firstNinePoints = 0L
        var firstNineDarts = 0L
        var checkoutHits = 0
        var checkoutAttempts = 0
        var bestLegDarts = 0
        var x01Legs = 0
        var x01LegsWon = 0
        var x01MatchesWon = 0

        for (r in records) {
            val idx = r.state.players.indexOfFirst { it.name == name }
            if (idx < 0) continue
            gamesPlayed++
            if (r.state.winnerIndices.contains(idx)) gamesWon++

            when (val s = r.state) {
                is X01State -> {
                    x01Played++
                    if (s.winnerIndices.contains(idx)) x01MatchesWon++

                    // Aggregate across EVERY leg this player played: each
                    // completed leg's snapshot plus the in-progress current leg.
                    // This makes a multi-leg match report the same per-metric
                    // figures it would as a sequence of single-leg games.
                    val legStates = s.allLegStatesFor(idx)
                    legStates.forEachIndexed { legIndex, ps ->
                        x01Legs++
                        val turns = ps.turns
                        x01TotalPoints += X01Stats.pointsScored(ps, s.startScore).toLong()
                        x01TotalDarts += (turns.size * 3).toLong()
                        x01HighTurn = maxOf(x01HighTurn, X01Stats.highestTurn(ps))
                        X01Stats.checkout(ps)?.let {
                            x01HighCheckout = maxOf(x01HighCheckout, it)
                        }

                        // Scoring buckets: a busted turn scores 0, so only count
                        // applied (non-bust) entered values.
                        for (t in turns) {
                            if (t.bust) continue
                            if (t.entered == 180) oneEighties++
                            if (t.entered >= 140) oneFortyPlus++
                            if (t.entered >= 100) tonPlus++
                        }

                        // First-9: first three turns of this leg. Busts count as
                        // 9 darts scoring 0 points, consistent with the average.
                        for (t in turns.take(3)) {
                            firstNinePoints += t.applied.toLong()
                            firstNineDarts += 3L
                        }

                        // Checkout opportunities & hits (see object-level comment).
                        for (t in turns) {
                            if (t.scoreBefore <= 170) checkoutAttempts++
                            if (t.finished) checkoutHits++
                        }

                        // Did this player win THIS leg? A completed leg is won by
                        // its recorded winnerIndex; the current leg is won only if
                        // the player is in winnerIndices (i.e. they won the match
                        // on this leg). Both surface as a finished last turn.
                        val isCompletedLeg = legIndex < s.completedLegs.size
                        val wonThisLeg = if (isCompletedLeg) {
                            s.completedLegs[legIndex].winnerIndex == idx
                        } else {
                            s.winnerIndices.contains(idx)
                        }
                        if (wonThisLeg && turns.lastOrNull()?.finished == true) {
                            x01LegsWon++
                            val dartsToFinish = turns.size * 3
                            if (bestLegDarts == 0 || dartsToFinish < bestLegDarts) {
                                bestLegDarts = dartsToFinish
                            }
                        }
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
                // Around the Clock statistics are out of scope for now; the
                // branch only exists to keep this `when` exhaustive.
                is AroundTheClockState -> {}
            }
        }

        val avg = if (x01TotalDarts == 0L) 0.0
                  else x01TotalPoints.toDouble() * 3.0 / x01TotalDarts
        val firstNineAvg = if (firstNineDarts == 0L) 0.0
                           else firstNinePoints.toDouble() * 3.0 / firstNineDarts
        val checkoutPct = if (checkoutAttempts == 0) 0.0
                          else checkoutHits.toDouble() / checkoutAttempts
        val avgDartsPerLeg = if (x01Legs == 0) 0.0
                             else x01TotalDarts.toDouble() / x01Legs
        return PlayerStats(
            name = name,
            gamesPlayed = gamesPlayed,
            gamesWon = gamesWon,
            x01ThreeDartAvg = avg,
            x01HighestTurn = x01HighTurn,
            x01HighestCheckout = x01HighCheckout,
            x01GamesPlayed = x01Played,
            x01OneEighties = oneEighties,
            x01TonPlus = tonPlus,
            x01OneFortyPlus = oneFortyPlus,
            x01FirstNineAvg = firstNineAvg,
            x01CheckoutPct = checkoutPct,
            x01CheckoutHits = checkoutHits,
            x01CheckoutAttempts = checkoutAttempts,
            x01BestLegDarts = bestLegDarts,
            x01AvgDartsPerLeg = avgDartsPerLeg,
            x01LegsWon = x01LegsWon,
            x01MatchesWon = x01MatchesWon,
            cricketGamesPlayed = cricketPlayed,
            cricketGamesWon = cricketWon,
            halfItGamesPlayed = halfItPlayed,
            halfItHighScore = halfItHigh,
        )
    }
}
