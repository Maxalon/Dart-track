package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GameState
import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.CricketState
import com.dartrack.model.HalfItState
import com.dartrack.model.X01State
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persisted record of a (possibly in-progress) game. The polymorphic
 * [state] field uses kotlinx.serialization's sealed-interface support.
 */
@Serializable
data class GameRecord(
    val id: String,
    val mode: GameMode,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val state: GameState,
) {
    val isFinished: Boolean get() = state.isFinished
    val playerNames: List<String> get() = state.players.map { it.name }
    val winnerNames: List<String> get() = state.winnerIndices.map { state.players[it].name }
}

object GameJson {
    val format: Json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
}

fun GameState.toMode(): GameMode = when (this) {
    is X01State -> GameMode.X01
    is CricketState -> GameMode.CRICKET
    is HalfItState -> GameMode.HALF_IT
    is AroundTheClockState -> GameMode.AROUND_CLOCK
    is BobsTwentySevenState -> GameMode.BOBS_27
}
