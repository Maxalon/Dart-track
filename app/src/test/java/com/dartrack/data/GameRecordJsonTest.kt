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
    fun legacyX01Json_withoutMatchFields_decodesToSingleLeg() {
        // Simulates a game persisted BEFORE the match-play feature: no
        // legsToWin / legWins / startingPlayerIndex / completedLegs fields.
        val legacy = """
            {"id":"old","mode":"X01","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "state":{"type":"x01",
               "players":[{"name":"Alice"},{"name":"Bob"}],
               "perPlayer":[{"player":{"name":"Alice"},"turns":[]},
                            {"player":{"name":"Bob"},"turns":[]}],
               "startScore":501,"doubleOut":true,
               "currentPlayerIndex":0,"winnerIndices":[]}}
        """.trimIndent()
        val decoded = json.decodeFromString(GameRecord.serializer(), legacy)
        val state = decoded.state as X01State
        assertEquals(1, state.legsToWin, "defaults to single leg")
        assertTrue(state.legWins.isEmpty())
        assertEquals(0, state.startingPlayerIndex)
        assertTrue(state.completedLegs.isEmpty())
        assertEquals(0, state.legsWonBy(0), "empty legWins treated as zeros")
        // The sets layer also defaults to "off" for legacy data.
        assertEquals(1, state.setsToWin, "defaults to single set")
        assertTrue(state.setWins.isEmpty())
        assertEquals(0, state.setsWonBy(0), "empty setWins treated as zeros")
        // Still fully playable from the legacy state.
        val after = state.applyTurn(60)
        assertEquals(441, after.scoreFor(0))
    }

    @Test
    fun legsOnlyJson_withoutSetFields_decodesToSingleSet() {
        // Simulates a game persisted with the legs feature but BEFORE sets:
        // legsToWin / legWins / completedLegs present, but no setsToWin/setWins.
        val legacy = """
            {"id":"legs","mode":"X01","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "state":{"type":"x01",
               "players":[{"name":"Alice"},{"name":"Bob"}],
               "perPlayer":[{"player":{"name":"Alice"},"turns":[]},
                            {"player":{"name":"Bob"},"turns":[]}],
               "startScore":40,"doubleOut":false,
               "currentPlayerIndex":1,"winnerIndices":[],
               "legsToWin":3,"legWins":[1,0],"startingPlayerIndex":1,
               "completedLegs":[{"perPlayer":[
                  {"player":{"name":"Alice"},"turns":[
                     {"scoreBefore":40,"entered":40,"bust":false,"finished":true}]},
                  {"player":{"name":"Bob"},"turns":[]}],"winnerIndex":0}]}}
        """.trimIndent()
        val decoded = json.decodeFromString(GameRecord.serializer(), legacy)
        val state = decoded.state as X01State
        assertEquals(3, state.legsToWin)
        assertEquals(1, state.legsWonBy(0), "current-set legs preserved")
        assertEquals(1, state.setsToWin, "no sets layer for legacy legs-only data")
        assertTrue(state.setWins.isEmpty())
        assertEquals(0, state.setsWonBy(0))
        assertEquals(1, state.completedLegs.size)
    }

    @Test
    fun x01SetMatchRecord_roundTrips() {
        var state = X01State.new(players, startScore = 40, doubleOut = false,
            legsToWin = 2, setsToWin = 2)
        state = state.applyTurn(40)                 // Alice wins set1 leg1
        state = state.applyTurn(0)                  // Bob
        state = state.applyTurn(40)                 // Alice wins set1 -> set rollover
        val record = GameRecord("set-1", GameMode.X01, 1L, 2L, state)
        val decoded = roundTrip(record)
        assertEquals(record, decoded)
        val s = decoded.state as X01State
        assertEquals(2, s.setsToWin)
        assertEquals(1, s.setsWonBy(0))
        assertEquals(0, s.legsWonBy(0), "legs reset after set won")
        assertEquals(2, s.completedLegs.size)
    }

    @Test
    fun x01MatchRecord_roundTrips() {
        var state = X01State.new(players, startScore = 40, doubleOut = false, legsToWin = 2)
        state = state.applyTurn(40) // Alice wins leg 1; leg 2 begins
        val record = GameRecord("match-1", GameMode.X01, 1L, 2L, state)
        val decoded = roundTrip(record)
        assertEquals(record, decoded)
        val s = decoded.state as X01State
        assertEquals(2, s.legsToWin)
        assertEquals(1, s.completedLegs.size)
        assertEquals(1, s.legsWonBy(0))
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
