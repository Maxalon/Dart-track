package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.bot.BotLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pure, Android-free **round-robin tournament** core. A tournament is a
 * meta-layer that sits ON TOP of individual games: it owns the list of
 * competitors, the fixture schedule, the per-match results, and the derived
 * standings table. It deliberately knows nothing about how a single match is
 * actually played — every [TournamentMatch] is a 1v1 that will (later) be run
 * as a real [com.dartrack.model.GameState], and this layer only records who won.
 *
 * Everything here is pure (no clock, no filesystem, no global RNG) so it is
 * trivially unit-testable: schedules are built by the deterministic circle
 * method, ids are derived from indices/rounds (never a UUID), and standings are
 * a pure function of the recorded results. Persistence reuses the project's
 * [GameJson] format and the same versioned-store pattern as [GameStore].
 *
 * Mirrors the style and comment density of Records.kt / PlayerStats.kt.
 */

/**
 * One entrant in a tournament. A competitor is EITHER a registered human
 * ([playerId] set to their stable registry id, [isBot] = false) OR a CPU
 * opponent ([isBot] = true, [botLevel] set, [playerId] = null). The two shapes
 * are mutually exclusive; [fillWithCpus] only ever produces the CPU shape and
 * the human shape is supplied by the caller.
 *
 * [toGamePlayer] maps the competitor onto a game seat ([GamePlayer]) so a match
 * can be handed to the engine: a human keeps their registry id, while a CPU gets
 * a STABLE synthetic id of the form `"bot:<index>"` (deterministic, no UUID in
 * the pure layer) plus its [GamePlayer.isBot] / [GamePlayer.botLevel] flags. The
 * [index] is the competitor's position in [TournamentState.competitors], which is
 * stable for the life of the tournament, so the same CPU always maps to the same
 * seat id across matches.
 */
@Serializable
data class TournamentCompetitor(
    val name: String,
    val playerId: String? = null,
    val isBot: Boolean = false,
    val botLevel: BotLevel? = null,
) {
    /**
     * Project this competitor onto a game seat. A human seat carries their
     * registry id (or an empty id if somehow absent, matching how a name-only
     * seat reads back as a human). A CPU seat is flagged [GamePlayer.isBot] with
     * its [botLevel] and a stable `"bot:<index>"` id derived from its position so
     * the mapping is reproducible without any UUID.
     */
    fun toGamePlayer(index: Int): GamePlayer =
        if (isBot) {
            GamePlayer(name = name, id = "bot:$index", isBot = true, botLevel = botLevel)
        } else {
            GamePlayer(name = name, id = playerId.orEmpty())
        }
}

/**
 * A single fixture between [TournamentState.competitors]`[homeIndex]` and
 * `[awayIndex]`. The pairing is fixed at schedule time; everything else is the
 * mutable result. Once [played] is true the match carries a [winnerIndex] — a
 * COMPETITOR index (either [homeIndex] or [awayIndex]), or `null` for a draw —
 * and optionally the [gameId] of the real game that decided it.
 *
 * [id] is a stable, schedule-derived string ("m-<round>-<i>-<j>") so a match can
 * be referenced across [recordResult] calls without depending on list position.
 */
@Serializable
data class TournamentMatch(
    val id: String,
    val round: Int,
    val homeIndex: Int,
    val awayIndex: Int,
    val gameId: String? = null,
    val winnerIndex: Int? = null,
    val played: Boolean = false,
)

/**
 * The complete state of a round-robin tournament. [competitors] is fixed once
 * created (indices into it are used everywhere); [matches] is the full fixture
 * list with results filled in by [recordResult]. [pointsForWin] / [pointsForDraw]
 * drive the [standings] points total (a loss is always worth 0). [createdAtEpochMs]
 * is supplied by the caller (the pure layer never reads the clock).
 */
@Serializable
data class TournamentState(
    val id: String,
    val name: String,
    val competitors: List<TournamentCompetitor>,
    val mode: GameMode,
    val matches: List<TournamentMatch>,
    val pointsForWin: Int = 3,
    val pointsForDraw: Int = 1,
    val createdAtEpochMs: Long = 0L,
)

