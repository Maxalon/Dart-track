package com.dartrack.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Outcome of one server call. Callers branch on the three cases:
 *  - [Ok]: a 2xx answer, decoded.
 *  - [HttpError]: the server answered with a non-2xx status; [code] is the
 *    server's machine-readable `error` field (e.g. `invalid_credentials`).
 *  - [NetworkError]: no usable answer at all (offline, DNS, timeout, TLS, or a
 *    2xx body we couldn't parse) — distinguishable from a server-side refusal.
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    sealed interface Failure : ApiResult<Nothing>

    data class HttpError(val status: Int, val code: String, val message: String?) : Failure

    data class NetworkError(val cause: IOException) : Failure
}

/** True for a 401 — on an authenticated call that means the token is no longer valid. */
val ApiResult<*>.isUnauthorized: Boolean
    get() = this is ApiResult.HttpError && status == 401

// ---- Wire types (mirror Dart-Track-Server's JSON) ---------------------------

@Serializable
data class ApiUser(val id: String, val username: String)

@Serializable
data class AuthResponse(val token: String, val user: ApiUser)

@Serializable
data class MeResponse(val id: String, val username: String, val statsHidden: Boolean = false)

/** An accepted friend; [since] is epoch ms. */
@Serializable
data class FriendEntry(val id: String, val username: String, val since: Long = 0)

/** A pending friend request (incoming or outgoing); [createdAt] is epoch ms. */
@Serializable
data class FriendRequestEntry(val id: String, val username: String, val createdAt: Long = 0)

/** `GET /v1/friends`. */
@Serializable
data class FriendsList(
    val friends: List<FriendEntry> = emptyList(),
    val incoming: List<FriendRequestEntry> = emptyList(),
    val outgoing: List<FriendRequestEntry> = emptyList(),
)

/** Answer to sending/accepting a request: [status] is `requested` or `friends`. */
@Serializable
data class FriendRequestResult(val status: String, val user: ApiUser) {
    val becameFriends: Boolean get() = status == "friends"
}

@Serializable
private data class FriendRequestBody(val username: String)

// ---- Game sync ----------------------------------------------------------------

/**
 * One account seat of an uploaded game. [linkId] is set only on the upload that
 * moves a transferred guest's game into that friend's history (POST /v1/links).
 */
@Serializable
data class UploadParticipant(val userId: String, val seat: Int, val linkId: String? = null)

/**
 * One game for `POST /v1/games`: the complete local GameRecord as [record]
 * (verbatim; the server never reads inside it) plus client [meta].
 * [status] is `finished`, `unfinished` or `abandoned`.
 */
@Serializable
data class GameUpload(
    val id: String,
    val mode: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val record: JsonObject,
    val meta: JsonObject? = null,
    val participants: List<UploadParticipant>,
)

@Serializable
private data class UploadBody(val games: List<GameUpload>)

/** Per-game answer: `stored`, `unchanged`, `deleted`, `purged` or `rejected` (with [error]). */
@Serializable
data class UploadResult(val id: String, val result: String, val error: String? = null)

@Serializable
data class UploadResponse(val results: List<UploadResult> = emptyList())

/** An account seat in a downloaded game. */
@Serializable
data class RemoteParticipant(val userId: String, val username: String = "", val seat: Int)

/** One game from `GET /v1/games`, as some phone uploaded it. */
@Serializable
data class RemoteGame(
    val id: String,
    val mode: String = "",
    val status: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val record: JsonObject,
    val meta: JsonObject? = null,
    val uploadedBy: String? = null,
    val participants: List<RemoteParticipant> = emptyList(),
)

/** A page of `GET /v1/games`: what changed since the cursor, and the next cursor. */
@Serializable
data class GamesPage(
    val games: List<RemoteGame> = emptyList(),
    val deleted: List<String> = emptyList(),
    val cursor: Long = 0,
    val hasMore: Boolean = false,
)

// ---- Guest transfer & stats ----------------------------------------------------

@Serializable
private data class LinkBody(val friendId: String, val guestName: String)

/** An active guest → friend transfer created by this account. */
@Serializable
data class LinkEntry(val id: String, val friendId: String, val guestName: String = "", val createdAt: Long = 0)

@Serializable
data class LinksList(val links: List<LinkEntry> = emptyList())

@Serializable
private data class StatsBody(val snapshot: JsonObject, val computedAt: Long)

@Serializable
private data class MePatchBody(val statsHidden: Boolean)

/** A friend's uploaded stats snapshot (opaque to the server). */
@Serializable
data class FriendStatsResponse(val snapshot: JsonObject, val computedAt: Long = 0, val updatedAt: Long = 0)

