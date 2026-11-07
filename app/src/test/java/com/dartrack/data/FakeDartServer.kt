package com.dartrack.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * A tiny in-memory stand-in for Dart-Track-Server's sync, links and stats
 * routes (mirroring src/routes/games.ts and social.ts closely enough for
 * end-to-end SyncManager tests): per-participant change sequence, delete =
 * remove from your own history, purge once everyone deleted, uploads that
 * never remove participants, link checks, opaque stats snapshots.
 */
internal class FakeDartServer : Dispatcher() {

    data class User(val id: String, val username: String, var statsHidden: Boolean = false)
    data class Game(
        val id: String,
        var mode: String,
        var status: String,
        val createdAt: Long,
        var updatedAt: Long,
        var record: JsonObject,
        val uploadedBy: String,
    )
    data class Participant(var seat: Int, var linkId: String?, var deletedAt: Long?, var seq: Long)
    data class Link(val id: String, val createdBy: String, val friendId: String, val guestName: String)

    val users = LinkedHashMap<String, User>() // token -> user
    private val friendships = HashSet<Set<String>>()
    val games = LinkedHashMap<String, Game>()
    val participants = HashMap<String, LinkedHashMap<String, Participant>>() // gameId -> userId -> row
    val purged = HashSet<String>()
    val links = ArrayList<Link>()
    val stats = HashMap<String, JsonObject>()
    val requests = ArrayList<String>()
    private var seq = 0L

    fun addUser(token: String, id: String, username: String) {
        users[token] = User(id, username)
    }

    fun befriend(a: String, b: String) {
        friendships.add(setOf(a, b))
    }

