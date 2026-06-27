package com.dartrack.model.bot

import com.dartrack.model.CRICKET_MARKS_TO_CLOSE
import com.dartrack.model.CRICKET_TARGETS
import com.dartrack.model.CricketState
import com.dartrack.model.GamePlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the Cricket branch of the pure CPU opponent [DartsBot.cricketVisit].
 * Everything is deterministic via injected seeds, so the statistical assertions
 * are stable run-to-run. No Android, no IO, no global RNG.
 */
class CricketBotTest {

    private fun players(n: Int): List<GamePlayer> =
        (0 until n).map { GamePlayer("P$it", "id$it") }

    private fun freshState(n: Int = 2, cutThroat: Boolean = false): CricketState =
        CricketState.new(players(n), cutThroat)

    private fun assertWellFormed(visit: Map<Int, Int>, ctx: String) {
        val sum = visit.values.sum()
        assertTrue(sum in 0..9, "$ctx: marks sum $sum out of 0..9")
        for ((tgt, m) in visit) {
            assertTrue(tgt in CRICKET_TARGETS, "$ctx: key $tgt not a Cricket target")
            assertTrue(m >= 0, "$ctx: value $m negative for target $tgt")
        }
    }

    // -------------------------------------------------------- shape / legality

    @Test
    fun cricketVisit_isAlwaysWellFormed_acrossManySeeds() {
        for (seed in 0L until 1000L) {
            val bot = DartsBot(BotLevel.PRO, seed = seed)
            val visit = bot.cricketVisit(freshState(), playerIndex = 0)
            assertWellFormed(visit, "PRO seed=$seed")
        }
        // And across levels / a few seats, the contract must hold too.
        for (level in BotLevel.entries) {
            for (seed in 0L until 50L) {
                val bot = DartsBot(level, seed = seed)
                val s = freshState(n = 4)
                assertWellFormed(bot.cricketVisit(s, playerIndex = 0), "$level/4p seed=$seed")
            }
        }
    }

    @Test
    fun cricketVisit_resultIsAcceptedByApplyTurn() {
        // Whatever the bot returns must satisfy CricketState.applyTurn's require()s.
        var state = freshState()
        val bot = DartsBot(BotLevel.HARD, seed = 7L)
        repeat(200) {
            val visit = bot.cricketVisit(state, state.currentPlayerIndex)
            state = state.applyTurn(visit) // throws if illegal
            if (state.isFinished) state = freshState()
        }
    }

    // ----------------------------------------------------------- aim priority

    @Test
    fun freshGame_aimsAt20First_majorityOfMarksLandOn20() {
        // From a fresh state, the FIRST aimed number is always 20 (highest unclosed
        // value), so across many independent fresh visits 20 should collect the
        // lion's share of marks.
        var on20 = 0
        var total = 0
        for (seed in 0L until 2000L) {
            val bot = DartsBot(BotLevel.HARD, seed = seed)
            val visit = bot.cricketVisit(freshState(), playerIndex = 0)
            on20 += visit[20] ?: 0
            total += visit.values.sum()
        }
        assertTrue(total > 0, "expected some marks overall")
        assertTrue(
            on20 > total / 2,
            "20 should take the majority of marks from fresh visits (got $on20 of $total)",
        )
    }

    @Test
    fun aimsHighestUnclosedFirst_thenMovesDown() {
        // From a fresh board the bot grinds 20, then 19, then 18 — a CONTIGUOUS
        // descending prefix of CRICKET_TARGETS. It can never touch a number while a
        // strictly-higher one is still open (un-closed) at that point. With only 3
        // darts the furthest it can reach is 18 (three trebles: close 20, 19, hit 18).
        for (seed in 0L until 500L) {
            val bot = DartsBot(BotLevel.PRO, seed = seed)
            val visit = bot.cricketVisit(freshState(), playerIndex = 0)
            // Touched targets, ordered as on the board, must form a prefix of
            // 20,19,18,... with no gaps and never reach below 18.
            val touchedOrdered = CRICKET_TARGETS.filter { it in visit.keys }
            val expectedPrefix = CRICKET_TARGETS.take(touchedOrdered.size)
            assertEquals(
                expectedPrefix, touchedOrdered,
                "fresh visit must touch a top-down prefix (seed=$seed) → $visit",
            )
            assertTrue(
                touchedOrdered.all { it >= 18 },
                "fresh visit can't reach below 18 in 3 darts (seed=$seed) → $visit",
            )
        }
    }

