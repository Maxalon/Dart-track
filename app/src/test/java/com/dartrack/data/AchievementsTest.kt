package com.dartrack.data

import com.dartrack.model.AroundTheClockState
import com.dartrack.model.BaseballState
import com.dartrack.model.BermudaState
import com.dartrack.model.CheckoutTrainerState
import com.dartrack.model.CountUpState
import com.dartrack.model.CricketState
import com.dartrack.model.GOLF_HOLES
import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.GolfResult
import com.dartrack.model.GolfState
import com.dartrack.model.GotchaState
import com.dartrack.model.HalfItState
import com.dartrack.model.KillerState
import com.dartrack.model.ShanghaiState
import com.dartrack.model.X01PlayerState
import com.dartrack.model.X01State
import com.dartrack.model.X01Turn
import com.dartrack.model.bot.BotLevel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Worked examples for [achievementsFor] / [achievementSummary], the pure
 * milestone engine keyed by stable player id. Games are built by hand (or via the
 * model factories + applyTurn) with known shapes so each unlock can be reasoned
 * about on paper. Mirrors the style of [PlayerStatsTest].
 */
class AchievementsTest {

    private val PID = "p-1"
    private val NAME = "Alice"

    /** Look up one status by achievement id from a computed list. */
    private fun List<AchievementStatus>.byId(id: String): AchievementStatus =
        first { it.achievement.id == id }

    /** Convenience: compute statuses for [PID] and return the one with [id]. */
    private fun status(id: String, games: List<GameRecord>): AchievementStatus =
        achievementsFor(PID, games).byId(id)

    private fun turn(scoreBefore: Int, entered: Int, finished: Boolean = false, bust: Boolean = false) =
        X01Turn(scoreBefore = scoreBefore, entered = entered, bust = bust, finished = finished)

    /**
     * A single-seat X01 game record for [PID]. [createdAt] feeds chronological
     * ordering / unlockedAtMs; [won] sets the seat as a winner.
     */
    private fun x01Record(
        recId: String,
        turns: List<X01Turn>,
        startScore: Int = 501,
        won: Boolean = false,
        createdAt: Long = 0L,
        playerId: String = PID,
        playerName: String = NAME,
    ): GameRecord {
        val player = GamePlayer(name = playerName, id = playerId)
        val state = X01State(
            players = listOf(player),
            perPlayer = listOf(X01PlayerState(player, turns)),
            startScore = startScore,
            winnerIndices = if (won) listOf(0) else emptyList(),
        )
        return GameRecord(
            id = recId,
            mode = GameMode.X01,
            createdAtEpochMs = createdAt,
            updatedAtEpochMs = createdAt,
            state = state,
        )
    }

    // ---- first_win ----------------------------------------------------------

    @Test
    fun firstWin_lockedWithNoWins() {
        // A single finished-but-lost game: first_win stays locked.
        val lost = x01Record("r1", listOf(turn(40, 20)), startScore = 40, won = false)
        val s = status("first_win", listOf(lost))
        assertFalse(s.unlocked, "no wins -> locked")
        assertEquals(0, s.progress, "no progress")
        assertEquals(1, s.target, "one-shot target")
        assertNull(s.unlockedAtMs, "locked -> no timestamp")
    }

    @Test
    fun firstWin_unlockedOnWin_withTimestamp() {
        val won = x01Record(
            "r1", listOf(turn(40, 40, finished = true)),
            startScore = 40, won = true, createdAt = 1234L,
        )
        val s = status("first_win", listOf(won))
        assertTrue(s.unlocked, "won a game -> unlocked")
        assertEquals(1, s.progress, "progress hits target")
        assertEquals(1234L, s.unlockedAtMs, "unlockedAtMs = qualifying game createdAt")
    }

    // ---- games_N tiers ------------------------------------------------------

    @Test
    fun gamesTen_progressAndUnlockAtNthGame() {
        // 12 games created at t = 0..11 (each a played game). games_10 unlocks on
        // the 10th game (createdAt = 9), progress caps at the target (10, not 12).
        val games = (0 until 12).map { i ->
            x01Record("r$i", listOf(turn(40, 20)), startScore = 40, createdAt = i.toLong())
        }
        val s = status("games_10", games)
        assertTrue(s.unlocked, "12 >= 10 -> unlocked")
        assertEquals(10, s.progress, "progress capped at target")
        assertEquals(10, s.target)
        assertEquals(9L, s.unlockedAtMs, "10th game by createdAt is t=9")
    }

    @Test
    fun gamesTen_lockedBelowThreshold_showsRawProgress() {
        val games = (0 until 4).map { i ->
            x01Record("r$i", listOf(turn(40, 20)), startScore = 40, createdAt = i.toLong())
        }
        val s = status("games_10", games)
        assertFalse(s.unlocked, "4 < 10 -> locked")
        assertEquals(4, s.progress, "raw progress shown while locked")
        assertNull(s.unlockedAtMs, "locked -> null timestamp")
    }

