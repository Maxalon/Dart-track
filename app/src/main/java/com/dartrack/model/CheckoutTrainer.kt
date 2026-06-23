package com.dartrack.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Checkout Trainer — a finishing drill for 1..4 players over a ladder of
 * double-out checkout TARGETS.
 *
 * Every player attempts the SAME ordered list of [targets] (default
 * [DEFAULT_TARGETS], all valid double-out finishes). Play is round-robin BY
 * TARGET: in round R (0-based) every player attempts `targets[R]` exactly once,
 * in seat order; once the last seat has thrown, the ladder advances to
 * `targets[R + 1]`. The game finishes after the last target's round.
 *
 * Each attempt is one visit of up to 3 darts at the current target (double-out).
 * The player records the outcome:
 *  - HIT: they checked out, with the number of darts used (1, 2, or 3).
 *  - MISS: they did not check out (darts used is irrelevant and not recorded).
 *
 * A player's score is the number of checkouts HIT. The winner is whoever hit the
 * most; ties are broken by the FEWEST total darts used on hits (a more efficient
 * finisher wins); any remaining ties are all recorded in [winnerIndices].
 *
 * Undo reverts the most recent attempt and is reusable. Because forward state is
 * awkward to invert across a target boundary (and the finish), undo is
 * implemented as a full replay of all recorded attempts minus the last one,
 * mirroring Shanghai / Catch 40.
 *
 * The UI surfaces the suggested route for the current target via [Checkout];
 * because every default target is a finishable double-out, each has a non-null
 * suggestion.
 */
val DEFAULT_TARGETS: List<Int> = listOf(40, 60, 80, 100, 120, 140, 160, 170)

/** The most darts a single checkout attempt may use. */
const val CHECKOUT_TRAINER_MAX_DARTS: Int = 3

@Serializable
data class CheckoutAttempt(
    /** true when the player checked the target out this attempt. */
    val hit: Boolean,
    /** darts used, 1..3 when [hit]; 0 (ignored) on a miss. */
    val darts: Int,
)

@Serializable
data class CheckoutPlayerState(
    val player: GamePlayer,
    val attempts: List<CheckoutAttempt> = emptyList(),
) {
    /** number of checkouts HIT (the player's score). */
    val hits: Int get() = attempts.count { it.hit }

    /** total darts used across all HIT attempts (the tie-break metric). */
    val dartsOnHits: Int get() = attempts.filter { it.hit }.sumOf { it.darts }
}

