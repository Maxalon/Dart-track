package com.dartrack.data

import com.dartrack.model.GameMode

/**
 * Pure, Android-free **single-elimination (knockout) bracket** core, the second
 * tournament [TournamentFormat] alongside the round-robin league in Tournament.kt.
 * A bracket is still a tree of 1v1 [TournamentMatch]es, but here a single loss
 * ends a competitor's run: the winner of each match is promoted up the tree until
 * one competitor survives the final.
 *
 * Like the round-robin core this layer is entirely pure — no clock, no filesystem,
 * no RNG. The whole tree is derived deterministically from the competitor count by
 * the standard seeded-bracket construction, match ids are derived from round+index
 * (never a UUID), and advancement is a pure function of the recorded winners. It
 * reuses the same [TournamentState] / [TournamentMatch] shapes and the same
 * game-glue ([buildMatchGameRecord]) as the league, so a bracket match is played
 * and reconciled exactly like a league fixture — only the wiring between matches
 * (the feeder links) is new. Mirrors the style/comment density of Tournament.kt.
 *
 * ## How the tree is built (seeding, byes, feeder links)
 *  - The competitor count is padded up to the next power of two `P` with phantom
 *    "bye" slots (indices `>= count`). The number of byes is exactly `P - count`.
 *  - Round 1 is paired by **standard bracket seeding** ([seedOrder]) so the two
 *    strongest seeds (0 and 1) can only meet in the final: slot positions hold
 *    seeds `[0, P-1, .., 1, ..]`, paired adjacently `(slot0,slot1),(slot2,slot3)…`.
 *  - A round-1 pairing where one side is a bye is NOT played; instead the real
 *    competitor is **pre-placed** straight into the round-2 slot that pairing
 *    feeds (a bye is a free pass). Only pairings with two real competitors become
 *    actual round-1 matches.
 *  - Every later-round match starts empty ([TBD] vs [TBD]); the two matches that
 *    feed it carry [TournamentMatch.feedsMatchId] pointing at it, one with
 *    [TournamentMatch.feedsSlot] 0 (home) and the other 1 (away). When a feeding
 *    match is decided, [advanceBracket] drops the winner into that slot.
 *  - The single last-round match is the **final**; it feeds nobody
 *    ([TournamentMatch.feedsMatchId] == null).
 */

/**
 * The standard single-elimination **seed order** for a bracket of size [size] (a
 * power of two): the seed that occupies each slot from top to bottom, such that
 * the pairing of adjacent slots `(0,1),(2,3),…` is the canonical seeded first
 * round (1-vs-last, etc.) and higher seeds only meet later. Built recursively: a
 * bracket of size `2n` interleaves the size-`n` order `s` with its complements
 * `2n-1-s`, which is exactly how tennis/cup draws are laid out.
 *
 * Examples: `size 2 -> [0,1]`, `size 4 -> [0,3,1,2]`, `size 8 -> [0,7,3,4,1,6,2,5]`.
 * Requires [size] to be a power of two `>= 1`.
 */
internal fun seedOrder(size: Int): List<Int> {
    require(size >= 1 && (size and (size - 1)) == 0) { "bracket size must be a power of two, got $size" }
    var order = listOf(0)
    while (order.size < size) {
        val n = order.size * 2
        val next = ArrayList<Int>(n)
        for (s in order) {
            next.add(s)          // the seed
            next.add(n - 1 - s)  // its mirror, so 1 meets the bottom half, etc.
        }
        order = next
    }
    return order
}

/** The smallest power of two `>= n` (for `n >= 1`). */
private fun nextPowerOfTwo(n: Int): Int {
    var p = 1
    while (p < n) p = p shl 1
    return p
}

/**
 * Build the full knockout fixture tree for [competitorCount] competitors (indices
 * `0 until competitorCount`), padded to the next power of two `P` with byes.
 *
 * The returned list is ordered round-by-round (round 1 first) then by bracket
 * position within the round. Round-1 matches with two real competitors carry both
 * indices set; a round-1 pairing involving a bye produces NO match (the real
 * competitor is pre-placed into the round-2 slot it would have fed). Every match
 * from round 2 onward starts [TBD] vs [TBD] and is filled by [advanceBracket] as
 * its feeders resolve. Match ids are stable: `"se-r<round>-<index>"` (1-based
 * round, 0-based index within the round). The single final (last round) feeds
 * nobody. Requires `count >= 2`.
 */