    @Test
    fun gamesTen_unlockTimestampFollowsChronologicalOrder() {
        // Provide games OUT of created order (repo hands updatedAt order). The 10th
        // game in true createdAt order must supply the timestamp regardless.
        val games = (0 until 10).map { i ->
            x01Record("r$i", listOf(turn(40, 20)), startScore = 40, createdAt = i.toLong())
        }.shuffled()
        val s = status("games_10", games)
        assertTrue(s.unlocked)
        assertEquals(9L, s.unlockedAtMs, "10th game chronologically is createdAt=9")
    }

    // ---- ton_club / one_eighty ---------------------------------------------

    @Test
    fun tonClubAndOneEighty_detection() {
        // One 180 visit (non-bust) -> both ton_club and one_eighty unlock.
        val g = x01Record(
            "r1",
            listOf(turn(501, 180), turn(321, 60)),
            won = false, createdAt = 7L,
        )
        val all = achievementsFor(PID, listOf(g))
        assertTrue(all.byId("ton_club").unlocked, "180 is a 100+ visit")
        assertTrue(all.byId("one_eighty").unlocked, "exact 180 unlocks one_eighty")
        assertEquals(7L, all.byId("one_eighty").unlockedAtMs, "timestamp from the game")
    }

    @Test
    fun tonClub_bustHundredDoesNotCount_andSub100Locked() {
        // A busted 100 must NOT count; an 80 visit is below the ton bar.
        val g = x01Record(
            "r1",
            listOf(turn(120, 100, bust = true), turn(120, 80)),
        )
        val all = achievementsFor(PID, listOf(g))
        assertFalse(all.byId("ton_club").unlocked, "busted 100 + 80 -> no ton")
        assertFalse(all.byId("one_eighty").unlocked, "no 180 -> locked")
    }

    // ---- high_finish_100 / big_fish via a real finishing turn --------------

    @Test
    fun highFinish_and_bigFish_via170Checkout() {
        // 501 -> 170 left -> finish with a 170 checkout. checkout(ps) = 170, which
        // is both a 100+ finish and the big_fish maximum.
        val turns = listOf(
            turn(501, 180),
            turn(321, 151),
            turn(170, 170, finished = true),
        )
        val g = x01Record("r1", turns, won = true, createdAt = 3L)
        val all = achievementsFor(PID, listOf(g))
        assertTrue(all.byId("high_finish_100").unlocked, "170 checkout is a 100+ finish")
        assertTrue(all.byId("big_fish").unlocked, "170 checkout is the big fish")
        assertEquals(3L, all.byId("big_fish").unlockedAtMs)
    }

    @Test
    fun highFinish_unlocked_butBigFishLocked_at100() {
        // A 100 checkout clears high_finish_100 but is not the 170 big_fish.
        // 200 start -> 100 left -> finish with a 100 checkout.
        val seq = listOf(
            turn(200, 100),
            turn(100, 100, finished = true),
        )
        val g = x01Record("r1", seq, startScore = 200, won = true)
        val all = achievementsFor(PID, listOf(g))
        assertTrue(all.byId("high_finish_100").unlocked, "100 checkout clears high finish")
        assertFalse(all.byId("big_fish").unlocked, "100 != 170 -> big_fish locked")
    }

    // ---- sharp_18 -----------------------------------------------------------

    @Test
    fun sharp18_wonLegInUnderEighteenDarts() {
        // 6 turns (18 darts) finishing a won leg -> exactly the 18-dart bound.
        val turns = listOf(
            turn(501, 100),
            turn(401, 100),
            turn(301, 100),
            turn(201, 100),
            turn(101, 60),
            turn(41, 41, finished = true),
        )
        val g = x01Record("r1", turns, won = true)
        val s = status("sharp_18", listOf(g))
        assertTrue(s.unlocked, "won leg in 18 darts is sharp")
    }

    @Test
    fun sharp18_lockedWhenFinishTakesTooManyDarts() {
        // 7 turns (21 darts) to finish -> over the 18-dart bound.
        val turns = listOf(
            turn(501, 80),
            turn(421, 80),
            turn(341, 80),
            turn(261, 80),
            turn(181, 80),
            turn(101, 60),
            turn(41, 41, finished = true),
        )
        val g = x01Record("r1", turns, won = true)
        val s = status("sharp_18", listOf(g))
        assertFalse(s.unlocked, "21 darts is not sharp")
    }

    @Test
    fun sharp18_lockedWhenLegNotWon() {
        // A short finished sequence but the seat is NOT a winner -> not a won leg.
        val turns = listOf(turn(40, 40, finished = true))
        val g = x01Record("r1", turns, startScore = 40, won = false)
        val s = status("sharp_18", listOf(g))
        assertFalse(s.unlocked, "leg must be won to count")
    }

    // ---- shanghai_master (via the model factory + applyTurn) ---------------

    @Test
    fun shanghaiMaster_instantWin() {
        // Build a real Shanghai game and trigger an instant Shanghai on round 1
        // (1 single + 1 double + 1 triple). The model sets winnerIndices for us.
        val player = GamePlayer(name = NAME, id = PID)
        val state = ShanghaiState.new(listOf(player)).applyTurn(singles = 1, doubles = 1, triples = 1)
        assertTrue(state.isFinished, "Shanghai is an instant win")
        val g = GameRecord("r1", GameMode.SHANGHAI, 5L, 5L, state)
        val s = status("shanghai_master", listOf(g))
        assertTrue(s.unlocked, "instant Shanghai unlocks the achievement")
        assertEquals(5L, s.unlockedAtMs)
    }