/**
 * The classic **circle method** round-robin schedule for [n] competitors.
 *
 * Returns the rounds; each round is a list of `(i, j)` competitor-index pairs to
 * be played that round. Guarantees: every distinct unordered pair appears exactly
 * once across all rounds, and no competitor appears more than once within a round.
 *
 * How the circle method works: fix competitor 0 in place and arrange the rest
 * around a circle. Each round pairs the two ends of the circle inward
 * (`0↔last, 1↔last-1, …`); then everyone except the fixed competitor rotates one
 * seat clockwise and the next round is read off. After `m-1` rotations of an
 * `m`-element circle, every pairing has occurred exactly once.
 *
 * Odd [n] is handled by adding a phantom "bye" competitor (index `n`): the real
 * competitor paired with the phantom simply sits out that round. So:
 *  - **even n** ⇒ `n-1` rounds, no byes (everyone plays every round);
 *  - **odd n**  ⇒ `n` rounds, exactly one competitor on a bye each round.
 *
 * Requires `n >= 2`.
 */
fun roundRobinSchedule(n: Int): List<List<Pair<Int, Int>>> {
    require(n >= 2) { "round-robin needs at least 2 competitors, got $n" }

    // Pad to an even count with a phantom "bye" seat for odd n. A pairing that
    // includes the phantom (index == n) is dropped, leaving that competitor idle.
    val even = n % 2 == 0
    val size = if (even) n else n + 1
    val bye = n // phantom index used only for odd n
    val rounds = size - 1
    val half = size / 2

    // Rotating ring of all seats except the fixed seat (kept at position 0).
    val ring = ArrayList<Int>(size - 1)
    for (s in 1 until size) ring.add(s)

    val schedule = ArrayList<List<Pair<Int, Int>>>(rounds)
    repeat(rounds) {
        val pairs = ArrayList<Pair<Int, Int>>(half)
        // Pair the fixed seat (0) with the head of the ring.
        addPair(pairs, 0, ring[0], bye)
        // Then fold the rest of the ring inward: 2nd vs last, 3rd vs 2nd-last, …
        for (k in 1 until half) {
            addPair(pairs, ring[k], ring[size - 1 - k], bye)
        }
        schedule.add(pairs)
        // Rotate the ring one step clockwise (the fixed seat never moves).
        ring.add(0, ring.removeAt(ring.size - 1))
    }
    return schedule
}

/**
 * Append the pairing `(a, b)` to [pairs] unless it involves the phantom [bye]
 * seat (an odd-n bye), in which case the real competitor just sits out. The pair
 * is stored low-index-first so match ids are canonical and stable.
 */
private fun addPair(pairs: MutableList<Pair<Int, Int>>, a: Int, b: Int, bye: Int) {
    if (a == bye || b == bye) return
    pairs.add(if (a < b) a to b else b to a)
}

/**
 * Build a fresh [TournamentState] from [competitors] (at least two required),
 * scheduling every pairing once via [roundRobinSchedule]. Each fixture gets a
 * stable id "m-<round>-<i>-<j>" (round is 0-based; i < j are the competitor
 * indices), so ids are reproducible and independent of list order.
 */
fun createTournament(
    id: String,
    name: String,
    competitors: List<TournamentCompetitor>,
    mode: GameMode,
    pointsForWin: Int = 3,
    pointsForDraw: Int = 1,
    createdAtEpochMs: Long = 0L,
): TournamentState {
    require(competitors.size >= 2) {
        "a tournament needs at least 2 competitors, got ${competitors.size}"
    }
    val schedule = roundRobinSchedule(competitors.size)
    val matches = ArrayList<TournamentMatch>()
    schedule.forEachIndexed { round, pairs ->
        for ((i, j) in pairs) {
            matches.add(
                TournamentMatch(
                    id = "m-$round-$i-$j",
                    round = round,
                    homeIndex = i,
                    awayIndex = j,
                ),
            )
        }
    }
    return TournamentState(
        id = id,
        name = name,
        competitors = competitors,
        mode = mode,
        matches = matches,
        pointsForWin = pointsForWin,
        pointsForDraw = pointsForDraw,
        createdAtEpochMs = createdAtEpochMs,
    )
}

/**
 * Pad [humans] up to [targetSize] with CPU competitors so a tournament can be run
 * with a full bracket even when not enough people signed up. The added CPUs are
 * named "CPU 1 (Hard)", "CPU 2 (Easy)", … — numbered from 1 and cycling through
 * [levels] in order (CPU k uses `levels[(k-1) % levels.size]`). The human list is
 * returned UNCHANGED (same instances, same order) when it already has at least
 * [targetSize] entries, and also unchanged when [levels] is empty (we cannot
 * invent a CPU without a level). Pure and deterministic — no UUIDs, no RNG.
 */
