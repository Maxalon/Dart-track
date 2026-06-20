package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Catch 40 — a doubles-practice game for 1..4 players built around a
 * "catch-or-stay" doubles ladder.
 *
 * Every player has their OWN current target double, starting at D20 (value 40)
 * and laddering DOWN one double at a time: D20(40) → D19(38) → … → D1(2). On a
 * player's turn they throw 3 darts at their current double and record how many
 * hit it (0..3):
 *   - hits >= 1  -> they "catch" it: score += (2 * doubleNumber) (the value of
 *                   the double, e.g. 40 for D20) and their current double
 *                   advances DOWN by one. Catching D1 FINISHES that player (they
 *                   have caught the whole ladder) and they stop throwing.
 *   - hits == 0  -> they STAY on the same double next turn (no score, no advance).
 *
 * Turns are lockstep (player 0, 1, …). To bound games, each player is capped at
 * [CATCH40_MAX_TURNS] turns. The game ends when every player is FINISHED or has
 * used all of their turns. Highest [Catch40PlayerState.score] wins (ties allowed
 * -> all in winnerIndices). Darts thrown = turns * 3.
 */
const val CATCH40_START_DOUBLE: Int = 20
const val CATCH40_MAX_HITS: Int = 3
const val CATCH40_MAX_TURNS: Int = 20

@Serializable
data class Catch40Turn(
    /** number of darts that hit the player's current double this turn, 0..3. */
    val hits: Int,
)

@Serializable
data class Catch40PlayerState(
    val player: GamePlayer,
    val turns: List<Catch40Turn> = emptyList(),
    val score: Int = 0,
    /** the double NUMBER this player is currently aiming at (1..20). */
    val doubleNumber: Int = CATCH40_START_DOUBLE,
    /** true once this player has caught the whole ladder (caught D1). */
    val finished: Boolean = false,
) {
    /** darts thrown so far (3 per turn). */
    val darts: Int get() = turns.size * 3

    /** the displayed value of the current double (2 * number, e.g. 40 for D20). */
    val doubleValue: Int get() = 2 * doubleNumber
}

