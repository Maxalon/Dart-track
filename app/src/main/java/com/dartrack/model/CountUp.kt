package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Count-Up — a high-total scoring practice game for 1..4 players.
 *
 * The game runs for a fixed [ROUNDS] rounds. On a player's turn they enter a
 * single 3-dart TOTAL in 0..180 (the same entry style as X01); that total is
 * added to their running score. Turns are lockstep (player 0, 1, …) and a round
 * completes once every player has thrown once.
 *
 * After all [ROUNDS] rounds the highest cumulative total wins (ties allowed ->
 * all recorded in winnerIndices). Darts thrown = turns * 3.
 */
const val ROUNDS: Int = 8

@Serializable
data class CountUpPlayerState(
    val player: GamePlayer,
    /** the 3-dart totals entered so far, one per completed turn (each 0..180). */
    val turns: List<Int> = emptyList(),
    val total: Int = 0,
) {
    /** darts thrown so far (3 per turn). */
    val darts: Int get() = turns.size * 3
}

@Serializable
@SerialName("count_up")
data class CountUpState(
    override val players: List<GamePlayer>,
    val perPlayer: List<CountUpPlayerState>,
    /** how many rounds the game runs for (fixed at [ROUNDS]). */
    val rounds: Int = ROUNDS,
    /** index of the round currently being played (0-based, 0..rounds-1). */
    val currentRound: Int = 0,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /** The 1-based round number [playerIndex] is currently on (1..rounds). */
    fun currentRoundNumber(playerIndex: Int): Int =
        (currentRound + 1).coerceAtMost(rounds)

    fun applyTurn(total: Int): CountUpState {
        if (isFinished) return this
        require(total in 0..180) { "total must be in 0..180" }
        val me = perPlayer[currentPlayerIndex]
        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(
                turns = me.turns + total,
                total = me.total + total,
            )
        }
        return advanceFrom(updated, currentPlayerIndex)
    }

    /**
     * Advance the lockstep turn from [fromIndex]. Once the last player of the
     * final round has thrown, the game ends and the highest total(s) win.
     */
    private fun advanceFrom(
        updated: List<CountUpPlayerState>,
        fromIndex: Int,
    ): CountUpState {
        val size = players.size
        val nextInRound = fromIndex + 1
        if (nextInRound < size) {
            // Still players to throw this round.
            return copy(
                perPlayer = updated,
                currentPlayerIndex = nextInRound,
                currentRound = currentRound,
            )
        }

        // Round complete (last player just threw).
        val newRound = currentRound + 1
        if (newRound >= rounds) {
            // Final round done -> finish with highest total(s).
            return copy(
                perPlayer = updated,
                currentPlayerIndex = 0,
                currentRound = newRound,
                winnerIndices = winnersOf(updated),
            )
        }

        // Next round: first player throws.
        return copy(
            perPlayer = updated,
            currentPlayerIndex = 0,
            currentRound = newRound,
        )
    }

    fun undoLast(): CountUpState {
        // Full-replay style undo: rebuild the game from all recorded turns minus
        // the most recent one. This stays correct across round boundaries and the
        // finish, where forward state is otherwise hard to invert.
        val totalTurns = perPlayer.sumOf { it.turns.size }
        if (totalTurns == 0) return this

        val lastActor = lastActorIndex() ?: return this
        val keptTurns = perPlayer.mapIndexed { idx, ps ->
            if (idx == lastActor) ps.turns.dropLast(1) else ps.turns
        }
        val seq = chronological(keptTurns)
        val freshPer = perPlayer.map { it.copy(turns = emptyList(), total = 0) }
        return replayFrom(freshPer, seq)
    }

    /** The player index whose turn most recently completed. */
    private fun lastActorIndex(): Int? {
        // Players act in round order; within a round, lower indices first. The
        // most-recent actor is the player with the highest turns.size, breaking
        // ties toward the higher player index (acted later within the round).
        var best = -1
        var bestTurns = -1
        perPlayer.forEachIndexed { idx, ps ->
            if (ps.turns.isEmpty()) return@forEachIndexed
            if (ps.turns.size >= bestTurns) {
                best = idx
                bestTurns = ps.turns.size
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun chronological(turnLists: List<List<Int>>): List<Int> {
        // Flatten round by round, player order within a round.
        val maxRounds = turnLists.maxOfOrNull { it.size } ?: 0
        val seq = mutableListOf<Int>()
        for (round in 0 until maxRounds) {
            turnLists.forEach { turns ->
                turns.getOrNull(round)?.let { seq.add(it) }
            }
        }
        return seq
    }

    /** Replay a chronological list of totals onto a fresh state. */
    private fun replayFrom(
        freshPer: List<CountUpPlayerState>,
        seq: List<Int>,
    ): CountUpState {
        var state = copy(
            perPlayer = freshPer,
            currentPlayerIndex = 0,
            currentRound = 0,
            winnerIndices = emptyList(),
        )
        for (t in seq) {
            state = state.applyTurn(t)
        }
        return state
    }

    private fun winnersOf(per: List<CountUpPlayerState>): List<Int> {
        val maxTotal = per.maxOf { it.total }
        return per.withIndex().filter { it.value.total == maxTotal }.map { it.index }
    }

    companion object {
        fun new(players: List<GamePlayer>, rounds: Int = ROUNDS): CountUpState {
            require(players.isNotEmpty()) { "Count-Up needs at least one player" }
            return CountUpState(
                players = players,
                perPlayer = players.map { CountUpPlayerState(it) },
                rounds = rounds,
            )
        }
    }
}