    @Test
    fun shanghaiMaster_lockedWhenWinIsNotAShanghai() {
        // A plain Shanghai win (highest total after 7 rounds) where the last turn
        // is not itself a Shanghai must NOT unlock shanghai_master.
        val player = GamePlayer(name = NAME, id = PID)
        var state = ShanghaiState.new(listOf(player))
        // 7 rounds, scoring a single each round (never a Shanghai).
        repeat(7) { state = state.applyTurn(singles = 1, doubles = 0, triples = 0) }
        assertTrue(state.isFinished, "game ends after 7 rounds")
        val g = GameRecord("r1", GameMode.SHANGHAI, 0L, 0L, state)
        assertFalse(status("shanghai_master", listOf(g)).unlocked, "no instant Shanghai")
    }

    // ---- clock_cleaner (Around the Clock win) ------------------------------

    @Test
    fun clockCleaner_winAroundTheClock() {
        val player = GamePlayer(name = NAME, id = PID)
        var state = AroundTheClockState.new(listOf(player))
        // Clear all 20 targets: 7 turns of 3 (=21 capped to 20).
        repeat(7) { state = state.applyTurn(hits = 3) }
        assertTrue(state.isFinished, "cleared the board -> winner")
        val g = GameRecord("r1", GameMode.AROUND_CLOCK, 8L, 8L, state)
        val s = status("clock_cleaner", listOf(g))
        assertTrue(s.unlocked, "winning ATC unlocks clock_cleaner")
        assertEquals(8L, s.unlockedAtMs)
    }

    // ---- streak_3 across ordered games -------------------------------------

    @Test
    fun streak3_unlocksOnThreeConsecutiveWins() {
        // W, W, W in created order -> best streak 3.
        val g = (0 until 3).map { i ->
            x01Record("r$i", listOf(turn(40, 40, finished = true)), startScore = 40, won = true, createdAt = i.toLong())
        }
        val s = status("streak_3", g)
        assertTrue(s.unlocked, "three wins in a row")
        assertEquals(3, s.progress, "best streak length is 3")
        assertNull(s.unlockedAtMs, "streak spans games -> no single timestamp")
    }

    @Test
    fun streak3_lossBreaksTheStreak() {
        // W, W, LOSS, W, W (chronological) -> best run is only 2, locked. Provided
        // OUT of order to prove the engine sorts by createdAtEpochMs.
        val wins1 = x01Record("a", listOf(turn(40, 40, finished = true)), startScore = 40, won = true, createdAt = 0L)
        val wins2 = x01Record("b", listOf(turn(40, 40, finished = true)), startScore = 40, won = true, createdAt = 1L)
        val loss = x01Record("c", listOf(turn(40, 20)), startScore = 40, won = false, createdAt = 2L)
        val wins3 = x01Record("d", listOf(turn(40, 40, finished = true)), startScore = 40, won = true, createdAt = 3L)
        val wins4 = x01Record("e", listOf(turn(40, 40, finished = true)), startScore = 40, won = true, createdAt = 4L)
        val s = status("streak_3", listOf(wins4, loss, wins1, wins3, wins2)) // shuffled input
        assertFalse(s.unlocked, "a loss in the middle caps the streak at 2")
        assertEquals(2, s.progress, "best streak length is 2")
    }

    // ---- all_rounder distinct-mode counting --------------------------------

    @Test
    fun allRounder_countsDistinctModesWon() {
        val player = GamePlayer(name = NAME, id = PID)
        fun winRecord(id: String, mode: GameMode): GameRecord {
            // The state's concrete type doesn't matter for all_rounder (it keys on
            // GameRecord.mode + a winning seat); reuse a trivially-won X01 state.
            val state = X01State(
                players = listOf(player),
                perPlayer = listOf(X01PlayerState(player, listOf(turn(40, 40, finished = true)))),
                startScore = 40,
                winnerIndices = listOf(0),
            )
            return GameRecord(id, mode, 0L, 0L, state)
        }
        // Win in 4 distinct modes (X01 counted once across two records) -> locked;
        // adding a 5th distinct mode unlocks.
        val four = listOf(
            winRecord("g1", GameMode.X01),
            winRecord("g2", GameMode.X01),
            winRecord("g3", GameMode.CRICKET),
            winRecord("g4", GameMode.HALF_IT),
            winRecord("g5", GameMode.SHANGHAI),
        )
        val sFour = status("all_rounder", four)
        assertFalse(sFour.unlocked, "only 4 distinct modes won")
        assertEquals(4, sFour.progress, "X01 duplicates collapse to one")

        val five = four + winRecord("g6", GameMode.AROUND_CLOCK)
        val sFive = status("all_rounder", five)
        assertTrue(sFive.unlocked, "5 distinct modes -> unlocked")
        assertEquals(5, sFive.progress)
        assertNull(sFive.unlockedAtMs, "distinct-mode unlock spans games")
    }

