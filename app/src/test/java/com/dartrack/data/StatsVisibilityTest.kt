package com.dartrack.data

import com.dartrack.data.SyncFixtures.finishedX01
import com.dartrack.data.SyncFixtures.seat
import com.dartrack.model.GamePlayer
import com.dartrack.model.Player
import com.dartrack.net.ApiResult
import com.dartrack.net.FriendEntry
import com.dartrack.net.FriendStatsResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whose stats the stats screens show logged out vs. logged in, how other
 * phones' guests are kept out, and the friend stats snapshot (build, JSON
 * round-trip, rendering inputs, fetch outcomes).
 */
class StatsVisibilityTest {

    private val me = Player("p-me", "Max", accountId = "uMe")
    private val friend = Player("p-friend", "Anna", accountId = "uF")
    private val exFriend = Player("p-x", "Xavier", accountId = "uX")
    private val guest = Player("p-guest", "Bob")
    private val hidden = Player("p-hidden", "Stranger", accountId = "uS", hidden = true)
    private val players = listOf(me, friend, exFriend, guest, hidden)
    private val account = Account("t", "uMe", "max", mePlayerId = me.id)
    private val friends = listOf(FriendEntry("uF", "anna"), FriendEntry("uNew", "Nina"))

    @Test
    fun loggedOut_everyVisiblePlayerLocally() {
        val subjects = statsSubjects(players, null, friends)
        assertEquals(listOf("Anna", "Bob", "Max", "Xavier"), subjects.map { it.player.name })
        assertTrue(subjects.all { it.source == StatsSource.LOCAL })
    }

    @Test
    fun loggedIn_meAndGuestsLocally_friendsFromSnapshot_nobodyElse() {
        val subjects = statsSubjects(players, account, friends)
        assertEquals(listOf("Max", "Anna", "Bob", "Nina"), subjects.map { it.player.name }, "me first")
        assertEquals(
            listOf(StatsSource.LOCAL, StatsSource.FRIEND, StatsSource.LOCAL, StatsSource.FRIEND),
            subjects.map { it.source },
        )
        assertEquals(friend.id, subjects[1].player.id, "a friend's local player is used")
        assertEquals(friendStandInId("uNew"), subjects[3].player.id, "a friend with no local player gets a stand-in")
        assertTrue(subjects.none { it.player.id == exFriend.id || it.player.id == hidden.id })
    }

    @Test
    fun headToHead_dropsRemoteSeatsAndUncoveredPlayers_keepsCpus() {
        val game = finishedX01(
            "g",
            listOf(
                seat(me), seat(guest), seat(hidden), seat(exFriend),
                GamePlayer("Bob", remoteSeatId("g", 4)), GamePlayer("CPU", "bot:1", isBot = true),
            ),
        )
        val all = headToHead(me.id, listOf(game))
        assertEquals(5, all.size)
        val shown = visibleHeadToHead(all, players, statsSubjects(players, account, friends))
        assertEquals(setOf(guest.id, "bot:1"), shown.map { it.opponentId }.toSet())
    }

    @Test
    fun overview_remoteGuestNeverMergesWithALocalNamesake_friendCardFromSnapshot() {
        val local = finishedX01("local", listOf(seat(guest), seat(me)))
        // From another phone: their guest is also called "Bob" (and wins there).
        val remote = finishedX01("remote", listOf(GamePlayer("Bob", remoteSeatId("remote", 0)), seat(me)))
        val snap = buildStatsSnapshot("their-id", listOf(finishedX01("x", listOf(GamePlayer("A", "their-id")))))
        val overview = statsOverview(
            listOf(local, remote), players, account, friends,
            mapOf("uF" to FriendStats(FriendStatsStatus.OK, snap), "uNew" to FriendStats(FriendStatsStatus.HIDDEN)),
        )
        val bob = overview.single { it.name == "Bob" }
        assertEquals(1, bob.stats!!.gamesPlayed, "only this phone's Bob")
        assertEquals(2, overview.single { it.name == "Max" }.stats!!.gamesPlayed)
        assertTrue(overview.none { isRemoteSeatId(it.name) })
        val anna = overview.single { it.name == "Anna" }
        assertTrue(anna.isFriend)
        assertEquals(1, anna.stats!!.gamesPlayed)
        assertEquals(1, anna.stats!!.gamesWon)
        assertNotNull(anna.friendTrend)
        val nina = overview.single { it.name == "Nina" }
        assertNull(nina.stats)
        assertEquals("Nina hides their stats.", friendStatsNote(nina.name, nina.friendStatus))
        assertTrue(overview.none { it.name == "Xavier" || it.name == "Stranger" })
    }

