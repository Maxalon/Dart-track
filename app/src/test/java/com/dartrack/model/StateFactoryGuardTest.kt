package com.dartrack.model

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Robustness guards for the game-state factories: every `<State>.new(...)`
 * companion must reject an empty seat list rather than constructing a
 * degenerate state that crashes later (e.g. on `players.size` modulo).
 *
 * The UI gates this, but the model is total: an empty player list is an
 * [IllegalArgumentException] from the factory. CountUp and CheckoutTrainer
 * already enforce this and are covered by their own tests.
 */
class StateFactoryGuardTest {

    @Test
    fun x01_rejectsEmptyPlayers() {
        assertFailsWith<IllegalArgumentException> { X01State.new(emptyList()) }
    }

    @Test
    fun cricket_rejectsEmptyPlayers() {
        assertFailsWith<IllegalArgumentException> { CricketState.new(emptyList()) }
    }

    @Test
    fun halfIt_rejectsEmptyPlayers() {
        assertFailsWith<IllegalArgumentException> { HalfItState.new(emptyList()) }
    }

    @Test
    fun aroundTheClock_rejectsEmptyPlayers() {
        assertFailsWith<IllegalArgumentException> { AroundTheClockState.new(emptyList()) }
    }

    @Test
    fun bobsTwentySeven_rejectsEmptyPlayers() {
        assertFailsWith<IllegalArgumentException> { BobsTwentySevenState.new(emptyList()) }
    }

    @Test
    fun shanghai_rejectsEmptyPlayers() {
        assertFailsWith<IllegalArgumentException> { ShanghaiState.new(emptyList()) }
    }

    @Test
    fun catch40_rejectsEmptyPlayers() {
        assertFailsWith<IllegalArgumentException> { Catch40State.new(emptyList()) }
    }

    @Test
    fun baseball_rejectsEmptyPlayers() {
        assertFailsWith<IllegalArgumentException> { BaseballState.new(emptyList()) }
    }

    @Test
    fun golf_rejectsEmptyPlayers() {
        assertFailsWith<IllegalArgumentException> { GolfState.new(emptyList()) }
    }

    @Test
    fun gotcha_rejectsEmptyPlayers() {
        assertFailsWith<IllegalArgumentException> { GotchaState.new(emptyList()) }
    }
}
