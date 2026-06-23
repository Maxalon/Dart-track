package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Gotcha — a race to EXACTLY a target total for 1..4 players, with a "knock you
 * back" twist. Numpad entry like Count-Up / X01.
 *
 * Every player starts at 0. On a turn the active player enters a 0..180 three-
 * dart total which is added to their running total:
 *  - If the new total would EXCEED [target] it is a BUST: the turn scores 0 and
 *    the player stays on their current total.
 *  - If the new total EQUALS [target] the player WINS immediately.
 *  - Otherwise the new total stands.
 *
 * "Gotcha": after a (non-winning, non-bust) turn, if the player's new total
 * EXACTLY equals another player's CURRENT total (and that total is > 0), that
 * opponent is knocked back to 0. Several opponents can be reset by one turn.
 *
 * Turns are lockstep (player 0, 1, …). The first player to land on [target]
 * exactly wins; only one player can win (the game ends the instant a target is
 * hit), so [winnerIndices] holds a single index. Darts thrown = turns * 3.
 */
const val GOTCHA_DEFAULT_TARGET: Int = 301

/** The targets a Gotcha game may be created with. */
val GOTCHA_ALLOWED_TARGETS: List<Int> = listOf(301, 501)

@Serializable
data class GotchaPlayerState(
    val player: GamePlayer,
    /** the 3-dart totals entered so far, one per completed turn (each 0..180). */
    val turns: List<Int> = emptyList(),
    val total: Int = 0,
) {
    /** darts thrown so far (3 per turn). */
    val darts: Int get() = turns.size * 3
}

@Serializable
@SerialName("gotcha")
data class GotchaState(
    override val players: List<GamePlayer>,
    val perPlayer: List<GotchaPlayerState>,
    /** the exact total a player races to (301 or 501). */
    val target: Int = GOTCHA_DEFAULT_TARGET,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /** Points the active player still needs to land EXACTLY on [target]. */
    fun remainingFor(playerIndex: Int): Int = target - perPlayer[playerIndex].total

    /**
     * Apply the active player's 3-dart [entered] total. Overshoot busts (turn
     * scores 0, player stays); landing on [target] wins immediately; otherwise
     * the new total stands and may "gotcha" (reset to 0) any opponent currently
     * sitting on that exact total. No-op once the game is finished.
     */
    fun applyTurn(entered: Int): GotchaState {
        if (isFinished) return this
        require(entered in 0..180) { "entered must be in 0..180" }
        val me = perPlayer[currentPlayerIndex]
        val candidate = me.total + entered

        // BUST: overshooting the target leaves the player where they were. The
        // turn is still recorded (so undo / darts count stay consistent), but it
        // adds nothing and triggers no gotcha.
        if (candidate > target) {
            val updated = perPlayer.toMutableList().also {
                it[currentPlayerIndex] = me.copy(turns = me.turns + entered)
            }
            return advanceFrom(updated, currentPlayerIndex)
        }

        // Land the new total. A gotcha only applies on a non-winning advance.
        var updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(
                turns = me.turns + entered,
                total = candidate,
            )
        }

        if (candidate == target) {
            // Exact hit -> immediate win, no gotcha.
            return copy(
                perPlayer = updated,
                winnerIndices = listOf(currentPlayerIndex),
            )
        }

        // Gotcha: knock any OTHER player who currently sits on this exact total
        // (and is above 0) back to 0. Reset score only; their turn history is
        // preserved so the per-player darts count stays honest.
        updated = updated.mapIndexed { idx, ps ->
            if (idx != currentPlayerIndex && ps.total > 0 && ps.total == candidate) {
                ps.copy(total = 0)
            } else {
                ps
            }
        }.toMutableList()

        return advanceFrom(updated, currentPlayerIndex)
    }

    /** Advance the lockstep turn from [fromIndex] (no finish here; wins are immediate). */
    private fun advanceFrom(
        updated: List<GotchaPlayerState>,
        fromIndex: Int,
    ): GotchaState {
        val size = players.size
        val next = (fromIndex + 1) % size
        return copy(perPlayer = updated, currentPlayerIndex = next)
    }

    fun undoLast(): GotchaState {
        // Full-replay style undo: rebuild the game from all recorded turns minus
        // the most recent one. Replaying is the only robust way to invert a turn
        // that may have reset opponents (a gotcha) or won the game, since those
        // side effects depend on the totals at the moment the turn was taken.
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

    private fun chronological(turnLists: List<List<Int>>): List<Pair<Int, Int>> {
        // Flatten to a chronological list of (playerIndex, entered): round by
        // round, player order within each lockstep round. A player contributes
        // their k-th turn in round k.
        val maxRounds = turnLists.maxOfOrNull { it.size } ?: 0
        val seq = mutableListOf<Pair<Int, Int>>()
        for (round in 0 until maxRounds) {
            turnLists.forEachIndexed { idx, turns ->
                turns.getOrNull(round)?.let { seq.add(idx to it) }
            }
        }
        return seq
    }

    /**
     * Replay a chronological list of (playerIndex, entered) onto a fresh state.
     * We drive [currentPlayerIndex] explicitly from the recorded actor so the
     * replay exactly mirrors the lockstep order the turns were taken in (and so
     * gotcha resets recompute against the correct intermediate totals).
     */
    private fun replayFrom(
        freshPer: List<GotchaPlayerState>,
        seq: List<Pair<Int, Int>>,
    ): GotchaState {
        var state = copy(
            perPlayer = freshPer,
            currentPlayerIndex = 0,
            winnerIndices = emptyList(),
        )
        for ((idx, entered) in seq) {
            state = state.copy(currentPlayerIndex = idx).applyTurn(entered)
        }
        return state
    }

    companion object {
        fun new(players: List<GamePlayer>, target: Int = GOTCHA_DEFAULT_TARGET): GotchaState {
            require(players.isNotEmpty()) { "Gotcha needs at least one player" }
            require(target in GOTCHA_ALLOWED_TARGETS) {
                "target must be one of $GOTCHA_ALLOWED_TARGETS"
            }
            return GotchaState(
                players = players,
                perPlayer = players.map { GotchaPlayerState(it) },
                target = target,
            )
        }
    }
}
