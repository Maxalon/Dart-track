package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Bob's 27 — a doubles-practice game. Every player starts on 27 and works
 * through the doubles of 1, 2, ..., 20 in order (20 rounds; no bull, kept to
 * doubles 1..20 for simplicity).
 *
 * On a player's turn for double N they enter how many of their 3 darts hit that
 * double (0..3):
 *   - hits > 0  -> score += hits * (2 * N)
 *   - hits == 0 -> score -= (2 * N)
 *
 * A player whose score drops to 0 or below is "out" (eliminated) and keeps their
 * last score; they stop throwing. Players still in play continue through double
 * 20. Once double 20 is done (or everyone is out) the game ends and the highest
 * score wins (ties allowed). Darts thrown = turns * 3.
 */
const val BOBS27_START: Int = 27
const val BOBS27_LAST_DOUBLE: Int = 20
const val BOBS27_MAX_HITS: Int = 3

@Serializable
data class BobsTwentySevenTurn(
    /** number of darts that hit the round's double this turn, 0..3. */
    val hits: Int,
)

@Serializable
data class BobsTwentySevenPlayerState(
    val player: GamePlayer,
    val turns: List<BobsTwentySevenTurn> = emptyList(),
    val score: Int = BOBS27_START,
    /** true once this player's score has dropped to <= 0. */
    val out: Boolean = false,
) {
    /** darts thrown so far (3 per turn). */
    val darts: Int get() = turns.size * 3
}

