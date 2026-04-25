package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One confirmed turn in an X01 game. The user enters a single 0-180 score
 * for the three darts; we don't track individual darts.
 *
 * [bust] is true if the entered score would have taken the player below 0
 * (or below 1 with double-out enabled, or ended on a non-double with double-out)
 * and was therefore discarded. [scoreBefore] is the player's score going in.
 */
@Serializable
data class X01Turn(
    val scoreBefore: Int,
    val entered: Int,
    val bust: Boolean,
    val finished: Boolean = false,
) {
    val applied: Int get() = if (bust) 0 else entered
    val scoreAfter: Int get() = scoreBefore - applied
}

@Serializable
data class X01PlayerState(
    val player: GamePlayer,
    val turns: List<X01Turn> = emptyList(),
)

@Serializable
@SerialName("x01")
data class X01State(
    override val players: List<GamePlayer>,
    val perPlayer: List<X01PlayerState>,
    val startScore: Int = 501,
    val doubleOut: Boolean = true,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    fun currentPlayerScore(): Int =
        perPlayer[currentPlayerIndex].turns.lastOrNull()?.scoreAfter ?: startScore

    fun scoreFor(playerIndex: Int): Int =
        perPlayer[playerIndex].turns.lastOrNull()?.scoreAfter ?: startScore

    /**
     * Apply a 3-dart total entered on the keypad. Returns the new state and
     * whether the turn was a bust or a win.
     *
     * Bust rules:
     *  - score below 0: bust
     *  - if doubleOut: score == 1 is a bust (cannot finish), score that
     *    finishes (==current) requires the user to flag it via [finishedOnDouble]
     *    (we don't know what was hit; if doubleOut is on and the player claims
     *    a finish, we trust them — see UI). For automatic bust detection we
     *    only flag below-zero/below-one busts.
     *
     * If the turn brings the score to exactly 0, it's a finish (winner).
     */
    fun applyTurn(entered: Int, finishedOnDouble: Boolean = !doubleOut): X01State {
        require(entered in 0..180) { "3-dart total must be 0..180" }
        if (isFinished) return this
        val before = currentPlayerScore()
        val after = before - entered

        val bust = when {
            after < 0 -> true
            after == 0 && doubleOut && !finishedOnDouble -> true
            after == 1 && doubleOut -> true
            else -> false
        }
        val finished = !bust && after == 0

        val newTurn = X01Turn(
            scoreBefore = before,
            entered = entered,
            bust = bust,
            finished = finished,
        )
        val updatedPlayers = perPlayer.toMutableList().also {
            val cur = it[currentPlayerIndex]
            it[currentPlayerIndex] = cur.copy(turns = cur.turns + newTurn)
        }

        val newWinners = if (finished) winnerIndices + currentPlayerIndex else winnerIndices
        val nextPlayer = if (finished) currentPlayerIndex
                         else (currentPlayerIndex + 1) % players.size
        return copy(
            perPlayer = updatedPlayers,
            currentPlayerIndex = nextPlayer,
            winnerIndices = newWinners,
        )
    }

    /** Undo the most recently confirmed turn. */
    fun undoLast(): X01State {
        val targetIdx = if (winnerIndices.isNotEmpty()) winnerIndices.last()
                        else (currentPlayerIndex - 1 + players.size) % players.size
        val target = perPlayer[targetIdx]
        if (target.turns.isEmpty()) return this
        val updated = perPlayer.toMutableList().also {
            it[targetIdx] = target.copy(turns = target.turns.dropLast(1))
        }
        return copy(
            perPlayer = updated,
            currentPlayerIndex = targetIdx,
            winnerIndices = winnerIndices.filter { it != targetIdx },
        )
    }

    companion object {
        fun new(
            players: List<GamePlayer>,
            startScore: Int = 501,
            doubleOut: Boolean = true,
        ): X01State = X01State(
            players = players,
            perPlayer = players.map { X01PlayerState(it) },
            startScore = startScore,
            doubleOut = doubleOut,
        )

        val SUPPORTED_STARTS = listOf(101, 201, 301, 401, 501, 701, 901)
    }
}

object X01Stats {
    /** Total points scored against the start. */
    fun pointsScored(player: X01PlayerState, startScore: Int): Int {
        val last = player.turns.lastOrNull()?.scoreAfter ?: startScore
        return startScore - last
    }

    /**
     * Three-dart average. For a busted turn we still count 3 darts thrown but
     * 0 points scored — matches conventional X01 stats.
     */
    fun threeDartAverage(player: X01PlayerState, startScore: Int): Double {
        val darts = player.turns.size * 3
        if (darts == 0) return 0.0
        return pointsScored(player, startScore).toDouble() * 3.0 / darts
    }

    fun highestTurn(player: X01PlayerState): Int =
        player.turns.filterNot { it.bust }.maxOfOrNull { it.entered } ?: 0

    fun checkout(player: X01PlayerState): Int? =
        player.turns.lastOrNull()?.takeIf { it.finished }?.entered
}
