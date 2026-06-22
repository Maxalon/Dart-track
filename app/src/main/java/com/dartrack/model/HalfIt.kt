package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 9-round Half-It. In each round every player throws 3 darts at the round's
 * target and enters the points scored on that target. Score 0? Your running
 * total is halved (rounded down).
 */
@Serializable
sealed interface HalfItTarget {
    val label: String
    @Serializable @SerialName("number") data class Number(val n: Int) : HalfItTarget {
        override val label: String get() = n.toString()
    }
    @Serializable @SerialName("any_double") data object AnyDouble : HalfItTarget {
        override val label: String get() = "Any Double"
    }
    @Serializable @SerialName("any_triple") data object AnyTriple : HalfItTarget {
        override val label: String get() = "Any Triple"
    }
    @Serializable @SerialName("bullseye") data object Bullseye : HalfItTarget {
        override val label: String get() = "Bullseye"
    }
}

val HALF_IT_ROUNDS: List<HalfItTarget> = listOf(
    HalfItTarget.Number(15),
    HalfItTarget.Number(16),
    HalfItTarget.AnyDouble,
    HalfItTarget.Number(17),
    HalfItTarget.Number(18),
    HalfItTarget.AnyTriple,
    HalfItTarget.Number(19),
    HalfItTarget.Number(20),
    HalfItTarget.Bullseye,
)

@Serializable
data class HalfItRoundEntry(
    val pointsScored: Int,
    /** running total after this round (after halving if zero). */
    val totalAfter: Int,
)

@Serializable
data class HalfItPlayerState(
    val player: GamePlayer,
    val rounds: List<HalfItRoundEntry> = emptyList(),
) {
    val total: Int get() = rounds.lastOrNull()?.totalAfter ?: 0
}

@Serializable
@SerialName("halfit")
data class HalfItState(
    override val players: List<GamePlayer>,
    val perPlayer: List<HalfItPlayerState>,
    /** index into HALF_IT_ROUNDS for the round currently being played. */
    val currentRound: Int = 0,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    fun applyTurn(pointsScored: Int): HalfItState {
        if (isFinished) return this
        require(pointsScored >= 0) { "points must be >= 0" }
        val me = perPlayer[currentPlayerIndex]
        val running = me.total
        val newTotal = if (pointsScored == 0) running / 2 else running + pointsScored
        val updatedRounds = me.rounds + HalfItRoundEntry(pointsScored, newTotal)
        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(rounds = updatedRounds)
        }

        val nextPlayer = (currentPlayerIndex + 1) % players.size
        val advanceRound = nextPlayer == 0
        val newRound = if (advanceRound) currentRound + 1 else currentRound

        return if (newRound >= HALF_IT_ROUNDS.size) {
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

    fun undoLast(): HalfItState {
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

    fun currentTarget(): HalfItTarget? =
        HALF_IT_ROUNDS.getOrNull(currentRound)

    companion object {
        fun new(players: List<GamePlayer>): HalfItState {
            require(players.isNotEmpty()) { "Half-It needs at least one player" }
            return HalfItState(
                players = players,
                perPlayer = players.map { HalfItPlayerState(it) },
            )
        }
    }
}
