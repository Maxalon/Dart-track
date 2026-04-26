package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The seven Cricket targets in the order shown on the scoreboard. */
val CRICKET_TARGETS = listOf(20, 19, 18, 17, 16, 15, 25)

const val CRICKET_MARKS_TO_CLOSE = 3

/**
 * Marks added per target on a single 3-dart turn. A "mark" is one segment hit
 * (single = 1, double = 2, triple = 3; bull = 1, double-bull = 2). Maximum
 * 9 marks total per turn (3 darts × triple).
 *
 * [pointsEarned] is captured at turn time so points stay permanent — once
 * earned, they don't disappear later when an opponent closes the target.
 */
@Serializable
data class CricketTurn(
    val marksByTarget: Map<Int, Int> = emptyMap(),
    val pointsEarned: Int = 0,
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

    /** Sum of points actually earned across all turns. Permanent. */
    val score: Int get() = turns.sumOf { it.pointsEarned }
}

@Serializable
@SerialName("cricket")
data class CricketState(
    override val players: List<GamePlayer>,
    val perPlayer: List<CricketPlayerState>,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    fun scoreFor(playerIndex: Int): Int = perPlayer[playerIndex].score

    fun applyTurn(marksByTarget: Map<Int, Int>): CricketState {
        if (isFinished) return this
        require(marksByTarget.values.sum() in 0..9) { "max 9 marks (3 darts × triple)" }
        require(marksByTarget.keys.all { it in CRICKET_TARGETS }) { "invalid target" }

        val me = perPlayer[currentPlayerIndex]
        val cumBefore = me.cumulativeMarks()
        var pointsEarned = 0
        for ((target, marksThisTurn) in marksByTarget) {
            if (marksThisTurn <= 0) continue
            val before = cumBefore[target] ?: 0
            val after = before + marksThisTurn
            val scoringMarks =
                (after - CRICKET_MARKS_TO_CLOSE).coerceAtLeast(0) -
                (before - CRICKET_MARKS_TO_CLOSE).coerceAtLeast(0)
            if (scoringMarks <= 0) continue
            // Points score only if at least one opponent has not yet closed
            // this target at the moment of the turn.
            val anyOpponentOpen = perPlayer
                .filterIndexed { idx, _ -> idx != currentPlayerIndex }
                .any { !it.isClosed(target) }
            if (anyOpponentOpen) pointsEarned += scoringMarks * target
        }

        val turn = CricketTurn(
            marksByTarget = marksByTarget.filterValues { v -> v > 0 },
            pointsEarned = pointsEarned,
        )
        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(turns = me.turns + turn)
        }
        val newState = copy(perPlayer = updated)

        // Win: all targets closed AND score ≥ every opponent's score.
        val newMe = updated[currentPlayerIndex]
        val newWinners = if (newMe.hasClosedAll()) {
            val myScore = newMe.score
            val leadsOrTies = updated.indices
                .filter { it != currentPlayerIndex }
                .all { updated[it].score <= myScore }
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
