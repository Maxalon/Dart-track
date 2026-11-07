package com.dartrack.data

import com.dartrack.model.GamePlayer
import com.dartrack.model.Player
import com.dartrack.net.GameUpload
import com.dartrack.net.GamesPage
import com.dartrack.net.RemoteGame
import com.dartrack.net.UploadParticipant
import com.dartrack.net.UploadResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Game-history sync: the pure, Android-free half (SyncManager does the IO).
 *
 * What syncs: every local game with at least one seat that belongs to an
 * account — the "me" player, or a player linked to an accepted friend. The
 * complete GameRecord goes up verbatim, with the account seats as
 * participants. Games with only guests/CPUs never leave the phone.
 *
 * Downloaded games (played on another phone) land in local history with their
 * account seats mapped to local players (me, a friend's player, or a hidden
 * player for an account that isn't a friend) and every other seat rewritten to
 * a "remote:<gameId>:<seat>" id so the other phone's guests never merge with
 * this phone's players.
 */

/** An unfinished game uploads only once it has been untouched this long. */
const val UNFINISHED_UPLOAD_DELAY_MS: Long = 24L * 60 * 60 * 1000

/** Seat-id prefix of a guest/CPU seat that came from another phone. */
const val REMOTE_SEAT_PREFIX = "remote:"

fun remoteSeatId(gameId: String, seat: Int): String = "$REMOTE_SEAT_PREFIX$gameId:$seat"

fun isRemoteSeatId(id: String): Boolean = id.startsWith(REMOTE_SEAT_PREFIX)

/**
 * What the server last accepted for one game: its [updatedAt] and the account
 * seats ([participants], see [participantsKey]). The game uploads again when
 * either changes (a newer local edit, or e.g. a guest transferred to a friend).
 */
@Serializable
data class UploadMark(val updatedAt: Long, val participants: String)

/**
 * Persisted sync bookkeeping (`sync.json`), scoped to one account: a different
 * login starts from a clean state (see [syncStateFor]).
 */
@Serializable
data class SyncState(
    val accountId: String,
    /** Server cursor for `GET /v1/games?since=`; 0 = from the beginning. */
    val cursor: Long = 0,
    /** gameId -> what the server has (uploaded from here, or downloaded). */
    val uploaded: Map<String, UploadMark> = emptyMap(),
    /** Synced games deleted on this phone, still to be `DELETE`d on the server. */
    val pendingDeletes: List<String> = emptyList(),
    /** friend accountId -> linkId of the guest transferred to them (one per friend). */
    val links: Map<String, String> = emptyMap(),
    /** gameId -> (friend accountId -> linkId) to send with that game's next upload. */
    val pendingLinks: Map<String, Map<String, String>> = emptyMap(),
    val lastSyncedAt: Long? = null,
    val lastError: String? = null,
    /** Hash of the last stats snapshot the server accepted (skip identical re-uploads). */
    val snapshotHash: Int? = null,
)

fun encodeSyncState(state: SyncState): String =
    GameJson.format.encodeToString(SyncState.serializer(), state)

/** Decodes sync.json; missing, blank or corrupt means "no state" (a full sync). */
fun decodeSyncState(text: String): SyncState? {
    if (text.isBlank()) return null
    return runCatching { GameJson.format.decodeFromString(SyncState.serializer(), text) }.getOrNull()
}

/** [stored] if it belongs to [accountId], otherwise a fresh state for it. */
fun syncStateFor(stored: SyncState?, accountId: String): SyncState =
    if (stored?.accountId == accountId) stored else SyncState(accountId)

/** The logged-in user as sync sees it: their account, their local player, their friends. */
data class SyncIdentity(
    val userId: String,
    val mePlayerId: String,
    val friendIds: Set<String>,
)

// ---------------------------------------------------------------------------
// Upload
// ---------------------------------------------------------------------------

/**
 * The account seats of [record]: the me player's seat as [SyncIdentity.userId]
 * and each seat whose player is linked to an accepted friend as that friend.
 * CPUs, guests, hidden strangers and remote seats are not participants. An
 * account sitting twice keeps its first seat.
 */
fun participantsFor(
    record: GameRecord,
    playersById: Map<String, Player>,
    identity: SyncIdentity,
): List<UploadParticipant> {
    val seen = HashSet<String>()
    val out = ArrayList<UploadParticipant>()
    record.state.players.forEachIndexed { seat, gp ->
        if (gp.isBot || gp.id.isBlank()) return@forEachIndexed
        val userId = if (gp.id == identity.mePlayerId) {
            identity.userId
        } else {
            playersById[gp.id]?.accountId?.takeIf { it in identity.friendIds && it != identity.userId }
        } ?: return@forEachIndexed
        if (seen.add(userId)) out.add(UploadParticipant(userId, seat))
    }
    return out
}

/** Stable text form of a participant list for [UploadMark.participants]. */
fun participantsKey(participants: List<UploadParticipant>): String =
    participants.sortedBy { it.seat }.joinToString(",") { "${it.userId}:${it.seat}" }

/**
 * Upload status of [record] at [nowMs], or null when it shouldn't upload yet:
 * `finished` once it has a winner; `unfinished` once untouched for
 * [UNFINISHED_UPLOAD_DELAY_MS] (so an in-progress game doesn't re-upload every
 * turn). The app has no explicit abandon action, so `abandoned` is never sent.
 */
fun uploadStatus(record: GameRecord, nowMs: Long): String? = when {
    record.isFinished -> "finished"
    nowMs - record.updatedAtEpochMs >= UNFINISHED_UPLOAD_DELAY_MS -> "unfinished"
    else -> null
}

/** The [UploadMark] [record] would get if uploaded now. */
fun uploadMarkFor(record: GameRecord, playersById: Map<String, Player>, identity: SyncIdentity): UploadMark =
    UploadMark(record.updatedAtEpochMs, participantsKey(participantsFor(record, playersById, identity)))

/** One planned upload and the mark to store once the server has it. */
data class PlannedUpload(val upload: GameUpload, val mark: UploadMark)

/** The full GameRecord as a JSON object, exactly as games.json stores it. */
fun recordToJson(record: GameRecord): JsonObject =
    GameJson.format.encodeToJsonElement(GameRecord.serializer(), record).jsonObject

/** A downloaded record decoded; null when this app version can't read it. */
fun recordFromJson(json: JsonObject): GameRecord? =
    runCatching { GameJson.format.decodeFromJsonElement(GameRecord.serializer(), json) }.getOrNull()

/**
 * Every game that should upload now: it qualifies (≥1 account seat), has an
 * [uploadStatus], isn't queued for deletion, and differs from what the server
 * last accepted ([SyncState.uploaded]) or carries a pending transfer linkId.
 * Participants named in [SyncState.pendingLinks] for that game get their
 * linkId. Oldest first, so a first sync uploads history in order.
 */
fun planUploads(
    games: List<GameRecord>,
    players: List<Player>,
    identity: SyncIdentity,
    state: SyncState,
    meta: JsonObject?,
    nowMs: Long,
): List<PlannedUpload> {
    val byId = players.associateBy { it.id }
    val deleting = state.pendingDeletes.toHashSet()
    return games
        .asSequence()
        .filter { it.id !in deleting }
        .sortedBy { it.createdAtEpochMs }
        .mapNotNull { record ->
            val participants = participantsFor(record, byId, identity)
            if (participants.isEmpty()) return@mapNotNull null
            val status = uploadStatus(record, nowMs) ?: return@mapNotNull null
            val mark = UploadMark(record.updatedAtEpochMs, participantsKey(participants))
            val links = state.pendingLinks[record.id].orEmpty()
            if (state.uploaded[record.id] == mark && links.isEmpty()) return@mapNotNull null
            PlannedUpload(
                upload = GameUpload(
                    id = record.id,
                    mode = record.mode.name,
                    status = status,
                    createdAt = record.createdAtEpochMs,
                    updatedAt = record.updatedAtEpochMs,
                    record = recordToJson(record),
                    meta = meta,
                    participants = participants.map { p -> links[p.userId]?.let { p.copy(linkId = it) } ?: p },
                ),
                mark = mark,
            )
        }
        .toList()
}

/** [state] after a batch's results, plus the ids the server says are gone for us. */
data class UploadApplied(val state: SyncState, val removeLocally: Set<String>)

/**
 * Folds the server's per-game [results] for [batch] into [state]:
 *  - `stored` / `unchanged`: the mark is recorded; the game's pending linkIds are done.
 *  - `rejected` (e.g. no longer friends): also recorded, so it isn't retried
 *    every sync — it uploads again once the game or its participants change.
 *  - `deleted` / `purged`: this account deleted the game elsewhere (or everyone
 *    did) — the local copy goes too.
 * A game the server didn't answer for stays pending.
 */
fun applyUploadResults(state: SyncState, batch: List<PlannedUpload>, results: List<UploadResult>): UploadApplied {
    val byId = results.associateBy { it.id }
    val uploaded = state.uploaded.toMutableMap()
    val pendingLinks = state.pendingLinks.toMutableMap()
    val remove = HashSet<String>()
    for (planned in batch) {
        val id = planned.upload.id
        when (byId[id]?.result) {
            "stored", "unchanged", "rejected" -> {
                uploaded[id] = planned.mark
                pendingLinks.remove(id)
            }
            "deleted", "purged" -> {
                uploaded.remove(id)
                pendingLinks.remove(id)
                remove.add(id)
            }
            else -> Unit
        }
    }
    return UploadApplied(state.copy(uploaded = uploaded, pendingLinks = pendingLinks), remove)
}

// ---------------------------------------------------------------------------
// Download
// ---------------------------------------------------------------------------

/**
 * The accounts in [games] that need a local player: every participant except
 * the user themself. Friends get a normal (visible) player; anyone else a
 * hidden one. One spec per account.
 */
fun accountSpecsFor(games: List<RemoteGame>, identity: SyncIdentity): List<AccountPlayerSpec> =
    games.flatMap { it.participants }
        .filter { it.userId != identity.userId }
        .distinctBy { it.userId }
        .map { AccountPlayerSpec(it.userId, it.username.ifBlank { "Player" }, hidden = it.userId !in identity.friendIds) }

/**
 * Rewrites the seats of a downloaded game for this phone:
 *  - the user's own seat -> the me player;
 *  - another account's seat -> that account's local player ([accountPlayers]);
 *  - any other seat (the other phone's guests and CPUs) keeps its name and
 *    flags but gets the id [remoteSeatId] — unless this phone already has the
 *    game ([local], e.g. it was played here), whose own seat is kept.
 * The id is deterministic, so downloading the same game again never creates
 * anything new. Uses [withPlayers], so every mode is covered.
 */
fun remapDownloadedGame(
    remote: RemoteGame,
    decoded: GameRecord,
    local: GameRecord?,
    identity: SyncIdentity,
    me: Player,
    accountPlayers: Map<String, Player>,
): GameRecord {
    val bySeat = remote.participants.associateBy { it.seat }
    val localSeats = local?.state?.players.orEmpty()
    val seats = decoded.state.players.mapIndexed { i, seat ->
        val participant = bySeat[i]
        val player = when {
            participant == null -> null
            participant.userId == identity.userId -> me
            else -> accountPlayers[participant.userId]
        }
        when {
            player != null -> GamePlayer(name = player.name, id = player.id)
            localSeats.getOrNull(i) != null -> localSeats[i]
            else -> seat.copy(id = remoteSeatId(remote.id, i))
        }
    }
    return decoded.copy(id = remote.id, state = decoded.state.withPlayers(seats))
}

/** A downloaded page turned into local changes, plus the state to persist first. */
data class DownloadApplied(
    val state: SyncState,
    val upserts: List<GameRecord>,
    val removals: Set<String>,
)

/**
 * Applies one `GET /v1/games` page to [state] and [localGames]:
 *  - a game newer than the local copy (or new) is remapped
 *    ([remapDownloadedGame]) and stored; its mark is set so it doesn't bounce
 *    straight back up. An older/equal one leaves the local copy alone.
 *  - games this phone is about to delete, and records this app version can't
 *    decode, are skipped.
 *  - `deleted` ids (deleted by this account on another phone) are removed.
 * [playersAfter] is the registry once [accountPlayers] exist (for the marks).
 */
fun applyDownloadPage(
    state: SyncState,
    page: GamesPage,
    localGames: List<GameRecord>,
    identity: SyncIdentity,
    me: Player,
    accountPlayers: Map<String, Player>,
    playersAfter: List<Player>,
): DownloadApplied {
    val local = localGames.associateBy { it.id }
    val deleting = state.pendingDeletes.toHashSet()
    val byId = playersAfter.associateBy { it.id }
    val uploaded = state.uploaded.toMutableMap()
    val upserts = ArrayList<GameRecord>()
    for (remote in page.games) {
        if (remote.id in deleting) continue
        val decoded = recordFromJson(remote.record) ?: continue
        val existing = local[remote.id]
        if (existing != null && existing.updatedAtEpochMs >= decoded.updatedAtEpochMs) {
            // Ours is as new or newer: keep it. When it's the very version the
            // server has, remember that so it doesn't upload again for nothing.
            if (existing.updatedAtEpochMs == decoded.updatedAtEpochMs && uploaded[remote.id] == null) {
                uploaded[remote.id] = uploadMarkFor(existing, byId, identity)
            }
            continue
        }
        val record = remapDownloadedGame(remote, decoded, existing, identity, me, accountPlayers)
        upserts.add(record)
        uploaded[remote.id] = uploadMarkFor(record, byId, identity)
    }
    val removals = page.deleted.toHashSet()
    removals.forEach { uploaded.remove(it) }
    return DownloadApplied(
        state = state.copy(cursor = page.cursor, uploaded = uploaded),
        upserts = upserts,
        removals = removals,
    )
}

// ---------------------------------------------------------------------------
// Deletes
// ---------------------------------------------------------------------------

/**
 * Queues [gameId] for `DELETE /v1/games/:id` when the server knows it (it was
 * uploaded or downloaded); a game that never synced needs nothing. The game
 * stops counting as uploaded either way.
 */
fun queueDelete(state: SyncState, gameId: String): SyncState {
    if (gameId !in state.uploaded) return state
    return state.copy(
        uploaded = state.uploaded - gameId,
        pendingLinks = state.pendingLinks - gameId,
        pendingDeletes = (state.pendingDeletes + gameId).distinct(),
    )
}

/** The server confirmed (or no longer needs) the delete of [gameId]. */
fun deleteDone(state: SyncState, gameId: String): SyncState =
    state.copy(pendingDeletes = state.pendingDeletes - gameId)

// ---------------------------------------------------------------------------
// Guest -> friend transfer
// ---------------------------------------------------------------------------

/** Ids of the games in which player [guestId] has a seat. */
fun guestGameIds(games: List<GameRecord>, guestId: String): List<String> =
    games.filter { r -> r.state.players.any { it.id == guestId } }.map { it.id }

/** Local players that can be transferred to a friend: visible guests with no account. */
fun transferableGuests(players: List<Player>, mePlayerId: String?): List<Player> =
    players.filter { !it.hidden && it.accountId == null && it.id != mePlayerId }

/**
 * Records a successful transfer: [friendId] now has link [linkId], and each of
 * [gameIds] must upload once more with that friend's participant carrying it.
 */
fun recordLink(state: SyncState, friendId: String, linkId: String, gameIds: List<String>): SyncState {
    val pending = state.pendingLinks.toMutableMap()
    for (id in gameIds) pending[id] = pending[id].orEmpty() + (friendId to linkId)
    return state.copy(links = state.links + (friendId to linkId), pendingLinks = pending)
}