    @Test
    fun overview_loggedOut_isTheLocalAggregateMinusHiddenAndRemote() {
        val g = finishedX01("g", listOf(seat(guest), seat(friend), seat(hidden), GamePlayer("Zed", remoteSeatId("g", 3))))
        val overview = statsOverview(listOf(g), players, null, friends, emptyMap())
        assertEquals(listOf("Anna", "Bob"), overview.map { it.name })
        assertTrue(overview.none { it.isFriend })
    }

    @Test
    fun leaderboards_rankFriendsOnTheirSnapshot_andSkipFriendsWithoutOne() {
        val games = listOf(finishedX01("g", listOf(seat(me), seat(guest))))
        val friendGames = (1..5).map { finishedX01("f$it", listOf(GamePlayer("Anna", "theirs"))) }
        val snap = buildStatsSnapshot("theirs", friendGames)
        val inputs = leaderboardInputs(
            statsSubjects(players, account, friends),
            mapOf("uF" to FriendStats(FriendStatsStatus.OK, snap), "uNew" to FriendStats(FriendStatsStatus.NONE)),
        )
        assertEquals(listOf("Max", "Anna", "Bob"), inputs.players.map { it.name })
        val board = leaderboard(LeaderboardCategory.MOST_WINS, games, inputs.players, statsOverride = inputs.statsOverride)
        assertEquals(listOf("Anna" to 5.0, "Max" to 1.0, "Bob" to 0.0), board.map { it.playerName to it.value })
    }

    // ---- snapshot ----------------------------------------------------------------

    @Test
    fun snapshot_roundTripsThroughJson_andFeedsTheSameScreens() {
        val games = listOf(
            finishedX01("a", listOf(seat(me), seat(guest)), createdAt = 1),
            finishedX01("b", listOf(seat(guest), seat(me)), createdAt = 2),
        )
        val snap = buildStatsSnapshot(me.id, games)
        assertEquals(playerStats(me.id, games), snap.stats)
        assertEquals(playerRecords(me.id, games), snap.records)
        assertEquals(101, snap.records.highestVisit)
        assertEquals(threeDartAvgTrendById(me.id, games), snap.trend)
        val decoded = snapshotFromJson(snapshotToJson(snap))
        assertEquals(snap, decoded)
        assertEquals(achievementsFor(me.id, games), decoded!!.achievementStatuses())
        assertEquals(achievementSummary(me.id, games), decoded.achievementSummary())
        val overview = decoded.toOverview("Max")
        val local = StatsAggregator.statsFor("Max", games)
        assertEquals(local, overview, "the friend card shows what a local card would")
    }

    @Test
    fun snapshot_unknownAchievementsAreLocked_unreadableSnapshotIsNull() {
        val snap = buildStatsSnapshot(me.id, emptyList()).copy(achievements = emptyList())
        assertTrue(snap.achievementStatuses().none { it.unlocked })
        assertEquals(AchievementCatalog.all.size, snap.achievementStatuses().size)
        assertNull(snapshotFromJson(JsonObject(mapOf("version" to JsonPrimitive(99)))))
    }

    @Test
    fun friendStatsFetch_outcomes() {
        val snap = snapshotToJson(buildStatsSnapshot(me.id, emptyList()))
        val ok = nextFriendStats(null, ApiResult.Ok(FriendStatsResponse(snap, computedAt = 5)), 9)
        assertEquals(FriendStatsStatus.OK, ok!!.status)
        assertEquals(5, ok.computedAt)
        assertEquals(FriendStatsStatus.HIDDEN, nextFriendStats(ok, ApiResult.HttpError(403, "stats_hidden", null), 9)!!.status)
        assertEquals(FriendStatsStatus.NONE, nextFriendStats(ok, ApiResult.HttpError(404, "no_stats", null), 9)!!.status)
        assertNull(nextFriendStats(ok, ApiResult.HttpError(403, "not_friends", null), 9))
        assertEquals(ok, nextFriendStats(ok, ApiResult.NetworkError(IOException("offline")), 9), "offline keeps the cache")
        assertEquals(
            FriendStatsStatus.UNREADABLE,
            nextFriendStats(null, ApiResult.Ok(FriendStatsResponse(JsonObject(emptyMap()))), 9)!!.status,
        )
        val cache = FriendStatsCache("uMe", mapOf("uF" to ok))
        assertEquals(cache, decodeFriendStatsCache(encodeFriendStatsCache(cache)))
        assertEquals(cache.entries, friendStatsFor(cache, "uMe"))
        assertTrue(friendStatsFor(cache, "other").isEmpty())
    }

    @Test
    fun pickersHideHiddenPlayers() {
        val options = playerOptions(players, friends, me.id)
        assertTrue(options.none { it.player?.id == hidden.id })
        assertTrue(eligibleMePlayers(players, "uMe").none { it.hidden })
        assertEquals(players - hidden, visiblePlayers(players))
    }
}
