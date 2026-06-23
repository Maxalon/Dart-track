package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.GameState
import com.dartrack.model.GolfState
import com.dartrack.model.GotchaState
import com.dartrack.model.X01State
import com.dartrack.model.bot.BotLevel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Worked examples for the tournament<->game glue ([defaultStateForMode],
 * [buildMatchGameRecord], [reconcileMatch], [syncedWith]). Finished games are
 * built either by hand-setting [GameState.winnerIndices] (the seat the engine
 * would have reported) or by driving a real model to its natural finish, so the
 * seat->competitor mapping and draw detection are checked end-to-end. Mirrors the
 * style of [TournamentTest] / [RecordsTest].
 */
class TournamentGameTest {

    private fun human(name: String, id: String = name.lowercase()) =
        TournamentCompetitor(name = name, playerId = id, isBot = false)

    private fun cpu(name: String, level: BotLevel = BotLevel.HARD) =
        TournamentCompetitor(name = name, isBot = true, botLevel = level)

    /** A 2-competitor tournament (single match "m-0-0-1") in [mode]. */
    private fun twoUpTournament(
        mode: GameMode = GameMode.X01,
        home: TournamentCompetitor = human("Ann"),
        away: TournamentCompetitor = cpu("CPU 1 (Hard)"),
    ): TournamentState =
        createTournament(id = "t1", name = "Cup", competitors = listOf(home, away), mode = mode)

    /** Set winnerIndices on a [GameState] to mimic the engine's reported seats. */
    private fun GameState.withWinners(seats: List<Int>): GameState = when (this) {
        is X01State -> copy(winnerIndices = seats)
        is GolfState -> copy(winnerIndices = seats)
        is GotchaState -> copy(winnerIndices = seats)
        else -> error("test only hand-wins X01/Golf/Gotcha states")
    }

    /** A finished GameRecord linked to [gameId], reporting [seats] as the winner. */
    private fun finishedRecord(
        state: TournamentState,
        match: TournamentMatch,
        gameId: String,
        seats: List<Int>,
    ): GameRecord {
        val base = buildMatchGameRecord(state, match, gameId, nowMs = 1_000L)
        return base.copy(state = base.state.withWinners(seats))
    }

    // ---- defaultStateForMode: all 12 modes ----------------------------------

    @Test
    fun defaultStateForMode_allModes_seatTwoAndNotFinished() {
        val seats = listOf(GamePlayer(name = "A", id = "a"), GamePlayer(name = "B", id = "b"))
        for (mode in GameMode.values()) {
            val state = defaultStateForMode(mode, seats)
            assertEquals(seats, state.players, "$mode: keeps the passed seats")
            assertEquals(2, state.players.size, "$mode: exactly two seats")
            assertFalse(state.isFinished, "$mode: a fresh state is not finished")
            assertEquals(mode, state.toMode(), "$mode: state maps back to the same mode")
        }
    }

    @Test
    fun defaultStateForMode_x01_is501DoubleOut() {
        val seats = listOf(GamePlayer("A", "a"), GamePlayer("B", "b"))
        val state = defaultStateForMode(GameMode.X01, seats) as X01State
        assertEquals(501, state.startScore, "X01 default start is 501")
        assertTrue(state.doubleOut, "X01 default is double-out")
    }

    @Test
    fun defaultStateForMode_gotcha_isTarget301() {
        val seats = listOf(GamePlayer("A", "a"), GamePlayer("B", "b"))
        val state = defaultStateForMode(GameMode.GOTCHA, seats) as GotchaState
        assertEquals(301, state.target, "Gotcha default target is 301")
    }

    // ---- buildMatchGameRecord -----------------------------------------------

    @Test
    fun buildMatchGameRecord_idModeAndSeats() {
        val t = twoUpTournament(mode = GameMode.CRICKET, home = human("Ann"), away = cpu("Bot"))
        val match = t.matches.single()
        val rec = buildMatchGameRecord(t, match, gameId = "g-1", nowMs = 42L)

        assertEquals("g-1", rec.id, "record id is the supplied gameId")
        assertEquals(GameMode.CRICKET, rec.mode, "record mode is the tournament mode")
        assertEquals(42L, rec.createdAtEpochMs, "createdAt uses nowMs")
        assertEquals(42L, rec.updatedAtEpochMs, "updatedAt uses nowMs")
        assertFalse(rec.isFinished, "a freshly built match record is not finished")
    }

