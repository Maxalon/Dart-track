package com.dartrack.data

import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CricketState
import com.dartrack.model.HalfItState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State
import com.dartrack.model.X01Stats

/**
 * Per-player statistics keyed by the stable player [id] (UUID) rather than by
 * display name. This is the id-based counterpart to [StatsAggregator] (which
 * aggregates by name and must NOT be edited): a player's seat in a game is found
 * by matching `state.players[i].id == playerId`. Games where the player is not a
 * participant (no seat with a matching id) are excluded entirely.
 *
 * All logic here is pure and Android-free so it can be unit-tested without a
 * Context or any file IO.
 */

/**
 * Score-band ("visit") distribution over every X01 turn the player threw. A
 * busted turn (or an entered value of 0) counts as a "no-score" visit. The
 * thresholds are CUMULATIVE: a 140 visit counts toward [b20Plus], [b40Plus],
 * ... up to [b140Plus]. The lone exception is [b180], which is the count of
 * visits scoring EXACTLY 180.
 *
 * [total] is the number of X01 turns considered (the denominator for the
 * percentages). Each `pct*` field is the matching count divided by [total]
 * (0.0..1.0), or 0.0 when there are no turns.
 */
data class ScoreBandDistribution(
    val total: Int,
    /** entered == 0 or a busted turn (scores 0). */
    val noScore: Int,
    /** entered in 1..19 (non-bust). */
    val b1to19: Int,
    val b20Plus: Int,
    val b40Plus: Int,
    val b60Plus: Int,
    val b80Plus: Int,
    val b100Plus: Int,
    val b120Plus: Int,
    val b140Plus: Int,
    val b160Plus: Int,
    /** entered == 180 (non-bust). */
    val b180: Int,
) {
    val pctNoScore: Double get() = pct(noScore)
    val pct1to19: Double get() = pct(b1to19)
    val pct20Plus: Double get() = pct(b20Plus)
    val pct40Plus: Double get() = pct(b40Plus)
    val pct60Plus: Double get() = pct(b60Plus)
    val pct80Plus: Double get() = pct(b80Plus)
    val pct100Plus: Double get() = pct(b100Plus)
    val pct120Plus: Double get() = pct(b120Plus)
    val pct140Plus: Double get() = pct(b140Plus)
    val pct160Plus: Double get() = pct(b160Plus)
    val pct180: Double get() = pct(b180)

    private fun pct(count: Int): Double = if (total == 0) 0.0 else count.toDouble() / total
}

/** Per-mode (non-X01) summary for one player. */
data class ModeSummary(
    val gamesPlayed: Int,
    val gamesWon: Int,
    /**
     * "Best result" for this mode, interpreted per-mode (0 when not applicable):
     *  - Cricket: games won (mirrors [gamesWon]).
     *  - Half-It: highest final total reached in any game.
     *  - Shanghai: highest final total reached in any game.
     *  - Bob's 27: highest final score reached in any game.
     *  - Catch 40: highest final score reached in any game.
     *  - Around the Clock: fewest darts to clear the board in a WON game (0 if
     *    the player never finished a game).
     */
    val best: Int,
)

/** The full computed stats payload for a single player. */
data class PlayerStatsData(
    val playerId: String,
    // Overall (all modes).
    val gamesPlayed: Int,
    val gamesWon: Int,
    /** Win fraction 0.0..1.0 across all modes (0.0 when no games). */
    val winPct: Double,
    // X01.
    val x01GamesPlayed: Int,
    val x01ThreeDartAvg: Double,
    val x01FirstNineAvg: Double,
    val x01CheckoutPct: Double,
    val x01CheckoutHits: Int,
    val x01CheckoutAttempts: Int,
    val x01OneEighties: Int,
    /** Turns scoring 100+ (inclusive of 140+ and 180 turns). */
    val x01TonPlus: Int,
    /** Turns scoring 140+ (inclusive of 180 turns). */
    val x01OneFortyPlus: Int,
    /** Fewest darts to finish a won leg (0 if none). */
    val x01BestLegDarts: Int,
    val x01AvgDartsPerLeg: Double,
    val x01LegsWon: Int,
    val x01SetsWon: Int,
    val x01MatchesWon: Int,
    val scoreBands: ScoreBandDistribution,
    // Per-mode summaries.
    val cricket: ModeSummary,
    val halfIt: ModeSummary,
    val aroundTheClock: ModeSummary,
    val bobs27: ModeSummary,
    val shanghai: ModeSummary,
    val catch40: ModeSummary,
)

