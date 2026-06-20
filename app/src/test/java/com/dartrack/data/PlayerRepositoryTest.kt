package com.dartrack.data

import com.dartrack.model.Player
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests the pure (Android-free) player-registry logic that backs
 * [PlayerRepository]: name normalization, case-insensitive/trimmed dedupe,
 * blank rejection, unique-id assignment, and JSON round-trip of the on-disk
 * format (the same [GameJson.format] the repository uses).
 */
class PlayerRepositoryTest {

    private val json = GameJson.format

    // A deterministic id generator so tests don't depend on UUID randomness.
    private fun sequentialIds(vararg ids: String): () -> String {
        val it = ids.iterator()
        return { it.next() }
    }

    @Test
    fun addPlayer_createsNewPlayer() {
        val out = resolveAddPlayer(emptyList(), "Alice", sequentialIds("id-1"))
        assertTrue(out.changed, "new player should change the store")
        assertEquals("Alice", out.added.name)
        assertEquals("id-1", out.added.id)
        assertEquals(listOf(out.added), out.players)
    }

    @Test
    fun addPlayer_trimsName() {
        val out = resolveAddPlayer(emptyList(), "  Bob  ", sequentialIds("id-1"))
        assertEquals("Bob", out.added.name, "name should be trimmed")
    }

    @Test
    fun addPlayer_blankIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            resolveAddPlayer(emptyList(), "   ", sequentialIds("id-1"))
        }
        assertFailsWith<IllegalArgumentException> {
            resolveAddPlayer(emptyList(), "", sequentialIds("id-1"))
        }
    }

    @Test
    fun addPlayer_duplicateNameCaseInsensitive_returnsExisting() {
        val existing = listOf(Player("id-1", "Alice"))
        val out = resolveAddPlayer(existing, "ALICE", sequentialIds("id-2"))
        assertFalse(out.changed, "duplicate must not change the store")
        assertEquals(existing[0], out.added, "should return the existing player")
        assertEquals(existing, out.players, "list unchanged")
    }

    @Test
    fun addPlayer_duplicateNameWithSurroundingWhitespace_returnsExisting() {
        val existing = listOf(Player("id-1", "Alice"))
        val out = resolveAddPlayer(existing, "  alice ", sequentialIds("id-2"))
        assertFalse(out.changed)
        assertEquals("id-1", out.added.id)
    }

    @Test
    fun addPlayer_distinctNames_getDistinctIds() {
        val first = resolveAddPlayer(emptyList(), "Alice", sequentialIds("id-1"))
        val second = resolveAddPlayer(first.players, "Bob", sequentialIds("id-2"))
        assertTrue(second.changed)
        assertEquals(2, second.players.size)
        assertNotEquals(first.added.id, second.added.id)
    }

    @Test
    fun addPlayer_idCollision_isRetriedUntilUnique() {
        val existing = listOf(Player("dup", "Alice"))
        // Generator first yields the already-used id, then a fresh one.
        val out = resolveAddPlayer(existing, "Bob", sequentialIds("dup", "fresh"))
        assertTrue(out.changed)
        assertEquals("fresh", out.added.id, "collided id should be skipped")
    }

    @Test
    fun normalizePlayerName_trimsAndLowercases() {
        assertEquals("alice", normalizePlayerName("  Alice "))
    }

    @Test
    fun sortByName_isCaseInsensitive() {
        val unsorted = listOf(
            Player("3", "charlie"),
            Player("1", "Alice"),
            Player("2", "bob"),
        )
        val sorted = sortByName(unsorted)
        assertEquals(listOf("Alice", "bob", "charlie"), sorted.map { it.name })
    }

    @Test
    fun player_roundTrips() {
        val p = Player("id-1", "Alice")
        val text = json.encodeToString(Player.serializer(), p)
        assertEquals(p, json.decodeFromString(Player.serializer(), text))
    }

    @Test
    fun playerList_roundTrips() {
        val list = listOf(Player("id-1", "Alice"), Player("id-2", "Bob"))
        val ser = ListSerializer(Player.serializer())
        val text = json.encodeToString(ser, list)
        assertEquals(list, json.decodeFromString(ser, text))
    }
}