@Serializable
@SerialName("catch_40")
data class Catch40State(
    override val players: List<GamePlayer>,
    val perPlayer: List<Catch40PlayerState>,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /** The double NUMBER [playerIndex] is currently aiming at (1..20). */
    fun currentDouble(playerIndex: Int): Int = perPlayer[playerIndex].doubleNumber

    /** The displayed value of [playerIndex]'s current double (2 * number). */
    fun currentDoubleValue(playerIndex: Int): Int = perPlayer[playerIndex].doubleValue

    /** true once [playerIndex] has used all of their allotted turns. */
    private fun usedAllTurns(ps: Catch40PlayerState): Boolean =
        ps.turns.size >= CATCH40_MAX_TURNS

    /** true once [playerIndex] can no longer throw (finished or out of turns). */
    private fun isDone(ps: Catch40PlayerState): Boolean =
        ps.finished || usedAllTurns(ps)

    fun applyTurn(hits: Int): Catch40State {
        if (isFinished) return this
        require(hits in 0..CATCH40_MAX_HITS) {
            "hits must be in 0..$CATCH40_MAX_HITS"
        }
        val me = perPlayer[currentPlayerIndex]
        // A done player should never be the current thrower, but guard anyway.
        if (isDone(me)) return advanceFrom(perPlayer, currentPlayerIndex)

        val updatedMe = if (hits >= 1) {
            // Catch: add the double's value and ladder DOWN one double.
            val caughtD1 = me.doubleNumber <= 1
            me.copy(
                turns = me.turns + Catch40Turn(hits),
                score = me.score + (2 * me.doubleNumber),
                doubleNumber = (me.doubleNumber - 1).coerceAtLeast(1),
                finished = caughtD1,
            )
        } else {
            // Miss: stay on the same double, no score, no advance.
            me.copy(turns = me.turns + Catch40Turn(hits))
        }

        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = updatedMe
        }
        return advanceFrom(updated, currentPlayerIndex)
    }

    /**
     * Advance the lockstep turn from [fromIndex], skipping any player who is done
     * (finished or out of turns). When everyone is done the game ends and the
     * highest score(s) win.
     */
    private fun advanceFrom(
        updated: List<Catch40PlayerState>,
        fromIndex: Int,
    ): Catch40State {
        val size = players.size
        // Search lockstep order for the next player who can still throw, wrapping
        // around so all players keep taking turns until everyone is done.
        for (offset in 1..size) {
            val idx = (fromIndex + offset) % size
            if (!isDone(updated[idx])) {
                return copy(perPlayer = updated, currentPlayerIndex = idx)
            }
        }

        // Everyone is done -> finish with highest score(s).
        return copy(
            perPlayer = updated,
            currentPlayerIndex = firstActive(updated) ?: 0,
            winnerIndices = winnersOf(updated),
        )
    }

    fun undoLast(): Catch40State {
        // Full-replay style undo: rebuild the game from all recorded turns minus
        // the most recent one. This stays correct across the catch/stay/advance,
        // the per-player ladder, and finishing, where forward state is otherwise
        // hard to invert.
        val totalTurns = perPlayer.sumOf { it.turns.size }
        if (totalTurns == 0) return this

        val lastActor = lastActorIndex() ?: return this
        val keptTurns = perPlayer.mapIndexed { idx, ps ->
            if (idx == lastActor) ps.turns.dropLast(1) else ps.turns
        }
        val seq = chronological(keptTurns)
        val freshPer = perPlayer.map {
            it.copy(
                turns = emptyList(),
                score = 0,
                doubleNumber = CATCH40_START_DOUBLE,
                finished = false,
            )
        }
        return replayFrom(freshPer, seq)
    }

    /** The player index whose turn most recently completed. */
    private fun lastActorIndex(): Int? {
        // Players act in lockstep order; the most-recent actor is the player with
        // the highest turns.size, breaking ties toward the higher player index
        // (acted later within the current lockstep round).
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

    private fun chronological(turnLists: List<List<Catch40Turn>>): List<Pair<Int, Int>> {
        // Flatten to a chronological list of (playerIndex, hits): turn by turn,
        // player order within each lockstep round. A player contributes their
        // k-th turn in round k. Players finish independently, so iterate round,
        // then players.
        val maxRounds = turnLists.maxOfOrNull { it.size } ?: 0
        val seq = mutableListOf<Pair<Int, Int>>()
        for (round in 0 until maxRounds) {
            turnLists.forEachIndexed { idx, turns ->
                turns.getOrNull(round)?.let { seq.add(idx to it.hits) }
            }
        }
        return seq
    }

    /**
     * Replay a chronological list of (playerIndex, hits) onto a fresh state. We
     * drive [currentPlayerIndex] explicitly from the recorded actor so the replay
     * exactly mirrors the lockstep order the turns were taken in.
     */
    private fun replayFrom(
        freshPer: List<Catch40PlayerState>,
        seq: List<Pair<Int, Int>>,
    ): Catch40State {
        var state = copy(
            perPlayer = freshPer,
            currentPlayerIndex = 0,
            winnerIndices = emptyList(),
        )
        for ((idx, hits) in seq) {
            state = state.copy(currentPlayerIndex = idx).applyTurn(hits)
        }
        return state
    }

    private fun firstActive(per: List<Catch40PlayerState>): Int? =
        per.indexOfFirst { !isDone(it) }.takeIf { it >= 0 }

    private fun winnersOf(per: List<Catch40PlayerState>): List<Int> {
        val maxScore = per.maxOf { it.score }
        return per.withIndex().filter { it.value.score == maxScore }.map { it.index }
    }

    companion object {
        fun new(players: List<GamePlayer>): Catch40State = Catch40State(
            players = players,
            perPlayer = players.map { Catch40PlayerState(it) },
        )
    }
}