    @Test
    fun buildMatchGameRecord_seatsAreHomeThenAway_humanThenCpu() {
        val t = twoUpTournament(home = human("Ann", id = "ann-id"), away = cpu("Bot"))
        val match = t.matches.single() // home=0, away=1
        val rec = buildMatchGameRecord(t, match, gameId = "g-1", nowMs = 1L)
        val seats = rec.state.players

        assertEquals(2, seats.size, "exactly the two competitors are seated")
        // Seat 0 = home human keeps its registry id.
        assertEquals("Ann", seats[0].name)
        assertEquals("ann-id", seats[0].id)
        assertFalse(seats[0].isBot, "home seat is the human")
        // Seat 1 = away CPU gets a stable bot:<index> id and isBot flag.
        assertEquals("Bot", seats[1].name)
        assertEquals("bot:1", seats[1].id, "CPU seat id is bot:<awayIndex>")
        assertTrue(seats[1].isBot, "away seat is the CPU")
        assertEquals(BotLevel.HARD, seats[1].botLevel)
    }

    // ---- reconcileMatch: seat -> competitor mapping -------------------------

    @Test
    fun reconcileMatch_homeSeatWins_recordsHomeCompetitor() {
        val t = twoUpTournament()
        val match = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(match))
        val game = finishedRecord(state, match, "g-1", seats = listOf(0))

