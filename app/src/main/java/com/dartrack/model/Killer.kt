package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Killer — a knockout game for 2..4 players where you first earn the right to
 * deal damage, then try to drain everyone else's lives.
 *
 * Each seat is assigned a DISTINCT board number ("their number"): seat i gets
 * `20 - i`, so seat0=20, seat1=19, seat2=18, seat3=17. Every player starts with
 * [KILLER_DEFAULT_LIVES] lives (configurable, >= 1) and is NOT a killer.
 *
 * On a turn the active player p records, in order, which seats' DOUBLES they hit
 * with their 3 darts as a list `hits: List<Int>` of seat indices (length 0..3; a
 * missed dart is simply omitted). The turn resolves dart by dart, tracking
 * whether p is a killer at that instant (`isKillerNow`), seeded from p's killer
 * flag at the START of the turn (`wasKiller`):
 *
 *  - hit == p (own number):
 *      - if not yet a killer this turn -> p BECOMES a killer (no self-damage on
 *        the dart that promotes you).
 *      - else if p was ALREADY a killer at turn start -> SELF-KILL: p loses 1 life.
 *      - else (p only became a killer earlier this same turn) -> no effect.
 *  - hit == q (q != p, opponent's number):
 *      - if p is a killer at this instant -> q loses 1 life.
 *      - else -> no effect (you cannot damage anyone until you are a killer).
 *
 * Lives are floored at 0; a player at 0 lives is ELIMINATED and is skipped when
 * advancing turns. After a turn, if only one player remains alive the game ends
 * and that player is the winner. applyTurn is a no-op once finished.
 *
 * Undo is implemented by REPLAY: the full ordered [turns] history is stored and
 * undoLast() drops the last turn then folds applyTurn over the survivors from a
 * fresh [new] state, which is the only robust way to invert cross-player damage.
 * Darts thrown for a player = (their recorded turns) * 3 (approximation).
 */
const val KILLER_DEFAULT_LIVES: Int = 3
const val KILLER_MAX_PLAYERS: Int = 4
const val KILLER_MIN_PLAYERS: Int = 2
const val KILLER_MAX_DARTS: Int = 3

/** One recorded turn: who acted and which seats' doubles they hit, in order. */
@Serializable
data class KillerTurn(
    /** seat index of the player who took this turn. */
    val actorIndex: Int,
    /** seat indices whose double was hit, in dart order (size 0..3). */
    val hits: List<Int> = emptyList(),
)

/** Derived per-player state: their board number, remaining lives, killer flag. */
@Serializable
data class KillerPlayerState(
    val player: GamePlayer,
    /** the distinct board number assigned to this seat (20 - seatIndex). */
    val number: Int,
    val lives: Int,
    val isKiller: Boolean = false,
) {
    /** true when this player has been knocked out (no lives left). */
    val isEliminated: Boolean get() = lives <= 0
}

@Serializable
@SerialName("killer")
data class KillerState(
    override val players: List<GamePlayer>,
    val perPlayer: List<KillerPlayerState>,
    /** lives each player started with (>= 1). */
    val startLives: Int = KILLER_DEFAULT_LIVES,
    /** full ordered turn history; the source of truth for undo-by-replay. */
    val turns: List<KillerTurn> = emptyList(),
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /** The distinct board number assigned to [playerIndex] (20 - playerIndex). */
    fun assignedNumber(playerIndex: Int): Int = perPlayer[playerIndex].number

    /** true when [playerIndex] has been knocked out (no lives left). */
    fun isEliminated(playerIndex: Int): Boolean = perPlayer[playerIndex].isEliminated

    /** how many players are still alive (lives > 0). */
    val aliveCount: Int get() = perPlayer.count { !it.isEliminated }

    /**
     * Apply the active player's turn: [hits] lists the seat indices whose DOUBLE
     * was hit by each landed dart, in order (size 0..3). Resolves damage dart by
     * dart per the Killer rules, eliminates anyone reaching 0 lives, then advances
     * to the next alive player (or finishes if only one remains). No-op once the
     * game is finished.
     */
    fun applyTurn(hits: List<Int>): KillerState {
        if (isFinished) return this
        require(hits.size in 0..KILLER_MAX_DARTS) {
            "hits must contain 0..$KILLER_MAX_DARTS darts"
        }
        require(hits.all { it in players.indices }) {
            "every hit must be a valid player index in 0..${players.size - 1}"
        }

        val p = currentPlayerIndex
        val lives = perPlayer.map { it.lives }.toMutableList()
        val killer = perPlayer.map { it.isKiller }.toMutableList()
        val wasKiller = killer[p]
        var isKillerNow = wasKiller

        for (hit in hits) {
            if (hit == p) {
                // Own number.
                if (!isKillerNow) {
                    // Promotion to killer; no self-damage on this dart.
                    isKillerNow = true
                    killer[p] = true
                } else if (wasKiller) {
                    // Already a killer at the START of the turn -> self-kill.
                    lives[p] = (lives[p] - 1).coerceAtLeast(0)
                }
                // else: became a killer earlier this same turn -> redundant, no-op.
            } else {
                // Opponent's number: only a killer can deal damage.
                if (isKillerNow) {
                    lives[hit] = (lives[hit] - 1).coerceAtLeast(0)
                }
            }
        }

        val updated = perPlayer.mapIndexed { idx, ps ->
            ps.copy(lives = lives[idx], isKiller = killer[idx])
        }
        val history = turns + KillerTurn(actorIndex = p, hits = hits)

        // Win check: a clean Killer end leaves exactly one alive player.
        val alive = updated.withIndex().filter { it.value.lives > 0 }.map { it.index }
        if (players.size > 1 && alive.size <= 1) {
            return copy(
                perPlayer = updated,
                turns = history,
                winnerIndices = alive,
            )
        }

        return copy(
            perPlayer = updated,
            turns = history,
            currentPlayerIndex = nextAlive(p, updated),
        )
    }

    /** First player after [from] (exclusive, wrapping) that still has lives. */
    private fun nextAlive(from: Int, per: List<KillerPlayerState>): Int {
        val size = per.size
        var i = (from + 1) % size
        while (per[i].lives <= 0 && i != from) {
            i = (i + 1) % size
        }
        return i
    }

    /**
     * Undo the most recent turn by REPLAY: drop the last recorded turn and rebuild
     * every derived field (lives, killer flags, currentPlayerIndex, winnerIndices)
     * by folding applyTurn over the survivors from a fresh [new] state. Replaying
     * is the only robust way to invert a turn's cross-player damage. No-op when the
     * history is empty.
     */
    fun undoLast(): KillerState {
        if (turns.isEmpty()) return this
        val kept = turns.dropLast(1)
        var state = new(players, startLives)
        for (t in kept) {
            // Drive currentPlayerIndex from the recorded actor so the replay mirrors
            // the exact order turns were taken in (and damage recomputes correctly).
            state = state.copy(currentPlayerIndex = t.actorIndex).applyTurn(t.hits)
        }
        return state
    }

    companion object {
        fun new(players: List<GamePlayer>, startLives: Int = KILLER_DEFAULT_LIVES): KillerState {
            require(players.size in KILLER_MIN_PLAYERS..KILLER_MAX_PLAYERS) {
                "Killer needs $KILLER_MIN_PLAYERS..$KILLER_MAX_PLAYERS players"
            }
            require(startLives >= 1) { "startLives must be >= 1" }
            return KillerState(
                players = players,
                perPlayer = players.mapIndexed { idx, gp ->
                    KillerPlayerState(player = gp, number = 20 - idx, lives = startLives)
                },
                startLives = startLives,
            )
        }
    }
}