    @Test
    fun allRounder_lostGamesDoNotCountMode() {
        // Playing 5 modes but WINNING none -> all_rounder stays at 0.
        val player = GamePlayer(name = NAME, id = PID)
        fun lossRecord(id: String, mode: GameMode) = GameRecord(
            id, mode, 0L, 0L,
            HalfItState(
                players = listOf(player),
                perPlayer = listOf(com.dartrack.model.HalfItPlayerState(player)),
            ),
        )
        val games = listOf(
            lossRecord("g1", GameMode.X01),
            lossRecord("g2", GameMode.CRICKET),
            lossRecord("g3", GameMode.HALF_IT),
            lossRecord("g4", GameMode.SHANGHAI),
            lossRecord("g5", GameMode.BOBS_27),
        )
        val s = status("all_rounder", games)
        assertEquals(0, s.progress, "no wins -> no distinct modes")
        assertFalse(s.unlocked)
    }

    // ---- golf_under_par -----------------------------------------------------

    @Test
    fun golfUnderPar_finishingBelowPar() {
        // Single seat; play all 9 holes as TRIPLE (1 stroke each) -> 9 strokes,
        // well under the par of 3 * 9 = 27. The model sets winnerIndices on finish.
        val player = GamePlayer(name = NAME, id = PID)
        var state = GolfState.new(listOf(player))
        repeat(GOLF_HOLES) { state = state.applyResult(GolfResult.TRIPLE) }
        assertTrue(state.isFinished, "all holes played -> finished")
        val g = GameRecord("r1", GameMode.GOLF, 11L, 11L, state)
        val s = status("golf_under_par", listOf(g))
        assertTrue(s.unlocked, "9 strokes is under par")
        assertEquals(11L, s.unlockedAtMs, "timestamp from the qualifying game")
    }

    @Test
    fun golfUnderPar_lockedWhenOverPar() {
        // All MISS (5 strokes each) -> 45 strokes, well over par -> locked even
        // though the game is finished.
        val player = GamePlayer(name = NAME, id = PID)
        var state = GolfState.new(listOf(player))
        repeat(GOLF_HOLES) { state = state.applyResult(GolfResult.MISS) }
        assertTrue(state.isFinished)
        val g = GameRecord("r1", GameMode.GOLF, 0L, 0L, state)
        val s = status("golf_under_par", listOf(g))
        assertFalse(s.unlocked, "45 strokes is over par")
        assertNull(s.unlockedAtMs, "locked -> null timestamp")
    }

    // ---- checkout_perfect ---------------------------------------------------

    @Test
    fun checkoutPerfect_hittingEveryTarget() {
        // A short 2-target ladder, both HIT -> hits == targets.size.
        val player = GamePlayer(name = NAME, id = PID)
        var state = CheckoutTrainerState.new(listOf(player), targets = listOf(40, 60))
        state = state.applyAttempt(hit = true, darts = 2)
        state = state.applyAttempt(hit = true, darts = 3)
        assertTrue(state.isFinished, "both targets attempted -> finished")
        val g = GameRecord("r1", GameMode.CHECKOUT_TRAINER, 9L, 9L, state)
        val s = status("checkout_perfect", listOf(g))
        assertTrue(s.unlocked, "hit every target")
        assertEquals(9L, s.unlockedAtMs)
    }

    @Test
    fun checkoutPerfect_lockedWhenOneTargetMissed() {
        // First HIT, second MISS -> hits (1) < targets.size (2) -> locked.
        val player = GamePlayer(name = NAME, id = PID)
        var state = CheckoutTrainerState.new(listOf(player), targets = listOf(40, 60))
        state = state.applyAttempt(hit = true, darts = 2)
        state = state.applyAttempt(hit = false, darts = 0)
        assertTrue(state.isFinished)
        val g = GameRecord("r1", GameMode.CHECKOUT_TRAINER, 0L, 0L, state)
        assertFalse(status("checkout_perfect", listOf(g)).unlocked, "one miss breaks perfection")
    }

    // ---- baseball_slugger ---------------------------------------------------

    @Test
    fun baseballSlugger_reachingThirtyRuns() {
        // 3 triples per inning = 9 runs/inning; over 9 innings that is 81 >= 30.
        val player = GamePlayer(name = NAME, id = PID)
        var state = BaseballState.new(listOf(player))
        repeat(9) { state = state.applyTurn(singles = 0, doubles = 0, triples = 3) }
        assertTrue(state.isFinished, "9 innings played -> finished")
        assertTrue(state.perPlayer[0].total >= 30, "sanity: total clears the bar")
        val g = GameRecord("r1", GameMode.BASEBALL, 12L, 12L, state)
        val s = status("baseball_slugger", listOf(g))
        assertTrue(s.unlocked, "30+ runs unlocks slugger")
        assertEquals(12L, s.unlockedAtMs)
    }

