package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.X01PlayerState
import com.dartrack.model.X01State
import com.dartrack.model.X01Turn
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Worked examples for [threeDartAvgTrendById] and [headToHead]. Games are built
 * by hand with known per-turn totals and explicit winner seats so the expected
 * results can be computed on paper.
 */
class PlayerStatsTrendH2HTest {

    private val DELTA = 1e-9

    private fun turn(scoreBefore: Int, entered: Int, finished: Boolean = false, bust: Boolean = false) =
        X01Turn(scoreBefore = scoreBefore, entered = entered, bust = bust, finished = finished)

    /**
     * Build a multi-seat X01 game record. [seats] is (id, name); [turnsBySeat]
     * gives each seat's turns (defaults to none). [winners] are the winning seat
     * indices. [finished] controls [GameState.isFinished] via winnerIndices being
     * non-empty AND an explicit flag is not needed: X01State.isFinished derives
     * from winnerIndices, so a finished game must have a winner. For an
     * unfinished game pass winners = emptyList().
     */
    private fun record(
        recId: String,
        createdAtEpochMs: Long,
        seats: List<Pair<String, String>>,
        turnsBySeat: List<List<X01Turn>> = seats.map { emptyList() },
        startScore: Int = 501,
        winners: List<Int> = emptyList(),
    ): GameRecord {
        val players = seats.map { (id, name) -> GamePlayer(name = name, id = id) }
        val perPlayer = players.mapIndexed { i, p -> X01PlayerState(p, turnsBySeat[i]) }
        val state = X01State(
            players = players,
            perPlayer = perPlayer,
            startScore = startScore,
            winnerIndices = winners,
        )
        return GameRecord(
            id = recId,
            mode = GameMode.X01,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = createdAtEpochMs,
            state = state,
        )
    }

    // ---- Trend ------------------------------------------------------------

    @Test
    fun trend_onePointPerFinishedX01Game_chronological_valueCorrect() {
        // Two finished games for p-1, fed newest-first to prove the sort.
        // Game B (created later): 501 over 100,140,100,100,61 finish = 15 darts
        //   -> avg = 501 * 3 / 15 = 100.2
        // Game A (created earlier): 40 start, single 40 finish = 3 darts
        //   -> avg = 40 * 3 / 3 = 40.0
        val gameB = record(
            "B", createdAtEpochMs = 2000L,
            seats = listOf("p-1" to "Alice"),
            turnsBySeat = listOf(
                listOf(
                    turn(501, 100, finished = false, bust = false),
                    turn(401, 140, finished = false, bust = false),
                    turn(261, 100, finished = false, bust = false),
                    turn(161, 100, finished = false, bust = false),
                    turn(61, 61, finished = true, bust = false),
                ),
            ),
            startScore = 501,
            winners = listOf(0),
        )
        val gameA = record(
            "A", createdAtEpochMs = 1000L,
            seats = listOf("p-1" to "Alice"),
            turnsBySeat = listOf(listOf(turn(40, 40, finished = true, bust = false))),
            startScore = 40,
            winners = listOf(0),
        )

        val trend = threeDartAvgTrendById("p-1", listOf(gameB, gameA))
        assertEquals(2, trend.size, "one point per finished X01 game")
        // Ordered oldest -> newest by createdAtEpochMs.
        assertEquals(1000L, trend[0].timeMs, "first point is the earlier game")
        assertEquals(2000L, trend[1].timeMs, "second point is the later game")
        assertEquals(40.0, trend[0].threeDartAvg, 1e-6, "earlier game avg")
        assertEquals(100.2, trend[1].threeDartAvg, 1e-6, "later game avg")
    }

    @Test
    fun trend_excludesNonParticipantAndUnfinishedAndZeroDartGames() {
        // Finished game p-1 played in (counts).
        val played = record(
            "r1", createdAtEpochMs = 1000L,
            seats = listOf("p-1" to "Alice"),
            turnsBySeat = listOf(listOf(turn(40, 40, finished = true, bust = false))),
            startScore = 40,
            winners = listOf(0),
        )
        // Finished game p-1 is NOT a seat in (excluded).
        val notMine = record(
            "r2", createdAtEpochMs = 1500L,
            seats = listOf("p-2" to "Bob"),
            turnsBySeat = listOf(listOf(turn(40, 40, finished = true, bust = false))),
            startScore = 40,
            winners = listOf(0),
        )
        // Unfinished game (no winner) p-1 is in (excluded).
        val unfinished = record(
            "r3", createdAtEpochMs = 2000L,
            seats = listOf("p-1" to "Alice"),
            turnsBySeat = listOf(listOf(turn(501, 60, finished = false, bust = false))),
            startScore = 501,
            winners = emptyList(),
        )
        val trend = threeDartAvgTrendById("p-1", listOf(played, notMine, unfinished))
        assertEquals(1, trend.size, "only the finished game p-1 threw in")
        assertEquals(1000L, trend[0].timeMs, "the participating game's time")
        assertEquals(40.0, trend[0].threeDartAvg, 1e-6, "its 3-dart average")
    }

    // ---- Head-to-head -----------------------------------------------------

    @Test
    fun headToHead_played_won_lost_acrossGames_includingTie() {
        // p-1 vs p-2 across three shared games + one unshared game.
        // g1: p-1 wins (p-1 won, p-2 not)         -> won
        // g2: p-2 wins (p-2 won, p-1 not)         -> lost
        // g3: tie (both winners)                  -> neither
        // g4: p-1 vs p-3 only                     -> not counted toward p-2
        val g1 = record(
            "g1", 1000L,
            seats = listOf("p-1" to "Alice", "p-2" to "Bob"),
            winners = listOf(0),
        )
        val g2 = record(
            "g2", 2000L,
            seats = listOf("p-1" to "Alice", "p-2" to "Bob"),
            winners = listOf(1),
        )
        val g3 = record(
            "g3", 3000L,
            seats = listOf("p-1" to "Alice", "p-2" to "Bob"),
            winners = listOf(0, 1),
        )
        val g4 = record(
            "g4", 4000L,
            seats = listOf("p-1" to "Alice", "p-3" to "Cara"),
            winners = listOf(0),
        )

        val h2h = headToHead("p-1", listOf(g1, g2, g3, g4))
        // Sorted by played desc: p-2 (3) before p-3 (1).
        assertEquals(2, h2h.size, "two distinct opponents")
        val vsBob = h2h[0]
        assertEquals("p-2", vsBob.opponentId, "first opponent by played desc")
        assertEquals("Bob", vsBob.opponentName, "opponent name")
        assertEquals(3, vsBob.played, "three shared games with Bob")
        assertEquals(1, vsBob.won, "won g1")
        assertEquals(1, vsBob.lost, "lost g2 (tie g3 counts toward neither)")

        val vsCara = h2h[1]
        assertEquals("p-3", vsCara.opponentId, "second opponent")
        assertEquals(1, vsCara.played, "one shared game with Cara")
        assertEquals(1, vsCara.won, "won g4")
        assertEquals(0, vsCara.lost, "no losses to Cara")
    }
}
