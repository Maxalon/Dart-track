package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shanghai — a per-turn-entry practice game for 1..4 players.
 *
 * The game runs for 7 rounds, targeting the numbers 1, 2, ..., 7 in order: in
 * round R (1-based) everyone aims at the number R. On a player's turn they record
 * how many singles, doubles, and triples of the round's number they hit with
 * their 3 darts (each 0..3 with singles + doubles + triples <= 3). The round's
 * score added to their running total is:
 *
 *   (singles * 1 + doubles * 2 + triples * 3) * R
 *
 * Instant win ("Shanghai"): if in a single turn a player hits at least one single
 * AND one double AND one triple of the round number (s >= 1 && d >= 1 && t >= 1,
 * which with s + d + t <= 3 forces s = d = t = 1) that player instantly wins,
 * regardless of score.
 *
 * Otherwise, after all 7 rounds the highest total wins (ties allowed -> all
 * recorded in winnerIndices). Darts thrown = turns * 3.
 */
const val SHANGHAI_ROUNDS: Int = 7
const val SHANGHAI_MAX_DARTS: Int = 3

@Serializable
data class ShanghaiTurn(
    /** singles of the round number hit this turn, 0..3. */
    val singles: Int,
    /** doubles of the round number hit this turn, 0..3. */
    val doubles: Int,
    /** triples of the round number hit this turn, 0..3. */
    val triples: Int,
) {
    /** true when this turn is a Shanghai (one single, one double, one triple). */
    val isShanghai: Boolean get() = singles >= 1 && doubles >= 1 && triples >= 1
}

@Serializable
data class ShanghaiPlayerState(
    val player: GamePlayer,
    val turns: List<ShanghaiTurn> = emptyList(),
    val total: Int = 0,
) {
    /** darts thrown so far (3 per turn). */
    val darts: Int get() = turns.size * 3
}

@Serializable
@SerialName("shanghai")
data class ShanghaiState(
    override val players: List<GamePlayer>,
    val perPlayer: List<ShanghaiPlayerState>,
    /** index into rounds 1..7 currently being played (0-based). */
    val currentRound: Int = 0,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /** The number [playerIndex] is currently aiming at (1..7). */
    fun currentTarget(playerIndex: Int): Int =
        (currentRound + 1).coerceAtMost(SHANGHAI_ROUNDS)

    fun applyTurn(singles: Int, doubles: Int, triples: Int): ShanghaiState {
        if (isFinished) return this
        require(singles >= 0 && doubles >= 0 && triples >= 0) {
            "dart counts must be non-negative"
        }
        require(singles + doubles + triples in 0..SHANGHAI_MAX_DARTS) {
            "singles + doubles + triples must be in 0..$SHANGHAI_MAX_DARTS"
        }
        val me = perPlayer[currentPlayerIndex]
        val round = currentRound + 1
        val turn = ShanghaiTurn(singles, doubles, triples)
        val added = (singles * 1 + doubles * 2 + triples * 3) * round
        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(
                turns = me.turns + turn,
                total = me.total + added,
            )
        }

        // Instant Shanghai win ends the game immediately.
        if (turn.isShanghai) {
            return copy(
                perPlayer = updated,
                winnerIndices = listOf(currentPlayerIndex),
            )
        }

        return advanceFrom(updated, currentPlayerIndex)
    }

    /**
     * Advance the turn from [fromIndex]. Once the last player of round 7 has
     * thrown, the game ends and the highest total(s) win.
     */
    private fun advanceFrom(
        updated: List<ShanghaiPlayerState>,
        fromIndex: Int,
    ): ShanghaiState {
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
        if (newRound >= SHANGHAI_ROUNDS) {
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

    fun undoLast(): ShanghaiState {
        // Full-replay style undo: rebuild the game from all recorded turns minus
        // the most recent one. This stays correct across round boundaries and an
        // instant-win finish, where forward state is otherwise hard to invert.
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

    private fun chronological(turnLists: List<List<ShanghaiTurn>>): List<ShanghaiTurn> {
        // Flatten round by round, player order within a round.
        val maxRounds = turnLists.maxOfOrNull { it.size } ?: 0
        val seq = mutableListOf<ShanghaiTurn>()
        for (round in 0 until maxRounds) {
            turnLists.forEach { turns ->
                turns.getOrNull(round)?.let { seq.add(it) }
            }
        }
        return seq
    }

    /** Replay a chronological list of turns onto a fresh state. */
    private fun replayFrom(
        freshPer: List<ShanghaiPlayerState>,
        seq: List<ShanghaiTurn>,
    ): ShanghaiState {
        var state = copy(
            perPlayer = freshPer,
            currentPlayerIndex = 0,
            currentRound = 0,
            winnerIndices = emptyList(),
        )
        for (t in seq) {
            state = state.applyTurn(t.singles, t.doubles, t.triples)
        }
        return state
    }

    private fun winnersOf(per: List<ShanghaiPlayerState>): List<Int> {
        val maxTotal = per.maxOf { it.total }
        return per.withIndex().filter { it.value.total == maxTotal }.map { it.index }
    }

    companion object {
        fun new(players: List<GamePlayer>): ShanghaiState {
            require(players.isNotEmpty()) { "Shanghai needs at least one player" }
            return ShanghaiState(
                players = players,
                perPlayer = players.map { ShanghaiPlayerState(it) },
            )
        }
    }
}
