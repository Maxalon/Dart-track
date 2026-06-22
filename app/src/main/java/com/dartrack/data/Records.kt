package com.dartrack.data

import com.dartrack.model.Player
import com.dartrack.model.X01State
import com.dartrack.model.X01Stats
import kotlin.math.roundToInt

/**
 * Leaderboards & records: cross-player rankings and per-player "personal bests",
 * computed purely from stored game history plus the player registry. This is a
 * thin aggregation layer ON TOP of [playerStats] — it never re-derives metric
 * math, it only selects a metric, filters, ranks, and formats it. A player's
 * per-game stats come from `playerStats(player.id, games)` exactly as the rest of
 * the app sees them, so a leaderboard cell can never disagree with the player's
 * own stats screen.
 *
 * All logic here is pure and Android-free so it can be unit-tested without a
 * Context or any file IO.
 */

/**
 * A rankable metric. [label] is a short human title for the column; [higherIsBetter]
 * controls the sort direction (true ⇒ descending, the bigger number ranks #1;
 * false ⇒ ascending, the smaller number ranks #1, as for [FEWEST_DARTS_LEG]).
 *
 * Every category maps to a field already exposed by [PlayerStatsData] so the math
 * is never duplicated here. Some categories are X01-only and have a "qualifies"
 * gate beyond [minGames] (e.g. a player with no X01 games has no 3-dart average to
 * rank); see [qualifies] / [metric] in the leaderboard implementation.
 */
enum class LeaderboardCategory(val label: String, val higherIsBetter: Boolean) {
    /** Total games won across all modes. */
    MOST_WINS("Most Wins", higherIsBetter = true),
    /** Total games played across all modes. */
    MOST_GAMES("Most Games", higherIsBetter = true),
    /** Win fraction across all modes, shown as a percentage. */
    WIN_PCT("Win %", higherIsBetter = true),
    /** X01 dart-weighted 3-dart average (X01 games required). */
    BEST_THREE_DART_AVG("Best 3-Dart Avg", higherIsBetter = true),
    /** Total 180s thrown in X01 (X01 games required). */
    MOST_180S("Most 180s", higherIsBetter = true),
    /** X01 checkout %, shown as a percentage (a checkout opportunity required). */
    BEST_CHECKOUT_PCT("Best Checkout %", higherIsBetter = true),
    /** Fewest darts to finish a won X01 leg — LOWER is better (a won leg required). */
    FEWEST_DARTS_LEG("Fewest Darts (Leg)", higherIsBetter = false),
}

/**
 * One ranked row on a leaderboard. [value] is the raw metric (a Double so every
 * category shares one type; integer metrics are stored exactly, e.g. 12.0).
 * [display] is the pre-formatted human string for that value. [rank] is 1-based
 * with standard competition ranking — equal values share a rank and the next rank
 * skips accordingly ("1, 2, 2, 4").
 */
data class LeaderboardEntry(
    val playerId: String,
    val playerName: String,
    val value: Double,
    val display: String,
    val rank: Int,
)

/**
 * Build the leaderboard for [category] over [games], ranking the registered
 * [players]. For each player we compute `playerStats(player.id, games)` and read
 * off the category's metric; a player is dropped when they have fewer than
 * [minGames] games OR they don't qualify for an X01-only category (no X01 games /
 * no checkout opportunity / no won leg). Survivors are sorted by the metric
 * honoring [LeaderboardCategory.higherIsBetter]; equal metric values are ordered
 * by player name (case-insensitive) for a stable, deterministic result and SHARE
 * a rank (competition ranking "1, 2, 2, 4"). Returns an empty list when there are
 * no qualifying players (including empty inputs).
 */
