package com.dartrack.data

import android.content.Context
import android.os.Build
import com.dartrack.BuildConfig
import com.dartrack.model.Player
import com.dartrack.net.ApiClient
import com.dartrack.net.ApiResult
import com.dartrack.net.AuthResponse
import com.dartrack.net.isUnauthorized
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
 * The device's logged-in server account. [mePlayerId] is the local [Player]
 * that represents this user ("which player is you?"); null until chosen.
 */
@Serializable
data class Account(
    val token: String,
    val userId: String,
    val username: String,
    val mePlayerId: String? = null,
)

/**
 * Optional server account, mirroring the other single-file repositories (a
 * singleton [get], a [account] StateFlow, a [Mutex] guarding an atomic
 * temp-file + rename write via [persistLocked], [GameJson.format] for JSON).
 *
 * Stored in `noBackupFilesDir/account.json`: app-private and never part of a
 * backup or device transfer, so a bearer token can't leak through either.
 *
 * The app is fully usable without an account. Once logged in it stays logged
 * in (tokens never expire) until the user logs out — or until an
 * authenticated call answers 401, which means an admin reset or deleted the
 * account: then the token is dropped and [notice] carries a one-off message.
 */
class AccountRepository private constructor(
    private val file: File,
    private val api: ApiClient,
    private val players: PlayerRepository,
) {

    private val mutex = Mutex()
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)

    /** A non-blocking message for the UI (e.g. "You were logged out"), until [dismissNotice]. */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val text = runCatching { file.readText() }.getOrDefault("")
            _account.value = decodeAccount(text)
        }
    }

    suspend fun login(username: String, password: String): ApiResult<Account> =
        finishAuth(api.login(username.trim(), password, deviceName()))

    /** Second step of the admin-reset flow: sets [newPassword] and logs in. */
    suspend fun loginWithNewPassword(username: String, newPassword: String): ApiResult<Account> =
        finishAuth(api.login(username.trim(), password = null, deviceName = deviceName(), newPassword = newPassword))

    suspend fun signup(username: String, password: String): ApiResult<Account> =
        finishAuth(api.signup(username.trim(), password, deviceName()))

    /**
     * Logs out: revokes the token on the server (best effort — offline or an
     * already-dead token still logs out locally) and forgets the account. All
     * local data stays; the "me" player remains a normal local player (it keeps
     * its account link so logging back in to the same account reuses it).
     */
    suspend fun logout() {
        val current = _account.value ?: return
        api.logout(current.token)
        clear(current.token)
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): ApiResult<Unit> =
        authed { api.changePassword(it, currentPassword, newPassword) }

    /** Re-reads the profile (also how a revoked token is noticed on app start). */
    suspend fun refreshMe(): ApiResult<Unit> {
        val result = authed { api.me(it) }
        if (result is ApiResult.Ok) {
            update { it.copy(username = result.value.username) }
        }
        return when (result) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.Failure -> result
        }
    }

    /**
     * Makes [playerId] the "me" player: links it to this account (unlinking any
     * previous me player) and stores it in the account file.
     */
    suspend fun setMePlayer(playerId: String) {
        val current = _account.value ?: return
        players.linkToAccount(playerId, current.userId)
        update { it.copy(mePlayerId = playerId) }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /**
     * Runs an authenticated call with the current token. A 401 means the token
     * was revoked server-side: the account is cleared and [notice] is set. When
     * logged out, answers a synthetic 401 `not_logged_in` without any request.
     */
    suspend fun <T> authed(block: suspend (token: String) -> ApiResult<T>): ApiResult<T> {
        val token = _account.value?.token
            ?: return ApiResult.HttpError(401, "not_logged_in", null)
        val result = block(token)
        if (result.isUnauthorized && clear(token)) {
            _notice.value = "You were logged out — log in again."
        }
        return result
    }

    private suspend fun finishAuth(result: ApiResult<AuthResponse>): ApiResult<Account> {
        if (result !is ApiResult.Ok) return result as ApiResult.Failure
        val auth = result.value
        players.load()
        // Same account as before on this device, and its me player still
        // exists → reuse it silently; otherwise the UI asks "which player is you?".
        val me = players.findByAccount(auth.user.id)?.id
        val account = Account(auth.token, auth.user.id, auth.user.username, me)
        withContext(Dispatchers.IO) { mutex.withLock { persistLocked(account) } }
        _notice.value = null
        return ApiResult.Ok(account)
    }

    private suspend fun update(transform: (Account) -> Account) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _account.value ?: return@withLock
            persistLocked(transform(current))
        }
    }

    /** Forgets the account if it still holds [token]; true if it did. */
    private suspend fun clear(token: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_account.value?.token != token) return@withLock false
            file.delete()
            _account.value = null
            true
        }
    }

    private fun persistLocked(value: Account) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encodeAccount(value))
        if (!tmp.renameTo(file)) {
            // Fallback: overwrite
            file.writeText(tmp.readText())
            tmp.delete()
        }
        _account.value = value
    }

    private fun deviceName(): String = Build.MODEL ?: "Android"

    companion object {
        @Volatile private var instance: AccountRepository? = null
        fun get(context: Context): AccountRepository =
            instance ?: synchronized(this) {
                instance ?: AccountRepository(
                    File(context.applicationContext.noBackupFilesDir, "account.json"),
                    ApiClient(BuildConfig.SERVER_URL),
                    PlayerRepository.get(context),
                ).also { instance = it }
            }
    }
}

// ---------------------------------------------------------------------------
// Pure, Android-free logic (unit-testable without a Context or file IO).
// ---------------------------------------------------------------------------

fun encodeAccount(account: Account): String =
    GameJson.format.encodeToString(Account.serializer(), account)

/** Decodes the account file; a missing, blank or corrupt file means "logged out". */
fun decodeAccount(text: String): Account? {
    if (text.isBlank()) return null
    return runCatching { GameJson.format.decodeFromString(Account.serializer(), text) }.getOrNull()
}

/**
 * Players offered by the "which player is you?" picker for account [userId]:
 * every local player not already linked to a DIFFERENT account.
 */
fun eligibleMePlayers(players: List<Player>, userId: String): List<Player> =
    players.filter { it.accountId == null || it.accountId == userId }