@Serializable
@SerialName("checkout_trainer")
data class CheckoutTrainerState(
    override val players: List<GamePlayer>,
    val perPlayer: List<CheckoutPlayerState>,
    /** the ordered checkout targets every player attempts. */
    val targets: List<Int>,
    /** index into [targets] of the target currently being attempted (0-based). */
    val currentTargetIndex: Int = 0,
    override val currentPlayerIndex: Int = 0,
    override val winnerIndices: List<Int> = emptyList(),
) : GameState {

    /** The checkout value the active player is currently attempting. */
    val currentTarget: Int
        get() = targets[currentTargetIndex.coerceIn(0, targets.lastIndex)]

    /**
     * Record the active player's attempt: [hit] with [darts] used (1..3) on a
     * hit; [darts] is ignored on a miss. No-op once the game is finished.
     */
    fun applyAttempt(hit: Boolean, darts: Int): CheckoutTrainerState {
        if (isFinished) return this
        if (hit) {
            require(darts in 1..CHECKOUT_TRAINER_MAX_DARTS) {
                "darts must be in 1..$CHECKOUT_TRAINER_MAX_DARTS on a hit"
            }
        }
        val me = perPlayer[currentPlayerIndex]
        // On a miss the darts used are not tracked, so normalise to 0.
        val attempt = CheckoutAttempt(hit = hit, darts = if (hit) darts else 0)
        val updated = perPlayer.toMutableList().also {
            it[currentPlayerIndex] = me.copy(attempts = me.attempts + attempt)
        }
        return advanceFrom(updated, currentPlayerIndex)
    }

    /**
     * Advance the round-robin from [fromIndex]. Once the last seat of the final
     * target's round has thrown, the game ends and the highest hit count(s) win
     * (tie-break: fewest darts on hits).
     */
    private fun advanceFrom(
        updated: List<CheckoutPlayerState>,
        fromIndex: Int,
    ): CheckoutTrainerState {
        val size = players.size
        val nextInRound = fromIndex + 1
        if (nextInRound < size) {
            // Still seats to attempt the current target.
            return copy(
                perPlayer = updated,
                currentPlayerIndex = nextInRound,
                currentTargetIndex = currentTargetIndex,
            )
        }

        // Target complete (last seat just threw): advance the ladder.
        val newTargetIndex = currentTargetIndex + 1
        if (newTargetIndex >= targets.size) {
            // Final target done -> finish with the most hits (ties by darts).
            return copy(
                perPlayer = updated,
                currentPlayerIndex = 0,
                currentTargetIndex = newTargetIndex,
                winnerIndices = winnersOf(updated),
            )
        }

        // Next target: first seat attempts it.
        return copy(
            perPlayer = updated,
            currentPlayerIndex = 0,
            currentTargetIndex = newTargetIndex,
        )
    }

    fun undoLast(): CheckoutTrainerState {
        // Full-replay style undo: rebuild the game from all recorded attempts
        // minus the most recent one. This stays correct across target boundaries
        // and the finish, where forward state is otherwise hard to invert.
        val totalAttempts = perPlayer.sumOf { it.attempts.size }
        if (totalAttempts == 0) return this

        val lastActor = lastActorIndex() ?: return this
        val keptAttempts = perPlayer.mapIndexed { idx, ps ->
            if (idx == lastActor) ps.attempts.dropLast(1) else ps.attempts
        }
        val seq = chronological(keptAttempts)
        val freshPer = perPlayer.map { it.copy(attempts = emptyList()) }
        return replayFrom(freshPer, seq)
    }

    /** The seat index whose attempt most recently completed. */
    private fun lastActorIndex(): Int? {
        // Seats act in target order; within a target, lower indices first. The
        // most-recent actor is the seat with the highest attempts.size, breaking
        // ties toward the higher seat index (acted later within the round).
        var best = -1
        var bestCount = -1
        perPlayer.forEachIndexed { idx, ps ->
            if (ps.attempts.isEmpty()) return@forEachIndexed
            if (ps.attempts.size >= bestCount) {
                best = idx
                bestCount = ps.attempts.size
            }
        }
        return best.takeIf { it >= 0 }
    }

    private fun chronological(attemptLists: List<List<CheckoutAttempt>>): List<CheckoutAttempt> {
        // Flatten round by round, seat order within a round. Every seat takes
        // exactly one attempt per target, so a seat's k-th attempt is round k.
        val maxRounds = attemptLists.maxOfOrNull { it.size } ?: 0
        val seq = mutableListOf<CheckoutAttempt>()
        for (round in 0 until maxRounds) {
            attemptLists.forEach { attempts ->
                attempts.getOrNull(round)?.let { seq.add(it) }
            }
        }
        return seq
    }

    /** Replay a chronological list of attempts onto a fresh state. */
    private fun replayFrom(
        freshPer: List<CheckoutPlayerState>,
        seq: List<CheckoutAttempt>,
    ): CheckoutTrainerState {
        var state = copy(
            perPlayer = freshPer,
            currentTargetIndex = 0,
            currentPlayerIndex = 0,
            winnerIndices = emptyList(),
        )
        for (a in seq) {
            state = state.applyAttempt(a.hit, a.darts)
        }
        return state
    }

    private fun winnersOf(per: List<CheckoutPlayerState>): List<Int> {
        // Most hits wins; tie-break is fewest darts used on those hits. Any
        // players still level on both are all recorded as winners.
        val maxHits = per.maxOf { it.hits }
        val topHitters = per.withIndex().filter { it.value.hits == maxHits }
        val fewestDarts = topHitters.minOf { it.value.dartsOnHits }
        return topHitters
            .filter { it.value.dartsOnHits == fewestDarts }
            .map { it.index }
    }

    companion object {
        fun new(
            players: List<GamePlayer>,
            targets: List<Int> = DEFAULT_TARGETS,
        ): CheckoutTrainerState {
            require(players.isNotEmpty()) { "need at least one player" }
            require(targets.isNotEmpty()) { "need at least one target" }
            return CheckoutTrainerState(
                players = players,
                perPlayer = players.map { CheckoutPlayerState(it) },
                targets = targets,
            )
        }
    }
}
