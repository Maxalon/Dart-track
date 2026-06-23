package com.dartrack.data

import com.dartrack.model.CheckoutTrainerState
import com.dartrack.model.CountUpState
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.Player
import com.dartrack.model.X01PlayerState
import com.dartrack.model.X01State
import com.dartrack.model.X01Turn
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Worked examples for the leaderboards & records aggregator ([leaderboard],
 * [allLeaderboards], [playerRecords]). Games are built by hand (or via model
 * factories + applyTurn) with known per-turn totals so every ranked value can be
 * computed on paper. Mirrors the style of [PlayerStatsTest].
 */
class RecordsTest {

    private val DELTA = 1e-9

    private fun turn(scoreBefore: Int, entered: Int, finished: Boolean = false, bust: Boolean = false) =
        X01Turn(scoreBefore = scoreBefore, entered = entered, bust = bust, finished = finished)

    /** A single-seat X01 game record carrying [turns] for one id-keyed player. */
    private fun x01Record(
        recId: String,
        playerId: String,
        playerName: String,
        turns: List<X01Turn>,
        startScore: Int = 501,
        won: Boolean = false,
    ): GameRecord {
        val player = GamePlayer(name = playerName, id = playerId)
        val state = X01State(
            players = listOf(player),
            perPlayer = listOf(X01PlayerState(player, turns)),
            startScore = startScore,
            winnerIndices = if (won) listOf(0) else emptyList(),
        )
        return GameRecord(recId, GameMode.X01, 0L, 0L, state)
    }

    /** A one-leg X01 finish: a single [finishScore] checkout from a matching start. */
    private fun x01Finish(
        recId: String,
        playerId: String,
        playerName: String,
        finishScore: Int,
    ): GameRecord = x01Record(
        recId, playerId, playerName,
        listOf(turn(finishScore, finishScore, finished = true)),
        startScore = finishScore, won = true,
    )

    /** Build a finished Count-Up game for one player from their per-round totals. */
    private fun countUpRecord(
        recId: String,
        playerId: String,
        playerName: String,
        totals: List<Int>,
    ): GameRecord {
        val player = GamePlayer(name = playerName, id = playerId)
        var state = CountUpState.new(listOf(player), rounds = totals.size)
        totals.forEach { state = state.applyTurn(it) }
        return GameRecord(recId, GameMode.COUNT_UP, 0L, 0L, state)
    }

    /** Build a finished single-target Checkout Trainer game (one hit or miss). */
    private fun checkoutTrainerRecord(
        recId: String,
        playerId: String,
        playerName: String,
        hit: Boolean,
        darts: Int = 3,
    ): GameRecord {
        val player = GamePlayer(name = playerName, id = playerId)
        var state = CheckoutTrainerState.new(listOf(player), targets = listOf(40))
        state = state.applyAttempt(hit = hit, darts = darts)
        return GameRecord(recId, GameMode.CHECKOUT_TRAINER, 0L, 0L, state)
    }

    private fun player(id: String, name: String) = Player(id, name)

    // ---- ranking order per category -----------------------------------------

    @Test
    fun mostWins_ranksByWinsDescending() {
        // Alice wins 2, Bob wins 1, Cara wins 0 (all play >= 1 game).
        val games = listOf(
            x01Finish("a1", "a", "Alice", 40),
            x01Finish("a2", "a", "Alice", 50),
            x01Finish("b1", "b", "Bob", 40),
            x01Record("c1", "c", "Cara", listOf(turn(40, 20)), startScore = 40, won = false),
        )
        val board = leaderboard(
            LeaderboardCategory.MOST_WINS, games,
            listOf(player("a", "Alice"), player("b", "Bob"), player("c", "Cara")),
        )
        assertEquals(listOf("Alice", "Bob", "Cara"), board.map { it.playerName }, "ordered by wins desc")
        assertEquals(listOf(1, 2, 3), board.map { it.rank }, "ranks 1,2,3")
        assertEquals(listOf("2", "1", "0"), board.map { it.display }, "win counts as plain ints")
    }