fun fillWithCpus(
    humans: List<TournamentCompetitor>,
    targetSize: Int,
    levels: List<BotLevel>,
): List<TournamentCompetitor> {
    if (humans.size >= targetSize || levels.isEmpty()) return humans
    val out = ArrayList<TournamentCompetitor>(targetSize)
    out.addAll(humans)
    var cpuNumber = 1
    while (out.size < targetSize) {
        val level = levels[(cpuNumber - 1) % levels.size]
        out.add(
            TournamentCompetitor(
                name = "CPU $cpuNumber (${level.label})",
                isBot = true,
                botLevel = level,
            ),
        )
        cpuNumber++
    }
    return out
}

/**
 * One row of the standings table for a single competitor. [points] is the
 * derived total (`won*pointsForWin + drawn*pointsForDraw`); only [played] matches
 * contribute to any of these counts. [competitorIndex] indexes back into
 * [TournamentState.competitors].
 */
data class Standing(
    val competitorIndex: Int,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val points: Int,
)

/**
 * The full standings table for [state], one [Standing] per competitor, computed
 * only from matches that have been [played].
 *
 * Scoring per row: a win is [TournamentState.pointsForWin], a draw is
 * [TournamentState.pointsForDraw], a loss is 0. A drawn match (winnerIndex ==
 * null) counts a "drawn" for BOTH competitors; a decided match counts a win for
 * the winner and a loss for the other.
 *
 * Sort order (each level breaks ties in the previous one):
 *  1. points descending;
 *  2. wins descending;
 *  3. **head-to-head** points descending — among the currently-tied competitors,
 *     replay only the played matches BETWEEN them and compare the points each
 *     earned in that mini-table (the standard way to separate level pegs);
 *  4. fewer losses;
 *  5. competitor name, case-insensitive ascending (a fully deterministic final
 *     tiebreak).
 */
fun standings(state: TournamentState): List<Standing> {
    val n = state.competitors.size
    val won = IntArray(n)
    val drawn = IntArray(n)
    val lost = IntArray(n)

    for (m in state.matches) {
        if (!m.played) continue
        when (m.winnerIndex) {
            null -> {
                drawn[m.homeIndex]++
                drawn[m.awayIndex]++
            }
            m.homeIndex -> {
                won[m.homeIndex]++
                lost[m.awayIndex]++
            }
            m.awayIndex -> {
                won[m.awayIndex]++
                lost[m.homeIndex]++
            }
        }
    }

    val rows = (0 until n).map { idx ->
        val w = won[idx]; val d = drawn[idx]; val l = lost[idx]
        Standing(
            competitorIndex = idx,
            played = w + d + l,
            won = w,
            drawn = d,
            lost = l,
            points = w * state.pointsForWin + d * state.pointsForDraw,
        )
    }

    val byName = { idx: Int -> state.competitors[idx].name.lowercase() }
    return rows.sortedWith(
        compareByDescending<Standing> { it.points }
            .thenByDescending { it.won }
            // Head-to-head: only meaningful between competitors otherwise tied on
            // points+wins; computed against exactly that tied group.
            .thenByDescending { row -> headToHeadPoints(state, row.competitorIndex, rows) }
            .thenBy { it.lost }
            .thenBy { byName(it.competitorIndex) },
    )
}

/**
 * Head-to-head points for competitor [index] within its tie group. The group is
 * everyone in [allRows] sharing this competitor's (points, wins) pair — i.e. the
 * competitors a head-to-head tiebreak actually needs to separate. We then replay
 * only the played matches whose BOTH competitors are in that group and total the
 * points [index] earned in them (win/draw/loss scored with the tournament's point
 * values). A singleton group (no one tied) trivially scores 0, leaving the order
 * to fall through to the next tiebreak.
 */
private fun headToHeadPoints(
    state: TournamentState,
    index: Int,
    allRows: List<Standing>,
): Int {
    val me = allRows.first { it.competitorIndex == index }
    val group = allRows
        .filter { it.points == me.points && it.won == me.won }
        .map { it.competitorIndex }
        .toSet()
    if (group.size <= 1) return 0

    var pts = 0
    for (m in state.matches) {
        if (!m.played) continue
        if (m.homeIndex !in group || m.awayIndex !in group) continue
        if (m.homeIndex != index && m.awayIndex != index) continue
        pts += when (m.winnerIndex) {
            null -> state.pointsForDraw
            index -> state.pointsForWin
            else -> 0
        }
    }
    return pts
}

