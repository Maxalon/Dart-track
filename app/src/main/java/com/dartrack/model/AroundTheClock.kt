package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Around the Clock. Players race through targets 1..20 in order (no bull).
 * Each player starts aiming at 1. On their turn they enter how many of the
 * next consecutive targets they hit with their 3 darts (0..3); hitting N
 * advances their current target by N. A player finishes when they clear 20
 * (their current target advances past 20). First player(s) to finish win.
 * Fewer darts thrown is better, where darts = turns * 3.
 */
const val AROUND_CLOCK_LAST_TARGET: Int = 20
const val AROUND_CLOCK_MAX_HITS: Int = 3

@Serializable
data class AroundTheClockTurn(
    /** number of consecutive targets cleared this turn, 0..3. */
    val hits: Int,
)

@Serializable
data class AroundTheClockPlayerState(
    val player: GamePlayer,
    val turns: List<AroundTheClockTurn> = emptyList(),
) {
    /** total targets cleared so far across all turns. */
    val cleared: Int get() = turns.sumOf { it.hits }

    /** the target this player is currently aiming at (capped at last + 1). */
    val currentTarget: Int get() = (cleared + 1).coerceAtMost(AROUND_CLOCK_LAST_TARGET + 1)

    /** true once this player has cleared past the final target. */
    val finished: Boolean get() = cleared >= AROUND_CLOCK_LAST_TARGET

    /** darts thrown so far (3 per turn). */
    val darts: Int get() = turns.size * 3
}

@Serializable
@SerialName("around_clock")
data class AroundTheClockState(
    override val players: List<GamePlayer>,
    val perPlayer: List<AroundTheClockPlayerState>,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /** the target [playerIndex] is currently aiming at. */
    fun currentTarget(playerIndex: Int): Int = perPlayer[playerIndex].currentTarget

    fun applyTurn(hits: Int): AroundTheClockState {
        if (isFinished) return this
        require(hits in 0..AROUND_CLOCK_MAX_HITS) {
            "hits must be in 0..$AROUND_CLOCK_MAX_HITS"
        }
        val me = perPlayer[currentPlayerIndex]
        // Don't let a player clear more targets than remain.
        val remaining = AROUND_CLOCK_LAST_TARGET - me.cleared
        val applied = hits.coerceAtMost(remaining.coerceAtLeast(0))
        val updatedTurns = me.turns + AroundTheClockTurn(applied)
        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(turns = updatedTurns)
        }

        // Anyone who has now finished is a winner. First to finish wins; if
        // multiple cross the line on the same turn boundary they tie.
        val finishers = updated.withIndex()
            .filter { it.value.finished }
            .map { it.index }

        val nextPlayer = (currentPlayerIndex + 1) % players.size
        return copy(
            perPlayer = updated,
            currentPlayerIndex = nextPlayer,
            winnerIndices = finishers,
        )
    }

    fun undoLast(): AroundTheClockState {
        // last player to act = currentPlayerIndex - 1 (with wrap).
        val targetIdx = if (currentPlayerIndex == 0) players.size - 1
                        else currentPlayerIndex - 1
        val target = perPlayer[targetIdx]
        if (target.turns.isEmpty()) return this
        val updated = perPlayer.toMutableList().also {
            it[targetIdx] = target.copy(turns = target.turns.dropLast(1))
        }
        return copy(
            perPlayer = updated,
            currentPlayerIndex = targetIdx,
            winnerIndices = emptyList(),
        )
    }

    companion object {
        fun new(players: List<GamePlayer>): AroundTheClockState {
            require(players.isNotEmpty()) { "Around the Clock needs at least one player" }
            return AroundTheClockState(
                players = players,
                perPlayer = players.map { AroundTheClockPlayerState(it) },
            )
        }
    }
}
