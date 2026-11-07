package com.dartrack.data

import com.dartrack.data.SyncFixtures.bot
import com.dartrack.data.SyncFixtures.everyMode
import com.dartrack.data.SyncFixtures.finishedX01
import com.dartrack.data.SyncFixtures.seat
import com.dartrack.data.SyncFixtures.unfinishedX01
import com.dartrack.model.Player
import com.dartrack.net.GamesPage
import com.dartrack.net.RemoteGame
import com.dartrack.net.RemoteParticipant
import com.dartrack.net.UploadParticipant
import com.dartrack.net.UploadResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of game-history sync (Sync.kt): which games upload and with
 * which participants/status, how results update the bookkeeping, how
 * downloaded games are mapped onto local players for every mode, the
 * sync.json codec and account scoping, the delete queue and the transfer
 * (link) bookkeeping.
 */
class SyncTest {

    // Phone of user "uMe": me player, a friend (uF), a stranger account (uX,
    // linked locally but not a friend), a guest, a hidden synced player.
    private val me = Player("p-me", "Max", accountId = "uMe")
    private val friend = Player("p-friend", "Anna", accountId = "uF")
    private val exFriend = Player("p-x", "Xavier", accountId = "uX")
    private val guest = Player("p-guest", "Guest Gary")
    private val hidden = Player("p-hidden", "Stranger", accountId = "uS", hidden = true)
    private val players = listOf(me, friend, exFriend, guest, hidden)
    private val identity = SyncIdentity(userId = "uMe", mePlayerId = me.id, friendIds = setOf("uF"))
    private val now = 10 * UNFINISHED_UPLOAD_DELAY_MS

    // ---- participants & status ------------------------------------------------

    @Test
    fun participants_areMeAndAcceptedFriendsOnly_withTheirSeats() {
        val r = finishedX01("g", listOf(seat(guest), seat(friend), bot(), seat(exFriend), seat(hidden), seat(me)))
        val out = participantsFor(r, players.associateBy { it.id }, identity)
        assertEquals(listOf(UploadParticipant("uF", 1), UploadParticipant("uMe", 5)), out)
    }

    @Test
    fun participants_anAccountSittingTwiceKeepsItsFirstSeat() {
        val r = finishedX01("g", listOf(seat(me), seat(friend), seat(friend)))
        assertEquals(listOf(0, 1), participantsFor(r, players.associateBy { it.id }, identity).map { it.seat })
    }

    @Test
    fun status_finished_unfinishedOnlyAfter24hUntouched() {
        assertEquals("finished", uploadStatus(finishedX01("a", listOf(seat(me))), now))
        val fresh = unfinishedX01("b", listOf(seat(me)), updatedAt = now - UNFINISHED_UPLOAD_DELAY_MS + 1)
        assertNull(uploadStatus(fresh, now), "in-progress game younger than 24h doesn't upload")
        val stale = unfinishedX01("c", listOf(seat(me)), updatedAt = now - UNFINISHED_UPLOAD_DELAY_MS)
        assertEquals("unfinished", uploadStatus(stale, now))
    }

    // ---- planning uploads -----------------------------------------------------

    @Test
    fun plan_onlyGamesWithAnAccountSeat_neverGuestOrBotOnlyGames() {
        val games = listOf(
            finishedX01("mine", listOf(seat(me), seat(guest))),
            finishedX01("friendOnly", listOf(seat(friend), seat(guest))),
            finishedX01("guests", listOf(seat(guest), bot())),
            finishedX01("strangers", listOf(seat(exFriend), seat(hidden))),
            unfinishedX01("live", listOf(seat(me)), updatedAt = now - 1000),
        )
        val plan = planUploads(games, players, identity, SyncState("uMe"), null, now)
        assertEquals(setOf("mine", "friendOnly"), plan.map { it.upload.id }.toSet())
    }

