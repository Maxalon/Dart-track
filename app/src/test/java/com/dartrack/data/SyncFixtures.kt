package com.dartrack.data

import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BaseballState
import com.dartrack.model.BermudaState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CheckoutTrainerState
import com.dartrack.model.CountUpState
import com.dartrack.model.CricketState
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.GameState
import com.dartrack.model.GolfResult
import com.dartrack.model.GolfState
import com.dartrack.model.GotchaState
import com.dartrack.model.HalfItState
import com.dartrack.model.KillerState
import com.dartrack.model.Player
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State

/** Shared builders for the sync tests. */
internal object SyncFixtures {

    /** A game record around [state]; mode derived from the state. */
    fun record(id: String, state: GameState, createdAt: Long = 1, updatedAt: Long = 2): GameRecord =
        GameRecord(id, state.toMode(), createdAtEpochMs = createdAt, updatedAtEpochMs = updatedAt, state = state)

    /** A finished X01 101 game won by seat 0. */
    fun finishedX01(id: String, seats: List<GamePlayer>, createdAt: Long = 1, updatedAt: Long = 2): GameRecord =
        record(id, X01State.new(seats, startScore = 101, doubleOut = false).applyTurn(101), createdAt, updatedAt)

    /** An X01 game still in progress. */
    fun unfinishedX01(id: String, seats: List<GamePlayer>, updatedAt: Long): GameRecord =
        record(id, X01State.new(seats, startScore = 501).applyTurn(60), createdAt = 1, updatedAt = updatedAt)

    fun seat(p: Player): GamePlayer = GamePlayer(name = p.name, id = p.id)

    fun bot(id: String = "bot:1"): GamePlayer = GamePlayer(name = "CPU · Easy", id = id, isBot = true)

    /** One in-progress game of each of the 14 modes over [seats], ids = mode name. */
    fun everyMode(seats: List<GamePlayer>): List<GameRecord> {
        val states: List<GameState> = listOf(
            X01State.new(seats, startScore = 501, doubleOut = true).applyTurn(60),
            CricketState.new(seats).applyTurn(mapOf(20 to 3)),
            HalfItState.new(seats).applyTurn(15),
            AroundTheClockState.new(seats).applyTurn(2),
            BobsTwentySevenState.new(seats).applyTurn(1),
            ShanghaiState.new(seats).applyTurn(1, 1, 0),
            Catch40State.new(seats).applyTurn(2),
            CountUpState.new(seats).applyTurn(100),
            CheckoutTrainerState.new(seats).applyAttempt(hit = true, darts = 2),
            BaseballState.new(seats).applyTurn(1, 1, 1),
            GolfState.new(seats).applyResult(GolfResult.TRIPLE),
            GotchaState.new(seats, target = 501).applyTurn(140),
            KillerState.new(seats).applyTurn(listOf(0)),
            BermudaState.new(seats).applyTurn(36),
        )
        return states.map { record(it.toMode().name, it) }.also { list ->
            check(list.map { it.mode }.toSet() == GameMode.entries.toSet()) { "fixture must cover every mode" }
        }
    }
}
