package com.dartrack.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.dartrack.BuildConfig
import com.dartrack.model.Player
import com.dartrack.net.ApiClient
import com.dartrack.net.ApiResult
import com.dartrack.net.FriendEntry
import com.dartrack.net.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What the UI shows about sync: running now, when it last succeeded, the last error. */
data class SyncStatus(
    val running: Boolean = false,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null,
)

/** How one sync run ended. */
sealed interface SyncOutcome {
    data object Done : SyncOutcome
    data object NotLoggedIn : SyncOutcome

    /** Logged in, but "which player is you?" hasn't been answered. */
    data object NoMePlayer : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

/** How a guest → friend transfer ended. */
sealed interface LinkOutcome {
    /** [moved] games now belong to the friend. */
    data class Done(val moved: Int) : LinkOutcome

    /** Not allowed (a guest already went to this friend, not friends, …). */
    data class Blocked(val message: String) : LinkOutcome
    data class Failed(val message: String) : LinkOutcome
}

/**
 * The single app-scoped game-history sync (see Sync.kt for the rules). A run:
 *  1. refreshes the friends list (who counts as an account seat),
 *  2. sends queued deletes (`DELETE /v1/games/:id`),
 *  3. uploads every game that qualifies and changed, in batches of
 *     [ApiClient.SYNC_BATCH],
 *  4. downloads everything that changed for the account (`GET /v1/games`,
 *     paging while `hasMore`) into local history,
 *  5. uploads the me player's stats snapshot if it changed.
 *
 * Runs never overlap ([runMutex]); [requestSync] coalesces triggers (app start,
 * a game finishing, connectivity returning, pull-to-refresh) into at most one
 * queued run. Bookkeeping lives in `sync.json` ([SyncState], scoped to the
 * account so another login starts clean), written atomically like the other
 * repositories. No push, no WorkManager: sync only happens while the app runs.
 */
class SyncManager internal constructor(
    private val file: File,
    private val accounts: AccountRepository,
    private val friends: FriendsRepository,
    private val players: PlayerRepository,
    private val games: GameRepository,
    /** Client metadata sent with every uploaded game (app/device versions). */
    private val meta: JsonObject?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val runMutex = Mutex()
    private val stateMutex = Mutex()
    private val queued = AtomicBoolean(false)
    private var started = false

    private val _state = MutableStateFlow<SyncState?>(null)

    /** The persisted bookkeeping (whatever account it belongs to). */
    val state: StateFlow<SyncState?> = _state.asStateFlow()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val api get() = accounts.api

    suspend fun load() = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            val text = runCatching { file.readText() }.getOrDefault("")
            _state.value = decodeSyncState(text)
            publishStatus()
        }
    }

    /**
     * Wires the automatic triggers once per process: a sync whenever a game
     * newly finishes that the server doesn't have yet, and whenever the device
     * gets a network (which also covers app start when online).
     */
    fun start(context: Context) {
        if (started) return
        started = true
        scope.launch {
            var previous: Set<String>? = null
            games.games.collect { list ->
                val finished = list.filter { it.isFinished }.associate { it.id to it.updatedAtEpochMs }
                val before = previous
                previous = finished.keys
                if (before == null) return@collect
                val marks = _state.value?.uploaded.orEmpty()
                val fresh = finished.filter { (id, updatedAt) -> id !in before && marks[id]?.updatedAt != updatedAt }
                if (fresh.isNotEmpty()) requestSync()
            }
        }
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = requestSync()
            })
        }
    }

    /** Fire-and-forget sync; while one is queued, further requests are dropped. */
    fun requestSync() {
        if (accounts.account.value == null) return
        if (!queued.compareAndSet(false, true)) return
        scope.launch {
            runMutex.withLock {
                queued.set(false)
                runLocked()
            }
        }
    }

    /** Runs a sync now (waiting for a running one first), e.g. for pull-to-refresh. */
    suspend fun sync(): SyncOutcome = runMutex.withLock { runLocked() }

    /**
     * Deletes game [id] locally and, if the server knows it, queues the
     * server-side delete (this account's history only — friends keep theirs).
     */
    suspend fun deleteGame(id: String) {
        if (!games.isLoaded) games.load()
        // Queue first: a download running right now then skips the game
        // instead of bringing it back.
        updateState { it?.let { s -> queueDelete(s, id) } }
        games.delete(id)
        requestSync()
    }

    // ---- Guest → friend transfer ------------------------------------------------

    /**
     * Why a guest can't be moved to [friend] right now, or null when it can:
     * this account already moved a guest to them (known locally, or from
     * `GET /v1/links` — which also catches a transfer made on another phone).
     */
    suspend fun linkBlockReason(friend: FriendEntry): String? {
        val account = accounts.account.value ?: return "Log in to move games to a friend."
        val result = accounts.authed { api.links(it) }
        if (result is ApiResult.Ok) {
            val serverLinks = result.value.links.associate { it.friendId to it.id }
            updateState { s -> syncStateFor(s, account.userId).let { it.copy(links = it.links + serverLinks) } }
        }
        val known = syncStateFor(_state.value, account.userId).links
        return if (friend.id in known) alreadyLinkedMessage(friend.username) else null
    }

    /**
     * Moves local guest [guestId]'s games to [friend]: `POST /v1/links`, then
     * merges the guest into the friend's local player (the guest disappears;
     * the friend is just a friend afterwards) and marks those games to upload
     * again with the friend's participant carrying the linkId.
     */
    suspend fun linkGuest(guestId: String, friend: FriendEntry): LinkOutcome {
        val account = accounts.account.value ?: return LinkOutcome.Blocked("Log in to move games to a friend.")
        val guest = players.byId(guestId) ?: return LinkOutcome.Failed("That player no longer exists.")
        if (guest.accountId != null || guest.hidden) return LinkOutcome.Blocked("${guest.name} isn't a guest.")
        if (friend.id in syncStateFor(_state.value, account.userId).links) {
            return LinkOutcome.Blocked(alreadyLinkedMessage(friend.username))
        }
        val result = accounts.authed { api.createLink(it, friend.id, guest.name) }
        val link = when (result) {
            is ApiResult.Ok -> result.value
            is ApiResult.HttpError -> return when (result.code) {
                "already_linked" -> {
                    linkBlockReason(friend) // learn the server's link
                    LinkOutcome.Blocked(alreadyLinkedMessage(friend.username))
                }
                "not_friends" -> LinkOutcome.Blocked("You can only move games to an accepted friend.")
                else -> LinkOutcome.Failed(result.userMessage())
            }
            is ApiResult.NetworkError -> return LinkOutcome.Failed(result.userMessage())
        }
        // Not while a sync run is planning uploads: the pending linkIds and the
        // merged seats must land together.
        val moved = runMutex.withLock {
            val target = players.playerForAccount(friend.id, friend.username)
            val gameIds = guestGameIds(games.games.value, guest.id)
            updateState { s -> recordLink(syncStateFor(s, account.userId), friend.id, link.id, gameIds) }
            players.merge(guest.id, target.id, games)
            gameIds.size
        }
        requestSync()
        return LinkOutcome.Done(moved)
    }

    // ---- The run ----------------------------------------------------------------

    private suspend fun runLocked(): SyncOutcome {
        // Never merge into stores that haven't been read yet: the next write
        // would replace games.json / players.json with only what sync added.
        if (!games.isLoaded) games.load()
        if (!players.isLoaded) players.load()
        val account = accounts.account.value ?: return SyncOutcome.NotLoggedIn
        val me = account.mePlayerId?.let { players.byId(it) }
        if (me == null) {
            finish(account.userId, error = "Choose which player is you (Settings → Account) to sync games.")
            return SyncOutcome.NoMePlayer
        }
        _status.value = _status.value.copy(running = true)
        try {
            return runSteps(account, me)
        } finally {
            _status.value = _status.value.copy(running = false)
        }
    }

    private suspend fun runSteps(account: Account, me: Player): SyncOutcome {
        updateState { syncStateFor(it, account.userId) }
        friends.refresh() // best effort: the cached list is fine offline
        val friendIds = friendsFor(friends.cache.value, accounts.account.value).friends.mapTo(HashSet()) { it.id }
        val identity = SyncIdentity(account.userId, me.id, friendIds)

        // 1. Queued deletes.
        for (id in current(account).pendingDeletes) {
            when (val r = accounts.authed { api.deleteGame(it, id) }) {
                is ApiResult.Ok -> updateState { deleteDone(it!!, id) }
                is ApiResult.HttpError ->
                    if (r.status == 401) return fail(account, r) else updateState { deleteDone(it!!, id) } // 404 etc.: nothing left to delete
                is ApiResult.NetworkError -> return fail(account, r)
            }
        }

        // 2. Uploads.
        val planned = planUploads(games.games.value, players.all(), identity, current(account), meta, clock())
        for (batch in planned.chunked(ApiClient.SYNC_BATCH)) {
            val r = accounts.authed { api.uploadGames(it, batch.map { p -> p.upload }) }
            if (r !is ApiResult.Ok) return fail(account, r as ApiResult.Failure)
            var removed: Set<String> = emptySet()
            updateState {
                val applied = applyUploadResults(it!!, batch, r.value.results)
                removed = applied.removeLocally
                applied.state
            }
            games.applySync(emptyList(), removed)
        }

        // 3. Downloads.
        while (true) {
            val since = current(account).cursor
            val r = accounts.authed { api.gamesSince(it, since) }
            if (r !is ApiResult.Ok) return fail(account, r as ApiResult.Failure)
            val page = r.value
            val specs = accountSpecsFor(page.games, identity)
            val accountPlayers = if (specs.isEmpty()) emptyMap() else players.playersForAccounts(specs)
            var applied: DownloadApplied? = null
            // Marks first, then the games: the finish observer must already see
            // a downloaded game as synced when it appears.
            updateState {
                applyDownloadPage(it!!, page, games.games.value, identity, me, accountPlayers, players.all())
                    .also { a -> applied = a }
                    .state
            }
            applied?.let { games.applySync(it.upserts, it.removals) }
            if (!page.hasMore || page.cursor <= since) break
        }

        // 4. My stats snapshot, when it changed.
        val snapshot = snapshotToJson(buildStatsSnapshot(me.id, games.games.value))
        val hash = snapshot.toString().hashCode()
        if (current(account).snapshotHash != hash) {
            val r = accounts.authed { api.putMyStats(it, snapshot, clock()) }
            if (r !is ApiResult.Ok) return fail(account, r as ApiResult.Failure)
            updateState { it!!.copy(snapshotHash = hash) }
        }

        finish(account.userId, error = null)
        return SyncOutcome.Done
    }

    private fun current(account: Account): SyncState = syncStateFor(_state.value, account.userId)

    private suspend fun fail(account: Account, failure: ApiResult.Failure): SyncOutcome {
        val message = failure.userMessage()
        // A 401 has already logged the account out; nothing to record for it.
        if (accounts.account.value != null) finish(account.userId, error = message)
        return SyncOutcome.Failed(message)
    }

    private suspend fun finish(accountId: String, error: String?) {
        updateState { s ->
            val st = syncStateFor(s, accountId)
            if (error == null) st.copy(lastSyncedAt = clock(), lastError = null) else st.copy(lastError = error)
        }
    }

    private suspend fun updateState(transform: (SyncState?) -> SyncState?) = withContext(Dispatchers.IO) {
        stateMutex.withLock {
            val next = transform(_state.value)
            if (next != null && next != _state.value) persistLocked(next)
        }
    }

    private fun publishStatus() {
        val st = _state.value?.takeIf { it.accountId == accounts.account.value?.userId }
        _status.value = _status.value.copy(lastSyncedAt = st?.lastSyncedAt, lastError = st?.lastError)
    }

    private fun persistLocked(value: SyncState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encodeSyncState(value))
        if (!tmp.renameTo(file)) {
            // Fallback: overwrite
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _state.value = value
        publishStatus()
    }

    companion object {
        @Volatile private var instance: SyncManager? = null
        fun get(context: Context): SyncManager =
            instance ?: synchronized(this) {
                instance ?: SyncManager(
                    File(context.applicationContext.filesDir, "sync.json"),
                    AccountRepository.get(context),
                    FriendsRepository.get(context),
                    PlayerRepository.get(context),
                    GameRepository.get(context),
                    clientMeta(),
                ).also { instance = it }
            }

        /** App and device details sent as `meta` with every uploaded game. */
        private fun clientMeta(): JsonObject = JsonObject(
            mapOf(
                "appVersion" to JsonPrimitive(BuildConfig.VERSION_NAME),
                "versionCode" to JsonPrimitive(BuildConfig.VERSION_CODE),
                "buildType" to JsonPrimitive(BuildConfig.BUILD_TYPE),
                "deviceManufacturer" to JsonPrimitive(Build.MANUFACTURER ?: ""),
                "deviceModel" to JsonPrimitive(Build.MODEL ?: ""),
                "androidSdk" to JsonPrimitive(Build.VERSION.SDK_INT),
                "androidRelease" to JsonPrimitive(Build.VERSION.RELEASE ?: ""),
            ),
        )
    }
}

/** The "one guest per friend" message. */
fun alreadyLinkedMessage(friendName: String): String =
    "You've already moved a guest's games to $friendName. Only one guest per friend can be moved."
