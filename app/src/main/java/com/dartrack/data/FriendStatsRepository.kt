package com.dartrack.data

import android.content.Context
import com.dartrack.net.ApiResult
import com.dartrack.net.FriendStatsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** What `GET /v1/friends/:id/stats` last said about a friend. */
@Serializable
enum class FriendStatsStatus {
    /** A readable snapshot. */
    OK,

    /** 403 stats_hidden: they hide their stats from friends. */
    HIDDEN,

    /** 404 no_stats: they haven't synced stats yet. */
    NONE,

    /** A snapshot this app version can't read. */
    UNREADABLE,
}

/** One friend's last-known stats; [computedAt] is when their phone computed them. */
@Serializable
data class FriendStats(
    val status: FriendStatsStatus,
    val snapshot: StatsSnapshot? = null,
    val computedAt: Long = 0,
    val fetchedAt: Long = 0,
)

/** Friends' stats (by friend account id), tagged with the account that fetched them. */
@Serializable
data class FriendStatsCache(
    val accountId: String,
    val entries: Map<String, FriendStats> = emptyMap(),
)

/**
 * Friends' uploaded stats snapshots, mirroring the other single-file
 * repositories (singleton [get], a [cache] StateFlow, a [Mutex] guarding an
 * atomic temp-file + rename write, [GameJson.format] for JSON).
 *
 * Fetched when a stats screen opens and cached in `friend_stats.json`, so the
 * last-known numbers still show offline. Scoped to the account like friends.json.
 */
class FriendStatsRepository internal constructor(
    private val file: File,
    private val accounts: AccountRepository,
) {

    private val mutex = Mutex()
    private val _cache = MutableStateFlow<FriendStatsCache?>(null)
    val cache: StateFlow<FriendStatsCache?> = _cache.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val text = runCatching { file.readText() }.getOrDefault("")
            _cache.value = decodeFriendStatsCache(text)
        }
    }

    /**
     * Fetches the stats of every friend in [friendIds] (one call each; a
     * friends circle is small). Network trouble keeps what was cached; friends
     * no longer in the list are dropped.
     */
    suspend fun refresh(friendIds: Collection<String>) {
        val accountId = accounts.account.value?.userId ?: return
        val before = friendStatsFor(_cache.value, accountId)
        val next = HashMap<String, FriendStats>()
        for (id in friendIds) {
            val result = accounts.authed { accounts.api.friendStats(it, id) }
            if (result.isUnauthorizedOrLoggedOut()) return
            nextFriendStats(before[id], result, System.currentTimeMillis())?.let { next[id] = it }
        }
        withContext(Dispatchers.IO) {
            mutex.withLock { persistLocked(FriendStatsCache(accountId, next)) }
        }
    }

    private fun ApiResult<*>.isUnauthorizedOrLoggedOut(): Boolean =
        this is ApiResult.HttpError && status == 401

    private fun persistLocked(value: FriendStatsCache) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encodeFriendStatsCache(value))
        if (!tmp.renameTo(file)) {
            // Fallback: overwrite
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _cache.value = value
    }

    companion object {
        @Volatile private var instance: FriendStatsRepository? = null
        fun get(context: Context): FriendStatsRepository =
            instance ?: synchronized(this) {
                instance ?: FriendStatsRepository(
                    File(context.applicationContext.filesDir, "friend_stats.json"),
                    AccountRepository.get(context),
                ).also { instance = it }
            }
    }
}

// ---------------------------------------------------------------------------
// Pure, Android-free logic (unit-testable without a Context or file IO).
// ---------------------------------------------------------------------------

fun encodeFriendStatsCache(cache: FriendStatsCache): String =
    GameJson.format.encodeToString(FriendStatsCache.serializer(), cache)

fun decodeFriendStatsCache(text: String): FriendStatsCache? {
    if (text.isBlank()) return null
    return runCatching { GameJson.format.decodeFromString(FriendStatsCache.serializer(), text) }.getOrNull()
}

/** The cached entries if they belong to [accountId] (empty for another login). */
fun friendStatsFor(cache: FriendStatsCache?, accountId: String?): Map<String, FriendStats> =
    if (accountId != null && cache?.accountId == accountId) cache.entries else emptyMap()

/**
 * A friend's next cache entry from one fetch: a snapshot (OK, or UNREADABLE
 * if it doesn't decode), HIDDEN on 403 stats_hidden, NONE on 404 no_stats,
 * null (drop it) on 403 not_friends, and [previous] for anything else
 * (offline, server trouble).
 */
fun nextFriendStats(previous: FriendStats?, result: ApiResult<FriendStatsResponse>, nowMs: Long): FriendStats? =
    when (result) {
        is ApiResult.Ok -> {
            val snapshot = snapshotFromJson(result.value.snapshot)
            FriendStats(
                status = if (snapshot != null) FriendStatsStatus.OK else FriendStatsStatus.UNREADABLE,
                snapshot = snapshot,
                computedAt = result.value.computedAt,
                fetchedAt = nowMs,
            )
        }
        is ApiResult.HttpError -> when {
            result.code == "stats_hidden" -> FriendStats(FriendStatsStatus.HIDDEN, fetchedAt = nowMs)
            result.code == "no_stats" -> FriendStats(FriendStatsStatus.NONE, fetchedAt = nowMs)
            result.code == "not_friends" -> null
            else -> previous
        }
        is ApiResult.NetworkError -> previous
    }