/**
 * Record the outcome of one match. Marks the match with [winnerIndex] (a
 * competitor index, or `null` for a draw) and the optional [gameId], setting
 * [TournamentMatch.played]. Safe to call repeatedly (it just overwrites the
 * result, so re-recording the same outcome is idempotent). A missing [matchId]
 * is a no-op (the state is returned unchanged). Requires that a non-null
 * [winnerIndex] is one of that match's two competitors.
 */
fun recordResult(
    state: TournamentState,
    matchId: String,
    winnerIndex: Int?,
    gameId: String?,
): TournamentState {
    val idx = state.matches.indexOfFirst { it.id == matchId }
    if (idx < 0) return state // unknown match -> no-op
    val match = state.matches[idx]
    require(winnerIndex == null || winnerIndex == match.homeIndex || winnerIndex == match.awayIndex) {
        "winner $winnerIndex is not a competitor in match $matchId " +
            "(${match.homeIndex} vs ${match.awayIndex})"
    }
    val updated = match.copy(
        gameId = gameId,
        winnerIndex = winnerIndex,
        played = true,
    )
    val newMatches = state.matches.toMutableList().also { it[idx] = updated }
    return state.copy(matches = newMatches)
}

/**
 * The next match still to be played, scanning in fixture order (earliest round
 * first, then schedule order within the round — which is exactly the order
 * [createTournament] emits matches). Returns `null` when every match has been
 * played.
 */
fun nextUnplayedMatch(state: TournamentState): TournamentMatch? =
    state.matches.firstOrNull { !it.played }

/** Whether every fixture in [state] has been played. Empty schedules are complete. */
fun isComplete(state: TournamentState): Boolean = state.matches.all { it.played }

/**
 * The champion competitor indices: those tied at the very top of [standings]
 * once the tournament [isComplete]. Returns a single index for an outright winner,
 * multiple for co-champions that even the head-to-head / name tiebreaks could not
 * separate (genuinely identical records), and an empty list while any match is
 * still outstanding.
 *
 * "Tied at the top" means sharing the leader's (points, wins, losses) — the
 * record-based tiebreaks; co-champions are reported in standings order. Name is a
 * presentation-only final tiebreak and never demotes a competitor out of a true
 * record tie for the title.
 */
fun champions(state: TournamentState): List<Int> {
    if (!isComplete(state)) return emptyList()
    val table = standings(state)
    val top = table.firstOrNull() ?: return emptyList()
    return table
        .filter { it.points == top.points && it.won == top.won && it.lost == top.lost }
        .map { it.competitorIndex }
}

/**
 * Versioned on-disk envelope for the tournaments store, mirroring [GameStore].
 * Persisting inside an envelope with an explicit [schemaVersion] lets a future
 * non-migratable schema break discard old data cleanly on load instead of
 * crashing. Reuses the shared [GameJson] format.
 */
@Serializable
data class TournamentStore(
    val schemaVersion: Int = 0,
    val tournaments: List<TournamentState> = emptyList(),
) {
    companion object {
        /** Bump whenever a non-migratable tournament schema break lands. */
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Pure, Android-free decode of the tournaments store from raw file text. Returns
 * the tournaments to surface. Returns an empty list when the text is blank, fails
 * to parse, or comes from an older schema (the WIPE path). Never throws.
 */
fun decodeTournamentStore(text: String): List<TournamentState> {
    if (text.isBlank()) return emptyList()
    val store = runCatching {
        GameJson.format.decodeFromString(TournamentStore.serializer(), text)
    }.getOrNull() ?: return emptyList()
    if (store.schemaVersion < TournamentStore.SCHEMA_VERSION) return emptyList()
    return store.tournaments
}

/** Pure encode of the current-version tournaments store to file text. */
fun encodeTournamentStore(tournaments: List<TournamentState>): String =
    GameJson.format.encodeToString(
        TournamentStore.serializer(),
        TournamentStore(schemaVersion = TournamentStore.SCHEMA_VERSION, tournaments = tournaments),
    )
