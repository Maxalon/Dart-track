package com.dartrack.model.bot

import com.dartrack.data.GameJson
import com.dartrack.data.GameRecord
import com.dartrack.model.Checkout
import com.dartrack.model.CountUpState
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.ROUNDS
import com.dartrack.model.X01State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for wiring the pure [DartsBot] into real game flow. These stay at the
 * MODEL level (model + bot only, no viewmodel/Android): a CPU seat is just a
 * [GamePlayer] flagged [GamePlayer.isBot] with a [GamePlayer.botLevel], and a
 * bot turn is applied through the SAME `applyTurn` path a human turn uses.
 *
 * Covers the backward-compatible serialization of the new seat fields and an
 * end-to-end "bot drives a leg" sanity check for both X01 and Count-Up.
 */
class BotIntegrationTest {

    private val json = GameJson.format

    // --------------------------------------------------------- serialization

    @Test
    fun gamePlayer_botSeat_roundTripsThroughGameJson() {
        val seat = GamePlayer(
            name = "CPU · Hard",
            id = "bot:abc-123",
            isBot = true,
            botLevel = BotLevel.HARD,
        )
        val text = json.encodeToString(GamePlayer.serializer(), seat)
        val decoded = json.decodeFromString(GamePlayer.serializer(), text)
        assertEquals(seat, decoded)
        assertTrue(decoded.isBot)
        assertEquals(BotLevel.HARD, decoded.botLevel)
    }

    @Test
    fun gamePlayer_defaultsAreHuman() {
        val human = GamePlayer(name = "Alice", id = "uuid-1")
        assertFalse(human.isBot, "a plain seat is not a bot")
        assertNull(human.botLevel, "a plain seat has no level")
    }

    @Test
    fun legacyGamePlayerJson_withoutBotFields_decodesAsHuman() {
        // A seat persisted BEFORE the CPU feature: name + id only, no isBot /
        // botLevel keys. It must decode to a human seat (defaults), so old saves
        // keep working.
        val legacy = """{"name":"Bob","id":"uuid-2"}"""
        val decoded = json.decodeFromString(GamePlayer.serializer(), legacy)
        assertEquals("Bob", decoded.name)
        assertEquals("uuid-2", decoded.id)
        assertFalse(decoded.isBot, "absent isBot defaults to false")
        assertNull(decoded.botLevel, "absent botLevel defaults to null")
    }

    @Test
    fun x01Record_withBotSeat_roundTrips() {
        // A whole game record carrying a bot seat survives the on-disk format.
        val seats = listOf(
            GamePlayer("Alice", "uuid-1"),
            GamePlayer("CPU · Pro", "bot:xyz", isBot = true, botLevel = BotLevel.PRO),
        )
        val state = X01State.new(seats, startScore = 501, doubleOut = true)
            .applyTurn(60) // Alice
            .applyTurn(100) // CPU
        val record = GameRecord(
            id = "x01-bot-1",
            mode = GameMode.X01,
            createdAtEpochMs = 1000L,
            updatedAtEpochMs = 2000L,
            state = state,
        )
        val text = json.encodeToString(GameRecord.serializer(), record)
        val decoded = json.decodeFromString(GameRecord.serializer(), text)
        assertEquals(record, decoded)
        val cpu = (decoded.state as X01State).players[1]
        assertTrue(cpu.isBot)
        assertEquals(BotLevel.PRO, cpu.botLevel)
    }

    // -------------------------------------------------- bot drives a leg (X01)

