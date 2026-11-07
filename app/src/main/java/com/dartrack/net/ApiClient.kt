package com.dartrack.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    // ---- Request plumbing ---------------------------------------------------

    private fun url(path: String): HttpUrl =
        base.newBuilder().addPathSegments(path).build()

    private fun builder(path: String, token: String?): Request.Builder =
        Request.Builder().url(url(path)).apply {
            if (token != null) header("Authorization", "Bearer $token")
        }

    internal fun get(path: String, token: String?): Request = builder(path, token).get().build()

    internal fun delete(path: String, token: String?): Request = builder(path, token).delete().build()

    /**
     * A POST with a JSON [body], or with an EMPTY body and no Content-Type when
     * [body] is null (the server rejects `application/json` with no content).
     */
    internal fun post(path: String, body: String?, token: String? = null): Request =
        builder(path, token).post(body?.toRequestBody(JSON_MEDIA) ?: EMPTY_BODY).build()

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
        else -> when {
            status == 401 -> "You were logged out — log in again."
            status == 429 -> "Too many attempts. Wait a minute and try again."
            status >= 500 -> "The server had a problem. Try again later."
            else -> message ?: "Something went wrong (error $status)."
        }
    }
}