fun singleEliminationMatches(competitorCount: Int): List<TournamentMatch> {
    require(competitorCount >= 2) {
        "a single-elimination bracket needs at least 2 competitors, got $competitorCount"
    }
    val p = nextPowerOfTwo(competitorCount)
    val totalRounds = Integer.numberOfTrailingZeros(p) // log2(P): rounds in the bracket
    // Seed -> competitor index (or TBD for a padded bye slot, seed >= count).
    val slots = seedOrder(p).map { seed -> if (seed < competitorCount) seed else TBD }

    // Build from the LAST round (the final) down to round 1 so each round can
    // reference the already-created match ids it feeds. We assemble per round then
    // reverse into the natural round-1-first order at the end.
    val byRoundDescending = ArrayList<List<TournamentMatch>>(totalRounds)

    // Round `totalRounds` is the final: one match, no feeder.
    var laterRound = listOf(
        TournamentMatch(
            id = matchId(totalRounds, 0),
            round = totalRounds,
            homeIndex = TBD,
            awayIndex = TBD,
        ),
    )
    byRoundDescending.add(laterRound)

    // Each earlier round has twice as many matches as the one it feeds; match `k`
    // in round r feeds match `k / 2` in round r+1, into slot `k % 2` (0=home,1=away).
    for (round in totalRounds - 1 downTo 1) {
        val count = 1 shl (totalRounds - round) // 2^(totalRounds-round) matches this round
        val thisRound = ArrayList<TournamentMatch>(count)
        for (k in 0 until count) {
            val feeds = laterRound[k / 2]
            thisRound.add(
                TournamentMatch(
                    id = matchId(round, k),
                    round = round,
                    homeIndex = TBD,
                    awayIndex = TBD,
                    feedsMatchId = feeds.id,
                    feedsSlot = k % 2,
                ),
            )
        }
        byRoundDescending.add(thisRound)
        laterRound = thisRound
    }

    val rounds = byRoundDescending.asReversed() // round 1 first
    val round1 = rounds[0].toMutableList()
    val round2 = if (totalRounds >= 2) rounds[1].toMutableList() else null

    // Seat round 1 from the seeded slots: pairing k is (slots[2k] vs slots[2k+1]).
    // Two real competitors -> a played-able match; a bye -> pre-place the real one
    // into the round-2 slot this pairing feeds, and emit no round-1 match.
    val resolvedRound1 = ArrayList<TournamentMatch>(round1.size)
    for (k in round1.indices) {
        val home = slots[2 * k]
        val away = slots[2 * k + 1]
        val match = round1[k]
        if (home != TBD && away != TBD) {
            resolvedRound1.add(match.copy(homeIndex = home, awayIndex = away))
        } else {
            // Exactly one side is real (a bracket never pads two byes into one
            // round-1 pairing for count >= P/2 + 1 ... and even if it did, the
            // fed slot would just stay TBD). Promote the real competitor now.
            val advancer = if (home != TBD) home else away
            if (advancer != TBD && round2 != null) {
                val target = round2.indexOfFirst { it.id == match.feedsMatchId }
                if (target >= 0) {
                    round2[target] = placeInSlot(round2[target], match.feedsSlot, advancer)
                }
            }
            // No round-1 match is emitted for a bye pairing.
        }
    }

    val out = ArrayList<TournamentMatch>()
    out.addAll(resolvedRound1)
    if (round2 != null) out.addAll(round2)
    for (r in 2 until rounds.size) out.addAll(rounds[r]) // rounds[2..] = round 3+
    return out
}

/** Stable bracket match id: `se-r<round>-<index>` (1-based round, 0-based index). */
private fun matchId(round: Int, index: Int): String = "se-r$round-$index"

/** Return [match] with competitor [value] placed into slot [slot] (0=home, 1=away). */
private fun placeInSlot(match: TournamentMatch, slot: Int, value: Int): TournamentMatch =
    if (slot == 0) match.copy(homeIndex = value) else match.copy(awayIndex = value)

/**
 * Build a fresh single-elimination [TournamentState] from [competitors] (at least
 * two required). The state is stamped [TournamentFormat.SINGLE_ELIMINATION] and its
 * matches come straight from [singleEliminationMatches], so any first-round byes
 * are already resolved into the round-2 slots they feed. The points fields are left
 * at their league defaults but are unused by a knockout (which ranks by round
 * reached, see [bracketStandings]). [createdAtEpochMs] is supplied by the caller.
 */