    @Test
    fun closed20_doesNotPileOnto20_whenLowerNumbersOpen() {
        // Craft a state where the bot has already closed 20 (3 marks) but 19..15,25
        // are all still open. Every dart must now aim at 19 (next highest open),
        // so the returned visit must never add to 20.
        val seats = players(2)
        // Give player 0 a turn worth of 3 marks on 20 → 20 is closed, nothing else.
        var state = CricketState.new(seats)
        state = state.applyTurn(mapOf(20 to 3)) // player 0 closes 20
        state = state.applyTurn(emptyMap())      // player 1 throws nothing → back to P0

        assertTrue(state.perPlayer[0].isClosed(20), "precondition: P0 has closed 20")
        assertEquals(0, state.currentPlayerIndex, "precondition: it's P0's turn again")

        for (seed in 0L until 500L) {
            val bot = DartsBot(BotLevel.PRO, seed = seed)
            val visit = bot.cricketVisit(state, playerIndex = 0)
            assertEquals(
                null, visit[20],
                "bot should not add marks to an already-closed 20 (seed=$seed) → $visit",
            )
            // Marks, if any, start at 19 (next open) and descend with no gaps; with
            // 3 darts the lowest reachable from here is 17 (close 19, close 18, hit 17).
            val touchedOrdered = CRICKET_TARGETS.filter { it in visit.keys }
            val expectedPrefix = CRICKET_TARGETS.drop(1).take(touchedOrdered.size) // 19,18,17,...
            assertEquals(
                expectedPrefix, touchedOrdered,
                "with 20 closed, marks descend from 19 with no gaps (seed=$seed) → $visit",
            )
        }
    }

    @Test
    fun fullyClosedSelf_scoresOnTargetsOpponentsHaveNotClosed() {
        // Priority 2 of the aim heuristic: the bot has closed EVERY target while an
        // opponent has not, so extra marks score — it should aim at the highest such
        // value (20). We construct this mid-game position directly (P0 closed all via
        // one turn of marks, P1 still wide open) without tripping the win check, so
        // it's a live "now go score" state. The bot must put all marks on 20.
        val seats = players(2)
        // P0 has 3 marks on every target (closed all); P1 has thrown nothing.
        val p0Turn = com.dartrack.model.CricketTurn(CRICKET_TARGETS.associateWith { 3 })
        val perPlayer = listOf(
            com.dartrack.model.CricketPlayerState(seats[0], turns = listOf(p0Turn)),
            com.dartrack.model.CricketPlayerState(seats[1], turns = emptyList()),
        )
        val state = CricketState(players = seats, perPlayer = perPlayer, currentPlayerIndex = 0)

        assertTrue(!state.isFinished, "crafted live position is not finished")
        assertTrue(state.perPlayer[0].hasClosedAll(), "precondition: P0 closed all")
        assertTrue(!state.perPlayer[1].isClosed(20), "precondition: P1 still open on 20")

        val bot = DartsBot(BotLevel.PRO, seed = 3L)
        val visit = bot.cricketVisit(state, playerIndex = 0)
        // Marks must land on 20 (highest target an opponent has not closed), so the
        // extra marks score points.
        for (tgt in visit.keys) {
            assertEquals(20, tgt, "scoring should target 20, got $tgt → $visit")
        }
    }

    // -------------------------------------------------------------- calibration

    private fun meanMarks(level: BotLevel, n: Int = 2000, seed: Long = 2024L): Double {
        val bot = DartsBot(level, seed = seed)
        val state = freshState()
        var sum = 0L
        repeat(n) { sum += bot.cricketVisit(state, playerIndex = 0).values.sum() }
        return sum.toDouble() / n
    }

    @Test
    fun meanMarksPerVisit_increasesMonotonicallyWithLevel() {
        val easy = meanMarks(BotLevel.EASY)
        val medium = meanMarks(BotLevel.MEDIUM)
        val hard = meanMarks(BotLevel.HARD)
        val pro = meanMarks(BotLevel.PRO)
        assertTrue(easy < medium, "EASY ($easy) < MEDIUM ($medium)")
        assertTrue(medium < hard, "MEDIUM ($medium) < HARD ($hard)")
        assertTrue(hard < pro, "HARD ($hard) < PRO ($pro)")
        // PRO is meaningfully more productive than EASY.
        assertTrue(pro - easy >= 2.5, "PRO ($pro) should be well above EASY ($easy)")
        // Empirical means track the published per-level targets.
        assertTrue(kotlin.math.abs(easy - 1.7) < 0.4, "EASY mean $easy near ~1.7")
        assertTrue(kotlin.math.abs(pro - 5.0) < 0.5, "PRO mean $pro near ~5.0")
    }

    @Test
    fun analyticMarks_matchEmpirical_andAreMonotonic() {
        var prev = -1.0
        for (level in BotLevel.entries) {
            val bot = DartsBot(level, seed = 0L)
            val analytic = bot.expectedCricketMarksPerVisit()
            assertTrue(analytic > prev, "$level analytic $analytic must exceed previous $prev")
            prev = analytic
            val empirical = meanMarks(level, seed = 99L)
            assertTrue(
                kotlin.math.abs(analytic - empirical) < 0.3,
                "$level analytic $analytic vs empirical $empirical",
            )
        }
    }

