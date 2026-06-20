package com.dartrack.data

import com.dartrack.model.CricketState
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.HalfItState
import com.dartrack.model.X01State
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JSON round-trip tests for the persistence format. These use the SAME
 * [GameJson.format] instance the repository uses (classDiscriminator = "type",
 * ignoreUnknownKeys = true) so they guard the on-disk schema. If serialization
 * config or any @SerialName changes incompatibly, these fail.
 */
class GameRecordJsonTest {

    private val json = GameJson.format
    private val players = listOf(GamePlayer("Alice"), GamePlayer("Bob"))

    private fun roundTrip(record: GameRecord): GameRecord {
        val text = json.encodeToString(GameRecord.serializer(), record)
        return json.decodeFromString(GameRecord.serializer(), text)
    }

    @Test
    fun x01Record_roundTrips() {
        val state = X01State.new(players, startScore = 501, doubleOut = true)
            .applyTurn(60)               // Alice -> 441
            .applyTurn(45)               // Bob -> 456
        val record = GameRecord(
            id = "x01-1",
            mode = GameMode.X01,
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        assertEquals(record, roundTrip(record))
    }

    @Test
    fun cricketRecord_roundTrips() {
        val state = CricketState.new(players)
            .applyTurn(mapOf(20 to 3))   // Alice closes 20
            .applyTurn(mapOf(19 to 2))   // Bob
        val record = GameRecord(
            id = "cricket-1",
            mode = GameMode.CRICKET,
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        assertEquals(record, roundTrip(record))
    }

    @Test
    fun halfItRecord_roundTrips() {
        val state = HalfItState.new(players)
            .applyTurn(15)               // Alice r0
            .applyTurn(0)                // Bob r0 (halved) -> advance round
        val record = GameRecord(
            id = "halfit-1",
            mode = GameMode.HALF_IT,
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        assertEquals(record, roundTrip(record))
    }

    @Test
    fun listOfMixedModes_roundTrips() {
        // Mirrors how GameRepository persists: a ListSerializer of GameRecord.
        val records = listOf(
            GameRecord("a", GameMode.X01, 1L, 2L,
                X01State.new(players).applyTurn(100)),
            GameRecord("b", GameMode.CRICKET, 3L, 4L,
                CricketState.new(players).applyTurn(mapOf(25 to 1))),
            GameRecord("c", GameMode.HALF_IT, 5L, 6L,
                HalfItState.new(players).applyTurn(20)),
        )
        val text = json.encodeToString(ListSerializer(GameRecord.serializer()), records)
        val decoded = json.decodeFromString(ListSerializer(GameRecord.serializer()), text)
        assertEquals(records, decoded)
    }

    @Test
    fun polymorphicDiscriminator_usesTypeKey() {
        val record = GameRecord("x", GameMode.X01, 1L, 2L, X01State.new(players))
        val text = json.encodeToString(GameRecord.serializer(), record)
        // classDiscriminator is "type"; X01State's @SerialName is "x01".
        assertTrue(text.contains("\"type\":\"x01\""), "expected type discriminator: $text")
    }

    @Test
    fun finishedX01Record_preservesWinner() {
        val state = X01State.new(players, startScore = 40, doubleOut = false)
            .applyTurn(40) // Alice finishes
        assertTrue(state.isFinished)
        val record = GameRecord("done", GameMode.X01, 1L, 2L, state)
        val decoded = roundTrip(record)
        assertEquals(listOf(0), decoded.state.winnerIndices)
        assertEquals(record, decoded)
    }
}