fun createSingleEliminationTournament(
    id: String,
    name: String,
    competitors: List<TournamentCompetitor>,
    mode: GameMode,
    createdAtEpochMs: Long = 0L,
): TournamentState {
    require(competitors.size >= 2) {
        "a single-elimination tournament needs at least 2 competitors, got ${competitors.size}"
    }
    return TournamentState(
        id = id,
        name = name,
        competitors = competitors,
        mode = mode,
        matches = singleEliminationMatches(competitors.size),
        createdAtEpochMs = createdAtEpochMs,
        format = TournamentFormat.SINGLE_ELIMINATION,
    )
}

/**
 * Record that bracket match [matchId] was won by [winnerIndex] and advance the
 * tree: the match is marked `played` with that winner and the optional [gameId],
 * AND — if it has a [TournamentMatch.feedsMatchId] — the winner is dropped into
 * that next match's [TournamentMatch.feedsSlot] (0=home, 1=away).
 *
 * [winnerIndex] must be one of the match's two CURRENT competitor indices (so you
 * cannot advance a match whose feeders haven't resolved, or name a competitor not
 * in it). A missing [matchId] is a no-op. The operation is idempotent-safe:
 * re-applying the same winner re-writes the same result and re-places the same
 * competitor into the same slot, leaving the state unchanged. Pure.
 */
fun advanceBracket(
    state: TournamentState,
    matchId: String,
    winnerIndex: Int,
    gameId: String?,
): TournamentState {
    val idx = state.matches.indexOfFirst { it.id == matchId }
    if (idx < 0) return state // unknown match -> no-op
    val match = state.matches[idx]
    require(winnerIndex == match.homeIndex || winnerIndex == match.awayIndex) {
        "winner $winnerIndex is not a competitor in match $matchId " +
            "(${match.homeIndex} vs ${match.awayIndex})"
    }

    val matches = state.matches.toMutableList()
    matches[idx] = match.copy(gameId = gameId, winnerIndex = winnerIndex, played = true)

    // Promote the winner into the match it feeds, if any.
    val feedsId = match.feedsMatchId
    if (feedsId != null) {
        val feedIdx = matches.indexOfFirst { it.id == feedsId }
        if (feedIdx >= 0) {
            matches[feedIdx] = placeInSlot(matches[feedIdx], match.feedsSlot, winnerIndex)
        }
    }
    return state.copy(matches = matches)
}

/**
 * The next bracket match ready to play: the first unplayed match whose BOTH
 * competitor slots are real (`!= TBD`), scanned by round then bracket order
 * (exactly the order [singleEliminationMatches] emits). Matches still awaiting a
 * feeder ([TBD] on either side) are skipped. Returns `null` when nothing is
 * currently playable (the bracket is finished, or every open match is still
 * waiting on a feeder). Pure.
 */
fun nextPlayableBracketMatch(state: TournamentState): TournamentMatch? =
    state.matches.firstOrNull { !it.played && it.homeIndex != TBD && it.awayIndex != TBD }

/**
 * The bracket champion: the [TournamentMatch.winnerIndex] of the final — the
 * single match that feeds nobody ([TournamentMatch.feedsMatchId] == null) — once it
 * has been played. Returns `null` while the final is still outstanding (or, in the
 * degenerate empty bracket, absent). Pure.
 */
fun bracketChampion(state: TournamentState): Int? {
    val final = state.matches.firstOrNull { it.feedsMatchId == null } ?: return null
    return if (final.played) final.winnerIndex else null
}

/**
 * One row describing how far a competitor advanced in a knockout. [roundReached]
 * is the round number of that competitor's LAST win (0 if they never won a match —
 * a first-round loser, or someone still waiting to play); [eliminated] is true once
 * they have lost a played match. The champion is the one competitor who is NOT
 * eliminated once the final is decided, and whose [roundReached] is the final's
 * round. Indices point back into [TournamentState.competitors].
 */
data class BracketStanding(
    val competitorIndex: Int,
    val roundReached: Int,
    val eliminated: Boolean,
)

