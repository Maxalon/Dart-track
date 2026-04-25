package com.dartrack.model

import kotlinx.serialization.Serializable

@Serializable
enum class GameMode { X01, CRICKET, HALF_IT }

@Serializable
data class GamePlayer(val name: String)

@Serializable
sealed interface GameState {
    val players: List<GamePlayer>
    val currentPlayerIndex: Int
    val winnerIndices: List<Int>
    val isFinished: Boolean get() = winnerIndices.isNotEmpty()
}
