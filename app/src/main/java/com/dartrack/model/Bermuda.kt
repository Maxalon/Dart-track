package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 12-round Bermuda (a.k.a. Treasure Island). In each round every player throws
 * 3 darts at the round's target and enters the points scored on that target.
 * Score 0? Your running total is halved (rounded down). After all 12 rounds the
 * highest total wins.
 */
@Serializable
sealed interface BermudaTarget {
    val label: String
    @Serializable @SerialName("number") data class Number(val n: Int) : BermudaTarget {
        override val label: String get() = n.toString()
    }
    @Serializable @SerialName("any_double") data object AnyDouble : BermudaTarget {
        override val label: String get() = "Any Double"
    }
    @Serializable @SerialName("any_triple") data object AnyTriple : BermudaTarget {
        override val label: String get() = "Any Triple"
    }
    @Serializable @SerialName("bullseye") data object Bullseye : BermudaTarget {
        override val label: String get() = "Bull"
    }
}

val BERMUDA_ROUNDS: List<BermudaTarget> = listOf(
    BermudaTarget.Number(12),
    BermudaTarget.Number(13),
    BermudaTarget.Number(14),
    BermudaTarget.AnyDouble,
    BermudaTarget.Number(15),
    BermudaTarget.Number(16),
    BermudaTarget.Number(17),
    BermudaTarget.AnyTriple,
    BermudaTarget.Number(18),
    BermudaTarget.Number(19),
    BermudaTarget.Number(20),
    BermudaTarget.Bullseye,
)

@Serializable
data class BermudaRoundEntry(
    val pointsScored: Int,
    /** running total after this round (after halving if zero). */
    val totalAfter: Int,
)

@Serializable
data class BermudaPlayerState(
    val player: GamePlayer,
    val rounds: List<BermudaRoundEntry> = emptyList(),
) {
    val total: Int get() = rounds.lastOrNull()?.totalAfter ?: 0
}

@Serializable
@SerialName("bermuda")
data class BermudaState(
    override val players: List<GamePlayer>,
    val perPlayer: List<BermudaPlayerState>,
    /** index into BERMUDA_ROUNDS for the round currently being played. */
    val currentRound: Int = 0,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    fun applyTurn(pointsScored: Int): BermudaState {
        if (isFinished) return this
        require(pointsScored >= 0) { "points must be >= 0" }
        val me = perPlayer[currentPlayerIndex]
        val running = me.total
        val newTotal = if (pointsScored == 0) running / 2 else running + pointsScored
        val updatedRounds = me.rounds + BermudaRoundEntry(pointsScored, newTotal)
        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(rounds = updatedRounds)
        }

        val nextPlayer = (currentPlayerIndex + 1) % players.size
        val advanceRound = nextPlayer == 0
        val newRound = if (advanceRound) currentRound + 1 else currentRound

        return if (newRound >= BERMUDA_ROUNDS.size) {
            val maxScore = updated.maxOf { it.total }
            val winners = updated.withIndex()
                .filter { it.value.total == maxScore }
                .map { it.index }
            copy(
                perPlayer = updated,
                currentPlayerIndex = nextPlayer,
                currentRound = newRound,
                winnerIndices = winners,
            )
        } else {
            copy(
                perPlayer = updated,
                currentPlayerIndex = nextPlayer,
                currentRound = newRound,
            )
        }
    }

    fun undoLast(): BermudaState {
        // last player to act = currentPlayerIndex - 1 (with round wrap)
        val (targetIdx, prevRound) = if (currentPlayerIndex == 0 && currentRound > 0) {
            (players.size - 1) to (currentRound - 1)
        } else if (currentPlayerIndex == 0 && currentRound == 0) {
            return this
        } else {
            (currentPlayerIndex - 1) to currentRound
        }
        val target = perPlayer[targetIdx]
        if (target.rounds.isEmpty()) return this
        val updated = perPlayer.toMutableList().also {
            it[targetIdx] = target.copy(rounds = target.rounds.dropLast(1))
        }
        return copy(
            perPlayer = updated,
            currentPlayerIndex = targetIdx,
            currentRound = prevRound,
            winnerIndices = emptyList(),
        )
    }

    fun currentTarget(): BermudaTarget? =
        BERMUDA_ROUNDS.getOrNull(currentRound)

    companion object {
        fun new(players: List<GamePlayer>): BermudaState {
            require(players.isNotEmpty()) { "Bermuda needs at least one player" }
            return BermudaState(
                players = players,
                perPlayer = players.map { BermudaPlayerState(it) },
            )
        }
    }
}