@Serializable
private data class SignupBody(val username: String, val password: String, val deviceName: String?)

@Serializable
private data class LoginBody(
    val username: String,
    val password: String? = null,
    val newPassword: String? = null,
    val deviceName: String?,
)

@Serializable
private data class ChangePasswordBody(val currentPassword: String, val newPassword: String)

@Serializable
private data class ErrorBody(val error: String? = null, val message: String? = null)

/**
 * Thin, suspend-based client for the Dart-Track-Server REST API. Stateless:
 * authenticated calls take the bearer [token] explicitly, so the client never
 * holds account state (that lives in AccountRepository). All IO runs on
 * [Dispatchers.IO]; nothing throws for HTTP/network problems — every call
 * returns an [ApiResult].
 */
class ApiClient(
    baseUrl: String,
    private val http: OkHttpClient = defaultHttpClient(),
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    suspend fun signup(username: String, password: String, deviceName: String?): ApiResult<AuthResponse> =
        call(
            post("v1/auth/signup", json.encodeToString(SignupBody.serializer(), SignupBody(username, password, deviceName))),
            AuthResponse.serializer(),
        )

    /**
     * Normal login with [password]. After an admin password reset the server
     * answers 409 `password_reset_required`; repeat the call with [newPassword]
     * (and no password) to set it and log in.
     */
    suspend fun login(
        username: String,
        password: String?,
        deviceName: String?,
        newPassword: String? = null,
    ): ApiResult<AuthResponse> =
        call(
            post(
                "v1/auth/login",
                json.encodeToString(LoginBody.serializer(), LoginBody(username, password, newPassword, deviceName)),
            ),
            AuthResponse.serializer(),
        )

    suspend fun logout(token: String): ApiResult<Unit> =
        callUnit(post("v1/auth/logout", null, token))

    suspend fun me(token: String): ApiResult<MeResponse> =
        call(get("v1/me", token), MeResponse.serializer())

    suspend fun changePassword(token: String, currentPassword: String, newPassword: String): ApiResult<Unit> =
        callUnit(
            post(
                "v1/me/password",
                json.encodeToString(ChangePasswordBody.serializer(), ChangePasswordBody(currentPassword, newPassword)),
                token,
            ),
        )

    // ---- Friends ------------------------------------------------------------

    suspend fun friends(token: String): ApiResult<FriendsList> =
        call(get("v1/friends", token), FriendsList.serializer())

    /** Request friendship by exact username (case-insensitive on the server). */
    suspend fun sendFriendRequest(token: String, username: String): ApiResult<FriendRequestResult> =
        call(
            post("v1/friends/requests", json.encodeToString(FriendRequestBody.serializer(), FriendRequestBody(username)), token),
            FriendRequestResult.serializer(),
        )

    suspend fun acceptFriendRequest(token: String, userId: String): ApiResult<FriendRequestResult> =
        call(post("v1/friends/requests/{}/accept", null, token, userId), FriendRequestResult.serializer())

    /** Declines their request, or cancels yours — the server treats both the same. */
    suspend fun deleteFriendRequest(token: String, userId: String): ApiResult<Unit> =
        callUnit(delete("v1/friends/requests/{}", token, userId))

    suspend fun removeFriend(token: String, userId: String): ApiResult<Unit> =
        callUnit(delete("v1/friends/{}", token, userId))

    // ---- Game sync ----------------------------------------------------------

    /** Uploads up to 100 games (the app sends at most [SYNC_BATCH] per call). */
    suspend fun uploadGames(token: String, games: List<GameUpload>): ApiResult<UploadResponse> =
        call(post("v1/games", json.encodeToString(UploadBody.serializer(), UploadBody(games)), token), UploadResponse.serializer())

    /** What changed for this account after [since] (a cursor from the previous page; 0 = everything). */
    suspend fun gamesSince(token: String, since: Long, limit: Int = 100): ApiResult<GamesPage> {
        val url = url("v1/games", emptyArray()).newBuilder()
            .addQueryParameter("since", since.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        return call(request, GamesPage.serializer())
    }

    /** Removes the game from this account's history only. */
    suspend fun deleteGame(token: String, gameId: String): ApiResult<Unit> =
        callUnit(delete("v1/games/{}", token, gameId))

    // ---- Guest transfer -----------------------------------------------------

    suspend fun createLink(token: String, friendId: String, guestName: String): ApiResult<LinkEntry> =
        call(post("v1/links", json.encodeToString(LinkBody.serializer(), LinkBody(friendId, guestName)), token), LinkEntry.serializer())

    suspend fun links(token: String): ApiResult<LinksList> =
        call(get("v1/links", token), LinksList.serializer())

    // ---- Stats --------------------------------------------------------------

    suspend fun putMyStats(token: String, snapshot: JsonObject, computedAt: Long): ApiResult<Unit> =
        callUnit(
            builder("v1/me/stats", token, emptyArray())
                .put(json.encodeToString(StatsBody.serializer(), StatsBody(snapshot, computedAt)).toRequestBody(JSON_MEDIA))
                .build(),
        )

    suspend fun setStatsHidden(token: String, hidden: Boolean): ApiResult<MeResponse> =
        call(
            builder("v1/me", token, emptyArray())
                .patch(json.encodeToString(MePatchBody.serializer(), MePatchBody(hidden)).toRequestBody(JSON_MEDIA))
                .build(),
            MeResponse.serializer(),
        )

    suspend fun friendStats(token: String, friendId: String): ApiResult<FriendStatsResponse> =
        call(get("v1/friends/{}/stats", token, friendId), FriendStatsResponse.serializer())

    // ---- Request plumbing ---------------------------------------------------

    /**
     * [path] relative to the base URL; each `{}` segment is filled, in order,
     * from [ids] as one percent-encoded segment (so an id can't add segments).
     */
    private fun url(path: String, ids: Array<out String>): HttpUrl {
        val next = ids.iterator()
        val builder = base.newBuilder()
        path.split('/').forEach { builder.addPathSegment(if (it == "{}") next.next() else it) }
        return builder.build()
    }

    private fun builder(path: String, token: String?, ids: Array<out String>): Request.Builder =
        Request.Builder().url(url(path, ids)).apply {
            if (token != null) header("Authorization", "Bearer $token")
        }

    internal fun get(path: String, token: String?, vararg ids: String): Request =
        builder(path, token, ids).get().build()

    internal fun delete(path: String, token: String?, vararg ids: String): Request =
        builder(path, token, ids).delete().build()

    /**
     * A POST with a JSON [body], or with an EMPTY body and no Content-Type when
     * [body] is null (the server rejects `application/json` with no content).
     */
    internal fun post(path: String, body: String?, token: String? = null, vararg ids: String): Request =
        builder(path, token, ids).post(body?.toRequestBody(JSON_MEDIA) ?: EMPTY_BODY).build()

    internal suspend fun <T> call(request: Request, serializer: KSerializer<T>): ApiResult<T> =
        execute(request) { text ->
            runCatching { json.decodeFromString(serializer, text) }
                .fold(
                    onSuccess = { ApiResult.Ok(it) },
                    onFailure = { ApiResult.NetworkError(IOException("Unexpected response", it)) },
                )
        }

    internal suspend fun callUnit(request: Request): ApiResult<Unit> =
        execute(request) { ApiResult.Ok(Unit) }

    private suspend fun <T> execute(request: Request, onSuccess: (String) -> ApiResult<T>): ApiResult<T> =
        withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        onSuccess(text)
                    } else {
                        val err = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
                        ApiResult.HttpError(
                            status = response.code,
                            code = err?.error ?: "http_${response.code}",
                            message = err?.message,
                        )
                    }
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e)
            }
        }

    companion object {
        /** Lenient wire format: unknown fields are ignored, nulls are omitted. */
        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** Most games per upload call (the server accepts 100). */
        const val SYNC_BATCH = 50

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Plain-language text for a failed call, for inline display. Known server
 * codes get a specific sentence; anything else falls back to the server's own
 * message or a generic one.
 */
fun ApiResult.Failure.userMessage(): String = when (this) {
    is ApiResult.NetworkError -> "Can't reach the server. Check your connection and try again."
    is ApiResult.HttpError -> when (code) {
        "invalid_credentials" -> "Wrong username or password."
        "username_taken" -> "That username is already taken."
        "invalid_request" ->
            "Usernames are 3–20 letters, digits or _ and passwords need at least 6 characters."
        "wrong_password" -> "Your current password is wrong."
        "password_reset_required" -> "Your password was reset. Choose a new one."
        "user_not_found" -> "There's no user with that name."
        "cannot_friend_self" -> "You can't add yourself."
        "already_friends" -> "You're already friends."
        "already_requested" -> "You've already sent a request."
        "request_not_found" -> "That request no longer exists."
        "not_friends" -> "You're not friends anymore."
        "already_linked" -> "You've already moved a guest to this friend."
        "stats_hidden" -> "They hide their stats."
        "no_stats" -> "No stats yet."
        else -> when {
            status == 401 -> "You were logged out — log in again."
            status == 429 -> "Too many attempts. Wait a minute and try again."
            status >= 500 -> "The server had a problem. Try again later."
            else -> message ?: "Something went wrong (error $status)."
        }
    }
}