    @Test
    fun botDrivesX01Leg_throughApplyTurn_terminatesInLegalFinishedState() {
        // Mirror exactly how the viewmodel will drive a bot turn: read the
        // current player's remaining + doubleOut, ask the bot for a visit, and
        // feed it through the same applyTurn a human uses (flagging a finish when
        // the bot returns a finishable remaining). Must terminate cleanly.
        val seats = listOf(
            GamePlayer("CPU A", "bot:a", isBot = true, botLevel = BotLevel.PRO),
            GamePlayer("CPU B", "bot:b", isBot = true, botLevel = BotLevel.PRO),
        )
        var state = X01State.new(seats, startScore = 501, doubleOut = true)
        // Deterministic per-seat bot, just as the viewmodel constructs one.
        val bots = seats.map { DartsBot(it.botLevel!!, seed = 99L) }

        var guard = 0
        while (!state.isFinished && guard < 1000) {
            val idx = state.currentPlayerIndex
            val rem = state.currentPlayerScore()
            val visit = bots[idx].x01Visit(rem, doubleOut = state.doubleOut)
            assertTrue(visit in 0..180, "engine would reject $visit")
            val finishes = visit == rem && Checkout.suggest(rem, doubleOut = true).isNotEmpty()
            state = state.applyTurn(visit, finishedOnDouble = finishes)
            guard++
        }

        assertTrue(state.isFinished, "two PRO bots should finish a 501 leg")
        // Exactly one winner, who is genuinely on 0.
        assertEquals(1, state.winnerIndices.size)
        val winner = state.winnerIndices.single()
        assertEquals(0, state.scoreFor(winner), "winner must be checked out on 0")
        // The winning turn is a real finish recorded against the winner.
        assertTrue(state.perPlayer[winner].turns.last().finished, "last winner turn is a finish")
    }

    @Test
    fun botDrivesX01Leg_everyVisitIsALegalTotal() {
        // Across a full leg, every bot visit fed to the engine is in 0..180 and
        // is never one of the forbidden 3-dart totals.
        val seats = listOf(
            GamePlayer("CPU A", "bot:a", isBot = true, botLevel = BotLevel.MEDIUM),
            GamePlayer("CPU B", "bot:b", isBot = true, botLevel = BotLevel.MEDIUM),
        )
        var state = X01State.new(seats, startScore = 301, doubleOut = true)
        val bots = seats.map { DartsBot(it.botLevel!!, seed = 1234L) }
        var guard = 0
        while (!state.isFinished && guard < 1000) {
            val idx = state.currentPlayerIndex
            val rem = state.currentPlayerScore()
            val visit = bots[idx].x01Visit(rem, doubleOut = state.doubleOut)
            assertTrue(visit in 0..180, "visit $visit out of range")
            assertFalse(
                visit in DartsBot.FORBIDDEN_VISIT_TOTALS,
                "visit $visit is a forbidden 3-dart total",
            )
            val finishes = visit == rem && Checkout.suggest(rem, doubleOut = true).isNotEmpty()
            state = state.applyTurn(visit, finishedOnDouble = finishes)
            guard++
        }
        assertTrue(state.isFinished)
    }

    // --------------------------------------------- bot drives a game (Count-Up)

    @Test
    fun botDrivesCountUpGame_throughApplyTurn_terminatesWithLegalWinner() {
        val seats = listOf(
            GamePlayer("CPU A", "bot:a", isBot = true, botLevel = BotLevel.HARD),
            GamePlayer("CPU B", "bot:b", isBot = true, botLevel = BotLevel.EASY),
        )
        var state = CountUpState.new(seats)
        val bots = seats.map { DartsBot(it.botLevel!!, seed = 55L) }

        var guard = 0
        while (!state.isFinished && guard < 1000) {
            val idx = state.currentPlayerIndex
            val visit = bots[idx].countUpVisit()
            assertTrue(visit in 0..180, "count-up visit $visit out of range")
            state = state.applyTurn(visit)
            guard++
        }

        assertTrue(state.isFinished, "a fixed-round Count-Up game always terminates")
        // Each seat threw exactly ROUNDS turns.
        assertEquals(ROUNDS, state.perPlayer[0].turns.size)
        assertEquals(ROUNDS, state.perPlayer[1].turns.size)
        // Winner(s) genuinely hold the maximum total.
        val maxTotal = state.perPlayer.maxOf { it.total }
        state.winnerIndices.forEach { w ->
            assertEquals(maxTotal, state.perPlayer[w].total, "winner must hold the top total")
        }
        assertTrue(state.winnerIndices.isNotEmpty())
    }
}