    @Test
    fun plan_uploadsTheCompleteRecordVerbatim_withModeStatusTimesAndMeta() {
        val r = finishedX01("g", listOf(seat(me), seat(friend)), createdAt = 5, updatedAt = 9)
        val meta = JsonObject(mapOf("appVersion" to JsonPrimitive("0.1.7"), "androidSdk" to JsonPrimitive(35)))
        val up = planUploads(listOf(r), players, identity, SyncState("uMe"), meta, now).single().upload
        assertEquals("X01", up.mode)
        assertEquals("finished", up.status)
        assertEquals(5, up.createdAt)
        assertEquals(9, up.updatedAt)
        assertEquals(meta, up.meta)
        assertEquals(r, recordFromJson(up.record), "record must round-trip to the identical GameRecord")
        assertEquals(listOf(UploadParticipant("uMe", 0), UploadParticipant("uF", 1)), up.participants)
    }

    @Test
    fun plan_skipsWhatTheServerHas_reuploadsOnNewerUpdateOrParticipantChange() {
        val r = finishedX01("g", listOf(seat(me), seat(guest)), updatedAt = 9)
        val first = planUploads(listOf(r), players, identity, SyncState("uMe"), null, now).single()
        val synced = SyncState("uMe", uploaded = mapOf("g" to first.mark))
        assertTrue(planUploads(listOf(r), players, identity, synced, null, now).isEmpty(), "unchanged -> nothing")

        val edited = r.copy(updatedAtEpochMs = 10)
        assertEquals(1, planUploads(listOf(edited), players, identity, synced, null, now).size, "newer edit re-uploads")

        // The guest seat becomes the friend (e.g. after a transfer): new participant -> re-upload.
        val moved = reassignPlayerInRecords(listOf(r), guest.id, friend.id, friend.name).single().record
        val again = planUploads(listOf(moved), players, identity, synced, null, now).single()
        assertEquals(listOf("uMe", "uF"), again.upload.participants.map { it.userId })
    }

    @Test
    fun plan_skipsGamesQueuedForDeletion_andOrdersOldestFirst() {
        val games = listOf(
            finishedX01("new", listOf(seat(me)), createdAt = 30),
            finishedX01("old", listOf(seat(me)), createdAt = 10),
            finishedX01("gone", listOf(seat(me)), createdAt = 20),
        )
        val plan = planUploads(games, players, identity, SyncState("uMe", pendingDeletes = listOf("gone")), null, now)
        assertEquals(listOf("old", "new"), plan.map { it.upload.id })
    }

    // ---- upload results -------------------------------------------------------

    @Test
    fun results_storeMarks_dropPendingLinks_andReportServerSideDeletes() {
        val games = listOf("a", "b", "c", "d", "e").map { finishedX01(it, listOf(seat(me))) }
        val state = SyncState("uMe", pendingLinks = mapOf("a" to mapOf("uF" to "L1"), "e" to mapOf("uF" to "L1")))
        val plan = planUploads(games, players, identity, state, null, now)
        val applied = applyUploadResults(
            state,
            plan,
            listOf(
                UploadResult("a", "stored"),
                UploadResult("b", "unchanged"),
                UploadResult("c", "deleted"),
                UploadResult("d", "purged"),
                // "e" got no answer: stays pending.
            ),
        )
        assertEquals(setOf("a", "b"), applied.state.uploaded.keys)
        assertEquals(setOf("c", "d"), applied.removeLocally)
        assertEquals(setOf("e"), applied.state.pendingLinks.keys)
    }

    @Test
    fun results_rejectedIsNotRetriedUntilTheGameChanges() {
        val r = finishedX01("g", listOf(seat(me), seat(friend)))
        val plan = planUploads(listOf(r), players, identity, SyncState("uMe"), null, now)
        val applied = applyUploadResults(SyncState("uMe"), plan, listOf(UploadResult("g", "rejected", "not_friends")))
        assertTrue(planUploads(listOf(r), players, identity, applied.state, null, now).isEmpty())
    }

    // ---- downloads: seat remapping --------------------------------------------