/**
 * Compute [PlayerStatsData] for [playerId] across [games].
 *
 * Seat resolution: the player's seat in a game is the first index `i` where
 * `state.players[i].id == playerId`. If [playerId] is blank, no game can match
 * (we never key on a blank id), so the player is treated as having no games.
 *
 * X01 metric definitions mirror [StatsAggregator] exactly (read its object-level
 * comment): 3-dart and first-9 averages are dart-weighted; checkout % uses the
 * same approximation (opportunity = turn with scoreBefore <= 170; hit = finished
 * turn); busted turns count as 9 darts scoring 0. Aggregation runs across every
 * leg via [X01State.allLegStatesFor].
 */
fun playerStats(playerId: String, games: List<GameRecord>): PlayerStatsData {
    var gamesPlayed = 0
    var gamesWon = 0

    var x01Played = 0
    var x01TotalPoints = 0L
    var x01TotalDarts = 0L
    var oneEighties = 0
    var tonPlus = 0
    var oneFortyPlus = 0
    var firstNinePoints = 0L
    var firstNineDarts = 0L
    var checkoutHits = 0
    var checkoutAttempts = 0
    var bestLegDarts = 0
    var x01Legs = 0
    var x01LegsWon = 0
    var x01SetsWon = 0
    var x01MatchesWon = 0

    // Score-band accumulators.
    var bandTotal = 0
    var noScore = 0
    var b1to19 = 0
    var b20Plus = 0
    var b40Plus = 0
    var b60Plus = 0
    var b80Plus = 0
    var b100Plus = 0
    var b120Plus = 0
    var b140Plus = 0
    var b160Plus = 0
    var b180 = 0

    var cricketPlayed = 0; var cricketWon = 0
    var halfItPlayed = 0; var halfItWon = 0; var halfItHigh = 0
    var atcPlayed = 0; var atcWon = 0; var atcBestDarts = 0
    var bobsPlayed = 0; var bobsWon = 0; var bobsHigh = 0
    var shanghaiPlayed = 0; var shanghaiWon = 0; var shanghaiHigh = 0
    var catchPlayed = 0; var catchWon = 0; var catchHigh = 0

    if (playerId.isNotBlank()) {
        for (r in games) {
            val idx = r.state.players.indexOfFirst { it.id == playerId }
            if (idx < 0) continue
            gamesPlayed++
            val won = r.state.winnerIndices.contains(idx)
            if (won) gamesWon++

            when (val s = r.state) {
                is X01State -> {
                    x01Played++
                    if (won) x01MatchesWon++
                    x01SetsWon += s.setsWonBy(idx)

                    val legStates = s.allLegStatesFor(idx)
                    legStates.forEachIndexed { legIndex, ps ->
                        x01Legs++
                        val turns = ps.turns
                        x01TotalPoints += X01Stats.pointsScored(ps, s.startScore).toLong()
                        x01TotalDarts += (turns.size * 3).toLong()

                        for (t in turns) {
                            // Score-band distribution (per visit). A busted turn
                            // or a literal 0 is a "no-score" visit; otherwise the
                            // cumulative thresholds apply to the entered total.
                            bandTotal++
                            val scored = if (t.bust) 0 else t.entered
                            if (scored == 0) {
                                noScore++
                            } else {
                                if (scored in 1..19) b1to19++
                                if (scored >= 20) b20Plus++
                                if (scored >= 40) b40Plus++
                                if (scored >= 60) b60Plus++
                                if (scored >= 80) b80Plus++
                                if (scored >= 100) b100Plus++
                                if (scored >= 120) b120Plus++
                                if (scored >= 140) b140Plus++
                                if (scored >= 160) b160Plus++
                                if (scored == 180) b180++
                            }

                            // Ton/180 buckets (same rule as StatsAggregator).
                            if (!t.bust) {
                                if (t.entered == 180) oneEighties++
                                if (t.entered >= 140) oneFortyPlus++
                                if (t.entered >= 100) tonPlus++
                            }
                        }

                        for (t in turns.take(3)) {
                            firstNinePoints += t.applied.toLong()
                            firstNineDarts += 3L
                        }

                        for (t in turns) {
                            if (t.scoreBefore <= 170) checkoutAttempts++
                            if (t.finished) checkoutHits++
                        }

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
                    if (won) cricketWon++
                }
                is HalfItState -> {
                    halfItPlayed++
                    if (won) halfItWon++
                    halfItHigh = maxOf(halfItHigh, s.perPlayer[idx].total)
                }
                is AroundTheClockState -> {
                    atcPlayed++
                    if (won) {
                        atcWon++
                        val darts = s.perPlayer[idx].darts
                        if (darts > 0 && (atcBestDarts == 0 || darts < atcBestDarts)) {
                            atcBestDarts = darts
                        }
                    }
                }
                is BobsTwentySevenState -> {
                    bobsPlayed++
                    if (won) bobsWon++
                    bobsHigh = maxOf(bobsHigh, s.perPlayer[idx].score)
                }
                is ShanghaiState -> {
                    shanghaiPlayed++
                    if (won) shanghaiWon++
                    shanghaiHigh = maxOf(shanghaiHigh, s.perPlayer[idx].total)
                }
                is Catch40State -> {
                    catchPlayed++
                    if (won) catchWon++
                    catchHigh = maxOf(catchHigh, s.perPlayer[idx].score)
                }
            }
        }
    }

    val winPct = if (gamesPlayed == 0) 0.0 else gamesWon.toDouble() / gamesPlayed
    val avg = if (x01TotalDarts == 0L) 0.0
              else x01TotalPoints.toDouble() * 3.0 / x01TotalDarts
    val firstNineAvg = if (firstNineDarts == 0L) 0.0
                       else firstNinePoints.toDouble() * 3.0 / firstNineDarts
    val checkoutPct = if (checkoutAttempts == 0) 0.0
                      else checkoutHits.toDouble() / checkoutAttempts
    val avgDartsPerLeg = if (x01Legs == 0) 0.0
                         else x01TotalDarts.toDouble() / x01Legs

    return PlayerStatsData(
        playerId = playerId,
        gamesPlayed = gamesPlayed,
        gamesWon = gamesWon,
        winPct = winPct,
        x01GamesPlayed = x01Played,
        x01ThreeDartAvg = avg,
        x01FirstNineAvg = firstNineAvg,
        x01CheckoutPct = checkoutPct,
        x01CheckoutHits = checkoutHits,
        x01CheckoutAttempts = checkoutAttempts,
        x01OneEighties = oneEighties,
        x01TonPlus = tonPlus,
        x01OneFortyPlus = oneFortyPlus,
        x01BestLegDarts = bestLegDarts,
        x01AvgDartsPerLeg = avgDartsPerLeg,
        x01LegsWon = x01LegsWon,
        x01SetsWon = x01SetsWon,
        x01MatchesWon = x01MatchesWon,
        scoreBands = ScoreBandDistribution(
            total = bandTotal,
            noScore = noScore,
            b1to19 = b1to19,
            b20Plus = b20Plus,
            b40Plus = b40Plus,
            b60Plus = b60Plus,
            b80Plus = b80Plus,
            b100Plus = b100Plus,
            b120Plus = b120Plus,
            b140Plus = b140Plus,
            b160Plus = b160Plus,
            b180 = b180,
        ),
        cricket = ModeSummary(cricketPlayed, cricketWon, best = cricketWon),
        halfIt = ModeSummary(halfItPlayed, halfItWon, best = halfItHigh),
        aroundTheClock = ModeSummary(atcPlayed, atcWon, best = atcBestDarts),
        bobs27 = ModeSummary(bobsPlayed, bobsWon, best = bobsHigh),
        shanghai = ModeSummary(shanghaiPlayed, shanghaiWon, best = shanghaiHigh),
        catch40 = ModeSummary(catchPlayed, catchWon, best = catchHigh),
    )
}

/**
 * Chronological 3-dart-average trend for the player identified by [playerId].
 *
 * This is the id-keyed counterpart to [TrendStats.threeDartAverageTrend] (which
 * keys by display name): the player's seat in each game is the first index `i`
 * where `state.players[i].id == playerId`. One [TrendPoint] is produced per
 * FINISHED X01 game the player took part in, using that player's 3-dart average
 * across all of their legs in the game (via [X01State.allLegStatesFor] +
 * [X01Stats.threeDartAverage]). The game's own [X01State.startScore] is used.
 *
 * Games where the player threw zero darts are skipped (the average would be 0.0
 * and would distort the line). A blank [playerId] never matches any seat, so an
 * empty list is returned. Points are ordered oldest-to-newest by
 * [GameRecord.createdAtEpochMs] (the repository hands lists sorted by updatedAt,
 * so an explicit sort is required for correctness). Reuses the [TrendPoint] type
 * declared in TrendStats.kt.
 */
fun threeDartAvgTrendById(playerId: String, games: List<GameRecord>): List<TrendPoint> {
    if (playerId.isBlank()) return emptyList()
    val points = ArrayList<TrendPoint>()
    for (r in games) {
        if (!r.isFinished) continue
        val state = r.state as? X01State ?: continue
        val idx = state.players.indexOfFirst { it.id == playerId }
        if (idx < 0) continue
        val legStates = state.allLegStatesFor(idx)
        // Skip games the player did not actually throw in.
        if (legStates.all { it.turns.isEmpty() }) continue
        val avg = X01Stats.threeDartAverage(legStates, state.startScore)
        points.add(TrendPoint(timeMs = r.createdAtEpochMs, threeDartAvg = avg))
    }
    points.sortBy { it.timeMs }
    return points
}

/**
 * Head-to-head record for [playerId] against a single opponent: across every
 * game that included BOTH players (any mode), how many were [played], [won] by
 * the player, and [lost] (the opponent won and the player did not).
 */
data class H2HRecord(
    val opponentId: String,
    val opponentName: String,
    val played: Int,
    val won: Int,
    val lost: Int,
)

/**
 * Head-to-head records for [playerId] vs every other player they have shared a
 * game with, across ALL modes.
 *
 * A game counts toward an opponent only when BOTH the player and that opponent
 * occupy a seat (matched by stable id). For each shared game:
 *  - [H2HRecord.won]  += 1 when [playerId] is in `winnerIndices` and the
 *    opponent is NOT.
 *  - [H2HRecord.lost] += 1 when the opponent is in `winnerIndices` and the
 *    player is NOT.
 *  - Ties / both-win / neither-win count toward neither (only [played]).
 *
 * Opponents are keyed by id; the most-recently-seen display name is reported.
 * A blank [playerId] yields an empty list. Sorted by [H2HRecord.played] desc,
 * then by opponent name (case-insensitive) asc. Pure and unit-testable.
 */
fun headToHead(playerId: String, games: List<GameRecord>): List<H2HRecord> {
    if (playerId.isBlank()) return emptyList()

    data class Acc(var name: String, var played: Int = 0, var won: Int = 0, var lost: Int = 0)
    // Preserve first-seen order for stable sorting of equal keys.
    val byOpponent = LinkedHashMap<String, Acc>()

    for (r in games) {
        val seats = r.state.players
        val myIdx = seats.indexOfFirst { it.id == playerId }
        if (myIdx < 0) continue
        val winners = r.state.winnerIndices
        val iWon = winners.contains(myIdx)

        seats.forEachIndexed { oppIdx, opp ->
            if (oppIdx == myIdx) return@forEachIndexed
            if (opp.id.isBlank() || opp.id == playerId) return@forEachIndexed
            val oppWon = winners.contains(oppIdx)
            val acc = byOpponent.getOrPut(opp.id) { Acc(opp.name) }
            acc.name = opp.name
            acc.played++
            if (iWon && !oppWon) acc.won++
            if (oppWon && !iWon) acc.lost++
        }
    }

    return byOpponent.entries
        .map { (id, a) -> H2HRecord(id, a.name, a.played, a.won, a.lost) }
        .sortedWith(compareByDescending<H2HRecord> { it.played }
            .thenBy { it.opponentName.lowercase() })
}