    @Test
    fun baseballSlugger_lockedBelowThreshold() {
        // 1 single per inning = 1 run/inning -> 9 total < 30 -> locked.
        val player = GamePlayer(name = NAME, id = PID)
        var state = BaseballState.new(listOf(player))
        repeat(9) { state = state.applyTurn(singles = 1, doubles = 0, triples = 0) }
        assertTrue(state.isFinished)
        val g = GameRecord("r1", GameMode.BASEBALL, 0L, 0L, state)
        assertFalse(status("baseball_slugger", listOf(g)).unlocked, "9 runs is below 30")
    }

    // ---- countup_high -------------------------------------------------------

    @Test
    fun countUpHigh_reachingFourHundred() {
        // 60 per round over 8 rounds = 480 >= 400.
        val player = GamePlayer(name = NAME, id = PID)
        var state = CountUpState.new(listOf(player))
        repeat(8) { state = state.applyTurn(60) }
        assertTrue(state.isFinished, "8 rounds played -> finished")
        val g = GameRecord("r1", GameMode.COUNT_UP, 13L, 13L, state)
        val s = status("countup_high", listOf(g))
        assertTrue(s.unlocked, "480 clears the 400 bar")
        assertEquals(13L, s.unlockedAtMs)
    }

    @Test
    fun countUpHigh_lockedBelowThreshold() {
        // 20 per round over 8 rounds = 160 < 400 -> locked.
        val player = GamePlayer(name = NAME, id = PID)
        var state = CountUpState.new(listOf(player))
        repeat(8) { state = state.applyTurn(20) }
        assertTrue(state.isFinished)
        val g = GameRecord("r1", GameMode.COUNT_UP, 0L, 0L, state)
        assertFalse(status("countup_high", listOf(g)).unlocked, "160 is below 400")
    }

    // ---- gotcha_winner ------------------------------------------------------

    @Test
    fun gotchaWinner_landingExactlyOnTarget() {
        // Single seat racing to 301: 180 then 121 lands exactly -> instant win.
        val player = GamePlayer(name = NAME, id = PID)
        var state = GotchaState.new(listOf(player), target = 301)
        state = state.applyTurn(180)
        state = state.applyTurn(121)
        assertTrue(state.isFinished, "landed exactly on 301 -> won")
        assertTrue(state.winnerIndices.contains(0))
        val g = GameRecord("r1", GameMode.GOTCHA, 14L, 14L, state)
        val s = status("gotcha_winner", listOf(g))
        assertTrue(s.unlocked, "winning Gotcha unlocks the achievement")
        assertEquals(14L, s.unlockedAtMs)
    }

    @Test
    fun gotchaWinner_lockedWhenNotWon() {
        // Two turns that never reach 301 -> game unfinished, no winner -> locked.
        val player = GamePlayer(name = NAME, id = PID)
        var state = GotchaState.new(listOf(player), target = 301)
        state = state.applyTurn(100)
        state = state.applyTurn(100)
        assertFalse(state.isFinished, "still short of target")
        val g = GameRecord("r1", GameMode.GOTCHA, 0L, 0L, state)
        assertFalse(status("gotcha_winner", listOf(g)).unlocked, "no win -> locked")
    }

    // ---- cricket_winner -----------------------------------------------------

    @Test
    fun cricketWinner_closingEveryTarget() {
        // Single seat: close all 7 targets (3 marks each) -> sole winner.
        val player = GamePlayer(name = NAME, id = PID)
        var state = CricketState.new(listOf(player))
        for (target in com.dartrack.model.CRICKET_TARGETS) {
            state = state.applyTurn(mapOf(target to 3))
        }
        assertTrue(state.isFinished, "all targets closed -> winner")
        assertTrue(state.winnerIndices.contains(0))
        val g = GameRecord("r1", GameMode.CRICKET, 30L, 30L, state)
        val s = status("cricket_winner", listOf(g))
        assertTrue(s.unlocked, "winning Cricket unlocks the achievement")
        assertEquals(30L, s.unlockedAtMs)
    }

    @Test
    fun cricketWinner_lockedWhenNotWon() {
        // Only one target closed -> game not finished, no win -> locked.
        val player = GamePlayer(name = NAME, id = PID)
        var state = CricketState.new(listOf(player))
        state = state.applyTurn(mapOf(20 to 3))
        assertFalse(state.isFinished, "board not cleared")
        val g = GameRecord("r1", GameMode.CRICKET, 0L, 0L, state)
        assertFalse(status("cricket_winner", listOf(g)).unlocked, "no win -> locked")
    }

    // ---- half_it_winner -----------------------------------------------------

    @Test
    fun halfItWinner_winningTheGame() {
        // Single seat: play all 9 rounds (sole player always wins on the last).
        val player = GamePlayer(name = NAME, id = PID)
        var state = HalfItState.new(listOf(player))
        repeat(com.dartrack.model.HALF_IT_ROUNDS.size) { state = state.applyTurn(30) }
        assertTrue(state.isFinished, "all rounds played -> winner")
        assertTrue(state.winnerIndices.contains(0))
        val g = GameRecord("r1", GameMode.HALF_IT, 31L, 31L, state)
        val s = status("half_it_winner", listOf(g))
        assertTrue(s.unlocked, "winning Half-It unlocks the achievement")
        assertEquals(31L, s.unlockedAtMs)
    }

