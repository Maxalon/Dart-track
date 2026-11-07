package com.dartrack.data

import com.dartrack.model.Player
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pure account logic: the account.json codec, the legacy players.json decode
 * (no accountId field), account linking in the registry, and which players the
 * "which player is you?" picker offers.
 */
class AccountTest {

    @Test
    fun account_roundTrips() {
        val a = Account(token = "tok", userId = "u1", username = "Max", mePlayerId = "p1")
        assertEquals(a, decodeAccount(encodeAccount(a)))
        val noMe = a.copy(mePlayerId = null)
        assertEquals(noMe, decodeAccount(encodeAccount(noMe)))
    }

    @Test
    fun account_blankOrCorruptMeansLoggedOut() {
        assertNull(decodeAccount(""))
        assertNull(decodeAccount("   "))
        assertNull(decodeAccount("{not json"))
        assertNull(decodeAccount("""{"token":"t"}"""))
    }

    @Test
    fun legacyPlayersJson_decodesWithNullAccountId() {
        val legacy = """[{"id":"p1","name":"Alice"},{"id":"p2","name":"Bob"}]"""
        val players = GameJson.format.decodeFromString(ListSerializer(Player.serializer()), legacy)
        assertEquals(listOf(Player("p1", "Alice"), Player("p2", "Bob")), players)
        assertNull(players[0].accountId)
    }

    @Test
    fun player_withoutAccount_encodesWithoutAccountIdField() {
        val text = GameJson.format.encodeToString(Player.serializer(), Player("p1", "Alice"))
        assertEquals("""{"id":"p1","name":"Alice"}""", text)
        val linked = Player("p1", "Alice", accountId = "u1")
        assertEquals(linked, GameJson.format.decodeFromString(Player.serializer(), GameJson.format.encodeToString(Player.serializer(), linked)))
    }

    @Test
    fun link_setsAccountAndUnlinksPreviousHolder() {
        val players = listOf(Player("p1", "A", accountId = "u1"), Player("p2", "B"), Player("p3", "C", accountId = "u9"))
        val next = linkPlayerToAccount(players, "p2", "u1")
        assertEquals(listOf(Player("p1", "A"), Player("p2", "B", "u1"), Player("p3", "C", "u9")), next)
        assertEquals("p2", findPlayerByAccount(next, "u1")?.id)
    }

    @Test
    fun link_missingPlayerIsNoOp() {
        val players = listOf(Player("p1", "A"))
        assertSame(players, linkPlayerToAccount(players, "nope", "u1"))
        assertNull(findPlayerByAccount(players, "u1"))
    }

    @Test
    fun eligibleMePlayers_excludesPlayersOfOtherAccounts() {
        val players = listOf(Player("p1", "A"), Player("p2", "B", "u1"), Player("p3", "C", "u2"))
        assertEquals(listOf("p1", "p2"), eligibleMePlayers(players, "u1").map { it.id })
    }
}
