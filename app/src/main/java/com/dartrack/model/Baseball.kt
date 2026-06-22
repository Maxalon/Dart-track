package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Baseball — a per-turn-entry practice game for 1..4 players modeled on the
 * 9-inning bar game.
 *
 * The game runs for 9 innings, targeting the numbers 1, 2, ..., 9 in order: in
 * inning N (1-based) everyone aims at the number N with their 3 darts. On a
 * player's turn they record how many singles, doubles, and triples of the
 * inning's number they hit (each 0..3 with singles + doubles + triples <= 3).
 * The "runs" scored that inning and added to their running total is:
 *
 *   singles * 1 + doubles * 2 + triples * 3
 *
 * Unlike Shanghai there is NO inning multiplier and NO instant win. After all 9
 * innings the highest total wins (ties allowed -> all recorded in
 * winnerIndices). Lockstep innings (player 0, 1, …); an inning completes once
 * every player has thrown once. Darts thrown = turns * 3.
 */
const val BASEBALL_INNINGS: Int = 9
const val BASEBALL_MAX_DARTS: Int = 3

@Serializable
data class BaseballInning(
    /** singles of the inning number hit this turn, 0..3. */
    val singles: Int,
    /** doubles of the inning number hit this turn, 0..3. */
    val doubles: Int,
    /** triples of the inning number hit this turn, 0..3. */
    val triples: Int,
) {
    /** runs scored this inning (s*1 + d*2 + t*3, no multiplier). */
    val runs: Int get() = singles * 1 + doubles * 2 + triples * 3
}

@Serializable
data class BaseballPlayerState(
    val player: GamePlayer,
    val innings: List<BaseballInning> = emptyList(),
    val total: Int = 0,
) {
    /** darts thrown so far (3 per inning). */
    val darts: Int get() = innings.size * 3
}

@Serializable
@SerialName("baseball")
data class BaseballState(
    override val players: List<GamePlayer>,
    val perPlayer: List<BaseballPlayerState>,
    /** index into innings 1..9 currently being played (0-based). */
    val currentInning: Int = 0,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /** The number [playerIndex] is currently aiming at (1..9). */
    fun currentTarget(playerIndex: Int): Int =
        (currentInning + 1).coerceAtMost(BASEBALL_INNINGS)

    fun applyTurn(singles: Int, doubles: Int, triples: Int): BaseballState {
        if (isFinished) return this
        require(singles >= 0 && doubles >= 0 && triples >= 0) {
            "dart counts must be non-negative"
        }
        require(singles + doubles + triples in 0..BASEBALL_MAX_DARTS) {
            "singles + doubles + triples must be in 0..$BASEBALL_MAX_DARTS"
        }
        val me = perPlayer[currentPlayerIndex]
        val inning = BaseballInning(singles, doubles, triples)
        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(
                innings = me.innings + inning,
                total = me.total + inning.runs,
            )
        }
        return advanceFrom(updated, currentPlayerIndex)
    }

    /**
     * Advance the lockstep turn from [fromIndex]. Once the last player of the
     * 9th inning has thrown, the game ends and the highest total(s) win.
     */
    private fun advanceFrom(
        updated: List<BaseballPlayerState>,
        fromIndex: Int,
    ): BaseballState {
        val size = players.size
        val nextInInning = fromIndex + 1
        if (nextInInning < size) {
            // Still players to throw this inning.
            return copy(
                perPlayer = updated,
                currentPlayerIndex = nextInInning,
                currentInning = currentInning,
            )
        }

        // Inning complete (last player just threw).
        val newInning = currentInning + 1
        if (newInning >= BASEBALL_INNINGS) {
            // Final inning done -> finish with highest total(s).
            return copy(
                perPlayer = updated,
                currentPlayerIndex = 0,
                currentInning = newInning,
                winnerIndices = winnersOf(updated),
            )
        }

        // Next inning: first player throws.
        return copy(
            perPlayer = updated,
            currentPlayerIndex = 0,
            currentInning = newInning,
        )
    }

    fun undoLast(): BaseballState {
        // Full-replay style undo: rebuild the game from all recorded innings minus
        // the most recent one. This stays correct across inning boundaries and the
        // finish, where forward state is otherwise hard to invert.
        val totalInnings = perPlayer.sumOf { it.innings.size }
        if (totalInnings == 0) return this

        val lastActor = lastActorIndex() ?: return this
        val keptInnings = perPlayer.mapIndexed { idx, ps ->
            if (idx == lastActor) ps.innings.dropLast(1) else ps.innings
        }
        val seq = chronological(keptInnings)
        val freshPer = perPlayer.map { it.copy(innings = emptyList(), total = 0) }
        return replayFrom(freshPer, seq)
    }

    /** The player index whose turn most recently completed. */
    private fun lastActorIndex(): Int? {
        // Players act in inning order; within an inning, lower indices first. The
        // most-recent actor is the player with the highest innings.size, breaking
        // ties toward the higher player index (acted later within the inning).
        var best = -1
        var bestInnings = -1
        perPlayer.forEachIndexed { idx, ps ->
            if (ps.innings.isEmpty()) return@forEachIndexed
            if (ps.innings.size >= bestInnings) {
                best = idx
                bestInnings = ps.innings.size
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun chronological(inningLists: List<List<BaseballInning>>): List<BaseballInning> {
        // Flatten inning by inning, player order within an inning.
        val maxInnings = inningLists.maxOfOrNull { it.size } ?: 0
        val seq = mutableListOf<BaseballInning>()
        for (inning in 0 until maxInnings) {
            inningLists.forEach { innings ->
                innings.getOrNull(inning)?.let { seq.add(it) }
            }
        }
        return seq
    }

    /** Replay a chronological list of innings onto a fresh state. */
    private fun replayFrom(
        freshPer: List<BaseballPlayerState>,
        seq: List<BaseballInning>,
    ): BaseballState {
        var state = copy(
            perPlayer = freshPer,
            currentPlayerIndex = 0,
            currentInning = 0,
            winnerIndices = emptyList(),
        )
        for (i in seq) {
            state = state.applyTurn(i.singles, i.doubles, i.triples)
        }
        return state
    }

    private fun winnersOf(per: List<BaseballPlayerState>): List<Int> {
        val maxTotal = per.maxOf { it.total }
        return per.withIndex().filter { it.value.total == maxTotal }.map { it.index }
    }

    companion object {
        fun new(players: List<GamePlayer>): BaseballState {
            require(players.isNotEmpty()) { "Baseball needs at least one player" }
            return BaseballState(
                players = players,
                perPlayer = players.map { BaseballPlayerState(it) },
            )
        }
    }
}