    @Test
    fun halfItWinner_lockedWhenNotFinished() {
        // A single round played -> game unfinished, no winner -> locked.
        val player = GamePlayer(name = NAME, id = PID)
        var state = HalfItState.new(listOf(player))
        state = state.applyTurn(30)
        assertFalse(state.isFinished, "rounds remain")
        val g = GameRecord("r1", GameMode.HALF_IT, 0L, 0L, state)
        assertFalse(status("half_it_winner", listOf(g)).unlocked, "no win -> locked")
    }

    // ---- killer_untouchable -------------------------------------------------

    @Test
    fun killerUntouchable_winWithoutLosingALife() {
        // Two-seat Killer, default 3 lives. Seat 0 promotes and drains seat 1 to 0
        // while seat 1 never lands a damaging dart -> seat 0 wins with full lives.
        val p0 = GamePlayer(name = NAME, id = PID)
        val p1 = GamePlayer(name = "Bob", id = "p-2")
        var state = KillerState.new(listOf(p0, p1))
        state = state.applyTurn(listOf(0, 1, 1)) // promote, seat1 3 -> 1
        state = state.applyTurn(emptyList())      // seat 1 does nothing
        state = state.applyTurn(listOf(1))        // seat1 1 -> 0, seat 0 wins
        assertTrue(state.isFinished, "one player left -> finished")
        assertEquals(listOf(0), state.winnerIndices, "sole winner is seat 0")
        assertEquals(state.startLives, state.perPlayer[0].lives, "seat 0 kept all lives")
        val g = GameRecord("r1", GameMode.KILLER, 40L, 40L, state)
        val all = achievementsFor(PID, listOf(g))
        assertTrue(all.byId("killer_winner").unlocked, "won Killer")
        assertTrue(all.byId("killer_untouchable").unlocked, "won without losing a life")
        assertEquals(40L, all.byId("killer_untouchable").unlockedAtMs)
    }

    @Test
    fun killerUntouchable_lockedWhenALifeIsLost() {
        // Same shape but seat 0 self-kills along the way, finishing below startLives:
        // killer_winner unlocks, killer_untouchable stays locked.
        val p0 = GamePlayer(name = NAME, id = PID)
        val p1 = GamePlayer(name = "Bob", id = "p-2")
        var state = KillerState.new(listOf(p0, p1))
        state = state.applyTurn(listOf(0, 1)) // promote, seat1 3 -> 2
        state = state.applyTurn(emptyList())
        state = state.applyTurn(listOf(0, 1)) // self-kill (3 -> 2), seat1 2 -> 1
        state = state.applyTurn(emptyList())
        state = state.applyTurn(listOf(1))     // seat1 1 -> 0, seat 0 wins
        assertTrue(state.isFinished)
        assertEquals(listOf(0), state.winnerIndices, "sole winner is seat 0")
        assertTrue(state.perPlayer[0].lives < state.startLives, "seat 0 lost a life")
        val g = GameRecord("r1", GameMode.KILLER, 0L, 0L, state)
        val all = achievementsFor(PID, listOf(g))
        assertTrue(all.byId("killer_winner").unlocked, "still a Killer win")
        assertFalse(all.byId("killer_untouchable").unlocked, "a life was lost -> locked")
    }

    // ---- bermuda_treasure ---------------------------------------------------

    @Test
    fun bermudaTreasure_reachingTwoFiftyOrMore() {
        // Single seat scoring 60 every round -> final total 720 >= 250.
        val player = GamePlayer(name = NAME, id = PID)
        var state = BermudaState.new(listOf(player))
        repeat(com.dartrack.model.BERMUDA_ROUNDS.size) { state = state.applyTurn(60) }
        assertTrue(state.isFinished, "all rounds played -> finished")
        assertTrue(state.perPlayer[0].total >= 250, "sanity: total clears the bar")
        val g = GameRecord("r1", GameMode.BERMUDA, 41L, 41L, state)
        val s = status("bermuda_treasure", listOf(g))
        assertTrue(s.unlocked, "250+ total unlocks treasure hunter")
        assertEquals(41L, s.unlockedAtMs)
    }

    @Test
    fun bermudaTreasure_lockedAtTwoFortyNine() {
        // Score 20 for 11 rounds (=220) + 29 in the last -> final total 249 < 250.
        val player = GamePlayer(name = NAME, id = PID)
        var state = BermudaState.new(listOf(player))
        repeat(com.dartrack.model.BERMUDA_ROUNDS.size - 1) { state = state.applyTurn(20) }
        state = state.applyTurn(29)
        assertTrue(state.isFinished)
        assertEquals(249, state.perPlayer[0].total, "sanity: exactly 249")
        val g = GameRecord("r1", GameMode.BERMUDA, 0L, 0L, state)
        assertFalse(status("bermuda_treasure", listOf(g)).unlocked, "249 is below 250")
    }

    // ---- full_house ---------------------------------------------------------

