package com.dartrack.model

import com.dartrack.model.bot.BotLevel
import kotlinx.serialization.Serializable

@Serializable
enum class GameMode { X01, CRICKET, HALF_IT, AROUND_CLOCK, BOBS_27, SHANGHAI, CATCH_40, COUNT_UP, CHECKOUT_TRAINER, BASEBALL, GOLF, GOTCHA }

/**
 * One seat in a game. Usually backed by a registered player ([id] is their
 * stable registry UUID). A seat may instead be a CPU opponent — [isBot] flags
 * it and [botLevel] is its difficulty. Both default off so games persisted
 * before the CPU feature decode unchanged (a legacy seat has no `isBot`/
 * `botLevel` keys and reads back as a human seat).
 */
@Serializable
data class GamePlayer(
    val name: String,
    val id: String = "",
    val isBot: Boolean = false,
    val botLevel: BotLevel? = null,
)

@Serializable
sealed interface GameState {
    val players: List<GamePlayer>
    val currentPlayerIndex: Int
    val winnerIndices: List<Int>
    val isFinished: Boolean get() = winnerIndices.isNotEmpty()
}
