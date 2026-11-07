package com.dartrack.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ApiClient] against a local [MockWebServer]: success decoding, the typed
 * error cases (server refusal vs. no connection), and the request shapes the
 * Dart-Track-Server expects (paths, bearer header, JSON bodies).
 */
class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApiClient(server.url("/").toString())
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun login_success_parsesTokenAndUser() = runBlocking<Unit> {
        server.enqueue(json(200, """{"token":"t1","user":{"id":"u1","username":"Max"},"extra":1}"""))
        val result = client.login("max", "secret1", "Pixel 8")
        val ok = assertIs<ApiResult.Ok<AuthResponse>>(result)
        assertEquals("t1", ok.value.token)
        assertEquals(ApiUser("u1", "Max"), ok.value.user)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/auth/login", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"username\":\"max\""))
        assertTrue(body.contains("\"password\":\"secret1\""))
        assertTrue(body.contains("\"deviceName\":\"Pixel 8\""))
        assertFalse(body.contains("newPassword"), "null fields are omitted")
    }

    @Test
    fun signup_returns201AndPostsToSignup() = runBlocking<Unit> {
        server.enqueue(json(201, """{"token":"t2","user":{"id":"u2","username":"Ann"}}"""))
        val result = client.signup("Ann", "secret1", "Pixel")
        assertIs<ApiResult.Ok<AuthResponse>>(result)
        assertEquals("/v1/auth/signup", server.takeRequest().path)
    }

    @Test
    fun login_passwordResetRequired_thenNewPasswordLogsIn() = runBlocking<Unit> {
        server.enqueue(json(409, """{"error":"password_reset_required","message":"Choose a new password"}"""))
        server.enqueue(json(200, """{"token":"t3","user":{"id":"u1","username":"Max"}}"""))

        val first = client.login("Max", "old", "Pixel")
        val err = assertIs<ApiResult.HttpError>(first)
        assertEquals(409, err.status)
        assertEquals("password_reset_required", err.code)
        server.takeRequest()

        val second = client.login("Max", password = null, deviceName = "Pixel", newPassword = "fresh12")
        assertIs<ApiResult.Ok<AuthResponse>>(second)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"newPassword\":\"fresh12\""))
        assertFalse(body.contains("\"password\""), "reset login sends no old password")
    }

    @Test
    fun unauthorized_isTypedAndFlagged() = runBlocking<Unit> {
        server.enqueue(json(401, """{"error":"unauthorized","message":"Missing or invalid token"}"""))
        val result = client.me("dead-token")
        val err = assertIs<ApiResult.HttpError>(result)
        assertEquals(401, err.status)
        assertEquals("unauthorized", err.code)
        assertTrue(result.isUnauthorized)
        assertEquals("Bearer dead-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun invalidCredentials_hasPlainLanguageMessage() = runBlocking<Unit> {
        server.enqueue(json(401, """{"error":"invalid_credentials","message":"x"}"""))
        val err = assertIs<ApiResult.HttpError>(client.login("a", "b", null))
        assertEquals("Wrong username or password.", err.userMessage())
    }

    @Test
    fun nonJsonErrorBody_fallsBackToStatusCode() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        val err = assertIs<ApiResult.HttpError>(client.me("t"))
        assertEquals(502, err.status)
        assertEquals("http_502", err.code)
        assertNull(err.message)
    }

    @Test
    fun networkFailure_isNetworkErrorNotHttpError() = runBlocking<Unit> {
        server.shutdown()
        val result = client.login("a", "b", null)
        assertIs<ApiResult.NetworkError>(result)
        assertFalse(result.isUnauthorized)
    }

    @Test
    fun malformedSuccessBody_isNetworkError() = runBlocking<Unit> {
        server.enqueue(json(200, """{"nope":true}"""))
        assertIs<ApiResult.NetworkError>(client.me("t"))
    }

    @Test
    fun me_parsesProfile() = runBlocking<Unit> {
        server.enqueue(json(200, """{"id":"u1","username":"Max","statsHidden":true}"""))
        val ok = assertIs<ApiResult.Ok<MeResponse>>(client.me("t"))
        assertEquals(MeResponse("u1", "Max", true), ok.value)
        assertEquals("/v1/me", server.takeRequest().path)
    }

    @Test
    fun logout_sendsEmptyBodyWithoutJsonContentType() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(204))
        assertIs<ApiResult.Ok<Unit>>(client.logout("t"))
        val req = server.takeRequest()
        assertEquals("/v1/auth/logout", req.path)
        assertEquals(0L, req.bodySize)
        assertNull(req.getHeader("Content-Type"))
    }

    @Test
    fun changePassword_wrongPassword() = runBlocking<Unit> {
        server.enqueue(json(403, """{"error":"wrong_password","message":"x"}"""))
        val err = assertIs<ApiResult.HttpError>(client.changePassword("t", "old", "newpass"))
        assertEquals("wrong_password", err.code)
        val req = server.takeRequest()
        assertEquals("/v1/me/password", req.path)
        assertEquals("""{"currentPassword":"old","newPassword":"newpass"}""", req.body.readUtf8())
    }

    // ---- Friends ------------------------------------------------------------

    @Test
    fun friends_parsesAllThreeLists() = runBlocking<Unit> {
        server.enqueue(
            json(
                200,
                """{"friends":[{"id":"u1","username":"Anna","since":10}],
                   "incoming":[{"id":"u2","username":"Ben","createdAt":20}],
                   "outgoing":[]}""",
            ),
        )
        val ok = assertIs<ApiResult.Ok<FriendsList>>(client.friends("t"))
        assertEquals(listOf(FriendEntry("u1", "Anna", 10)), ok.value.friends)
        assertEquals(listOf(FriendRequestEntry("u2", "Ben", 20)), ok.value.incoming)
        assertTrue(ok.value.outgoing.isEmpty())
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/friends", req.path)
        assertEquals("Bearer t", req.getHeader("Authorization"))
    }

    @Test
    fun sendFriendRequest_requestedAndAlreadyAsked() = runBlocking<Unit> {
        server.enqueue(json(201, """{"status":"requested","user":{"id":"u3","username":"Cleo"}}"""))
        server.enqueue(json(200, """{"status":"friends","user":{"id":"u2","username":"Ben"},"since":5}"""))

        val first = assertIs<ApiResult.Ok<FriendRequestResult>>(client.sendFriendRequest("t", "cleo"))
        assertFalse(first.value.becameFriends)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/friends/requests", req.path)
        assertEquals("""{"username":"cleo"}""", req.body.readUtf8())

        val second = assertIs<ApiResult.Ok<FriendRequestResult>>(client.sendFriendRequest("t", "ben"))
        assertTrue(second.value.becameFriends)
        assertEquals("Ben", second.value.user.username)
    }

    @Test
    fun sendFriendRequest_errors() = runBlocking<Unit> {
        server.enqueue(json(404, """{"error":"user_not_found","message":"x"}"""))
        server.enqueue(json(409, """{"error":"already_requested","message":"x"}"""))
        server.enqueue(json(400, """{"error":"cannot_friend_self","message":"x"}"""))
        assertEquals("user_not_found", assertIs<ApiResult.HttpError>(client.sendFriendRequest("t", "x")).code)
        val conflict = assertIs<ApiResult.HttpError>(client.sendFriendRequest("t", "y"))
        assertEquals(409, conflict.status)
        assertEquals("already_requested", conflict.code)
        assertEquals("You can't add yourself.", assertIs<ApiResult.HttpError>(client.sendFriendRequest("t", "me")).userMessage())
    }

    @Test
    fun acceptDeclineRemove_hitTheRightPaths() = runBlocking<Unit> {
        server.enqueue(json(200, """{"status":"friends","user":{"id":"u2","username":"Ben"},"since":5}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))

        assertIs<ApiResult.Ok<FriendRequestResult>>(client.acceptFriendRequest("t", "u2"))
        val accept = server.takeRequest()
        assertEquals("POST", accept.method)
        assertEquals("/v1/friends/requests/u2/accept", accept.path)
        assertEquals(0L, accept.bodySize)

        assertIs<ApiResult.Ok<Unit>>(client.deleteFriendRequest("t", "u3"))
        val decline = server.takeRequest()
        assertEquals("DELETE", decline.method)
        assertEquals("/v1/friends/requests/u3", decline.path)

        assertIs<ApiResult.Ok<Unit>>(client.removeFriend("t", "u1"))
        val remove = server.takeRequest()
        assertEquals("DELETE", remove.method)
        assertEquals("/v1/friends/u1", remove.path)
    }

    @Test
    fun idsAreEncodedAsASingleSegment() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(204))
        client.removeFriend("t", "a/b")
        assertEquals("/v1/friends/a%2Fb", server.takeRequest().path)
    }

    @Test
    fun friends_unauthorized() = runBlocking<Unit> {
        server.enqueue(json(401, """{"error":"unauthorized","message":"x"}"""))
        assertTrue(client.friends("dead").isUnauthorized)
    }
}
