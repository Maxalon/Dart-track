package com.dartrack.model

import kotlinx.serialization.Serializable

/**
 * Every per-mode setup knob, in one serializable bundle. The New Game screen
 * edits one of these for a single game, and a tournament stores one on its
 * [com.dartrack.data.TournamentState] so every match is created with the same
 * settings. Each field only matters for its own mode and is ignored otherwise.
 *
 * All fields are defaulted to the long-standing tournament defaults (501,
 * double-out, single leg, standard Cricket, Gotcha to 301, 3 Killer lives), so a
 * tournament persisted before options existed decodes to exactly the matches it
 * used to create.
 *
 * X01 legs/sets are stored as "first to N" counts — the same unit
 * [X01State.legsToWin] / [X01State.setsToWin] persist — and the UI converts
 * to/from "best of" with [bestOfToFirstTo] / [firstToToBestOf].
 */
@Serializable
data class GameOptions(
    val x01StartScore: Int = 501,
    val x01DoubleOut: Boolean = true,
    val x01LegsToWin: Int = 1,
    val x01SetsToWin: Int = 1,
    val cricketCutThroat: Boolean = false,
    val gotchaTarget: Int = GOTCHA_DEFAULT_TARGET,
    val killerLives: Int = KILLER_DEFAULT_LIVES,
)

/**
 * The fresh starting [GameState] for [mode], seated with [players] and
 * configured from [options]. Shared by the New Game screen and tournament match
 * creation so the two can't drift. The `when` is exhaustive over every
 * [GameMode] (no `else`), so adding a mode is a compile error here until it is
 * wired up. Pure.
 */
fun stateForMode(
    mode: GameMode,
    players: List<GamePlayer>,
    options: GameOptions = GameOptions(),
): GameState = when (mode) {
    GameMode.X01 -> X01State.new(
        players,
        startScore = options.x01StartScore,
        doubleOut = options.x01DoubleOut,
        legsToWin = options.x01LegsToWin,
        setsToWin = options.x01SetsToWin,
    )
    GameMode.CRICKET -> CricketState.new(players, cutThroat = options.cricketCutThroat)
    GameMode.HALF_IT -> HalfItState.new(players)
    GameMode.AROUND_CLOCK -> AroundTheClockState.new(players)
    GameMode.BOBS_27 -> BobsTwentySevenState.new(players)
    GameMode.SHANGHAI -> ShanghaiState.new(players)
    GameMode.CATCH_40 -> Catch40State.new(players)
    GameMode.COUNT_UP -> CountUpState.new(players)
    GameMode.CHECKOUT_TRAINER -> CheckoutTrainerState.new(players)
    GameMode.BASEBALL -> BaseballState.new(players)
    GameMode.GOLF -> GolfState.new(players)
    GameMode.GOTCHA -> GotchaState.new(players, target = options.gotchaTarget)
    GameMode.KILLER -> KillerState.new(players, startLives = options.killerLives)
    GameMode.BERMUDA -> BermudaState.new(players)
}