    /** Another phone's game: seat 0 their me (uF), 1 me (uMe), 2 a stranger (uS2), 3 their guest. */
    private fun remoteSeats() = listOf(
        com.dartrack.model.GamePlayer("Anna on her phone", "their-anna"),
        com.dartrack.model.GamePlayer("Maxi", "their-max"),
        com.dartrack.model.GamePlayer("Zoe", "their-zoe"),
        com.dartrack.model.GamePlayer("Bob", "their-bob"),
    )

    private fun remote(record: GameRecord) = RemoteGame(
        id = record.id,
        mode = record.mode.name,
        status = "unfinished",
        createdAt = record.createdAtEpochMs,
        updatedAt = record.updatedAtEpochMs,
        record = recordToJson(record),
        participants = listOf(
            RemoteParticipant("uF", "anna", 0),
            RemoteParticipant("uMe", "max", 1),
            RemoteParticipant("uS2", "zoe", 2),
        ),
    )

    @Test
    fun remap_everyMode_mapsMeFriendStrangerAndRemoteGuest_keepingGameplay() {
        val specs = accountSpecsFor(everyMode(remoteSeats()).map { remote(it) }, identity)
        assertEquals(
            listOf(AccountPlayerSpec("uF", "anna", hidden = false), AccountPlayerSpec("uS2", "zoe", hidden = true)),
            specs,
        )
        var ids = 0
        val resolved = resolveAccountPlayers(players, specs) { "new-${ids++}" }
        val stranger = resolved.byAccount.getValue("uS2")
        assertTrue(stranger.hidden, "a non-friend account becomes a hidden player")
        assertEquals(friend, resolved.byAccount["uF"], "the friend's existing player is reused")

        for (original in everyMode(remoteSeats())) {
            val out = remapDownloadedGame(remote(original), original, null, identity, me, resolved.byAccount)
            val seats = out.state.players
            assertEquals(listOf(friend.id, me.id, stranger.id, remoteSeatId(original.id, 3)), seats.map { it.id }, "${original.mode}")
            assertEquals(listOf("Anna", "Max", stranger.name, "Bob"), seats.map { it.name }, "${original.mode}")
            // Only the seat list changes; scores and turn state are untouched.
            assertEquals(original.state, out.state.withPlayers(original.state.players), "${original.mode}")
            assertEquals(original.copy(state = out.state), out)
        }
    }

    @Test
    fun remap_repeatedDownloadsDontDuplicatePlayersOrChangeTheRecord() {
        val original = everyMode(remoteSeats()).first()
        val specs = accountSpecsFor(listOf(remote(original)), identity)
        var ids = 0
        val once = resolveAccountPlayers(players, specs) { "new-${ids++}" }
        val twice = resolveAccountPlayers(once.players, specs) { "new-${ids++}" }
        assertEquals(once.players, twice.players, "second download creates no players")
        assertEquals(players.size + 1, twice.players.size, "only the stranger was added")
        assertEquals(
            remapDownloadedGame(remote(original), original, null, identity, me, once.byAccount),
            remapDownloadedGame(remote(original), original, null, identity, me, twice.byAccount),
        )
    }

    @Test
    fun remap_keepsThisPhonesOwnSeatsWhenTheGameCameFromHere() {
        // Played here (me + my guest), resumed and re-uploaded by the friend's phone.
        val local = finishedX01("g", listOf(seat(me), seat(guest)), updatedAt = 5)
        val theirs = local.copy(
            updatedAtEpochMs = 6,
            state = local.state.withPlayers(listOf(seat(me).copy(id = "x"), seat(guest).copy(id = remoteSeatId("g", 1)))),
        )
        val rg = remote(theirs).copy(participants = listOf(RemoteParticipant("uMe", "max", 0)))
        val out = remapDownloadedGame(rg, theirs, local, identity, me, emptyMap())
        assertEquals(listOf(me.id, guest.id), out.state.players.map { it.id })
    }

