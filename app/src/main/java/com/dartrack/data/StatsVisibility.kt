package com.dartrack.data

import com.dartrack.model.Player
import com.dartrack.net.FriendEntry

/*
 * Whose stats the stats screens (Statistics, Player stats, Leaderboards,
 * Achievements) show, and where they come from. Pure and Android-free.
 *
 * Logged out: every visible local player, computed from local games (as before
 * accounts existed).
 * Logged in: the "me" player and local guests (players with no account),
 * computed locally — plus each accepted friend, whose numbers come from the
 * snapshot they uploaded themselves (their full history, not just the games on
 * this phone). Hidden players (synced strangers), players linked to an account
 * that isn't a friend, and other phones' guest seats ("remote:" ids) never
 * appear.
 */

/** Where a [StatsSubject]'s numbers come from. */
enum class StatsSource {
    /** Computed on this phone from local games. */
    LOCAL,

    /** The friend's own uploaded snapshot (see [FriendStatsRepository]). */
    FRIEND,
}

/**
 * One player the stats screens offer. For a friend with no local player yet,
 * [player] is a stand-in with id [friendStandInId] (never stored).
 */
data class StatsSubject(val player: Player, val source: StatsSource) {
    val isFriend: Boolean get() = source == StatsSource.FRIEND
}

/** Id of the never-stored stand-in player for a friend who has no local player. */
fun friendStandInId(accountId: String): String = "friend:$accountId"

/**
 * The players the stats screens cover, sorted by name with the me player first
 * (when logged in). See the file comment for the rules.
 */
fun statsSubjects(players: List<Player>, account: Account?, friends: List<FriendEntry>): List<StatsSubject> {
    val visible = players.filterNot { it.hidden }
    if (account == null) {
        return visible.map { StatsSubject(it, StatsSource.LOCAL) }.sortedBy { it.player.name.lowercase() }
    }
    val isMe = { p: Player -> p.id == account.mePlayerId || (p.accountId != null && p.accountId == account.userId) }
    val local = visible.filter { isMe(it) || it.accountId == null }.map { StatsSubject(it, StatsSource.LOCAL) }
    val friendSubjects = friends.filter { it.id != account.userId }.map { f ->
        // A friend's player may still be hidden if they were a stranger when one
        // of their games synced; it's still their player.
        val player = players.firstOrNull { it.accountId == f.id }?.copy(hidden = false)
            ?: Player(id = friendStandInId(f.id), name = f.username, accountId = f.id)
        StatsSubject(player, StatsSource.FRIEND)
    }
    return (local + friendSubjects).sortedWith(
        compareBy<StatsSubject>({ !isMe(it.player) }, { it.player.name.lowercase() }),
    )
}

/**
 * Head-to-head rows worth showing for the selected player: opponents that are
 * other phones' guest seats, or registry players the stats screens don't cover
 * (hidden strangers, non-friend accounts), are dropped. CPU opponents stay.
 */
fun visibleHeadToHead(
    records: List<H2HRecord>,
    players: List<Player>,
    subjects: List<StatsSubject>,
): List<H2HRecord> {
    val registryIds = players.mapTo(HashSet()) { it.id }
    val shown = subjects.mapTo(HashSet()) { it.player.id }
    return records.filter { r ->
        !isRemoteSeatId(r.opponentId) && (r.opponentId !in registryIds || r.opponentId in shown)
    }
}

/**
 * [games] with every "remote:" seat renamed to its id, for the name-keyed
 * [StatsAggregator]: another phone's guest "Bob" must not merge with this
 * phone's "Bob". Ids and everything else are unchanged.
 */
fun gamesForNameStats(games: List<GameRecord>): List<GameRecord> = games.map { r ->
    if (r.state.players.none { isRemoteSeatId(it.id) }) {
        r
    } else {
        r.copy(state = r.state.withPlayers(r.state.players.map { if (isRemoteSeatId(it.id)) it.copy(name = it.id) else it }))
    }
}

/**
 * One card of the Statistics overview: a locally computed [stats] (name-keyed,
 * as before), or a friend's — from their snapshot when [friendStatus] is OK.
 */
data class OverviewEntry(
    val name: String,
    val stats: PlayerStats?,
    val isFriend: Boolean = false,
    /** For a friend: the state of their snapshot (null = not fetched yet). */
    val friendStatus: FriendStatsStatus? = null,
    /** For a friend: the 3-dart-average trend from their snapshot. */
    val friendTrend: List<TrendPoint>? = null,
)

/**
 * The Statistics overview list: [StatsAggregator] over local games (without
 * remote seats or names of players not covered by [statsSubjects]), plus a
 * card per friend from [friendStats] (keyed by account id). Sorted by name.
 */
fun statsOverview(
    games: List<GameRecord>,
    players: List<Player>,
    account: Account?,
    friends: List<FriendEntry>,
    friendStats: Map<String, FriendStats>,
): List<OverviewEntry> {
    val subjects = statsSubjects(players, account, friends)
    val localIds = subjects.filter { it.source == StatsSource.LOCAL }.mapTo(HashSet()) { it.player.id }
    val excludedNames = players.filter { it.id !in localIds }.mapTo(HashSet()) { normalizePlayerName(it.name) }
    val local = StatsAggregator.aggregate(gamesForNameStats(games))
        .filter { !isRemoteSeatId(it.name) && normalizePlayerName(it.name) !in excludedNames }
        .map { OverviewEntry(it.name, it) }
    val remote = subjects.filter { it.isFriend }.map { s ->
        val fs = s.player.accountId?.let { friendStats[it] }
        val snapshot = fs?.snapshot?.takeIf { fs.status == FriendStatsStatus.OK }
        OverviewEntry(
            s.player.name,
            snapshot?.toOverview(s.player.name),
            isFriend = true,
            friendStatus = fs?.status,
            friendTrend = snapshot?.trend,
        )
    }
    return (local + remote).sortedBy { it.name.lowercase() }
}

/** Plain-language line for a friend whose stats can't be shown, or null when they can. */
fun friendStatsNote(name: String, status: FriendStatsStatus?): String? = when (status) {
    FriendStatsStatus.OK -> null
    FriendStatsStatus.HIDDEN -> "$name hides their stats."
    FriendStatsStatus.NONE -> "$name has no stats yet."
    FriendStatsStatus.UNREADABLE -> "$name's stats need a newer version of the app."
    null -> "$name's stats haven't loaded yet (they load when you're online)."
}

/** Who the leaderboards rank, and the snapshot stats that replace local ones for friends. */
data class LeaderboardInputs(val players: List<Player>, val statsOverride: Map<String, PlayerStatsData>)

/**
 * Leaderboard inputs from [subjects]: local subjects rank on local games;
 * friends rank on their snapshot's stats and are left out while they have no
 * readable snapshot ([friendStats] keyed by account id).
 */
fun leaderboardInputs(subjects: List<StatsSubject>, friendStats: Map<String, FriendStats>): LeaderboardInputs {
    val override = HashMap<String, PlayerStatsData>()
    val players = subjects.mapNotNull { s ->
        if (!s.isFriend) return@mapNotNull s.player
        val fs = s.player.accountId?.let { friendStats[it] }
        val snap = fs?.snapshot?.takeIf { fs.status == FriendStatsStatus.OK } ?: return@mapNotNull null
        override[s.player.id] = snap.stats
        s.player
    }
    return LeaderboardInputs(players, override)
}