fun leaderboard(
    category: LeaderboardCategory,
    games: List<GameRecord>,
    players: List<Player>,
    minGames: Int = 1,
): List<LeaderboardEntry> {
    // One stats payload per registered player (deduped by id; the registry should
    // already be unique, but a duplicate id would otherwise double-rank a player).
    val computed = players
        .distinctBy { it.id }
        .mapNotNull { player ->
            val stats = playerStats(player.id, games)
            if (stats.gamesPlayed < minGames) return@mapNotNull null
            if (!category.qualifies(stats)) return@mapNotNull null
            Triple(player, stats, category.metric(stats))
        }

    // Sort by the metric in the category's direction, breaking ties by name
    // (case-insensitive) so equal values have a deterministic, stable order.
    val byMetric: Comparator<Triple<Player, PlayerStatsData, Double>> =
        if (category.higherIsBetter) compareByDescending { it.third }
        else compareBy { it.third }
    val sorted = computed.sortedWith(
        byMetric.thenBy { it.first.name.lowercase() },
    )

    // Assign 1-based competition ranks: equal metric values share a rank, and the
    // following rank is the row's 1-based position (so ranks read "1, 2, 2, 4").
    val out = ArrayList<LeaderboardEntry>(sorted.size)
    var lastValue: Double? = null
    var lastRank = 0
    sorted.forEachIndexed { index, (player, _, value) ->
        val rank = if (lastValue != null && value == lastValue) lastRank else index + 1
        lastValue = value
        lastRank = rank
        out.add(
            LeaderboardEntry(
                playerId = player.id,
                playerName = player.name,
                value = value,
                display = category.format(value),
                rank = rank,
            ),
        )
    }
    return out
}

/** Every leaderboard keyed by category (same options as [leaderboard]). */
fun allLeaderboards(
    games: List<GameRecord>,
    players: List<Player>,
    minGames: Int = 1,
): Map<LeaderboardCategory, List<LeaderboardEntry>> =
    LeaderboardCategory.values().associateWith { leaderboard(it, games, players, minGames) }

/**
 * Whether [stats] has any data to rank for this category beyond the [minGames]
 * gate. The overall categories always qualify once the player has played; the
 * X01-only categories require the relevant denominator to be non-zero so a player
 * who never threw an X01 game (or never had a checkout chance / won leg) is
 * excluded rather than ranked with a meaningless 0.
 */
private fun LeaderboardCategory.qualifies(stats: PlayerStatsData): Boolean = when (this) {
    LeaderboardCategory.MOST_WINS, LeaderboardCategory.MOST_GAMES, LeaderboardCategory.WIN_PCT -> true
    LeaderboardCategory.BEST_THREE_DART_AVG, LeaderboardCategory.MOST_180S -> stats.x01GamesPlayed > 0
    LeaderboardCategory.BEST_CHECKOUT_PCT -> stats.x01CheckoutAttempts > 0
    LeaderboardCategory.FEWEST_DARTS_LEG -> stats.x01BestLegDarts > 0
}

/** The raw Double metric this category ranks, read straight off [stats]. */
private fun LeaderboardCategory.metric(stats: PlayerStatsData): Double = when (this) {
    LeaderboardCategory.MOST_WINS -> stats.gamesWon.toDouble()
    LeaderboardCategory.MOST_GAMES -> stats.gamesPlayed.toDouble()
    LeaderboardCategory.WIN_PCT -> stats.winPct
    LeaderboardCategory.BEST_THREE_DART_AVG -> stats.x01ThreeDartAvg
    LeaderboardCategory.MOST_180S -> stats.x01OneEighties.toDouble()
    LeaderboardCategory.BEST_CHECKOUT_PCT -> stats.x01CheckoutPct
    LeaderboardCategory.FEWEST_DARTS_LEG -> stats.x01BestLegDarts.toDouble()
}

/**
 * Human formatting for a metric [value]: percentages render as a whole-percent
 * string ("67%"), averages keep one decimal ("92.4"), and the count / dart
 * categories render as plain integers ("12"). Kept here so a leaderboard cell and
 * any other surface format identically.
 */
private fun LeaderboardCategory.format(value: Double): String = when (this) {
    LeaderboardCategory.WIN_PCT, LeaderboardCategory.BEST_CHECKOUT_PCT ->
        "${(value * 100).roundToInt()}%"
    LeaderboardCategory.BEST_THREE_DART_AVG -> oneDecimal(value)
    LeaderboardCategory.MOST_WINS, LeaderboardCategory.MOST_GAMES,
    LeaderboardCategory.MOST_180S, LeaderboardCategory.FEWEST_DARTS_LEG ->
        value.roundToInt().toString()
}

/** Format a Double to exactly one decimal place without locale/String.format. */
private fun oneDecimal(value: Double): String {
    val scaled = (value * 10).roundToInt()
    val whole = scaled / 10
    val frac = if (scaled < 0) -scaled % 10 else scaled % 10
    return "$whole.$frac"
}

