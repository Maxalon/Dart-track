package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.Player
import com.dartrack.model.X01State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic tests for player management: rename (normalization + uniqueness),
 * delete (registry removal), and merge (game-record reassignment +
 * registry removal), including the no-op edge cases. These exercise the
 * Android-free helpers ([resolveRename], [removePlayer],
 * [reassignPlayerInRecords]) that back the suspend repository methods.
 */
class PlayerManagementTest {

    private fun players(vararg p: Player): List<Player> = p.toList()

    // Build an X01 GameRecord with the given seats (id + name preserved).
    private fun record(id: String, vararg seats: GamePlayer): GameRecord {
        val state = X01State.new(seats.toList(), startScore = 501, doubleOut = true)
        return GameRecord(
            id = id,
            mode = GameMode.X01,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            state = state,
        )
    }

    // ---- rename ----------------------------------------------------------

    @Test
    fun rename_appliesTrimmedName() {
        val existing = players(Player("a", "Alice"), Player("b", "Bob"))
        val out = resolveRename(existing, "a", "  Alicia  ")
        assertTrue(out.changed, "rename should change the list")
        assertEquals("Alicia", out.players.first { it.id == "a" }.name, "name should be trimmed")
        assertEquals("Bob", out.players.first { it.id == "b" }.name, "other players untouched")
    }

    @Test
    fun rename_blankIsRejected() {
        val existing = players(Player("a", "Alice"))
        assertFailsWith<IllegalArgumentException> { resolveRename(existing, "a", "   ") }
        assertFailsWith<IllegalArgumentException> { resolveRename(existing, "a", "") }
    }

    @Test
    fun rename_missingIdIsNoOp() {
        val existing = players(Player("a", "Alice"))
        val out = resolveRename(existing, "nope", "Zed")
        assertFalse(out.changed, "unknown id is a no-op")
        assertEquals(existing, out.players, "list unchanged")
    }

    @Test
    fun rename_sameNameIsNoOp() {
        val existing = players(Player("a", "Alice"))
        val out = resolveRename(existing, "a", "Alice")
        assertFalse(out.changed, "no change when name is identical")
        assertEquals(existing, out.players, "list unchanged")
    }

    @Test
    fun rename_caseChangeOfSameNameIsApplied() {
        val existing = players(Player("a", "alice"))
        val out = resolveRename(existing, "a", "Alice")
        assertTrue(out.changed, "changing letter case is a real rename")
        assertEquals("Alice", out.players.first { it.id == "a" }.name)
    }

    @Test
    fun rename_collisionWithAnotherPlayerIsRejected() {
        val existing = players(Player("a", "Alice"), Player("b", "Bob"))
        // Case-insensitive + trimmed clash with Bob.
        val out = resolveRename(existing, "a", "  bOb ")
        assertFalse(out.changed, "must reject a name already used by another player")
        assertEquals(existing, out.players, "list unchanged on collision")
    }

    // ---- delete ----------------------------------------------------------

    @Test
    fun delete_removesPlayer() {
        val existing = players(Player("a", "Alice"), Player("b", "Bob"))
        val next = removePlayer(existing, "a")
        assertEquals(listOf(Player("b", "Bob")), next, "only the target is removed")
    }

    @Test
    fun delete_missingIdIsNoOp() {
        val existing = players(Player("a", "Alice"))
        val next = removePlayer(existing, "nope")
        assertEquals(existing, next, "removing an absent id leaves the list unchanged")
    }

    // ---- merge: record reassignment -------------------------------------

    @Test
    fun reassign_rewritesMatchingSeatsWithTargetIdAndName() {
        val records = listOf(
            record("g1", GamePlayer("Alice", "a"), GamePlayer("Bob", "b")),
        )
        val out = reassignPlayerInRecords(records, fromId = "a", intoId = "b", intoName = "Bob")
        assertEquals(1, out.count { it.changed }, "the record referencing 'a' changed")
        val seats = out[0].record.state.players
        assertEquals(
            listOf(GamePlayer("Bob", "b"), GamePlayer("Bob", "b")),
            seats,
            "seat 'a' is rewritten to target id/name; existing target seat kept",
        )
    }

    @Test
    fun reassign_leavesUnrelatedRecordsUnchanged() {
        val r1 = record("g1", GamePlayer("Alice", "a"), GamePlayer("Carol", "c"))
        val r2 = record("g2", GamePlayer("Bob", "b"), GamePlayer("Carol", "c"))
        val out = reassignPlayerInRecords(listOf(r1, r2), "a", "b", "Bob")
        assertEquals(1, out.count { it.changed }, "only g1 referenced 'a'")
        assertTrue(out[0].changed, "g1 changed")
        assertFalse(out[1].changed, "g2 unchanged")
        assertEquals(r2, out[1].record, "unchanged record is returned as-is")
    }

    @Test
    fun reassign_usesProvidedTargetName_evenIfSeatNameDiffered() {
        // A game stored 'a' under a stale display name; merge uses canonical name.
        val records = listOf(record("g1", GamePlayer("OldAliceName", "a")))
        val out = reassignPlayerInRecords(records, "a", "b", "Bob")
        assertEquals(
            listOf(GamePlayer("Bob", "b")),
            out[0].record.state.players,
            "reassigned seat takes the target's canonical name",
        )
    }

    @Test
    fun reassign_sameFromAndIntoIsNoOp() {
        val records = listOf(record("g1", GamePlayer("Alice", "a")))
        val out = reassignPlayerInRecords(records, "a", "a", "Alice")
        assertEquals(0, out.count { it.changed }, "fromId == intoId reassigns nothing")
        assertEquals(records, out.map { it.record }, "records returned unchanged")
    }

    @Test
    fun reassign_noMatchingRecordsChangesNothing() {
        val records = listOf(record("g1", GamePlayer("Carol", "c")))
        val out = reassignPlayerInRecords(records, "a", "b", "Bob")
        assertEquals(0, out.count { it.changed }, "no seat referenced 'a'")
        assertEquals(records, out.map { it.record }, "records returned unchanged")
    }

    @Test
    fun merge_endToEndPureFlow_reassignsThenRemovesFromRegistry() {
        // Mirrors PlayerRepository.merge composition over the pure helpers.
        val registry = players(Player("a", "Alice"), Player("b", "Bob"))
        val into = registry.first { it.id == "b" }
        val records = listOf(record("g1", GamePlayer("Alice", "a"), GamePlayer("Bob", "b")))

        val reassigned = reassignPlayerInRecords(records, "a", into.id, into.name)
        assertEquals(1, reassigned.count { it.changed }, "one game reassigned")

        val afterRegistry = removePlayer(registry, "a")
        assertEquals(listOf(Player("b", "Bob")), afterRegistry, "source removed from registry")
    }
}