    private fun areFriends(a: String, b: String) = setOf(a, b) in friendships

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        requests.add("${request.method} $path")
        val me = users[request.getHeader("Authorization")?.removePrefix("Bearer ")] ?: return error(401, "unauthorized")
        val body = request.body.readUtf8().takeIf { it.isNotBlank() }?.let { com.dartrack.net.ApiClient.json.parseToJsonElement(it).jsonObject }
        return when {
            request.method == "GET" && path == "/v1/friends" -> ok(
                obj(
                    "friends" to JsonArray(
                        users.values.filter { it.id != me.id && areFriends(me.id, it.id) }
                            .map { obj("id" to str(it.id), "username" to str(it.username), "since" to JsonPrimitive(1)) },
                    ),
                    "incoming" to JsonArray(emptyList()),
                    "outgoing" to JsonArray(emptyList()),
                ),
            )
            request.method == "POST" && path == "/v1/games" -> ok(
                obj("results" to JsonArray(body!!.getValue("games").jsonArray.map { upload(me.id, it.jsonObject) })),
            )
            request.method == "GET" && path == "/v1/games" -> download(
                me.id,
                request.requestUrl!!.queryParameter("since")!!.toLong(),
                request.requestUrl!!.queryParameter("limit")!!.toInt(),
            )
            request.method == "DELETE" && path.startsWith("/v1/games/") -> deleteGame(me.id, path.removePrefix("/v1/games/"))
            request.method == "PUT" && path == "/v1/me/stats" -> {
                stats[me.id] = body!!.getValue("snapshot").jsonObject
                MockResponse().setResponseCode(204)
            }
            request.method == "POST" && path == "/v1/links" -> {
                val friendId = body!!.getValue("friendId").jsonPrimitive.content
                if (!areFriends(me.id, friendId)) return error(403, "not_friends")
                if (links.any { it.createdBy == me.id && it.friendId == friendId }) return error(409, "already_linked")
                val link = Link("L${links.size + 1}", me.id, friendId, body.getValue("guestName").jsonPrimitive.content)
                links.add(link)
                ok(linkJson(link), 201)
            }
            request.method == "GET" && path == "/v1/links" ->
                ok(obj("links" to JsonArray(links.filter { it.createdBy == me.id }.map { linkJson(it) })))
            request.method == "PATCH" && path == "/v1/me" -> {
                me.statsHidden = body!!.getValue("statsHidden").jsonPrimitive.boolean
                ok(obj("id" to str(me.id), "username" to str(me.username), "statsHidden" to JsonPrimitive(me.statsHidden)))
            }
            else -> error(404, "not_found")
        }
    }

    private fun upload(me: String, g: JsonObject): JsonObject {
        val id = g.getValue("id").jsonPrimitive.content
        fun result(r: String, error: String? = null) =
            obj("id" to str(id), "result" to str(r), *(if (error != null) arrayOf("error" to str(error)) else emptyArray()))
        if (id in purged) return result("purged")
        val existing = games[id]
        val rows = participants.getOrPut(id) { LinkedHashMap() }
        if (existing != null) {
            val mine = rows[me]
            if (mine == null && existing.uploadedBy != me) return result("rejected", "not_a_participant")
            if (mine?.deletedAt != null) return result("deleted")
        }
        val incoming = g.getValue("participants").jsonArray.map { it.jsonObject }
        for (p in incoming) {
            val userId = p.getValue("userId").jsonPrimitive.content
            val linkId = p["linkId"]?.jsonPrimitive?.content
            if (userId in rows && linkId == null) continue
            if (users.values.none { it.id == userId }) return result("rejected", "unknown_user")
            if (userId != me && !areFriends(me, userId)) return result("rejected", "not_friends")
            if (linkId != null && links.none { it.id == linkId && it.createdBy == me && it.friendId == userId }) {
                return result("rejected", "invalid_link")
            }
        }
        val updatedAt = g.getValue("updatedAt").jsonPrimitive.long
        var changed = false
        if (existing == null) {
            games[id] = Game(
                id, g.getValue("mode").jsonPrimitive.content, g.getValue("status").jsonPrimitive.content,
                g.getValue("createdAt").jsonPrimitive.long, updatedAt, g.getValue("record").jsonObject, me,
            )
            changed = true
        } else if (updatedAt > existing.updatedAt) {
            existing.updatedAt = updatedAt
            existing.record = g.getValue("record").jsonObject
            existing.status = g.getValue("status").jsonPrimitive.content
            changed = true
        }
        val s = ++seq
        for (p in incoming) {
            val userId = p.getValue("userId").jsonPrimitive.content
            val seat = p.getValue("seat").jsonPrimitive.int
            val linkId = p["linkId"]?.jsonPrimitive?.content
            val prev = rows[userId]
            if (prev == null) {
                rows[userId] = Participant(seat, linkId, null, s)
                changed = true
            } else if (prev.seat != seat || (linkId != null && prev.linkId != linkId)) {
                prev.seat = seat
                if (linkId != null) prev.linkId = linkId
                changed = true
            }
        }
        if (changed) {
            val bump = ++seq
            rows.values.forEach { it.seq = bump }
        }
        return result(if (changed) "stored" else "unchanged")
    }

    private fun download(me: String, since: Long, limit: Int): MockResponse {
        val rows = participants.flatMap { (gameId, byUser) -> byUser[me]?.let { listOf(gameId to it) }.orEmpty() }
            .filter { it.second.seq > since }
            .sortedBy { it.second.seq }
        val page = rows.take(limit)
        val gamesOut = ArrayList<JsonElement>()
        val deleted = ArrayList<JsonElement>()
        for ((gameId, row) in page) {
            if (row.deletedAt != null) {
                deleted.add(str(gameId))
                continue
            }
            val g = games.getValue(gameId)
            gamesOut.add(
                obj(
                    "id" to str(g.id), "mode" to str(g.mode), "status" to str(g.status),
                    "createdAt" to JsonPrimitive(g.createdAt), "updatedAt" to JsonPrimitive(g.updatedAt),
                    "record" to g.record, "meta" to JsonNull, "uploadedBy" to str(g.uploadedBy),
                    "participants" to JsonArray(
                        participants.getValue(gameId).entries.sortedBy { it.value.seat }.map { (userId, p) ->
                            obj(
                                "userId" to str(userId),
                                "username" to str(users.values.first { it.id == userId }.username),
                                "seat" to JsonPrimitive(p.seat),
                            )
                        },
                    ),
                ),
            )
        }
        val cursor = page.lastOrNull()?.second?.seq ?: since
        return ok(
            obj(
                "games" to JsonArray(gamesOut), "deleted" to JsonArray(deleted),
                "cursor" to JsonPrimitive(cursor), "hasMore" to JsonPrimitive(rows.size > limit),
            ),
        )
    }

    private fun deleteGame(me: String, gameId: String): MockResponse {
        val row = participants[gameId]?.get(me)
        if (row == null) {
            return if (gameId in purged) MockResponse().setResponseCode(204) else error(404, "game_not_found")
        }
        if (row.deletedAt == null) {
            row.deletedAt = 1
            row.seq = ++seq
            if (participants.getValue(gameId).values.all { it.deletedAt != null }) {
                games.remove(gameId)
                purged.add(gameId)
            }
        }
        return MockResponse().setResponseCode(204)
    }

    private fun linkJson(l: Link) = obj(
        "id" to str(l.id), "friendId" to str(l.friendId), "guestName" to str(l.guestName), "createdAt" to JsonPrimitive(1),
    )

    private fun str(s: String) = JsonPrimitive(s)
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
    private fun ok(body: JsonObject, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body.toString())
    private fun error(code: Int, error: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
            .setBody(obj("error" to str(error), "message" to str(error)).toString())
}
