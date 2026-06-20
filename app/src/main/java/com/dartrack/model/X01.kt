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
    // ---- Match-play (legs + sets) fields. All defaulted for backward
    // compatibility: a game persisted before this feature deserializes as a
    // single-set, single-leg match (setsToWin = 1, legsToWin = 1, no history)
    // with identical behavior to before.
    /**
     * First player to win this many legs wins the CURRENT SET. When
     * [setsToWin] == 1 there is no sets layer, so this is equivalent to the
     * legacy meaning "legs needed to win the match".
     */
    val legsToWin: Int = 1,
    /**
     * Per-player legs won in the CURRENT set so far. Resets to zeros when a set
     * is won. Empty is treated as all-zeros.
     */
    val legWins: List<Int> = emptyList(),
    /** Who throws first in the CURRENT leg; rotates each leg. */
    val startingPlayerIndex: Int = 0,
    /**
     * Finished legs in order, for full per-leg history. Accumulates ALL legs
     * across the whole match (it is NOT reset when a set is won), so stats see
     * every leg of every set.
     */
    val completedLegs: List<X01CompletedLeg> = emptyList(),
    /**
     * First player to win this many sets wins the MATCH. 1 = no sets layer
     * (current legs-only behavior).
     */
    val setsToWin: Int = 1,
    /** Per-player sets won so far. Empty is treated as all-zeros. */
    val setWins: List<Int> = emptyList(),
) : GameState {

    fun currentPlayerScore(): Int =
        perPlayer[currentPlayerIndex].turns.lastOrNull()?.scoreAfter ?: startScore

    fun scoreFor(playerIndex: Int): Int =
        perPlayer[playerIndex].turns.lastOrNull()?.scoreAfter ?: startScore

    /** Legs won by [playerIndex] in the CURRENT set (current leg not counted). */
    fun legsWonBy(playerIndex: Int): Int = legWins.getOrElse(playerIndex) { 0 }

    /** Sets won by [playerIndex] so far (current set not yet counted). */
    fun setsWonBy(playerIndex: Int): Int = setWins.getOrElse(playerIndex) { 0 }

    /** True when this is a multi-leg or multi-set match (UI shows scoreboard). */
    val isMatch: Boolean get() = legsToWin > 1 || setsToWin > 1

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

        // The current player has just checked out and won the leg (in the set).
        val winnerIdx = currentPlayerIndex
        val baseLegWins = normalize(legWins)
        val newLegWins = baseLegWins.toMutableList().also { it[winnerIdx] = it[winnerIdx] + 1 }
        val completedLeg = X01CompletedLeg(perPlayer = updatedPlayers, winnerIndex = winnerIdx)
        val newCompleted = completedLegs + completedLeg
        val zeros = List(players.size) { 0 }

        if (newLegWins[winnerIdx] < legsToWin) {
            // NEXT LEG within the current set: reset every player's score, rotate
            // who throws first, keep the match in progress and the set ongoing.
            val nextStarter = (startingPlayerIndex + 1) % players.size
            return copy(
                perPlayer = players.map { X01PlayerState(it) },
                currentPlayerIndex = nextStarter,
                winnerIndices = winnerIndices,
                legWins = newLegWins,
                startingPlayerIndex = nextStarter,
                completedLegs = newCompleted,
            )
        }

        // SET WON: this leg win completed the set. Increment sets, reset legs.
        val baseSetWins = normalize(setWins)
        val newSetWins = baseSetWins.toMutableList().also { it[winnerIdx] = it[winnerIdx] + 1 }

        return if (newSetWins[winnerIdx] >= setsToWin) {
            // MATCH OVER: record the match winner; keep current player on them.
            // The deciding leg lives in BOTH completedLegs and the live perPlayer
            // so undo + allLegStatesFor dedup keep working.
            copy(
                perPlayer = updatedPlayers,
                currentPlayerIndex = winnerIdx,
                winnerIndices = winnerIndices + winnerIdx,
                legWins = newLegWins,
                completedLegs = newCompleted,
                setWins = newSetWins,
            )
        } else {
            // NEXT SET: reset legs to zero, start a fresh leg, rotate starter.
            val nextStarter = (startingPlayerIndex + 1) % players.size
            copy(
                perPlayer = players.map { X01PlayerState(it) },
                currentPlayerIndex = nextStarter,
                winnerIndices = winnerIndices,
                legWins = zeros,
                startingPlayerIndex = nextStarter,
                completedLegs = newCompleted,
                setWins = newSetWins,
            )
        }
    }

    /** Normalize a per-player counter list to player count, padding with zeros. */
    private fun normalize(counts: List<Int>): List<Int> =
        if (counts.size == players.size) counts
        else List(players.size) { counts.getOrElse(it) { 0 } }

    /** Undo the most recently confirmed turn, crossing leg and set boundaries. */
    fun undoLast(): X01State {
        // Case A — Match-over: the last action was the match-deciding checkout.
        // The winning leg is both the live [perPlayer] (board still shows it) AND
        // the last entry in [completedLegs]. Roll the match back into that leg as
        // an in-progress leg by dropping the winning turn and popping the snapshot.
        // Standings (sets + current-set legs) are recomputed from the remaining
        // completed legs, which correctly reverses a set-deciding checkout too:
        // the set the player just won is reopened with its prior leg tally.
        if (winnerIndices.isNotEmpty() && completedLegs.isNotEmpty()) {
            val winnerIdx = completedLegs.last().winnerIndex
            val target = perPlayer[winnerIdx]
            if (target.turns.isNotEmpty()) {
                val updated = perPlayer.toMutableList().also {
                    it[winnerIdx] = target.copy(turns = target.turns.dropLast(1))
                }
                val (sets, legs) = standingsFrom(completedLegs.dropLast(1))
                return copy(
                    perPlayer = updated,
                    currentPlayerIndex = winnerIdx,
                    winnerIndices = winnerIndices.filter { it != winnerIdx },
                    legWins = legs,
                    completedLegs = completedLegs.dropLast(1),
                    setWins = sets,
                )
            }
        }

        // Case B — Leg/set boundary: the current leg has not been started (no
        // turns by anyone) but at least one leg is complete. The last confirmed
        // action was the winning checkout of the previous leg (which may also have
        // closed a set). Restore that leg in full and put the cursor back on the
        // player who threw the winning dart. Recomputing standings from the
        // remaining completed legs reverses both a plain leg rollover (legWins
        // decremented) and a set rollover (the just-closed set reopened with its
        // pre-reset leg tally, that set's win removed).
        if (completedLegs.isNotEmpty() && perPlayer.all { it.turns.isEmpty() }) {
            val lastLeg = completedLegs.last()
            val winnerIdx = lastLeg.winnerIndex
            // The current (empty) leg's starter was rotated forward when the leg
            // (or set) rolled over; step it back to the restored leg's starter.
            val restoredStarter = (startingPlayerIndex - 1 + players.size) % players.size
            val (sets, legs) = standingsFrom(completedLegs.dropLast(1))
            return copy(
                perPlayer = lastLeg.perPlayer,
                currentPlayerIndex = winnerIdx,
                winnerIndices = winnerIndices.filter { it != winnerIdx },
                legWins = legs,
                startingPlayerIndex = restoredStarter,
                completedLegs = completedLegs.dropLast(1),
                setWins = sets,
            )
        }

        // Case C — Normal turn within a leg.
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

    /**
     * Set/leg standings derived purely from a list of completed legs, by
     * replaying the same accumulation rule [applyTurn] uses: each leg increments
     * the current set's legWins for its winner; when a player reaches [legsToWin]
     * legs the set closes (their setWins increments and legWins reset to zero).
     *
     * This is the inverse used by [undoLast] at set boundaries: when a set is won
     * the live [legWins] are reset to zero, discarding the just-finished set's
     * per-player leg tally, so to step back we must recompute from history.
     *
     * Returns (setWins, legWinsInCurrentSet). The final (in-progress) set's legs
     * are whatever remains after the last completed set closed.
     */
    private fun standingsFrom(legs: List<X01CompletedLeg>): Pair<List<Int>, List<Int>> {
        val sets = MutableList(players.size) { 0 }
        var curLegs = MutableList(players.size) { 0 }
        for (leg in legs) {
            val w = leg.winnerIndex
            curLegs[w] = curLegs[w] + 1
            if (curLegs[w] >= legsToWin) {
                sets[w] = sets[w] + 1
                curLegs = MutableList(players.size) { 0 }
            }
        }
        return sets to curLegs
    }

    companion object {
        fun new(
            players: List<GamePlayer>,
            startScore: Int = 501,
            doubleOut: Boolean = true,
            legsToWin: Int = 1,
            setsToWin: Int = 1,
        ): X01State = X01State(
            players = players,
            perPlayer = players.map { X01PlayerState(it) },
            startScore = startScore,
            doubleOut = doubleOut,
            legsToWin = legsToWin.coerceAtLeast(1),
            legWins = List(players.size) { 0 },
            startingPlayerIndex = 0,
            completedLegs = emptyList(),
            setsToWin = setsToWin.coerceAtLeast(1),
            setWins = List(players.size) { 0 },
        )

        val SUPPORTED_STARTS = listOf(101, 201, 301, 401, 501, 701, 901)
        val SUPPORTED_LEGS = listOf(1, 3, 5, 7)
        val SUPPORTED_SETS = listOf(1, 3, 5)
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