@Serializable
@SerialName("bobs_27")
data class BobsTwentySevenState(
    override val players: List<GamePlayer>,
    val perPlayer: List<BobsTwentySevenPlayerState>,
    /** index into doubles 1..20 for the round currently being played (0-based). */
    val currentRound: Int = 0,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /**
     * The double [playerIndex] is currently aiming at (1..20). All in-play
     * players face the same double each round; this is capped at the last double.
     */
    fun currentDouble(playerIndex: Int): Int =
        (currentRound + 1).coerceAtMost(BOBS27_LAST_DOUBLE)

    fun applyTurn(hits: Int): BobsTwentySevenState {
        if (isFinished) return this
        require(hits in 0..BOBS27_MAX_HITS) {
            "hits must be in 0..$BOBS27_MAX_HITS"
        }
        val me = perPlayer[currentPlayerIndex]
        // An out player should never be the current thrower, but guard anyway.
        if (me.out) return advanceFrom(perPlayer, currentPlayerIndex)

        val doubleValue = 2 * (currentRound + 1)
        val delta = if (hits > 0) hits * doubleValue else -doubleValue
        val newScore = me.score + delta
        val nowOut = newScore <= 0
        val updatedTurns = me.turns + BobsTwentySevenTurn(hits)
        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(
                turns = updatedTurns,
                score = newScore,
                out = nowOut,
            )
        }
        return advanceFrom(updated, currentPlayerIndex)
    }

    /**
     * Advance the turn from [fromIndex], skipping any players who are out and
     * rolling the round over once everyone still in play has thrown. Computes
     * the winner(s) and ends the game when the gauntlet is done or everyone is
     * out.
     */
    private fun advanceFrom(
        updated: List<BobsTwentySevenPlayerState>,
        fromIndex: Int,
    ): BobsTwentySevenState {
        val size = players.size
        // Find the next in-play player after fromIndex within this round (no wrap).
        var nextInRound = -1
        for (offset in 1 until size) {
            val idx = fromIndex + offset
            if (idx >= size) break
            if (!updated[idx].out) { nextInRound = idx; break }
        }

        if (nextInRound >= 0) {
            // Still players to throw this round; no round advance.
            return copy(
                perPlayer = updated,
                currentPlayerIndex = nextInRound,
                currentRound = currentRound,
            )
        }

        // Round complete (everyone after fromIndex is out or there are none).
        val newRound = currentRound + 1
        val anyoneLeft = updated.any { !it.out }
        if (newRound >= BOBS27_LAST_DOUBLE || !anyoneLeft) {
            return copy(
                perPlayer = updated,
                currentPlayerIndex = firstInPlay(updated) ?: 0,
                currentRound = newRound,
                winnerIndices = winnersOf(updated),
            )
        }

        // Next round: first in-play player throws.
        return copy(
            perPlayer = updated,
            currentPlayerIndex = firstInPlay(updated) ?: 0,
            currentRound = newRound,
        )
    }

    fun undoLast(): BobsTwentySevenState {
        // Reconstruct the entire game from the recorded turns minus the last one.
        // This keeps undo correct across out-elimination and round boundaries,
        // where forward state (skips, round counter) is otherwise hard to invert.
        val totalTurns = perPlayer.sumOf { it.turns.size }
        if (totalTurns == 0) return this

        // The last player to act = the in-play predecessor of currentPlayerIndex
        // with the most turns recorded. Simplest robust approach: drop the last
        // turn from the player who has thrown the most rounds (the one whose
        // turn most recently completed). Determine that by max turns.size; on a
        // tie the later player index acted later within the round.
        val lastActor = lastActorIndex() ?: return this
        // Per-player turn lists with the most recent turn removed.
        val keptTurns = perPlayer.mapIndexed { idx, ps ->
            if (idx == lastActor) ps.turns.dropLast(1) else ps.turns
        }
        val seq = chronological(keptTurns)
        val freshPer = perPlayer.map {
            it.copy(turns = emptyList(), score = BOBS27_START, out = false)
        }
        return replayFrom(freshPer, seq)
    }

    /** The player index whose turn most recently completed. */
    private fun lastActorIndex(): Int? {
        // Players act in round order; within a round, lower indices first. The
        // most-recent actor is the in-play player with the highest turns.size,
        // breaking ties toward the higher player index (acted later in-round).
        var best = -1
        var bestTurns = -1
        perPlayer.forEachIndexed { idx, ps ->
            if (ps.turns.isEmpty()) return@forEachIndexed
            // Higher turns.size = acted in a later round; on a tie the higher
            // index acted later within the current round.
            if (ps.turns.size >= bestTurns) {
                best = idx
                bestTurns = ps.turns.size
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun chronological(turnLists: List<List<BobsTwentySevenTurn>>): List<Int> {
        // Flatten to a chronological list of hits values: round by round, player
        // order within a round. A player contributes their k-th turn in round k.
        // Players go out independently, so iterate round, then players.
        val maxRounds = turnLists.maxOfOrNull { it.size } ?: 0
        val seq = mutableListOf<Int>()
        for (round in 0 until maxRounds) {
            turnLists.forEach { turns ->
                turns.getOrNull(round)?.let { seq.add(it.hits) }
            }
        }
        return seq
    }

    /** Replay a chronological list of hits onto a fresh state. */
    private fun replayFrom(
        freshPer: List<BobsTwentySevenPlayerState>,
        seq: List<Int>,
    ): BobsTwentySevenState {
        var state = copy(
            perPlayer = freshPer,
            currentPlayerIndex = firstInPlay(freshPer) ?: 0,
            currentRound = 0,
            winnerIndices = emptyList(),
        )
        for (hits in seq) {
            state = state.applyTurn(hits)
        }
        return state
    }

    private fun firstInPlay(per: List<BobsTwentySevenPlayerState>): Int? =
        per.indexOfFirst { !it.out }.takeIf { it >= 0 }

    private fun winnersOf(per: List<BobsTwentySevenPlayerState>): List<Int> {
        val maxScore = per.maxOf { it.score }
        return per.withIndex().filter { it.value.score == maxScore }.map { it.index }
    }

    companion object {
        fun new(players: List<GamePlayer>): BobsTwentySevenState {
            require(players.isNotEmpty()) { "Bob's 27 needs at least one player" }
            return BobsTwentySevenState(
                players = players,
                perPlayer = players.map { BobsTwentySevenPlayerState(it) },
            )
        }
    }
}