        val out = reconcileMatch(state, match.id, game)
        val played = out.matches.single()
        assertTrue(played.played, "match is now played")
        assertEquals(match.homeIndex, played.winnerIndex, "seat 0 -> home competitor")
        assertEquals("g-1", played.gameId, "links the deciding game")
    }

    @Test
    fun reconcileMatch_awaySeatWins_recordsAwayCompetitor() {
        val t = twoUpTournament()
        val match = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(match))
        val game = finishedRecord(state, match, "g-1", seats = listOf(1))

        val out = reconcileMatch(state, match.id, game)
        val played = out.matches.single()
        assertTrue(played.played)
        assertEquals(match.awayIndex, played.winnerIndex, "seat 1 -> away competitor")
    }

    @Test
    fun reconcileMatch_twoWinnerSeats_recordsDraw() {
        // A tie-capable finish (e.g. a Golf draw) reports both seats -> draw.
        val t = twoUpTournament(mode = GameMode.GOLF)
        val match = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(match))
        val game = finishedRecord(state, match, "g-1", seats = listOf(0, 1))

        val out = reconcileMatch(state, match.id, game)
        val played = out.matches.single()
        assertTrue(played.played, "a draw still marks the match played")
        assertNull(played.winnerIndex, "two winner seats -> draw (winnerIndex null)")
    }

    @Test
    fun reconcileMatch_naturalGolfTie_recordsDraw() {
        // Drive a real Golf game to a genuine tie (identical play) instead of
        // hand-setting winnerIndices, exercising the engine's own draw path.
        val t = twoUpTournament(mode = GameMode.GOLF)
        val match = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(match))
        var golf = buildMatchGameRecord(state, match, "g-1", 1L).state as GolfState
        // 9 holes, both seats record the same result each time -> equal strokes.
        repeat(com.dartrack.model.GOLF_HOLES * 2) {
            golf = golf.applyResult(com.dartrack.model.GolfResult.SINGLE)
        }
        assertEquals(listOf(0, 1), golf.winnerIndices, "identical play ties both seats")
        val game = buildMatchGameRecord(state, match, "g-1", 1L).copy(state = golf)

        val out = reconcileMatch(state, match.id, game)
        assertNull(out.matches.single().winnerIndex, "engine tie -> draw")
        assertTrue(out.matches.single().played)
    }

    @Test
    fun reconcileMatch_naturalGotchaWin_recordsHome() {
        // Drive a real Gotcha game to a home-seat win (seat 0 lands on 301).
        val t = twoUpTournament(mode = GameMode.GOTCHA)
        val match = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(match))
        var g = buildMatchGameRecord(state, match, "g-1", 1L).state as GotchaState
        g = g.applyTurn(180)            // seat 0 -> 180
        g = g.applyTurn(0)              // seat 1 -> 0
        g = g.applyTurn(121)            // seat 0 -> 301 -> win
        assertEquals(listOf(0), g.winnerIndices, "seat 0 reaches the target")
        val game = buildMatchGameRecord(state, match, "g-1", 1L).copy(state = g)

        val out = reconcileMatch(state, match.id, game)
        assertEquals(match.homeIndex, out.matches.single().winnerIndex)
    }

    @Test
    fun reconcileMatch_unfinishedGame_noChange() {
        val t = twoUpTournament()
        val match = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(match))
        // Default (fresh) state is not finished.
        val game = buildMatchGameRecord(state, match, "g-1", 1L)

        val out = reconcileMatch(state, match.id, game)
        assertEquals(state, out, "an unfinished game records nothing")
        assertFalse(out.matches.single().played)
    }

    @Test
    fun reconcileMatch_gameIdMismatch_noChange() {
        val t = twoUpTournament()
        val match = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(match))
        // Finished, but its id doesn't match the match's linked gameId.
        val game = finishedRecord(state, match, gameId = "OTHER", seats = listOf(0))

        val out = reconcileMatch(state, match.id, game)
        assertEquals(state, out, "a game not linked to the match is ignored")
    }

    @Test
    fun reconcileMatch_unknownMatchId_noChange() {
        val t = twoUpTournament()
        val match = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(match))
        val game = finishedRecord(state, match, "g-1", seats = listOf(0))

        val out = reconcileMatch(state, "no-such-match", game)
        assertEquals(state, out, "an unknown matchId is a no-op")
    }

    @Test
    fun reconcileMatch_alreadyPlayed_isIdempotent() {
        val t = twoUpTournament()
        val match = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(match))
        val game = finishedRecord(state, match, "g-1", seats = listOf(0))

        val once = reconcileMatch(state, match.id, game)
        // Re-reconciling the same finished game over the already-played match.
        val twice = reconcileMatch(once, match.id, game)
        assertEquals(once, twice, "re-reconciling an already-played match changes nothing")
    }

    // ---- syncedWith ----------------------------------------------------------

    @Test
    fun syncedWith_twoLinkedFinishedGames_updatesBothAndStandings() {
        // 3 competitors -> 3 matches. Link two of them to finished games.
        val t = createTournament(
            id = "t",
            name = "Cup",
            competitors = listOf(human("Ann"), human("Bob"), human("Cy")),
            mode = GameMode.X01,
        )
        val m0 = t.matches[0] // round 0
        val m1 = t.matches[1]
        val linked = t.copy(
            matches = t.matches.toMutableList().also {
                it[0] = m0.copy(gameId = "g0")
                it[1] = m1.copy(gameId = "g1")
            },
        )
        // Home wins g0; away wins g1.
        val g0 = finishedRecord(linked, linked.matches[0], "g0", seats = listOf(0))
        val g1 = finishedRecord(linked, linked.matches[1], "g1", seats = listOf(1))

        val out = linked.syncedWith(listOf(g0, g1))
        assertTrue(out.matches[0].played, "first linked match recorded")
        assertTrue(out.matches[1].played, "second linked match recorded")
        assertFalse(out.matches[2].played, "the unlinked match is untouched")

        assertEquals(linked.matches[0].homeIndex, out.matches[0].winnerIndex)
        assertEquals(linked.matches[1].awayIndex, out.matches[1].winnerIndex)

        // Standings reflect exactly two recorded wins (1 point-for-win competitor each).
        val table = standings(out)
        assertEquals(2, table.sumOf { it.won }, "exactly two wins across the table")
        assertEquals(2, table.sumOf { it.lost }, "exactly two losses across the table")
        assertEquals(0, table.sumOf { it.drawn }, "no draws")
    }

    @Test
    fun syncedWith_isIdempotent() {
        val t = twoUpTournament()
        val linked = t.copy(matches = listOf(t.matches.single().copy(gameId = "g-1")))
        val game = finishedRecord(linked, linked.matches.single(), "g-1", seats = listOf(1))

        val once = linked.syncedWith(listOf(game))
        val twice = once.syncedWith(listOf(game))
        assertEquals(once, twice, "syncedWith over the same games is idempotent")
        assertEquals(linked.matches.single().awayIndex, once.matches.single().winnerIndex)
    }

    @Test
    fun syncedWith_skipsUnlinkedAndUnfinished() {
        val t = createTournament(
            id = "t",
            name = "Cup",
            competitors = listOf(human("Ann"), human("Bob"), human("Cy")),
            mode = GameMode.X01,
        )
        // m0 linked + finished; m1 linked but its game is still in progress.
        val linked = t.copy(
            matches = t.matches.toMutableList().also {
                it[0] = t.matches[0].copy(gameId = "g0")
                it[1] = t.matches[1].copy(gameId = "g1")
            },
        )
        val finished = finishedRecord(linked, linked.matches[0], "g0", seats = listOf(0))
        val inProgress = buildMatchGameRecord(linked, linked.matches[1], "g1", 1L) // not finished

        val out = linked.syncedWith(listOf(finished, inProgress))
        assertTrue(out.matches[0].played, "the finished linked match is recorded")
        assertFalse(out.matches[1].played, "the in-progress linked match stays unplayed")
        assertFalse(out.matches[2].played, "the unlinked match stays unplayed")
    }

    @Test
    fun syncedWith_noGames_isNoOp() {
        val t = twoUpTournament()
        val linked = t.copy(matches = listOf(t.matches.single().copy(gameId = "g-1")))
        assertEquals(linked, linked.syncedWith(emptyList()), "no games -> unchanged")
    }
}