    @Test
    fun remap_friendThatWasAHiddenStrangerIsRevealed() {
        val before = listOf(me, Player("p-s", "anna", accountId = "uF", hidden = true))
        val out = resolveAccountPlayers(before, listOf(AccountPlayerSpec("uF", "anna", hidden = false))) { "x" }
        assertEquals(false, out.byAccount.getValue("uF").hidden)
        assertEquals("p-s", out.byAccount.getValue("uF").id)
    }

    @Test
    fun remotePlayers_neverHijackALocalName() {
        // A hidden stranger named "Bob" must not be handed out when a local "Bob" is typed.
        val list = listOf(Player("h", "Bob", accountId = "uS", hidden = true))
        val out = resolveAddPlayer(list, "bob") { "new" }
        assertTrue(out.changed)
        assertEquals("new", out.added.id)
        assertEquals("Bob (2)", out.players.first { it.id == "h" }.name)
        assertEquals(setOf("bob", "Bob (2)"), out.players.map { it.name }.toSet())
    }

    // ---- downloads: applying a page --------------------------------------------

    private fun page(vararg games: RemoteGame, deleted: List<String> = emptyList(), cursor: Long = 7) =
        GamesPage(games.toList(), deleted, cursor, hasMore = false)

    @Test
    fun page_addsNewGames_marksThem_movesTheCursor_andRemovesDeletedOnes() {
        val incoming = everyMode(remoteSeats()).first().copy(id = "new", updatedAtEpochMs = 50)
        val local = listOf(finishedX01("old", listOf(seat(me))))
        val specs = accountSpecsFor(listOf(remote(incoming)), identity)
        val resolved = resolveAccountPlayers(players, specs) { "s" }
        val applied = applyDownloadPage(
            SyncState("uMe", uploaded = mapOf("old" to UploadMark(2, "uMe:0"))),
            page(remote(incoming), deleted = listOf("old")),
            local, identity, me, resolved.byAccount, resolved.players,
        )
        assertEquals(listOf("new"), applied.upserts.map { it.id })
        assertEquals(setOf("old"), applied.removals)
        assertEquals(7, applied.state.cursor)
        assertEquals(setOf("new"), applied.state.uploaded.keys, "downloaded game is marked; deleted one forgotten")
        // The mark matches what planning would compute: no bounce back up.
        val after = mergeSyncChanges(local, applied.upserts, applied.removals)
        assertTrue(planUploads(after, resolved.players, identity, applied.state, null, now).isEmpty())
    }

    @Test
    fun page_keepsANewerOrEqualLocalCopy_andSkipsQueuedDeletesAndUnreadableRecords() {
        val mine = finishedX01("g", listOf(seat(me), seat(friend)), updatedAt = 20)
        val older = remote(mine.copy(updatedAtEpochMs = 10))
        val deleting = remote(finishedX01("del", listOf(seat(me)), updatedAt = 30))
        val broken = remote(finishedX01("bad", listOf(seat(me)))).copy(record = JsonObject(mapOf("nope" to JsonPrimitive(1))))
        val applied = applyDownloadPage(
            SyncState("uMe", pendingDeletes = listOf("del")),
            page(older, deleting, broken),
            listOf(mine), identity, me, emptyMap(), players,
        )
        assertTrue(applied.upserts.isEmpty())
        assertNull(applied.state.uploaded["g"], "local is newer: it still has to upload")

        val equal = applyDownloadPage(SyncState("uMe"), page(remote(mine)), listOf(mine), identity, me, emptyMap(), players)
        assertTrue(equal.upserts.isEmpty())
        assertNotNull(equal.state.uploaded["g"], "same version as the server: remembered as synced")
    }

    @Test
    fun mergeSyncChanges_replacesAddsAndRemoves() {
        val a = finishedX01("a", listOf(seat(me)))
        val b = finishedX01("b", listOf(seat(me)))
        val a2 = a.copy(updatedAtEpochMs = 99)
        val c = finishedX01("c", listOf(seat(me)))
        assertEquals(listOf(a2, c), mergeSyncChanges(listOf(a, b), listOf(a2, c), setOf("b")))
    }

