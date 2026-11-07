package com.dartrack.data

import com.dartrack.data.SyncFixtures.bot
import com.dartrack.data.SyncFixtures.finishedX01
import com.dartrack.data.SyncFixtures.seat
import com.dartrack.model.Player
import com.dartrack.net.ApiClient
import com.dartrack.net.FriendEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SyncManager] end to end: real repositories on temp files, talking through
 * the real [ApiClient] to a [FakeDartServer] behind a [MockWebServer]. Each
 * "phone" is its own directory. Covers upload → download on a friend's phone
 * → delete, the first-login batch upload and paged download, the guest →
 * friend transfer, and the stats snapshot.
 */
class SyncManagerTest {

    private lateinit var server: MockWebServer
    private val fake = FakeDartServer()
    private val dirs = ArrayList<File>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = fake
        server.start()
        fake.addUser("tA", "uA", "max")
        fake.addUser("tB", "uB", "anna")
        fake.befriend("uA", "uB")
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.shutdown() }
        dirs.forEach { it.deleteRecursively() }
    }

    /** Launched work never runs: the tests drive [SyncManager.sync] themselves. */
    private val inert = CoroutineScope(object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    })

    private inner class Phone(token: String, private val userId: String, private val username: String) {
        val dir: File = Files.createTempDirectory("phone").toFile().also { dirs.add(it) }
        val players = PlayerRepository(File(dir, "players.json"))
        val games = GameRepository(File(dir, "games.json"))
        val accounts = AccountRepository(File(dir, "account.json"), ApiClient(server.url("/").toString()), players)
        val friends = FriendsRepository(File(dir, "friends.json"), accounts, players)
        var now = 100 * UNFINISHED_UPLOAD_DELAY_MS
        val sync = SyncManager(File(dir, "sync.json"), accounts, friends, players, games, meta = null, clock = { now }, scope = inert)
        lateinit var me: Player
        private val token = token

        suspend fun boot(meName: String): Phone {
            players.load()
            games.load()
            me = players.addPlayer(meName)
            players.linkToAccount(me.id, userId)
            File(dir, "account.json").writeText(encodeAccount(Account(token, userId, username, me.id)))
            accounts.load()
            friends.load()
            sync.load()
            return this
        }

        suspend fun add(vararg records: GameRecord) = records.forEach { games.upsert(it) }
        fun gameIds() = games.games.value.map { it.id }.toSet()
        fun game(id: String) = games.games.value.single { it.id == id }
    }

    private fun count(request: String) = fake.requests.count { it == request }

    @Test
    fun upload_downloadOnTheFriendsPhone_thenDeletes() = runBlocking<Unit> {
        val a = Phone("tA", "uA", "max").boot("Max")
        val annaOnA = a.players.playerForAccount("uB", "anna")
        val gary = a.players.addPlayer("Gary")
        a.add(
            finishedX01("g1", listOf(seat(a.me), seat(annaOnA), seat(gary))),
            finishedX01("guests", listOf(seat(gary), bot())),
            finishedX01("solo", listOf(seat(a.me))),
        )
        assertIs<SyncOutcome.Done>(a.sync.sync())
        assertEquals(setOf("g1", "solo"), fake.games.keys, "guest/CPU-only games never leave the phone")
        assertEquals(mapOf("uA" to 0, "uB" to 1), fake.participants.getValue("g1").mapValues { it.value.seat })
        assertNotNull(a.sync.status.value.lastSyncedAt)
        assertNull(a.sync.status.value.lastError)

        // Anna's phone: the shared game arrives, her own seat is her me player,
        // Max becomes her friend's player, Gary stays a remote guest.
        val b = Phone("tB", "uB", "anna").boot("Anna")
        b.players.addPlayer("Gary") // her own, unrelated guest Gary
        assertIs<SyncOutcome.Done>(b.sync.sync())
        assertEquals(setOf("g1"), b.gameIds())
        val seats = b.game("g1").state.players
        val maxOnB = b.players.findByAccount("uA")!!
        assertEquals(listOf(maxOnB.id, b.me.id, remoteSeatId("g1", 2)), seats.map { it.id })
        assertEquals(listOf("max", "Anna", "Gary"), seats.map { it.name })
        assertEquals(false, maxOnB.hidden, "a friend is a normal player")
        assertEquals(0, playerStats(b.players.all().first { it.name == "Gary" }.id, b.games.games.value).gamesPlayed)

        // Again: nothing new locally, nothing bounces back up.
        val playersBefore = b.players.all()
        val uploadsBefore = count("POST /v1/games")
        assertIs<SyncOutcome.Done>(b.sync.sync())
        assertEquals(playersBefore, b.players.all())
        assertEquals(setOf("g1"), b.gameIds())
        assertEquals(uploadsBefore, count("POST /v1/games"))

        // Anna deletes it: gone from her history only; Max keeps his.
        b.sync.deleteGame("g1")
        assertEquals(listOf("g1"), b.sync.state.value!!.pendingDeletes)
        assertIs<SyncOutcome.Done>(b.sync.sync())
        assertTrue(b.sync.state.value!!.pendingDeletes.isEmpty())
        assertNotNull(fake.participants.getValue("g1").getValue("uB").deletedAt)
        assertIs<SyncOutcome.Done>(a.sync.sync())
        assertTrue("g1" in a.gameIds())
        // Max deletes too: the server purges it.
        a.sync.deleteGame("g1")
        assertIs<SyncOutcome.Done>(a.sync.sync())
        assertTrue("g1" in fake.purged)
    }

    @Test
    fun deleteOnAnotherPhoneOfTheSameAccount_removesItHere_andOfflineDeletesQueue() = runBlocking<Unit> {
        val a1 = Phone("tA", "uA", "max").boot("Max")
        a1.add(finishedX01("solo", listOf(seat(a1.me))))
        a1.sync.sync()
        val a2 = Phone("tA", "uA", "max").boot("Max")
        a2.sync.sync()
        assertEquals(setOf("solo"), a2.gameIds())

        // Offline: the delete waits in the queue.
        server.shutdown()
        a2.sync.deleteGame("solo")
        assertIs<SyncOutcome.Failed>(a2.sync.sync())
        assertEquals(listOf("solo"), a2.sync.state.value!!.pendingDeletes)
        assertNotNull(a2.sync.status.value.lastError)

        server = MockWebServer().apply { dispatcher = fake }
        server.start()
        val a2b = Phone("tA", "uA", "max") // same data dir, new server URL
        a2.dir.copyRecursively(a2b.dir, overwrite = true)
        a2b.players.load(); a2b.games.load(); a2b.accounts.load(); a2b.friends.load(); a2b.sync.load()
        assertIs<SyncOutcome.Done>(a2b.sync.sync())
        assertTrue(a2b.sync.state.value!!.pendingDeletes.isEmpty())

        val a1b = Phone("tA", "uA", "max")
        a1.dir.copyRecursively(a1b.dir, overwrite = true)
        a1b.players.load(); a1b.games.load(); a1b.accounts.load(); a1b.friends.load(); a1b.sync.load()
        assertIs<SyncOutcome.Done>(a1b.sync.sync())
        assertTrue(a1b.gameIds().isEmpty(), "deleted for this account on the other phone")
    }

    @Test
    fun firstSync_uploadsHistoryInBatches_andTheFriendPagesTheDownload() = runBlocking<Unit> {
        val a = Phone("tA", "uA", "max").boot("Max")
        val annaOnA = a.players.playerForAccount("uB", "anna")
        val history = (1..120).map { finishedX01("g$it", listOf(seat(a.me), seat(annaOnA)), createdAt = it.toLong()) }
        a.add(*history.toTypedArray())
        assertIs<SyncOutcome.Done>(a.sync.sync())
        assertEquals(3, count("POST /v1/games"), "120 games in batches of ${ApiClient.SYNC_BATCH}")
        assertEquals(120, fake.games.size)

        val b = Phone("tB", "uB", "anna").boot("Anna")
        val pagesBefore = count("GET /v1/games")
        assertIs<SyncOutcome.Done>(b.sync.sync())
        assertEquals(120, b.gameIds().size)
        assertTrue(count("GET /v1/games") - pagesBefore >= 2, "paged while hasMore")
        assertEquals(2, b.players.all().size, "Anna and one player for Max")
    }

    @Test
    fun guestTransfer_movesGamesToTheFriend_onceOnly() = runBlocking<Unit> {
        val a = Phone("tA", "uA", "max").boot("Max")
        val gary = a.players.addPlayer("Gary")
        a.add(
            finishedX01("g1", listOf(seat(a.me), seat(gary))),
            finishedX01("g2", listOf(seat(gary), bot())),
        )
        a.sync.sync()
        assertEquals(setOf("g1"), fake.games.keys)
        val anna = FriendEntry("uB", "anna")

        assertNull(a.sync.linkBlockReason(anna))
        val outcome = a.sync.linkGuest(gary.id, anna)
        assertEquals(LinkOutcome.Done(moved = 2), outcome)
        assertNull(a.players.byId(gary.id), "the guest is merged away")
        val annaOnA = a.players.findByAccount("uB")!!
        assertEquals(listOf(a.me.id, annaOnA.id), a.game("g1").state.players.map { it.id })

        assertIs<SyncOutcome.Done>(a.sync.sync())
        assertEquals("L1", fake.participants.getValue("g1").getValue("uB").linkId)
        assertEquals("L1", fake.participants.getValue("g2").getValue("uB").linkId)
        assertTrue(a.sync.state.value!!.pendingLinks.isEmpty())

        val b = Phone("tB", "uB", "anna").boot("Anna")
        b.sync.sync()
        assertEquals(setOf("g1", "g2"), b.gameIds())
        assertEquals(b.me.id, b.game("g2").state.players[0].id)

        // One guest per friend: refused locally without asking the server …
        val other = a.players.addPlayer("Gus")
        val linksBefore = count("POST /v1/links")
        assertIs<LinkOutcome.Blocked>(a.sync.linkGuest(other.id, anna))
        assertEquals(linksBefore, count("POST /v1/links"))
        // … and on another phone of the same account, via GET /v1/links.
        val a2 = Phone("tA", "uA", "max").boot("Max")
        assertEquals(alreadyLinkedMessage("anna"), a2.sync.linkBlockReason(anna))
        // A 409 from the server is reported the same way.
        val a3 = Phone("tA", "uA", "max").boot("Max")
        val g = a3.players.addPlayer("Gil")
        assertEquals(LinkOutcome.Blocked(alreadyLinkedMessage("anna")), a3.sync.linkGuest(g.id, anna))
        // Only accepted friends.
        fake.addUser("tC", "uC", "carl")
        assertIs<LinkOutcome.Blocked>(a3.sync.linkGuest(g.id, FriendEntry("uC", "carl")))
    }

    @Test
    fun statsSnapshot_isUploadedOnce_untilItChanges() = runBlocking<Unit> {
        val a = Phone("tA", "uA", "max").boot("Max")
        a.add(finishedX01("g1", listOf(seat(a.me))))
        a.sync.sync()
        val snapshot = snapshotFromJson(fake.stats.getValue("uA"))!!
        assertEquals(1, snapshot.stats.gamesWon)
        a.sync.sync()
        assertEquals(1, count("PUT /v1/me/stats"), "unchanged stats aren't re-sent")
        a.add(finishedX01("g2", listOf(seat(a.me)), createdAt = 3, updatedAt = 4))
        a.sync.sync()
        assertEquals(2, count("PUT /v1/me/stats"))
        assertEquals(2, snapshotFromJson(fake.stats.getValue("uA"))!!.stats.gamesWon)
    }

    @Test
    fun unfinishedGame_waits24h_thenUploadsAsUnfinished() = runBlocking<Unit> {
        val a = Phone("tA", "uA", "max").boot("Max")
        a.add(SyncFixtures.unfinishedX01("live", listOf(seat(a.me)), updatedAt = a.now - 1000))
        a.sync.sync()
        assertTrue(fake.games.isEmpty())
        a.now += UNFINISHED_UPLOAD_DELAY_MS
        a.sync.sync()
        assertEquals("unfinished", fake.games.getValue("live").status)
    }

    @Test
    fun noMePlayer_orLoggedOut_doesNothing() = runBlocking<Unit> {
        val a = Phone("tA", "uA", "max").boot("Max")
        File(a.dir, "account.json").writeText(encodeAccount(Account("tA", "uA", "max", mePlayerId = null)))
        a.accounts.load()
        assertEquals(SyncOutcome.NoMePlayer, a.sync.sync())
        File(a.dir, "account.json").delete()
        a.accounts.load()
        assertEquals(SyncOutcome.NotLoggedIn, a.sync.sync())
        assertTrue(fake.requests.isEmpty())
    }
}