    @Test
    fun bestThreeDartAvg_ranksHigherFirst_oneDecimalDisplay() {
        // Alice: 501 over 15 darts -> 100.2; Bob: 60 over 3 darts -> 60.0.
        val aliceTurns = listOf(
            turn(501, 100), turn(401, 140), turn(261, 100), turn(161, 100),
            turn(61, 61, finished = true),
        )
        val games = listOf(
            x01Record("a1", "a", "Alice", aliceTurns, won = true),
            x01Finish("b1", "b", "Bob", 60),
        )
        val board = leaderboard(
            LeaderboardCategory.BEST_THREE_DART_AVG, games,
            listOf(player("a", "Alice"), player("b", "Bob")),
        )
        assertEquals(listOf("Alice", "Bob"), board.map { it.playerName }, "higher avg first")
        assertEquals(100.2, board[0].value, 1e-6, "Alice raw avg")
        assertEquals("100.2", board[0].display, "one-decimal display")
        assertEquals("60.0", board[1].display, "trailing .0 kept")
    }

    // ---- higherIsBetter vs lower-is-better ----------------------------------

    @Test
    fun fewestDartsLeg_isLowerIsBetter() {
        assertTrue(!LeaderboardCategory.FEWEST_DARTS_LEG.higherIsBetter, "lower-is-better flag")
        // Alice finishes in 3 darts (one turn), Bob in 6 (two turns).
        val bobTurns = listOf(turn(80, 40), turn(40, 40, finished = true))
        val games = listOf(
            x01Finish("a1", "a", "Alice", 40),
            x01Record("b1", "b", "Bob", bobTurns, startScore = 80, won = true),
        )
        val board = leaderboard(
            LeaderboardCategory.FEWEST_DARTS_LEG, games,
            listOf(player("a", "Alice"), player("b", "Bob")),
        )
        assertEquals(listOf("Alice", "Bob"), board.map { it.playerName }, "fewest darts ranks first")
        assertEquals(listOf("3", "6"), board.map { it.display }, "dart counts as ints")
        assertEquals(3.0, board[0].value, DELTA, "Alice best leg = 3 darts")
    }

    // ---- competition-ranking ties (1,2,2,4) with name tiebreak --------------

    @Test
    fun ties_useCompetitionRanking_andNameTiebreak() {
        // Wins: Dave=2, Bob=1, Cara=1, Alice=0. Bob & Cara tie at rank 2; Alice 4.
        val games = listOf(
            x01Finish("d1", "d", "Dave", 40),
            x01Finish("d2", "d", "Dave", 50),
            x01Finish("b1", "b", "Bob", 40),
            x01Finish("c1", "c", "Cara", 40),
            x01Record("a1", "a", "Alice", listOf(turn(40, 20)), startScore = 40, won = false),
        )
        val board = leaderboard(
            LeaderboardCategory.MOST_WINS, games,
            listOf(player("a", "Alice"), player("b", "Bob"), player("c", "Cara"), player("d", "Dave")),
        )
        assertEquals(listOf("Dave", "Bob", "Cara", "Alice"), board.map { it.playerName }, "name tiebreak Bob<Cara")
        assertEquals(listOf(1, 2, 2, 4), board.map { it.rank }, "competition ranking 1,2,2,4")
    }

    @Test
    fun ties_nameTiebreakIsCaseInsensitive() {
        // Both have 1 win; tie-break by name case-insensitively: "alice" < "BOB".
        val games = listOf(
            x01Finish("b1", "b", "BOB", 40),
            x01Finish("a1", "a", "alice", 40),
        )
        val board = leaderboard(
            LeaderboardCategory.MOST_WINS, games,
            listOf(player("b", "BOB"), player("a", "alice")),
        )
        assertEquals(listOf("alice", "BOB"), board.map { it.playerName }, "case-insensitive name order")
        assertEquals(listOf(1, 1), board.map { it.rank }, "equal values share rank 1")
    }

    // ---- minGames filtering --------------------------------------------------

