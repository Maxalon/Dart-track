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
    /**
     * When true, plays Cut-Throat (American) Cricket: excess marks penalise
     * opponents instead of rewarding the thrower, and the LOWEST score wins.
     * Defaults to false ⇒ standard Cricket behaviour, byte-for-byte unchanged.
     */
    val cutThroat: Boolean = false,
) : GameState {

    /**
     * Score is computed per target. Standard: if you've closed it (>=3 marks)
     * and at least one opponent has not, every additional mark on it counts as
     * (target × marks-past-3) points for YOU. Cut-throat inverts this: those
     * excess points are charged AGAINST you for every target an opponent has
     * closed while you have not — i.e. you receive the points opponents "give
     * away" — and the lowest total wins.
     */
    fun scoreFor(playerIndex: Int): Int =
        if (cutThroat) cutThroatScoreFor(playerIndex) else standardScoreFor(playerIndex)

    private fun standardScoreFor(playerIndex: Int): Int {
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

    /**
     * In cut-throat, points are received from opponents: for each OTHER player
     * who has closed a target on which I am still open, their excess marks on
     * that target × the target value are added to MY score (a penalty).
     */
    private fun cutThroatScoreFor(playerIndex: Int): Int {
        val me = perPlayer[playerIndex]
        var total = 0
        for (target in CRICKET_TARGETS) {
            if (me.isClosed(target)) continue
            perPlayer.forEachIndexed { idx, other ->
                if (idx == playerIndex) return@forEachIndexed
                val theirs = other.cumulativeMarks()[target] ?: 0
                if (theirs <= CRICKET_MARKS_TO_CLOSE) return@forEachIndexed
                total += (theirs - CRICKET_MARKS_TO_CLOSE) * target
            }
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

        // Win condition: all targets closed AND your score beats every
        // opponent's (ties allowed). Standard wants the HIGHEST score, so you
        // must be >= every opponent; cut-throat wants the LOWEST, so you must
        // be <= every opponent.
        val me = updated[currentPlayerIndex]
        val newWinners = if (me.hasClosedAll()) {
            val myScore = newState.scoreFor(currentPlayerIndex)
            val leadsOrTies = updated.indices
                .filter { it != currentPlayerIndex }
                .all {
                    val theirScore = newState.scoreFor(it)
                    if (cutThroat) theirScore >= myScore else theirScore <= myScore
                }
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
        fun new(players: List<GamePlayer>, cutThroat: Boolean = false): CricketState {
            require(players.isNotEmpty()) { "Cricket needs at least one player" }
            return CricketState(
                players = players,
                perPlayer = players.map { CricketPlayerState(it) },
                cutThroat = cutThroat,
            )
        }
    }
}