    @Test
    fun fullHouse_requiresPlayingEveryMode() {
        // Play (win or lose) each mode at least once -> unlock once all modes seen.
        val player = GamePlayer(name = NAME, id = PID)
        fun playRecord(id: String, mode: GameMode): GameRecord {
            val state = X01State(
                players = listOf(player),
                perPlayer = listOf(X01PlayerState(player, listOf(turn(40, 20)))),
                startScore = 40,
            )
            return GameRecord(id, mode, 0L, 0L, state)
        }
        val allModes = GameMode.values().toList()
        // Play every mode but one -> locked at size-1; the last one unlocks.
        val missingOne = allModes.dropLast(1).mapIndexed { i, m -> playRecord("g$i", m) }
        val sMissing = status("full_house", missingOne)
        assertFalse(sMissing.unlocked, "one mode still unplayed")
        assertEquals(allModes.size - 1, sMissing.progress)

        val complete = missingOne + playRecord("last", allModes.last())
        val sComplete = status("full_house", complete)
        assertTrue(sComplete.unlocked, "playing every mode unlocks full_house")
        assertEquals(allModes.size, sComplete.progress)
        assertNull(sComplete.unlockedAtMs, "breadth unlock spans games")
    }

    // ---- bot_slayer_pro / bot_slayer_hard -----------------------------------

    /**
     * A two-seat X01 game where the human [PID] sits at seat 0 and a CPU opponent
     * of [level] sits at seat 1. [humanWins] decides who is recorded as the winner.
     */
    private fun botGame(
        recId: String,
        level: BotLevel,
        humanWins: Boolean,
        mode: GameMode = GameMode.X01,
        createdAt: Long = 0L,
    ): GameRecord {
        val human = GamePlayer(name = NAME, id = PID)
        val bot = GamePlayer(name = "CPU", id = "bot:1", isBot = true, botLevel = level)
        val state = X01State(
            players = listOf(human, bot),
            perPlayer = listOf(
                X01PlayerState(human, listOf(turn(40, 40, finished = true))),
                X01PlayerState(bot, listOf(turn(40, 20))),
            ),
            startScore = 40,
            winnerIndices = if (humanWins) listOf(0) else listOf(1),
        )
        return GameRecord(recId, mode, createdAt, createdAt, state)
    }

    @Test
    fun botSlayerPro_beatingAProBot() {
        val g = botGame("r1", BotLevel.PRO, humanWins = true, createdAt = 21L)
        val all = achievementsFor(PID, listOf(g))
        assertTrue(all.byId("bot_slayer_pro").unlocked, "beat a PRO bot")
        assertEquals(21L, all.byId("bot_slayer_pro").unlockedAtMs)
        // Beating PRO does not imply the HARD-specific feat.
        assertFalse(all.byId("bot_slayer_hard").unlocked, "opponent was PRO, not HARD")
    }

    @Test
    fun botSlayerHard_beatingAHardBot() {
        val g = botGame("r1", BotLevel.HARD, humanWins = true, mode = GameMode.COUNT_UP)
        val s = status("bot_slayer_hard", listOf(g))
        assertTrue(s.unlocked, "beat a HARD bot in Count-Up (keys on the seat, not the mode)")
    }

    @Test
    fun botSlayer_lockedWhenBotWins() {
        // The PRO bot wins -> the human did not beat it -> both bot feats locked.
        val g = botGame("r1", BotLevel.PRO, humanWins = false)
        val all = achievementsFor(PID, listOf(g))
        assertFalse(all.byId("bot_slayer_pro").unlocked, "human lost -> no slaying")
        assertFalse(all.byId("bot_slayer_hard").unlocked)
    }

    @Test
    fun botSlayer_lockedWhenOpponentTooEasy() {
        // Human beats a MEDIUM bot: neither the HARD nor PRO feat unlocks.
        val g = botGame("r1", BotLevel.MEDIUM, humanWins = true)
        val all = achievementsFor(PID, listOf(g))
        assertFalse(all.byId("bot_slayer_pro").unlocked, "MEDIUM is below PRO")
        assertFalse(all.byId("bot_slayer_hard").unlocked, "MEDIUM is below HARD")
    }

    // ---- mode_explorer ------------------------------------------------------

    @Test
    fun modeExplorer_countsDistinctModesPlayed() {
        // Play (not necessarily win) 7 distinct modes -> locked at 7; an 8th
        // distinct mode unlocks. Wins are irrelevant to "played".
        val player = GamePlayer(name = NAME, id = PID)
        fun playRecord(id: String, mode: GameMode): GameRecord {
            val state = X01State(
                players = listOf(player),
                perPlayer = listOf(X01PlayerState(player, listOf(turn(40, 20)))),
                startScore = 40,
            )
            return GameRecord(id, mode, 0L, 0L, state)
        }
        val sevenModes = listOf(
            GameMode.X01, GameMode.CRICKET, GameMode.HALF_IT, GameMode.AROUND_CLOCK,
            GameMode.BOBS_27, GameMode.SHANGHAI, GameMode.CATCH_40,
        )
        val seven = sevenModes.mapIndexed { i, m -> playRecord("g$i", m) }
        val sSeven = status("mode_explorer", seven)
        assertFalse(sSeven.unlocked, "only 7 distinct modes played")
        assertEquals(7, sSeven.progress)

        val eight = seven + playRecord("g7", GameMode.COUNT_UP)
        val sEight = status("mode_explorer", eight)
        assertTrue(sEight.unlocked, "8 distinct modes -> unlocked")
        assertEquals(8, sEight.progress)
        assertNull(sEight.unlockedAtMs, "breadth unlock spans games")
    }

