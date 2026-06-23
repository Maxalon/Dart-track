package com.dartrack.data

import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BaseballState
import com.dartrack.model.BobsTwentySevenState
import com.dartrack.model.Catch40State
import com.dartrack.model.CheckoutTrainerState
import com.dartrack.model.CountUpState
import com.dartrack.model.CricketState
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.GameState
import com.dartrack.model.GolfState
import com.dartrack.model.GotchaState
import com.dartrack.model.HalfItState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01State

/**
 * Pure, Android-free glue between the round-robin tournament core (Tournament.kt)
 * and the per-mode game engine. A [TournamentMatch] is just a recorded 1v1; this
 * layer turns one into a real, playable [GameRecord], and folds finished games'
 * outcomes back into the tournament's results.
 *
 * Everything here is pure (no clock, no filesystem, no RNG): the caller supplies
 * ids and timestamps, seats come from [TournamentCompetitor.toGamePlayer] (which
 * derives stable ids from competitor indices), and the default per-mode states
 * are the engines' own `.new(...)` factories. This keeps the seam unit-testable
 * and mirrors the comment density of GameRecord.kt / Tournament.kt.
 */

/**
 * The default starting [GameState] for a tournament match in [mode], seated with
 * [players] (a tournament match is always exactly the two competitors). Each mode
 * is created via its own `.new(...)` factory with sensible tournament defaults:
 *  - **X01** -> 501, double-out (the standard tournament leg);
 *  - **Cricket** -> standard scoring (not cut-throat);
 *  - **Gotcha** -> race to 301;
 *  - every other mode -> its plain `.new(players)` (no extra knobs to set).
 *
 * The `when` is exhaustive over all 12 [GameMode]s (no `else`) so adding a mode
 * is a compile error here until it is wired up. Pure.
 */
fun defaultStateForMode(mode: GameMode, players: List<GamePlayer>): GameState = when (mode) {
    GameMode.X01 -> X01State.new(players, startScore = 501, doubleOut = true)
    GameMode.CRICKET -> CricketState.new(players, cutThroat = false)
    GameMode.HALF_IT -> HalfItState.new(players)
    GameMode.AROUND_CLOCK -> AroundTheClockState.new(players)
    GameMode.BOBS_27 -> BobsTwentySevenState.new(players)
    GameMode.SHANGHAI -> ShanghaiState.new(players)
    GameMode.CATCH_40 -> Catch40State.new(players)
    GameMode.COUNT_UP -> CountUpState.new(players)
    GameMode.CHECKOUT_TRAINER -> CheckoutTrainerState.new(players)
    GameMode.BASEBALL -> BaseballState.new(players)
    GameMode.GOLF -> GolfState.new(players)
    GameMode.GOTCHA -> GotchaState.new(players, target = 301)
}

/**
 * Build the playable [GameRecord] for [match] within [state]. Seat 0 is the home
 * competitor (`state.competitors[match.homeIndex]`) and seat 1 is the away
 * competitor, each projected with [TournamentCompetitor.toGamePlayer] so humans
 * keep their registry id and CPUs get their stable `"bot:<index>"` seat. The
 * record carries the supplied [gameId] (so [reconcileMatch] can later link the
 * game back to this match) and the same [nowMs] for both created/updated stamps.
 * Pure — no clock, no id generation.
 */
fun buildMatchGameRecord(
    state: TournamentState,
    match: TournamentMatch,
    gameId: String,
    nowMs: Long,
): GameRecord {
    val home = state.competitors[match.homeIndex].toGamePlayer(match.homeIndex)
    val away = state.competitors[match.awayIndex].toGamePlayer(match.awayIndex)
    return GameRecord(
        id = gameId,
        mode = state.mode,
        createdAtEpochMs = nowMs,
        updatedAtEpochMs = nowMs,
        state = defaultStateForMode(state.mode, listOf(home, away)),
    )
}

/**
 * Fold a finished [game] back into [state] as the result of match [matchId].
 *
 * This only records anything when ALL of the following hold, otherwise [state] is
 * returned unchanged (so calling it on an in-progress game, a mismatched game, or
 * an already-recorded match is a safe no-op):
 *  - the match exists and isn't already [TournamentMatch.played];
 *  - the match is actually linked to this game (`game.id == match.gameId`);
 *  - the game is finished.
 *
 * The game's winner is reported as SEAT indices into its two-seat player list,
 * which [buildMatchGameRecord] laid out as `[home, away]`. We map that seat back
 * to a COMPETITOR index for [recordResult]:
 *  - `winnerIndices == [0]` (home seat) -> `match.homeIndex`;
 *  - `winnerIndices == [1]` (away seat) -> `match.awayIndex`;
 *  - anything else — a tie (`[0, 1]`), both, or an empty/unexpected set — is
 *    recorded as a DRAW (`winnerIndex = null`).
 *
 * Pure.
 */
fun reconcileMatch(state: TournamentState, matchId: String, game: GameRecord): TournamentState {
    val match = state.matches.firstOrNull { it.id == matchId } ?: return state
    if (match.played) return state
    if (game.id != match.gameId) return state
    if (!game.isFinished) return state

    val winnerIndex = when (game.state.winnerIndices) {
        listOf(0) -> match.homeIndex
        listOf(1) -> match.awayIndex
        else -> null // tie / both / neither -> draw
    }
    return recordResult(state, matchId, winnerIndex, game.id)
}

/**
 * Reconcile EVERY linkable match in this tournament against [games] in one pass,
 * returning the updated state. A match is reconciled when it has a non-null
 * [TournamentMatch.gameId] whose linked game is present in [games] and finished;
 * [reconcileMatch] then guards the rest (already-played matches are skipped). The
 * fold is therefore idempotent — re-running it over the same finished games leaves
 * the state unchanged. Pure.
 */
fun TournamentState.syncedWith(games: List<GameRecord>): TournamentState {
    val byId = games.associateBy { it.id }
    return matches.fold(this) { acc, match ->
        val gameId = match.gameId ?: return@fold acc
        val game = byId[gameId] ?: return@fold acc
        reconcileMatch(acc, match.id, game)
    }
}
