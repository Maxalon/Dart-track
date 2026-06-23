package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Golf — a 9-hole "stroke play" practice game for 1..4 players.
 *
 * Every hole targets its own number: hole N (1-based) is played on the number N
 * (holes 1..9). On a player's turn at a hole they throw up to 3 darts at that
 * number and record their BEST single dart as a stroke count, mirroring real
 * golf where lower is better:
 *
 *   Triple -> 1 stroke, Double -> 2 strokes, Single -> 3 strokes, Miss -> 5.
 *
 * The recorded result is the best (lowest-stroke) of the three darts. Play is
 * round-robin BY HOLE like Shanghai: in hole H (0-based) every player records
 * exactly one result, in seat order; once the last seat has played, the game
 * advances to the next hole. The game finishes after the 9th hole.
 *
 * LOWEST total strokes after 9 holes wins (ties allowed -> all recorded in
 * winnerIndices). Darts thrown are not tracked per hole (only the best dart's
 * outcome matters), so there is no darts metric. Undo reverts the most recent
 * hole result and is reusable, implemented as a full replay of all recorded
 * results minus the last one (Shanghai / Catch 40 pattern).
 */
const val GOLF_HOLES: Int = 9

/**
 * The best dart a player landed on a hole, with its stroke cost. Lower strokes
 * are better (TRIPLE is the best outcome, MISS the worst), matching golf.
 */
@Serializable
enum class GolfResult(val strokes: Int) {
    TRIPLE(1),
    DOUBLE(2),
    SINGLE(3),
    MISS(5),
}

@Serializable
data class GolfPlayerState(
    val player: GamePlayer,
    /** one result per hole played so far, in hole order. */
    val results: List<GolfResult> = emptyList(),
    /** running stroke total (sum of [GolfResult.strokes]); lower is better. */
    val strokes: Int = 0,
) {
    /** holes played so far. */
    val holesPlayed: Int get() = results.size
}

@Serializable
@SerialName("golf")
data class GolfState(
    override val players: List<GamePlayer>,
    val perPlayer: List<GolfPlayerState>,
    /** index of the hole currently being played (0-based, 0..GOLF_HOLES-1). */
    val currentHole: Int = 0,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /** The 1-based hole number [playerIndex] is currently on (1..GOLF_HOLES). */
    fun currentHoleNumber(playerIndex: Int): Int =
        (currentHole + 1).coerceAtMost(GOLF_HOLES)

    /** Record the active player's best dart [result] for the current hole. */
    fun applyResult(result: GolfResult): GolfState {
        if (isFinished) return this
        val me = perPlayer[currentPlayerIndex]
        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(
                results = me.results + result,
                strokes = me.strokes + result.strokes,
            )
        }
        return advanceFrom(updated, currentPlayerIndex)
    }

    /**
     * Advance the round-robin from [fromIndex]. Once the last seat of the 9th
     * hole has played, the game ends and the LOWEST stroke total(s) win.
     */
    private fun advanceFrom(
        updated: List<GolfPlayerState>,
        fromIndex: Int,
    ): GolfState {
        val size = players.size
        val nextInHole = fromIndex + 1
        if (nextInHole < size) {
            // Still seats to play the current hole.
            return copy(
                perPlayer = updated,
                currentPlayerIndex = nextInHole,
                currentHole = currentHole,
            )
        }

        // Hole complete (last seat just played).
        val newHole = currentHole + 1
        if (newHole >= GOLF_HOLES) {
            // Final hole done -> finish with the LOWEST stroke total(s).
            return copy(
                perPlayer = updated,
                currentPlayerIndex = 0,
                currentHole = newHole,
                winnerIndices = winnersOf(updated),
            )
        }

        // Next hole: first seat plays it.
        return copy(
            perPlayer = updated,
            currentPlayerIndex = 0,
            currentHole = newHole,
        )
    }

    fun undoLast(): GolfState {
        // Full-replay style undo: rebuild the game from all recorded results minus
        // the most recent one. This stays correct across hole boundaries and the
        // finish, where forward state is otherwise hard to invert.
        val totalResults = perPlayer.sumOf { it.results.size }
        if (totalResults == 0) return this

        val lastActor = lastActorIndex() ?: return this
        val keptResults = perPlayer.mapIndexed { idx, ps ->
            if (idx == lastActor) ps.results.dropLast(1) else ps.results
        }
        val seq = chronological(keptResults)
        val freshPer = perPlayer.map { it.copy(results = emptyList(), strokes = 0) }
        return replayFrom(freshPer, seq)
    }

    /** The seat index whose hole result most recently completed. */
    private fun lastActorIndex(): Int? {
        // Seats act in hole order; within a hole, lower indices first. The
        // most-recent actor is the seat with the highest results.size, breaking
        // ties toward the higher seat index (acted later within the hole).
        var best = -1
        var bestCount = -1
        perPlayer.forEachIndexed { idx, ps ->
            if (ps.results.isEmpty()) return@forEachIndexed
            if (ps.results.size >= bestCount) {
                best = idx
                bestCount = ps.results.size
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun chronological(resultLists: List<List<GolfResult>>): List<GolfResult> {
        // Flatten hole by hole, seat order within a hole. Every seat records
        // exactly one result per hole, so a seat's k-th result is hole k.
        val maxHoles = resultLists.maxOfOrNull { it.size } ?: 0
        val seq = mutableListOf<GolfResult>()
        for (hole in 0 until maxHoles) {
            resultLists.forEach { results ->
                results.getOrNull(hole)?.let { seq.add(it) }
            }
        }
        return seq
    }

    /** Replay a chronological list of results onto a fresh state. */
    private fun replayFrom(
        freshPer: List<GolfPlayerState>,
        seq: List<GolfResult>,
    ): GolfState {
        var state = copy(
            perPlayer = freshPer,
            currentHole = 0,
            currentPlayerIndex = 0,
            winnerIndices = emptyList(),
        )
        for (r in seq) {
            state = state.applyResult(r)
        }
        return state
    }

    private fun winnersOf(per: List<GolfPlayerState>): List<Int> {
        // LOWEST stroke total wins; ties record all.
        val minStrokes = per.minOf { it.strokes }
        return per.withIndex().filter { it.value.strokes == minStrokes }.map { it.index }
    }

    companion object {
        fun new(players: List<GamePlayer>): GolfState {
            require(players.isNotEmpty()) { "Golf needs at least one player" }
            return GolfState(
                players = players,
                perPlayer = players.map { GolfPlayerState(it) },
            )
        }
    }
}