    // ---- sync.json --------------------------------------------------------------

    @Test
    fun state_roundTrips_andIsScopedToTheAccount() {
        val state = SyncState(
            accountId = "uMe",
            cursor = 42,
            uploaded = mapOf("g" to UploadMark(9, "uMe:0,uF:1")),
            pendingDeletes = listOf("x"),
            links = mapOf("uF" to "L1"),
            pendingLinks = mapOf("g" to mapOf("uF" to "L1")),
            lastSyncedAt = 123,
            lastError = "offline",
            snapshotHash = 77,
        )
        val decoded = decodeSyncState(encodeSyncState(state))
        assertEquals(state, decoded)
        assertEquals(state, syncStateFor(decoded, "uMe"))
        assertEquals(SyncState("other"), syncStateFor(decoded, "other"), "another login starts clean")
        assertNull(decodeSyncState(""))
        assertNull(decodeSyncState("{not json"))
        assertEquals(SyncState("uMe"), syncStateFor(null, "uMe"))
    }

    // ---- deletes ----------------------------------------------------------------

    @Test
    fun deleteQueue_onlyForGamesTheServerKnows() {
        val state = SyncState("uMe", uploaded = mapOf("g" to UploadMark(1, "uMe:0")), pendingLinks = mapOf("g" to mapOf("uF" to "L")))
        val queued = queueDelete(state, "g")
        assertEquals(listOf("g"), queued.pendingDeletes)
        assertTrue(queued.uploaded.isEmpty())
        assertTrue(queued.pendingLinks.isEmpty())
        assertEquals(queued, queueDelete(queued, "g"), "idempotent")
        assertEquals(state, queueDelete(state, "never-synced"), "a local-only game needs no server delete")
        assertTrue(deleteDone(queued, "g").pendingDeletes.isEmpty())
    }

    // ---- guest -> friend transfer ------------------------------------------------

    @Test
    fun link_mergedGuestGamesReuploadWithTheFriendCarryingTheLinkId() {
        val g1 = finishedX01("g1", listOf(seat(me), seat(guest)))
        val g2 = finishedX01("g2", listOf(seat(guest), bot()))
        val other = finishedX01("g3", listOf(seat(me), seat(friend)))
        val games = listOf(g1, g2, other)
        assertEquals(listOf("g1", "g2"), guestGameIds(games, guest.id))

        // Everything that could upload has; g2 never qualified (guest + CPU only).
        val before = planUploads(games, players, identity, SyncState("uMe"), null, now)
        var state = applyUploadResults(SyncState("uMe"), before, before.map { UploadResult(it.upload.id, "stored") }).state

        state = recordLink(state, "uF", "L1", guestGameIds(games, guest.id))
        assertEquals(mapOf("uF" to "L1"), state.links)
        val merged = reassignPlayerInRecords(games, guest.id, friend.id, friend.name).map { it.record }
        val plan = planUploads(merged, players - guest, identity, state, null, now)
        assertEquals(listOf("g1", "g2"), plan.map { it.upload.id }, "both of the guest's games go up again")
        for (p in plan) {
            val f = p.upload.participants.single { it.userId == "uF" }
            assertEquals("L1", f.linkId)
            assertNull(p.upload.participants.firstOrNull { it.userId == "uMe" }?.linkId)
        }
        // Once stored, the linkId isn't sent again.
        val done = applyUploadResults(state, plan, plan.map { UploadResult(it.upload.id, "stored") }).state
        assertTrue(done.pendingLinks.isEmpty())
        assertEquals(mapOf("uF" to "L1"), done.links, "the friend keeps counting as linked")
        assertTrue(planUploads(merged, players - guest, identity, done, null, now).isEmpty())
    }

    @Test
    fun link_onlyVisibleUnlinkedGuestsCanBeTransferred() {
        assertEquals(listOf(guest), transferableGuests(players, me.id))
        assertTrue(transferableGuests(listOf(guest), guest.id).isEmpty(), "the me player is never a guest")
    }
}