/**
 * A compact "personal bests" summary for ONE player, derived from their game
 * history. The overall / aggregate figures ([gamesPlayed], [gamesWon], [winPct],
 * [total180s]) and the cross-game bests already maintained by [playerStats]
 * ([bestLegDarts], [bestCheckoutPct]) are taken verbatim from that payload; the
 * SINGLE-GAME highs that [playerStats] does not expose ([bestGameThreeDartAvg],
 * [most180sInAGame], [highestCheckout]) are computed here from the X01 legs. The
 * per-mode highs mirror [ModeSummary.best] for the practice modes.
 *
 * Everything is 0 (or 0.0) when the player has no qualifying data, e.g. a player
 * who never threw an X01 game reports zero across all the X01 bests.
 */
data class PlayerRecords(
    val playerId: String,
    // Overall.
    val gamesPlayed: Int,
    val gamesWon: Int,
    val winPct: Double,
    // X01 lifetime bests.
    val total180s: Int,
    /** Fewest darts to finish a won leg (0 if none). Mirrors playerStats. */
    val bestLegDarts: Int,
    /** Best lifetime checkout % (0.0 if no opportunities). Mirrors playerStats. */
    val bestCheckoutPct: Double,
    /** Highest 3-dart average in any single X01 game (0.0 if none). */
    val bestGameThreeDartAvg: Double,
    /** Most 180s thrown in any single X01 game (0). */
    val most180sInAGame: Int,
    /** Highest finishing checkout across all X01 legs (0 if none). */
    val highestCheckout: Int,
    // Per-mode single-game highs (mirror ModeSummary.best; 0 when never played).
    val bestCountUp: Int,
    val bestCheckoutTrainerHits: Int,
    val bestShanghai: Int,
    val bestHalfIt: Int,
    val bestBobs27: Int,
    val bestCatch40: Int,
    /** Fewest darts to clear the board in a won Around-the-Clock game (0 if none). */
    val bestAroundTheClockDarts: Int,
)

/**
 * "Personal bests" for [playerId]. Reuses `playerStats(playerId, games)` for every
 * aggregate and cross-game best, then makes ONE extra pass over the player's X01
 * games to pull the single-game highs that the aggregate payload does not carry
 * (best per-game average, most 180s in one game, highest checkout via
 * [X01Stats.highestCheckout]). Seat resolution matches [playerStats] exactly
 * (first seat whose id equals [playerId]); a blank id matches nothing, so an
 * all-zero summary is returned.
 */
fun playerRecords(playerId: String, games: List<GameRecord>): PlayerRecords {
    val stats = playerStats(playerId, games)

    var bestGameAvg = 0.0
    var most180sInGame = 0
    var highestCheckout = 0
    if (playerId.isNotBlank()) {
        for (r in games) {
            val state = r.state as? X01State ?: continue
            val idx = state.players.indexOfFirst { it.id == playerId }
            if (idx < 0) continue
            val legStates = state.allLegStatesFor(idx)
            if (legStates.all { it.turns.isEmpty() }) continue // never threw -> skip

            val gameAvg = X01Stats.threeDartAverage(legStates, state.startScore)
            if (gameAvg > bestGameAvg) bestGameAvg = gameAvg

            val gameOneEighties = legStates.sumOf { ps ->
                ps.turns.count { !it.bust && it.entered == 180 }
            }
            if (gameOneEighties > most180sInGame) most180sInGame = gameOneEighties

            X01Stats.highestCheckout(legStates)?.let { co ->
                if (co > highestCheckout) highestCheckout = co
            }
        }
    }

    return PlayerRecords(
        playerId = playerId,
        gamesPlayed = stats.gamesPlayed,
        gamesWon = stats.gamesWon,
        winPct = stats.winPct,
        total180s = stats.x01OneEighties,
        bestLegDarts = stats.x01BestLegDarts,
        bestCheckoutPct = stats.x01CheckoutPct,
        bestGameThreeDartAvg = bestGameAvg,
        most180sInAGame = most180sInGame,
        highestCheckout = highestCheckout,
        bestCountUp = stats.countUp.best,
        bestCheckoutTrainerHits = stats.checkoutTrainer.best,
        bestShanghai = stats.shanghai.best,
        bestHalfIt = stats.halfIt.best,
        bestBobs27 = stats.bobs27.best,
        bestCatch40 = stats.catch40.best,
        bestAroundTheClockDarts = stats.aroundTheClock.best,
    )
}
