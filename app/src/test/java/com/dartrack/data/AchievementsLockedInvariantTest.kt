package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.X01State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ADVERSARIAL invariant: a player who appears in NO game (an unknown or blank id)
 * must have EVERY achievement locked, zero progress, no unlock timestamps, and a
 * summary of 0 / catalog-size. Guards the seat-resolution boundary so a stray id
 * can never spuriously unlock anything.
 */
class AchievementsLockedInvariantTest {

    private val players = listOf(GamePlayer("Alice", "pid-a"), GamePlayer("Bob", "pid-b"))

    /** A small history that DOES unlock things for pid-a, to prove the contrast. */
    private fun history(): List<GameRecord> {
        // Alice wins a finished X01 game (unlocks first_win etc. for pid-a).
        val state = X01State.new(players, startScore = 40, doubleOut = false).applyTurn(40)
        return listOf(GameRecord("g1", GameMode.X01, 100L, 200L, state))
    }

    @Test
    fun unknownPlayer_hasEveryAchievementLocked() {
        val statuses = achievementsFor("nobody-here", history())
        assertTrue(statuses.isNotEmpty(), "catalog must produce statuses")
        assertTrue(statuses.all { !it.unlocked }, "an unknown player unlocks nothing")
        assertTrue(statuses.all { it.progress == 0 }, "no progress for an unknown player")
        assertTrue(statuses.all { it.unlockedAtMs == null }, "no unlock timestamps")
    }

    @Test
    fun blankId_hasEveryAchievementLocked() {
        val statuses = achievementsFor("", history())
        assertTrue(statuses.all { !it.unlocked }, "a blank id matches no seat -> all locked")
        val summary = achievementSummary("", history())
        assertEquals(0, summary.unlocked, "blank id unlocks 0")
        assertEquals(statuses.size, summary.total, "summary total == catalog size")
        assertTrue(summary.total > 0)
    }

    @Test
    fun emptyHistory_locksEverythingEvenForARealId() {
        val statuses = achievementsFor("pid-a", emptyList())
        assertTrue(statuses.all { !it.unlocked }, "no games -> nothing unlocked")
        assertEquals(0, achievementSummary("pid-a", emptyList()).unlocked)
    }

    @Test
    fun realPlayerInHistory_unlocksAtLeastOne_provingTheContrast() {
        // Sanity foil: the SAME history that locks everything for an unknown id
        // must unlock something for the real winner, so the locked-invariant tests
        // above aren't passing vacuously.
        val summary = achievementSummary("pid-a", history())
        assertTrue(summary.unlocked >= 1, "the real winner unlocks at least first_win")
        assertFalse(
            achievementsFor("pid-a", history()).all { !it.unlocked },
            "real winner must have an unlocked achievement",
        )
    }
}