/**
 * How far each competitor got in the knockout [state], one [BracketStanding] per
 * competitor in competitor-index order. For each competitor we scan the played
 * matches they took part in: a win bumps [BracketStanding.roundReached] to that
 * match's round (rounds are monotonic up the tree, so the last win is the deepest);
 * a loss flags [BracketStanding.eliminated]. A competitor who has not yet lost and
 * has no recorded win sits at round 0, not eliminated. Keeping it this simple makes
 * "champion = deepest roundReached, not eliminated" fall straight out. Pure.
 */
fun bracketStandings(state: TournamentState): List<BracketStanding> {
    val n = state.competitors.size
    val roundReached = IntArray(n)          // round of each competitor's last win
    val eliminated = BooleanArray(n)        // has lost a played match

    for (m in state.matches) {
        if (!m.played) continue
        val w = m.winnerIndex
        // Mark the loser (the other real side of a decided match) as eliminated.
        if (w != null) {
            val loser = if (w == m.homeIndex) m.awayIndex else m.homeIndex
            if (loser != TBD) eliminated[loser] = true
            if (w != TBD && m.round > roundReached[w]) roundReached[w] = m.round
        }
    }

    return (0 until n).map { idx ->
        BracketStanding(
            competitorIndex = idx,
            roundReached = roundReached[idx],
            eliminated = eliminated[idx],
        )
    }
}

/**
 * Fold a finished [game] back into the knockout [state] as the result of match
 * [matchId], the bracket counterpart of [reconcileMatch]. It records nothing
 * (state returned unchanged) unless the match exists, is not already
 * [TournamentMatch.played], is the game's linked match (`game.id == match.gameId`),
 * and the game is finished — and, crucially, the game produced a single winning
 * seat.
 *
 * The game reports winners as SEAT indices into its `[home, away]` player list (as
 * laid out by [buildMatchGameRecord]); we map that seat to a competitor index and
 * call [advanceBracket]:
 *  - `winnerIndices == [0]` (home seat) -> `match.homeIndex`;
 *  - `winnerIndices == [1]` (away seat) -> `match.awayIndex`.
 *
 * A KNOCKOUT DRAW IS NOT DECIDABLE: a knockout match must yield a winner to know
 * who advances, so any non-singleton winner set — a tie (`[0, 1]`), both/neither,
 * or an empty set — is treated as NOT YET DECIDED and the match is left UNPLAYED
 * (no advancement). Modes that can legitimately draw (e.g. Golf) must therefore be
 * played to a decisive finish before a bracket match progresses. Pure.
 */
fun reconcileBracketMatch(
    state: TournamentState,
    matchId: String,
    game: GameRecord,
): TournamentState {
    val match = state.matches.firstOrNull { it.id == matchId } ?: return state
    if (match.played) return state
    if (game.id != match.gameId) return state
    if (!game.isFinished) return state

    val winnerIndex = when (game.state.winnerIndices) {
        listOf(0) -> match.homeIndex
        listOf(1) -> match.awayIndex
        else -> return state // tie / both / neither -> knockout has no winner yet -> leave unplayed
    }
    return advanceBracket(state, matchId, winnerIndex, game.id)
}

/**
 * Reconcile EVERY currently-playable, linked, finished, not-yet-played bracket
 * match against [games] in one pass, the bracket counterpart of [syncedWith]. A
 * match is folded when it has a non-null [TournamentMatch.gameId] whose linked game
 * is present in [games]; [reconcileBracketMatch] guards the rest (already-played,
 * unfinished, or undecidable-draw matches change nothing). Because advancing one
 * match can newly seat a later match, the fold runs in the bracket's natural
 * round-then-order sequence so an earlier-round result is visible to the matches it
 * feeds within the same pass. Idempotent — re-running over the same finished games
 * leaves the state unchanged. Pure.
 */
fun TournamentState.syncedBracketWith(games: List<GameRecord>): TournamentState {
    val byId = games.associateBy { it.id }
    // Fold over match IDS (stable) rather than the live list: advancing a match may
    // rewrite later entries, and we always re-find the match by id inside the helper.
    val ids = matches.map { it.id }
    return ids.fold(this) { acc, id ->
        val match = acc.matches.firstOrNull { it.id == id } ?: return@fold acc
        val gameId = match.gameId ?: return@fold acc
        val game = byId[gameId] ?: return@fold acc
        reconcileBracketMatch(acc, id, game)
    }
}
