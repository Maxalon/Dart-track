package com.dartrack.model

import kotlinx.serialization.Serializable

@Serializable
enum class GameMode { X01, CRICKET, HALF_IT, AROUND_CLOCK, BOBS_27, SHANGHAI, CATCH_40, COUNT_UP, CHECKOUT_TRAINER }

@Serializable
data class GamePlayer(val name: String, val id: String = "")

@Serializable
sealed interface GameState {
    val players: List<GamePlayer>
    val currentPlayerIndex: Int
    val winnerIndices: List<Int>
    val isFinished: Boolean get() = winnerIndices.isNotEmpty()
}