    @Test
    fun minGames_filtersPlayersBelowThreshold() {
        // Alice plays 2, Bob plays 1. minGames=2 keeps only Alice.
        val games = listOf(
            x01Finish("a1", "a", "Alice", 40),
            x01Finish("a2", "a", "Alice", 50),
            x01Finish("b1", "b", "Bob", 40),
        )
        val players = listOf(player("a", "Alice"), player("b", "Bob"))
        val board = leaderboard(LeaderboardCategory.MOST_GAMES, games, players, minGames = 2)
        assertEquals(listOf("Alice"), board.map { it.playerName }, "Bob dropped below minGames")
        assertEquals(1, board[0].rank, "sole survivor ranks 1")
        assertEquals("2", board[0].display, "two games played")
    }

    @Test
    fun registeredPlayerWithNoGamesIsExcluded() {
        // Cara is registered but never plays; default minGames=1 drops her.
        val games = listOf(x01Finish("a1", "a", "Alice", 40))
        val players = listOf(player("a", "Alice"), player("c", "Cara"))
        val board = leaderboard(LeaderboardCategory.MOST_GAMES, games, players)
        assertEquals(listOf("Alice"), board.map { it.playerName }, "no-game player excluded")
    }

    // ---- X01-only categories exclude non-X01 players -------------------------

    @Test
    fun playersWithNoX01_excludedFromX01Categories() {
        // Alice plays X01; Bob plays only Count-Up. Bob qualifies for MOST_GAMES
        // but NOT for any X01-only category.
        val games = listOf(
            x01Record(
                "a1", "a", "Alice",
                listOf(turn(501, 180), turn(321, 180), turn(141, 141, finished = true)),
                won = true,
            ),
            countUpRecord("b1", "b", "Bob", List(8) { 60 }),
        )
        val players = listOf(player("a", "Alice"), player("b", "Bob"))

        val most180s = leaderboard(LeaderboardCategory.MOST_180S, games, players)
        assertEquals(listOf("Alice"), most180s.map { it.playerName }, "Bob has no X01 -> excluded from 180s")
        assertEquals("2", most180s[0].display, "Alice threw two 180s")

        val avg = leaderboard(LeaderboardCategory.BEST_THREE_DART_AVG, games, players)
        assertEquals(listOf("Alice"), avg.map { it.playerName }, "Bob excluded from 3-dart avg")

        // But both appear for an all-modes category.
        val mostGames = leaderboard(LeaderboardCategory.MOST_GAMES, games, players)
        assertEquals(setOf("Alice", "Bob"), mostGames.map { it.playerName }.toSet(), "both counted for games")
    }

    @Test
    fun checkoutPct_requiresOpportunity_andFormatsAsPercent() {
        // Alice: 60 finish from 60 -> 1 opp, 1 hit = 100%. Bob: scores down from
        // 501 but never reaches a checkout opportunity (no turn with before<=170).
        val bobTurns = listOf(turn(501, 100), turn(401, 100)) // ends on 301, no opp
        val games = listOf(
            x01Finish("a1", "a", "Alice", 60),
            x01Record("b1", "b", "Bob", bobTurns, won = false),
        )
        val players = listOf(player("a", "Alice"), player("b", "Bob"))
        val board = leaderboard(LeaderboardCategory.BEST_CHECKOUT_PCT, games, players)
        assertEquals(listOf("Alice"), board.map { it.playerName }, "Bob has no checkout opp -> excluded")
        assertEquals("100%", board[0].display, "checkout % formatted as whole percent")
        assertEquals(1.0, board[0].value, DELTA, "raw checkout fraction")
    }

    @Test
    fun winPct_formatsAsRoundedWholePercent() {
        // Alice: 2 of 3 wins = 66.66% -> "67%". Bob: 1 of 2 = "50%".
        val games = listOf(
            x01Finish("a1", "a", "Alice", 40),
            x01Finish("a2", "a", "Alice", 50),
            x01Record("a3", "a", "Alice", listOf(turn(40, 20)), startScore = 40, won = false),
            x01Finish("b1", "b", "Bob", 40),
            x01Record("b2", "b", "Bob", listOf(turn(40, 20)), startScore = 40, won = false),
        )
        val board = leaderboard(
            LeaderboardCategory.WIN_PCT, games,
            listOf(player("a", "Alice"), player("b", "Bob")),
        )
        assertEquals(listOf("Alice", "Bob"), board.map { it.playerName }, "higher win% first")
        assertEquals("67%", board[0].display, "66.6% rounds to 67%")
        assertEquals("50%", board[1].display, "half rounds to 50%")
    }