    // ---- streak_5 -----------------------------------------------------------

    @Test
    fun streak5_unlocksOnFiveConsecutiveWins() {
        val g = (0 until 5).map { i ->
            x01Record("r$i", listOf(turn(40, 40, finished = true)), startScore = 40, won = true, createdAt = i.toLong())
        }
        val s = status("streak_5", g)
        assertTrue(s.unlocked, "five wins in a row")
        assertEquals(5, s.progress, "best streak length is 5")
        assertNull(s.unlockedAtMs, "streak spans games -> no single timestamp")
        // A run of 5 also satisfies the lower streak_3 tier.
        assertTrue(status("streak_3", g).unlocked, "5 in a row covers streak_3 too")
    }

    @Test
    fun streak5_lockedAtFourConsecutiveWins() {
        // W, W, W, W -> best run 4, below the streak_5 bar.
        val g = (0 until 4).map { i ->
            x01Record("r$i", listOf(turn(40, 40, finished = true)), startScore = 40, won = true, createdAt = i.toLong())
        }
        val s = status("streak_5", g)
        assertFalse(s.unlocked, "four wins is not five")
        assertEquals(4, s.progress, "best streak length is 4")
    }

    // ---- all_twelve ---------------------------------------------------------

    @Test
    fun allTwelve_requiresAWinInEveryMode() {
        val player = GamePlayer(name = NAME, id = PID)
        fun winRecord(id: String, mode: GameMode): GameRecord {
            val state = X01State(
                players = listOf(player),
                perPlayer = listOf(X01PlayerState(player, listOf(turn(40, 40, finished = true)))),
                startScore = 40,
                winnerIndices = listOf(0),
            )
            return GameRecord(id, mode, 0L, 0L, state)
        }
        val allModes = GameMode.values().toList()
        // Win every mode but one -> locked at size-1; adding the last unlocks.
        val missingOne = allModes.dropLast(1).mapIndexed { i, m -> winRecord("g$i", m) }
        val sMissing = status("all_twelve", missingOne)
        assertFalse(sMissing.unlocked, "one mode still un-won")
        assertEquals(allModes.size - 1, sMissing.progress)

        val complete = missingOne + winRecord("last", allModes.last())
        val sComplete = status("all_twelve", complete)
        assertTrue(sComplete.unlocked, "a win in every mode unlocks all_twelve")
        assertEquals(allModes.size, sComplete.progress)
        assertNull(sComplete.unlockedAtMs, "spans games -> null timestamp")
    }

    // ---- edge cases: blank id, non-participant, summary ---------------------

    @Test
    fun blankPlayerId_allLocked() {
        val g = x01Record("r1", listOf(turn(40, 40, finished = true)), startScore = 40, won = true)
        val all = achievementsFor("", listOf(g))
        assertEquals(AchievementCatalog.all.size, all.size, "one status per catalog entry")
        assertTrue(all.none { it.unlocked }, "blank id matches nothing -> all locked")
        assertTrue(all.all { it.unlockedAtMs == null }, "all timestamps null")
        val summary = achievementSummary("", listOf(g))
        assertEquals(0, summary.unlocked, "summary: nothing unlocked")
        assertEquals(AchievementCatalog.all.size, summary.total, "summary total = catalog size")
    }

    @Test
    fun playerNotInGame_isExcluded() {
        // Only p-2 plays (and wins); p-1 has no seat -> p-1 unlocks nothing.
        val g = x01Record(
            "r1", listOf(turn(40, 40, finished = true)),
            startScore = 40, won = true, playerId = "p-2", playerName = "Bob",
        )
        val all = achievementsFor(PID, listOf(g))
        assertTrue(all.none { it.unlocked }, "non-participant unlocks nothing")
        assertFalse(all.byId("first_win").unlocked, "no win credited to p-1")
        assertEquals(0, all.byId("games_10").progress, "no games counted for p-1")
    }

    @Test
    fun summary_countsUnlockedAcrossCatalog() {
        // A 180 + a finishing 170 in a won, sub-18-dart leg unlocks several at once.
        val turns = listOf(
            turn(501, 180),
            turn(321, 151),
            turn(170, 170, finished = true),
        )
        val g = x01Record("r1", turns, won = true, createdAt = 1L)
        val summary = achievementSummary(PID, listOf(g))
        // Expected unlocks from this single game: first_win, ton_club, one_eighty,
        // high_finish_100, big_fish, sharp_18 (3 turns = 9 darts, won leg).
        assertEquals(6, summary.unlocked, "six achievements from one rich game")
        assertEquals(AchievementCatalog.all.size, summary.total)
        // Sanity: the engine returns exactly the catalog in order.
        val ids = achievementsFor(PID, listOf(g)).map { it.achievement.id }
        assertEquals(AchievementCatalog.all.map { it.id }, ids, "catalog order preserved")
    }
}
