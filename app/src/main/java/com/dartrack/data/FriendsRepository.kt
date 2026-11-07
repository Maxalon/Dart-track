package com.dartrack.data

import android.content.Context
import com.dartrack.model.Player
import com.dartrack.net.ApiResult
import com.dartrack.net.FriendEntry
import com.dartrack.net.FriendRequestResult
import com.dartrack.net.FriendsList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Last-known friends list of account [accountId], as fetched at [fetchedAtEpochMs].
 * Tagged with the account so a different login never shows someone else's friends.
 */
@Serializable
data class FriendsCache(
    val accountId: String,
    val list: FriendsList = FriendsList(),
    val fetchedAtEpochMs: Long = 0,
)

/**
 * Friends of the logged-in account, mirroring the other single-file
 * repositories (singleton [get], a [cache] StateFlow, a [Mutex] guarding an
 * atomic temp-file + rename write, [GameJson.format] for JSON).
 *
 * The last successful `GET /v1/friends` is persisted to `friends.json`, so the
 * friend list keeps working offline (e.g. in game setup). Every mutating call
 * goes through [AccountRepository.authed] (a 401 logs out) and re-fetches the
 * list on success, so the server stays the single source of truth.
 */
class FriendsRepository private constructor(
    private val file: File,
    private val accounts: AccountRepository,
    private val players: PlayerRepository,
) {

    private val mutex = Mutex()
    private val _cache = MutableStateFlow<FriendsCache?>(null)
    val cache: StateFlow<FriendsCache?> = _cache.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val text = runCatching { file.readText() }.getOrDefault("")
            _cache.value = decodeFriendsCache(text)
        }
    }

    /** Fetches the list; on failure the cached one stays as it was. */
    suspend fun refresh(): ApiResult<FriendsList> {
        val accountId = accounts.account.value?.userId
            ?: return ApiResult.HttpError(401, "not_logged_in", null)
        _refreshing.value = true
        try {
            val result = accounts.authed { accounts.api.friends(it) }
            if (result is ApiResult.Ok) {
                val next = FriendsCache(accountId, result.value, System.currentTimeMillis())
                withContext(Dispatchers.IO) { mutex.withLock { persistLocked(next) } }
            }
            return result
        } finally {
            _refreshing.value = false
        }
    }

    suspend fun sendRequest(username: String): ApiResult<FriendRequestResult> =
        thenRefresh(accounts.authed { accounts.api.sendFriendRequest(it, username.trim()) })

    suspend fun accept(userId: String): ApiResult<FriendRequestResult> =
        thenRefresh(accounts.authed { accounts.api.acceptFriendRequest(it, userId) })

    /** Declines an incoming request or cancels an outgoing one. */
    suspend fun declineOrCancel(userId: String): ApiResult<Unit> =
        thenRefresh(accounts.authed { accounts.api.deleteFriendRequest(it, userId) })

    suspend fun remove(userId: String): ApiResult<Unit> =
        thenRefresh(accounts.authed { accounts.api.removeFriend(it, userId) })

    /**
     * The local player that seats [friend] in a game: the player already linked
     * to the friend's account, or a new one named after their username.
     */
    suspend fun playerFor(friend: FriendEntry): Player =
        players.playerForAccount(friend.id, friend.username)

    private suspend fun <T> thenRefresh(result: ApiResult<T>): ApiResult<T> {
        if (result is ApiResult.Ok) refresh()
        return result
    }

    private fun persistLocked(value: FriendsCache) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encodeFriendsCache(value))
        if (!tmp.renameTo(file)) {
            // Fallback: overwrite
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _cache.value = value
    }

    companion object {
        @Volatile private var instance: FriendsRepository? = null
        fun get(context: Context): FriendsRepository =
            instance ?: synchronized(this) {
                instance ?: FriendsRepository(
                    File(context.applicationContext.filesDir, "friends.json"),
                    AccountRepository.get(context),
                    PlayerRepository.get(context),
                ).also { instance = it }
            }
    }
}

// ---------------------------------------------------------------------------
// Pure, Android-free logic (unit-testable without a Context or file IO).
// ---------------------------------------------------------------------------

fun encodeFriendsCache(cache: FriendsCache): String =
    GameJson.format.encodeToString(FriendsCache.serializer(), cache)

/** Decodes friends.json; missing, blank or corrupt means "nothing cached". */
fun decodeFriendsCache(text: String): FriendsCache? {
    if (text.isBlank()) return null
    return runCatching { GameJson.format.decodeFromString(FriendsCache.serializer(), text) }.getOrNull()
}

/** The cached list if it belongs to [account]; empty when logged out or stale. */
fun friendsFor(cache: FriendsCache?, account: Account?): FriendsList =
    if (account != null && cache?.accountId == account.userId) cache.list else FriendsList()

/**
 * One entry of a player picker: a local [player], an accepted [friend], or
 * both (a friend whose local player already exists). A friend-only entry has
 * no player yet — it is created on selection via [FriendsRepository.playerFor].
 */
data class PlayerOption(val player: Player?, val friend: FriendEntry?) {
    init {
        require(player != null || friend != null) { "PlayerOption needs a player or a friend" }
    }

    val name: String get() = player?.name ?: friend!!.username
    val isFriend: Boolean get() = friend != null
}

/**
 * Builds the picker list: every local player (marked as a friend when it is
 * linked to one of [friends]) plus each friend who has no local player yet.
 * Sorted by name, except that the [mePlayerId] player (the logged-in user)
 * comes first.
 */
fun playerOptions(
    players: List<Player>,
    friends: List<FriendEntry>,
    mePlayerId: String? = null,
): List<PlayerOption> {
    val friendsById = friends.associateBy { it.id }
    val linked = players.mapNotNullTo(HashSet()) { it.accountId }
    val local = players.map { p -> PlayerOption(p, p.accountId?.let { friendsById[it] }) }
    val friendOnly = friends.filter { it.id !in linked }.map { PlayerOption(null, it) }
    return (local + friendOnly).sortedWith(
        compareBy<PlayerOption>({ it.player?.id != mePlayerId || mePlayerId == null }, { it.name.lowercase() }),
    )
}
