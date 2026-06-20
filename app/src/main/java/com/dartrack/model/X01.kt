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

/**
 * A finished leg, retained for full per-leg history / replay / stats.
 * [perPlayer] is the snapshot of every player's [X01PlayerState] at the
 * moment the leg ended; [winnerIndex] is the player who checked out.
 */
@Serializable
data class X01CompletedLeg(
    val perPlayer: List<X01PlayerState>,
    val winnerIndex: Int,
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
    // ---- Match-play (legs) fields. All defaulted for backward compatibility:
    // a game persisted before this feature deserializes as a single-leg match
    // (legsToWin = 1, no leg history) with identical behavior to before.
    /** First player to win this many legs wins the match. 1 = single leg. */
    val legsToWin: Int = 1,
    /** Per-player legs won so far. Empty is treated as all-zeros. */
    val legWins: List<Int> = emptyList(),
    /** Who throws first in the CURRENT leg; rotates each leg. */
    val startingPlayerIndex: Int = 0,
    /** Finished legs in order, for full per-leg history. */
    val completedLegs: List<X01CompletedLeg> = emptyList(),
) : GameState {

    fun currentPlayerScore(): Int =
        perPlayer[currentPlayerIndex].turns.lastOrNull()?.scoreAfter ?: startScore

    fun scoreFor(playerIndex: Int): Int =
        perPlayer[playerIndex].turns.lastOrNull()?.scoreAfter ?: startScore

    /** Legs won by [playerIndex] so far (current leg not yet counted). */
    fun legsWonBy(playerIndex: Int): Int = legWins.getOrElse(playerIndex) { 0 }

    /** True when this is a multi-leg match (UI shows the leg scoreboard). */
    val isMatch: Boolean get() = legsToWin > 1

    /**
     * Every [X01PlayerState] this player has had across ALL legs: each completed
     * leg's snapshot, plus the current in-progress leg. Used by stats so
     * aggregates sum across legs rather than only the current leg.
     *
     * When the match has ended, [applyTurn] stores the deciding (winning) leg in
     * BOTH [completedLegs] (as the last entry) and the live [perPlayer] — that
     * duplication is what lets [undoLast] reopen the final leg. So once the match
     * is over we must NOT also append [perPlayer] here, or every metric for the
     * final leg would be counted twice. Legacy/finished games loaded from old
     * JSON have an empty [completedLegs], so they still count via [perPlayer].
     */
    fun allLegStatesFor(playerIndex: Int): List<X01PlayerState> {
        val completed = completedLegs.map { it.perPlayer[playerIndex] }
        val finalLegDuplicated = winnerIndices.isNotEmpty() &&
            completedLegs.isNotEmpty() &&
            completedLegs.last().perPlayer == perPlayer
        return if (finalLegDuplicated) completed else completed + perPlayer[playerIndex]
    }

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

        if (!finished) {
            // Normal (non-finishing) turn: rotate to next player, unchanged.
            return copy(
                perPlayer = updatedPlayers,
                currentPlayerIndex = (currentPlayerIndex + 1) % players.size,
            )
        }

        // The current player has just checked out and won the leg.
        val winnerIdx = currentPlayerIndex
        val baseWins = if (legWins.size == players.size) legWins
                       else List(players.size) { legWins.getOrElse(it) { 0 } }
        val newWins = baseWins.toMutableList().also { it[winnerIdx] = it[winnerIdx] + 1 }
        val completedLeg = X01CompletedLeg(perPlayer = updatedPlayers, winnerIndex = winnerIdx)
        val newCompleted = completedLegs + completedLeg

        return if (newWins[winnerIdx] >= legsToWin) {
            // MATCH OVER: record the match winner; keep current player on them.
            copy(
                perPlayer = updatedPlayers,
                currentPlayerIndex = winnerIdx,
                winnerIndices = winnerIndices + winnerIdx,
                legWins = newWins,
                completedLegs = newCompleted,
            )
        } else {
            // START THE NEXT LEG: reset every player's score, rotate who throws
            // first, and keep the match in progress (winnerIndices stays empty).
            val nextStarter = (startingPlayerIndex + 1) % players.size
            copy(
                perPlayer = players.map { X01PlayerState(it) },
                currentPlayerIndex = nextStarter,
                winnerIndices = winnerIndices,
                legWins = newWins,
                startingPlayerIndex = nextStarter,
                completedLegs = newCompleted,
            )
        }
    }

    /** Undo the most recently confirmed turn, crossing leg boundaries. */
    fun undoLast(): X01State {
        // Match-over case: the last action was the match-deciding checkout. The
        // winning leg is both the live [perPlayer] (board still shows it) AND the
        // last entry in [completedLegs]. Roll the match back into that leg as an
        // in-progress leg by dropping the winning turn and popping the snapshot.
        if (winnerIndices.isNotEmpty() && completedLegs.isNotEmpty()) {
            val winnerIdx = completedLegs.last().winnerIndex
            val target = perPlayer[winnerIdx]
            if (target.turns.isNotEmpty()) {
                val updated = perPlayer.toMutableList().also {
                    it[winnerIdx] = target.copy(turns = target.turns.dropLast(1))
                }
                return copy(
                    perPlayer = updated,
                    currentPlayerIndex = winnerIdx,
                    winnerIndices = winnerIndices.filter { it != winnerIdx },
                    legWins = decrementLeg(winnerIdx),
                    completedLegs = completedLegs.dropLast(1),
                )
            }
        }

        // Leg-boundary case: the current leg has not been started (no turns by
        // anyone) but at least one leg is complete. The last confirmed action
        // was the winning checkout of the previous leg, so restore that leg in
        // full and put the cursor back on the player who threw the winning dart.
        if (completedLegs.isNotEmpty() && perPlayer.all { it.turns.isEmpty() }) {
            val lastLeg = completedLegs.last()
            val winnerIdx = lastLeg.winnerIndex
            // The current (empty) leg's starter was rotated forward when the leg
            // rolled over; step it back to the restored leg's starter.
            val restoredStarter = (startingPlayerIndex - 1 + players.size) % players.size
            return copy(
                perPlayer = lastLeg.perPlayer,
                currentPlayerIndex = winnerIdx,
                winnerIndices = winnerIndices.filter { it != winnerIdx },
                legWins = decrementLeg(winnerIdx),
                startingPlayerIndex = restoredStarter,
                completedLegs = completedLegs.dropLast(1),
            )
        }

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

    /** Return [legWins] (normalized to player count) with [idx] decremented. */
    private fun decrementLeg(idx: Int): List<Int> =
        (if (legWins.size == players.size) legWins
         else List(players.size) { legWins.getOrElse(it) { 0 } })
            .toMutableList()
            .also { it[idx] = (it[idx] - 1).coerceAtLeast(0) }

    companion object {
        fun new(
            players: List<GamePlayer>,
            startScore: Int = 501,
            doubleOut: Boolean = true,
            legsToWin: Int = 1,
        ): X01State = X01State(
            players = players,
            perPlayer = players.map { X01PlayerState(it) },
            startScore = startScore,
            doubleOut = doubleOut,
            legsToWin = legsToWin.coerceAtLeast(1),
            legWins = List(players.size) { 0 },
            startingPlayerIndex = 0,
            completedLegs = emptyList(),
        )

        val SUPPORTED_STARTS = listOf(101, 201, 301, 401, 501, 701, 901)
        val SUPPORTED_LEGS = listOf(1, 3, 5, 7)
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

    // ---- Multi-leg aggregates. These operate over every leg a player has
    // played (completed legs + the current leg) so a multi-leg match reports the
    // same figures it would if each leg were a separate single-leg game.

    /** Total points scored across all the player's legs. */
    fun pointsScored(legs: List<X01PlayerState>, startScore: Int): Int =
        legs.sumOf { pointsScored(it, startScore) }

    /** Three-dart average over all darts thrown across all legs. */
    fun threeDartAverage(legs: List<X01PlayerState>, startScore: Int): Double {
        val darts = legs.sumOf { it.turns.size } * 3
        if (darts == 0) return 0.0
        return pointsScored(legs, startScore).toDouble() * 3.0 / darts
    }

    /** Highest non-bust turn across all legs. */
    fun highestTurn(legs: List<X01PlayerState>): Int =
        legs.maxOfOrNull { highestTurn(it) } ?: 0

    /** Highest finishing checkout across all legs (null if none finished). */
    fun highestCheckout(legs: List<X01PlayerState>): Int? =
        legs.mapNotNull { checkout(it) }.maxOrNull()
}