    // ---- empty inputs --------------------------------------------------------

    @Test
    fun emptyInputs_yieldEmptyLists() {
        assertTrue(
            leaderboard(LeaderboardCategory.MOST_WINS, emptyList(), emptyList()).isEmpty(),
            "no players, no games -> empty",
        )
        // Players registered but zero games -> still empty (all below minGames).
        val players = listOf(player("a", "Alice"), player("b", "Bob"))
        assertTrue(
            leaderboard(LeaderboardCategory.MOST_WINS, emptyList(), players).isEmpty(),
            "players but no games -> empty",
        )
    }

    @Test
    fun duplicateRegistryIdRankedOnce() {
        // A duplicated id in the registry must not double-rank the same player.
        val games = listOf(x01Finish("a1", "a", "Alice", 40))
        val players = listOf(player("a", "Alice"), player("a", "Alice"))
        val board = leaderboard(LeaderboardCategory.MOST_GAMES, games, players)
        assertEquals(1, board.size, "deduped by id")
        assertEquals(1, board[0].rank, "single rank 1")
    }

    // ---- allLeaderboards -----------------------------------------------------

    @Test
    fun allLeaderboards_coversEveryCategory() {
        val games = listOf(x01Finish("a1", "a", "Alice", 40))
        val players = listOf(player("a", "Alice"))
        val all = allLeaderboards(games, players)
        assertEquals(LeaderboardCategory.values().toSet(), all.keys, "one entry per category")
        // Overall categories include Alice; her single 40 checkout means she also
        // qualifies for checkout %, best leg, etc.
        assertEquals("Alice", all.getValue(LeaderboardCategory.MOST_WINS).single().playerName, "wins board")
    }

    // ---- playerRecords -------------------------------------------------------

    @Test
    fun playerRecords_personalBestsForKnownPlayer() {
        // Game 1: 180,180,141 finish (501) -> avg 501*3/9 = 167.0, two 180s, CO 141.
        // Game 2: a low single-turn 40 finish -> avg 40.0, zero 180s, CO 40.
        val game1 = x01Record(
            "g1", "a", "Alice",
            listOf(turn(501, 180), turn(321, 180), turn(141, 141, finished = true)),
            won = true,
        )
        val game2 = x01Finish("g2", "a", "Alice", 40)
        val rec = playerRecords("a", listOf(game1, game2))

        assertEquals(2, rec.gamesPlayed, "two games")
        assertEquals(2, rec.gamesWon, "both won")
        assertEquals(2, rec.total180s, "two 180s lifetime")
        assertEquals(2, rec.most180sInAGame, "both 180s were in one game")
        assertEquals(141, rec.highestCheckout, "highest finishing checkout")
        assertEquals(3, rec.bestLegDarts, "fastest leg was the 40 finish (3 darts)")
        assertEquals(167.0, rec.bestGameThreeDartAvg, 1e-6, "best single-game avg = game 1")
    }

    @Test
    fun playerRecords_perModeHighs() {
        // Count-Up totals 8x60 = 480; Checkout Trainer one hit = 1 hit.
        val countUp = countUpRecord("c1", "a", "Alice", List(8) { 60 })
        val trainer = checkoutTrainerRecord("t1", "a", "Alice", hit = true, darts = 2)
        val rec = playerRecords("a", listOf(countUp, trainer))
        assertEquals(480, rec.bestCountUp, "count-up high total")
        assertEquals(1, rec.bestCheckoutTrainerHits, "one checkout hit")
        assertEquals(0, rec.total180s, "no X01 -> no 180s")
        assertEquals(0.0, rec.bestGameThreeDartAvg, DELTA, "no X01 -> zero best avg")
    }

    @Test
    fun playerRecords_blankIdIsAllZero() {
        val rec = playerRecords("", listOf(x01Finish("g1", "a", "Alice", 40)))
        assertEquals(0, rec.gamesPlayed, "blank id matches nothing")
        assertEquals(0, rec.highestCheckout, "no checkout")
        assertEquals(0.0, rec.bestGameThreeDartAvg, DELTA, "no average")
    }
}