    // -------------------------------------------------------------- determinism

    @Test
    fun sameSeed_producesIdenticalVisitSequence_overStateProgression() {
        val a = DartsBot(BotLevel.HARD, seed = 1234L)
        val b = DartsBot(BotLevel.HARD, seed = 1234L)
        var stateA = freshState()
        var stateB = freshState()
        repeat(300) {
            val va = a.cricketVisit(stateA, stateA.currentPlayerIndex)
            val vb = b.cricketVisit(stateB, stateB.currentPlayerIndex)
            assertEquals(va, vb, "visit $it must match for equal seeds")
            stateA = stateA.applyTurn(va)
            stateB = stateB.applyTurn(vb)
            if (stateA.isFinished) { stateA = freshState(); stateB = freshState() }
        }
    }

    @Test
    fun differentSeeds_diverge() {
        val a = DartsBot(BotLevel.MEDIUM, seed = 1L)
        val b = DartsBot(BotLevel.MEDIUM, seed = 2L)
        val s = freshState()
        val seqA = (0 until 200).map { a.cricketVisit(s, 0) }
        val seqB = (0 until 200).map { b.cricketVisit(s, 0) }
        assertTrue(seqA != seqB, "different seeds should not yield identical sequences")
    }

    // ----------------------------------------------------------- integration

    private fun playFullGame(seats: List<GamePlayer>, seed: Long, cap: Int = 500): CricketState {
        var state = CricketState.new(seats)
        val bots = seats.map { DartsBot(BotLevel.PRO, seed = seed) }
        var guard = 0
        while (!state.isFinished && guard < cap) {
            val idx = state.currentPlayerIndex
            val visit = bots[idx].cricketVisit(state, idx)
            state = state.applyTurn(visit)
            guard++
        }
        return state
    }

    @Test
    fun twoProBots_finishAFullCricketGame() {
        val state = playFullGame(players(2), seed = 99L)
        assertTrue(state.isFinished, "two PRO bots should finish Cricket within the cap")
        assertTrue(state.winnerIndices.isNotEmpty(), "a finished game has a winner")
        // The winner has genuinely closed every target.
        state.winnerIndices.forEach { w ->
            assertTrue(state.perPlayer[w].hasClosedAll(), "winner $w must have closed all targets")
        }
    }

    @Test
    fun fourProBots_finishAFullCricketGame() {
        val state = playFullGame(players(4), seed = 314L)
        assertTrue(state.isFinished, "four PRO bots should finish Cricket within the cap")
        assertTrue(state.winnerIndices.isNotEmpty(), "a finished game has a winner")
    }

    @Test
    fun integration_everyBotVisitIsAcceptedByEngine_acrossSeeds() {
        // Stress: several seeds, both 2p and 3p, every visit must apply cleanly and
        // the game must terminate within the cap.
        for (seed in listOf(1L, 2L, 7L, 42L, 100L)) {
            for (n in listOf(2, 3)) {
                val state = playFullGame(players(n), seed = seed)
                assertTrue(state.isFinished, "seed=$seed n=$n should finish")
            }
        }
    }

    @Test
    fun runningTallyClosesNumbersWithinAVisit_neverOverPilesEarly() {
        // A PRO from a crafted near-closed state (20 has 2 marks) should, on a
        // strong visit, close 20 (1 more mark) and then move down — i.e. it must
        // not dump all remaining darts onto 20 once it's closed.
        val seats = players(2)
        var state = CricketState.new(seats)
        state = state.applyTurn(mapOf(20 to 2)) // P0: 20 at 2/3
        state = state.applyTurn(emptyMap())      // P1
        // Only the FIRST dart can aim at 20 (it closes 20 with >=1 mark, or misses).
        // The aim re-evaluates between darts, so once 20 is closed the remaining
        // darts move to 19. A single dart adds at most 3 marks, so visit[20] <= 3,
        // and crucially across many seeds we must see 19 collecting marks too
        // (proof the bot moved on rather than dumping everything on 20).
        var sawNineteen = false
        for (seed in 0L until 500L) {
            val bot = DartsBot(BotLevel.PRO, seed = seed)
            val visit = bot.cricketVisit(state, 0)
            assertTrue(
                (visit[20] ?: 0) <= 3,
                "only one dart aims at 20 then it re-aims (seed=$seed) → $visit",
            )
            // Whenever 20 received any mark (so it closed), no further marks pile on
            // it beyond that single dart; lower targets must be 19/18 only.
            for (tgt in visit.keys) {
                assertTrue(tgt == 20 || tgt == 19 || tgt == 18, "got $tgt (seed=$seed)")
            }
            if ((visit[19] ?: 0) > 0) sawNineteen = true
        }
        assertTrue(sawNineteen, "across seeds the bot should move down to 19 after closing 20")
    }
}
