package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.X01State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the WIPE policy enforced by [decodeGameStore]: legacy name-only game
 * history (persisted as a bare JSON array, no version envelope) and any store
 * from an older schema are discarded and yield empty history WITHOUT throwing.
 * New (current-version) stores load normally and survive a round-trip.
 */
class GameStoreWipeTest {

    private val players = listOf(GamePlayer("Alice"), GamePlayer("Bob"))

    @Test
    fun legacyBareArray_isWipedToEmpty() {
        // The OLD on-disk format: a bare JSON array of name-only GameRecords,
        // exactly what shipped before the versioned envelope existed.
        val legacy = """
            [{"id":"old","mode":"X01","createdAtEpochMs":1,"updatedAtEpochMs":2,
              "state":{"type":"x01",
                "players":[{"name":"Alice"},{"name":"Bob"}],
                "perPlayer":[{"player":{"name":"Alice"},"turns":[]},
                             {"player":{"name":"Bob"},"turns":[]}],
                "startScore":501,"doubleOut":true,
                "currentPlayerIndex":0,"winnerIndices":[]}}]
        """.trimIndent()
        assertEquals(emptyList(), decodeGameStore(legacy), "legacy history must be wiped")
    }

    @Test
    fun olderSchemaVersion_isWipedToEmpty() {
        // A versioned envelope, but from an earlier schema (version 1).
        val older = """{"schemaVersion":1,"games":[]}"""
        assertEquals(emptyList(), decodeGameStore(older), "older schema must be wiped")
    }

    @Test
    fun blankOrCorrupt_isEmptyAndDoesNotThrow() {
        assertEquals(emptyList(), decodeGameStore(""))
        assertEquals(emptyList(), decodeGameStore("   "))
        assertEquals(emptyList(), decodeGameStore("not json at all"))
        assertEquals(emptyList(), decodeGameStore("{ broken"))
    }

    @Test
    fun currentVersionStore_loadsGames() {
        val record = GameRecord(
            id = "g1",
            mode = GameMode.X01,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            state = X01State.new(players).applyTurn(60),
        )
        val text = encodeGameStore(listOf(record))
        // Sanity: the envelope carries the current schema marker.
        assertTrue(
            text.contains("\"schemaVersion\":${GameStore.SCHEMA_VERSION}"),
            "expected version marker in: $text",
        )
        assertEquals(listOf(record), decodeGameStore(text), "current store round-trips")
    }

    @Test
    fun emptyCurrentStore_loadsEmpty() {
        val text = encodeGameStore(emptyList())
        assertEquals(emptyList(), decodeGameStore(text))
    }

    @Test
    fun newGamesWithPlayerIds_persistAndReload() {
        // Players now carry ids; confirm they survive the envelope round-trip.
        val idPlayers = listOf(GamePlayer("Alice", "pid-1"), GamePlayer("Bob", "pid-2"))
        val record = GameRecord(
            id = "g2",
            mode = GameMode.X01,
            createdAtEpochMs = 3L,
            updatedAtEpochMs = 4L,
            state = X01State.new(idPlayers),
        )
        val decoded = decodeGameStore(encodeGameStore(listOf(record)))
        assertEquals(1, decoded.size)
        assertEquals("pid-1", decoded[0].state.players[0].id)
    }
}
