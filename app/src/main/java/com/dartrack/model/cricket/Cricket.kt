package com.dartrack.model.cricket

import com.dartrack.model.GamePlayer
import com.dartrack.model.GameState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The seven Cricket targets in the order shown on the scoreboard. */
val CRICKET_TARGETS = listOf(20, 19, 18, 17, 16, 15, 25)

const val CRICKET_MARKS_TO_CLOSE = 3

/**
 * Marks added per target on a single 3-dart turn. A "mark" is one segment hit
 * (single = 1, double = 2, triple = 3; bull = 1, double-bull = 2). Maximum
 * 9 marks total per turn (3 darts × triple).
 */
@Serializable
data class CricketTurn(
    /** marksByTarget[target] = marks added this turn (0..3 per dart, max 9). */
    val marksByTarget: Map<Int, Int> = emptyMap(),
) {
    val totalMarks: Int get() = marksByTarget.values.sum()
}

@Serializable
data class CricketPlayerState(
    val player: GamePlayer,
    val turns: List<CricketTurn> = emptyList(),
) {
    /** Cumulative marks per target (uncapped — marks past 3 are scoring hits). */
    fun cumulativeMarks(): Map<Int, Int> = buildMap {
        CRICKET_TARGETS.forEach { put(it, 0) }
        for (t in turns) {
            for ((tgt, m) in t.marksByTarget) {
                put(tgt, (get(tgt) ?: 0) + m)
            }
        }
    }

    fun isClosed(target: Int): Boolean =
        (cumulativeMarks()[target] ?: 0) >= CRICKET_MARKS_TO_CLOSE

    fun hasClosedAll(): Boolean = CRICKET_TARGETS.all { isClosed(it) }
}

@Serializable
@SerialName("cricket")
data class CricketState(
    override val players: List<GamePlayer>,
    val perPlayer: List<CricketPlayerState>,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /**
     * Score is computed: for each target, if you've closed it (>=3 marks) and
     * at least one opponent has not, every additional mark on it counts as
     * (target × marks-past-3) points.
     */
    fun scoreFor(playerIndex: Int): Int {
        val me = perPlayer[playerIndex]
        val cum = me.cumulativeMarks()
        var total = 0
        for (target in CRICKET_TARGETS) {
            val mine = cum[target] ?: 0
            if (mine <= CRICKET_MARKS_TO_CLOSE) continue
            val opponentClosed = perPlayer
                .filterIndexed { idx, _ -> idx != playerIndex }
                .all { it.isClosed(target) }
            if (opponentClosed) continue
            total += (mine - CRICKET_MARKS_TO_CLOSE) * target
        }
        return total
    }

    fun applyTurn(marksByTarget: Map<Int, Int>): CricketState {
        if (isFinished) return this
        require(marksByTarget.values.sum() in 0..9) { "max 9 marks (3 darts × triple)" }
        require(marksByTarget.keys.all { it in CRICKET_TARGETS }) { "invalid target" }

        val updated = perPlayer.toMutableList().also {
            val cur = it[currentPlayerIndex]
            it[currentPlayerIndex] = cur.copy(
                turns = cur.turns + CricketTurn(marksByTarget.filterValues { v -> v > 0 })
            )
        }
        val newState = copy(perPlayer = updated)

        // Win condition: all targets closed AND score >= every opponent's score.
        val me = updated[currentPlayerIndex]
        val newWinners = if (me.hasClosedAll()) {
            val myScore = newState.scoreFor(currentPlayerIndex)
            val leadsOrTies = updated.indices
                .filter { it != currentPlayerIndex }
                .all { newState.scoreFor(it) <= myScore }
            if (leadsOrTies) winnerIndices + currentPlayerIndex else winnerIndices
        } else winnerIndices

        val nextPlayer = if (newWinners.isNotEmpty()) currentPlayerIndex
                         else (currentPlayerIndex + 1) % players.size
        return newState.copy(currentPlayerIndex = nextPlayer, winnerIndices = newWinners)
    }

    fun undoLast(): CricketState {
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
        fun new(players: List<GamePlayer>): CricketState = CricketState(
            players = players,
            perPlayer = players.map { CricketPlayerState(it) },
        )
    }
}
