package com.dartrack.ui.tournament

import com.dartrack.model.GameMode

/**
 * Human-readable label for a [GameMode], matching the chip/header text used by
 * NewGameScreen and HistoryScreen exactly. Kept in one place so the three
 * tournament screens stay in sync without repeating the `when`.
 */
internal fun modeLabel(mode: GameMode): String = when (mode) {
    GameMode.X01 -> "X01"
    GameMode.CRICKET -> "Cricket"
    GameMode.HALF_IT -> "Half-It"
    GameMode.AROUND_CLOCK -> "Around the Clock"
    GameMode.BOBS_27 -> "Bob's 27"
    GameMode.SHANGHAI -> "Shanghai"
    GameMode.CATCH_40 -> "Catch 40"
    GameMode.COUNT_UP -> "Count-Up"
    GameMode.CHECKOUT_TRAINER -> "Checkout Trainer"
    GameMode.BASEBALL -> "Baseball"
    GameMode.GOLF -> "Golf"
    GameMode.GOTCHA -> "Gotcha"
}
