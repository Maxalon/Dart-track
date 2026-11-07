package com.dartrack.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [ApiClient]'s game-sync, guest-transfer and stats calls against a
 * [MockWebServer]: methods, paths, query, bearer header, JSON bodies and the
 * decoded answers / error codes.
 */
class SyncApiTest {

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

    private val record = JsonObject(mapOf("id" to JsonPrimitive("g1"), "mode" to JsonPrimitive("X01")))

    @Test
    fun uploadGames_postsTheBatch_andDecodesResults() = runBlocking<Unit> {
        server.enqueue(json(200, """{"results":[{"id":"g1","result":"stored"},{"id":"g2","result":"rejected","error":"not_friends"}]}"""))
        val upload = GameUpload(
            id = "g1", mode = "X01", status = "finished", createdAt = 1, updatedAt = 2,
            record = record, meta = JsonObject(mapOf("androidSdk" to JsonPrimitive(35))),
            participants = listOf(UploadParticipant("u1", 0), UploadParticipant("u2", 1, linkId = "L1")),
        )
        val result = client.uploadGames("tok", listOf(upload))
        val ok = assertIs<ApiResult.Ok<UploadResponse>>(result)
        assertEquals(listOf(UploadResult("g1", "stored"), UploadResult("g2", "rejected", "not_friends")), ok.value.results)

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/games", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        val body = ApiClient.json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val game = body.getValue("games").jsonArray.single().jsonObject
        assertEquals(record, game.getValue("record"), "record is sent verbatim as an object")
        assertEquals(35, game.getValue("meta").jsonObject.getValue("androidSdk").jsonPrimitive.int)
        val participants = game.getValue("participants").jsonArray
        assertFalse(participants[0].jsonObject.containsKey("linkId"), "no linkId key unless transferring")
        assertEquals("L1", participants[1].jsonObject.getValue("linkId").jsonPrimitive.content)
    }

    @Test
    fun gamesSince_sendsCursorAndLimit_andDecodesThePage() = runBlocking<Unit> {
        server.enqueue(
            json(
                200,
                """{"games":[{"id":"g1","mode":"X01","status":"finished","createdAt":1,"updatedAt":2,
                  "record":{"id":"g1"},"meta":null,"uploadedBy":"u2",
                  "participants":[{"userId":"u1","username":"Max","seat":0}]}],
                  "deleted":["g9"],"cursor":17,"hasMore":true}""",
            ),
        )
        val page = assertIs<ApiResult.Ok<GamesPage>>(client.gamesSince("tok", since = 5)).value
        assertEquals(17, page.cursor)
        assertTrue(page.hasMore)
        assertEquals(listOf("g9"), page.deleted)
        assertEquals(listOf(RemoteParticipant("u1", "Max", 0)), page.games.single().participants)
        assertEquals("u2", page.games.single().uploadedBy)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/games?since=5&limit=100", req.path)
        assertEquals("Bearer tok", req.getHeader("Authorization"))
    }

    @Test
    fun deleteGame_isADeleteOnTheGame() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(204))
        assertIs<ApiResult.Ok<Unit>>(client.deleteGame("tok", "g/1"))
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/games/g%2F1", req.path)
    }

    @Test
    fun links_createListAndAlreadyLinked() = runBlocking<Unit> {
        server.enqueue(json(201, """{"id":"L1","friendId":"u2","guestName":"Gary","createdAt":3}"""))
        server.enqueue(json(409, """{"error":"already_linked","message":"x"}"""))
        server.enqueue(json(200, """{"links":[{"id":"L1","friendId":"u2","guestName":"Gary","createdAt":3}]}"""))

        val link = assertIs<ApiResult.Ok<LinkEntry>>(client.createLink("tok", "u2", "Gary")).value
        assertEquals(LinkEntry("L1", "u2", "Gary", 3), link)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/links", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"friendId\":\"u2\"") && body.contains("\"guestName\":\"Gary\""))

        val conflict = assertIs<ApiResult.HttpError>(client.createLink("tok", "u2", "Other"))
        assertEquals("already_linked", conflict.code)
        assertEquals("You've already moved a guest to this friend.", conflict.userMessage())
        server.takeRequest()

        assertEquals(listOf(link), assertIs<ApiResult.Ok<LinksList>>(client.links("tok")).value.links)
        assertEquals("/v1/links", server.takeRequest().path)
    }

    @Test
    fun stats_putPatchAndFriendStatsErrors() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(json(200, """{"id":"u1","username":"Max","statsHidden":true}"""))
        server.enqueue(json(200, """{"snapshot":{"version":1},"computedAt":10,"updatedAt":11}"""))
        server.enqueue(json(403, """{"error":"stats_hidden"}"""))
        server.enqueue(json(404, """{"error":"no_stats"}"""))

        val snapshot = JsonObject(mapOf("version" to JsonPrimitive(1)))
        assertIs<ApiResult.Ok<Unit>>(client.putMyStats("tok", snapshot, computedAt = 99))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/v1/me/stats", put.path)
        assertEquals("""{"snapshot":{"version":1},"computedAt":99}""", put.body.readUtf8())

        val me = assertIs<ApiResult.Ok<MeResponse>>(client.setStatsHidden("tok", true)).value
        assertTrue(me.statsHidden)
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/v1/me", patch.path)
        assertEquals("""{"statsHidden":true}""", patch.body.readUtf8())

        val stats = assertIs<ApiResult.Ok<FriendStatsResponse>>(client.friendStats("tok", "u2")).value
        assertEquals(snapshot, stats.snapshot)
        assertEquals(10, stats.computedAt)
        assertEquals("/v1/friends/u2/stats", server.takeRequest().path)

        assertEquals("stats_hidden", assertIs<ApiResult.HttpError>(client.friendStats("tok", "u2")).code)
        assertEquals("no_stats", assertIs<ApiResult.HttpError>(client.friendStats("tok", "u3")).code)
    }
}
