package com.dartrack.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The logged-in user's own stats as uploaded to `PUT /v1/me/stats`, so friends
 * can see them. Computed on this phone over the "me" player's local history by
 * the same functions the local stats screens use, and rendered on a friend's
 * phone by the same UI: [stats] drives Player stats / Leaderboards, [records]
 * the personal bests (and the Statistics overview card), [trend] the 3-dart
 * average chart and [achievements] the Achievements board. All-time only.
 *
 * The server treats it as opaque JSON. [version] lets a future app change the
 * shape; unknown fields are ignored when decoding.
 */
@Serializable
data class StatsSnapshot(
    val version: Int = SNAPSHOT_VERSION,
    val stats: PlayerStatsData,
    val records: PlayerRecords,
    val trend: List<TrendPoint> = emptyList(),
    val achievements: List<AchievementProgress> = emptyList(),
) {
    companion object {
        const val SNAPSHOT_VERSION = 1
    }
}

/** One achievement's state in a [StatsSnapshot] (the catalog text stays in the app). */
@Serializable
data class AchievementProgress(
    val id: String,
    val unlocked: Boolean,
    val progress: Int,
    val target: Int,
    val unlockedAtMs: Long? = null,
)

/** Snapshot of [playerId]'s all-time stats over [games]. */
fun buildStatsSnapshot(playerId: String, games: List<GameRecord>): StatsSnapshot =
    StatsSnapshot(
        stats = playerStats(playerId, games),
        records = playerRecords(playerId, games),
        trend = threeDartAvgTrendById(playerId, games),
        achievements = achievementsFor(playerId, games).map {
            AchievementProgress(it.achievement.id, it.unlocked, it.progress, it.target, it.unlockedAtMs)
        },
    )

fun snapshotToJson(snapshot: StatsSnapshot): JsonObject =
    GameJson.format.encodeToJsonElement(StatsSnapshot.serializer(), snapshot).jsonObject

/** A friend's snapshot decoded; null if it's unreadable by this app version. */
fun snapshotFromJson(json: JsonObject): StatsSnapshot? =
    runCatching { GameJson.format.decodeFromJsonElement(StatsSnapshot.serializer(), json) }.getOrNull()

/**
 * The snapshot's achievements as the board's [AchievementStatus] list, in
 * catalog order; an achievement the snapshot doesn't mention (e.g. one added in
 * a newer app version) shows as locked.
 */
fun StatsSnapshot.achievementStatuses(): List<AchievementStatus> {
    val byId = achievements.associateBy { it.id }
    return AchievementCatalog.all.map { a ->
        val p = byId[a.id]
        AchievementStatus(
            achievement = a,
            unlocked = p?.unlocked ?: false,
            progress = p?.progress ?: 0,
            target = p?.target ?: 1,
            unlockedAtMs = p?.unlockedAtMs,
        )
    }
}

fun StatsSnapshot.achievementSummary(): AchievementSummary =
    AchievementSummary(unlocked = achievements.count { it.unlocked }, total = AchievementCatalog.all.size)

/**
 * The Statistics overview card ([PlayerStats], normally built per name by
 * [StatsAggregator]) for a friend, from their snapshot.
 */
fun StatsSnapshot.toOverview(name: String): PlayerStats = PlayerStats(
    name = name,
    gamesPlayed = stats.gamesPlayed,
    gamesWon = stats.gamesWon,
    x01ThreeDartAvg = stats.x01ThreeDartAvg,
    x01HighestTurn = records.highestVisit,
    x01HighestCheckout = records.highestCheckout,
    x01GamesPlayed = stats.x01GamesPlayed,
    x01OneEighties = stats.x01OneEighties,
    x01TonPlus = stats.x01TonPlus,
    x01OneFortyPlus = stats.x01OneFortyPlus,
    x01FirstNineAvg = stats.x01FirstNineAvg,
    x01CheckoutPct = stats.x01CheckoutPct,
    x01CheckoutHits = stats.x01CheckoutHits,
    x01CheckoutAttempts = stats.x01CheckoutAttempts,
    x01BestLegDarts = stats.x01BestLegDarts,
    x01AvgDartsPerLeg = stats.x01AvgDartsPerLeg,
    x01LegsWon = stats.x01LegsWon,
    x01SetsWon = stats.x01SetsWon,
    x01MatchesWon = stats.x01MatchesWon,
    cricketGamesPlayed = stats.cricket.gamesPlayed,
    cricketGamesWon = stats.cricket.gamesWon,
    halfItGamesPlayed = stats.halfIt.gamesPlayed,
    halfItHighScore = stats.halfIt.best,
)
