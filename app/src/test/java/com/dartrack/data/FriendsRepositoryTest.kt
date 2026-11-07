package com.dartrack.data

import com.dartrack.model.Player
import com.dartrack.net.FriendEntry
import com.dartrack.net.FriendRequestEntry
import com.dartrack.net.FriendsList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure parts of [FriendsRepository]: the friends.json cache codec, account
 * scoping, the get-or-create of a friend's local player, and the picker list
 * that mixes local players with friends.
 */
class FriendsRepositoryTest {

    private fun sequentialIds(vararg ids: String): () -> String {
        val it = ids.iterator()
        return { it.next() }
    }

    private val sample = FriendsCache(
        accountId = "me",
        list = FriendsList(
            friends = listOf(FriendEntry("u1", "Anna", since = 1_700_000_000_000)),
            incoming = listOf(FriendRequestEntry("u2", "Ben", createdAt = 5)),
            outgoing = listOf(FriendRequestEntry("u3", "Cleo", createdAt = 6)),
        ),
        fetchedAtEpochMs = 42,
    )

    @Test
    fun cache_roundTrips() {
        assertEquals(sample, decodeFriendsCache(encodeFriendsCache(sample)))
    }

    @Test
    fun cache_blankOrCorruptIsNull() {
        assertNull(decodeFriendsCache(""))
        assertNull(decodeFriendsCache("[1,2"))
    }

    @Test
    fun cache_decodesServerShapeAndIgnoresUnknownFields() {
        val text = """{"accountId":"me","list":{"friends":[{"id":"u1","username":"Anna","since":1,"x":true}]},"future":1}"""
        val decoded = decodeFriendsCache(text)!!
        assertEquals(listOf(FriendEntry("u1", "Anna", 1)), decoded.list.friends)
        assertTrue(decoded.list.incoming.isEmpty())
    }

    @Test
    fun friendsFor_onlyForTheCachedAccount() {
        val me = Account("t", "me", "Me")
        assertEquals(sample.list, friendsFor(sample, me))
        assertEquals(FriendsList(), friendsFor(sample, me.copy(userId = "someone-else")))
        assertEquals(FriendsList(), friendsFor(sample, null))
        assertEquals(FriendsList(), friendsFor(null, me))
    }

    @Test
    fun ensureFriendPlayer_createsLinkedPlayerOnce() {
        val first = resolveAccountPlayer(emptyList(), "u1", "Anna", sequentialIds("p1"))
        assertTrue(first.changed)
        assertEquals(Player("p1", "Anna", accountId = "u1"), first.added)

        val second = resolveAccountPlayer(first.players, "u1", "Anna", sequentialIds("p2"))
        assertFalse(second.changed, "an already linked player is reused, not duplicated")
        assertEquals(first.added, second.added)
        assertEquals(1, second.players.size)
    }

    @Test
    fun ensureFriendPlayer_reusesLinkedPlayerEvenIfRenamedLocally() {
        val existing = listOf(Player("p1", "Annie", accountId = "u1"))
        val out = resolveAccountPlayer(existing, "u1", "Anna", sequentialIds("p2"))
        assertFalse(out.changed)
        assertEquals("p1", out.added.id)
    }

    @Test
    fun ensureFriendPlayer_doesNotHijackSameNamedLocalPlayer() {
        val existing = listOf(Player("p1", "anna"), Player("p2", "Anna (friend)"))
        val out = resolveAccountPlayer(existing, "u1", "Anna", sequentialIds("p3"))
        assertTrue(out.changed)
        assertEquals(Player("p3", "Anna (friend 2)", accountId = "u1"), out.added)
        assertNull(existing[0].accountId)
        assertEquals(3, out.players.size)
    }

    @Test
    fun playerOptions_mixesPlayersAndFriendsWithMeFirst() {
        val players = listOf(
            Player("p1", "Zed"),
            Player("p2", "Anna", accountId = "u1"),
            Player("pm", "Max", accountId = "me"),
        )
        val friends = listOf(FriendEntry("u1", "Anna"), FriendEntry("u4", "Bea"))
        val options = playerOptions(players, friends, mePlayerId = "pm")

        assertEquals(listOf("Max", "Anna", "Bea", "Zed"), options.map { it.name })
        assertEquals(listOf(false, true, true, false), options.map { it.isFriend })
        // Anna already has a local player: listed once, backed by it.
        assertEquals("p2", options[1].player?.id)
        // Bea has none yet: friend-only entry.
        assertNull(options[2].player)
        assertEquals("u4", options[2].friend?.id)
    }

    @Test
    fun playerOptions_withoutFriendsIsJustThePlayersByName() {
        val players = listOf(Player("p1", "bob"), Player("p2", "Al"))
        assertEquals(listOf("Al", "bob"), playerOptions(players, emptyList()).map { it.name })
    }
}
