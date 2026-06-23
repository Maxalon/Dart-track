package com.dartrack.data

import com.dartrack.model.GameMode
import com.dartrack.model.GamePlayer
import com.dartrack.model.GameState
import com.dartrack.model.GolfState
import com.dartrack.model.GotchaState
import com.dartrack.model.X01State
import com.dartrack.model.bot.BotLevel
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Worked examples for the single-elimination bracket core ([singleEliminationMatches],
 * [createSingleEliminationTournament], [advanceBracket], [nextPlayableBracketMatch],
 * [bracketChampion], [bracketStandings], [reconcileBracketMatch], [syncedBracketWith]).
 * Bracket shapes, bye placement and advancement are checked against hand-computed
 * invariants (round count = log2(P), bye count = P - n, the canonical seed order)
 * so every assertion is verifiable on paper. Mirrors the style of [TournamentTest]
 * / [TournamentGameTest].
 */
class TournamentBracketTest {

    private fun human(name: String, id: String = name.lowercase()) =
        TournamentCompetitor(name = name, playerId = id, isBot = false)

    private fun cpu(name: String, level: BotLevel = BotLevel.HARD) =
        TournamentCompetitor(name = name, isBot = true, botLevel = level)

    private fun named(vararg names: String) = names.map { human(it) }

    /** A bracket tournament over `n` auto-named competitors A, B, C, …. */
    private fun bracket(n: Int, mode: GameMode = GameMode.X01): TournamentState {
        val names = (0 until n).map { ('A' + it).toString() }
        return createSingleEliminationTournament("se", "Cup", names.map { human(it) }, mode)
    }

    /** The single final (the match feeding nobody). */
    private fun finalOf(state: TournamentState): TournamentMatch =
        state.matches.single { it.feedsMatchId == null }

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

    /**
     * Play the bracket to completion by always advancing the lower competitor
     * index (so seed 0 ultimately wins every meeting it is in). Returns the final
     * state once nothing more is playable.
     */
    private fun playOutLowestWins(state: TournamentState): TournamentState {
        var t = state
        while (true) {
            val m = nextPlayableBracketMatch(t) ?: break
            val winner = minOf(m.homeIndex, m.awayIndex)
            t = advanceBracket(t, m.id, winner, gameId = null)
        }
        return t
    }

    // ---- seed order (the canonical bracket layout) ---------------------------

    @Test
    fun seedOrder_isTheStandardBracketLayout() {
        assertEquals(listOf(0, 1), seedOrder(2), "size 2")
        assertEquals(listOf(0, 3, 1, 2), seedOrder(4), "size 4: 1-vs-last, 2-vs-3")
        assertEquals(listOf(0, 7, 3, 4, 1, 6, 2, 5), seedOrder(8), "size 8 canonical order")
        // Every seed appears exactly once.
        assertEquals((0 until 8).toSet(), seedOrder(8).toSet(), "size 8 is a permutation")
    }

    @Test
    fun seedOrder_rejectsNonPowerOfTwo() {
        assertFailsWith<IllegalArgumentException> { seedOrder(3) }
        assertFailsWith<IllegalArgumentException> { seedOrder(6) }
    }

    // ---- bracket shape for powers of two (n = 2, 4, 8) -----------------------

    @Test
    fun shape_powerOfTwo_roundCountAndMatchCount() {
        // n=2 -> log2(2)=1 round, 1 match (the final).
        bracket(2).let { t ->
            assertEquals(1, t.matches.map { it.round }.max(), "n=2 -> 1 round")
            assertEquals(1, t.matches.size, "n=2 -> a single final match")
        }
        // n=4 -> 2 rounds, 2 + 1 = 3 matches.
        bracket(4).let { t ->
            assertEquals(2, t.matches.map { it.round }.max(), "n=4 -> 2 rounds")
            assertEquals(3, t.matches.size, "n=4 -> 3 matches (2 semis + final)")
            assertEquals(2, t.matches.count { it.round == 1 }, "two round-1 matches")
        }
        // n=8 -> 3 rounds, 4 + 2 + 1 = 7 matches.
        bracket(8).let { t ->
            assertEquals(3, t.matches.map { it.round }.max(), "n=8 -> 3 rounds")
            assertEquals(7, t.matches.size, "n=8 -> 7 matches total")
            assertEquals(4, t.matches.count { it.round == 1 }, "four round-1 matches")
            assertEquals(2, t.matches.count { it.round == 2 }, "two round-2 matches")
        }
    }

    @Test
    fun shape_exactlyOneFinalFeedingNull_everyOtherFeedsOneSlot() {
        for (n in intArrayOf(2, 4, 8)) {
            val t = bracket(n)
            val finals = t.matches.filter { it.feedsMatchId == null }
            assertEquals(1, finals.size, "n=$n: exactly one final (feeds null)")
            // Every non-final match feeds an existing match, into slot 0 or 1.
            val ids = t.matches.map { it.id }.toSet()
            t.matches.filter { it.feedsMatchId != null }.forEach { m ->
                assertTrue(m.feedsMatchId in ids, "n=$n: ${m.id} feeds a real match")
                assertTrue(m.feedsSlot == 0 || m.feedsSlot == 1, "n=$n: ${m.id} feeds slot 0/1")
            }
        }
    }

    @Test
    fun shape_eachNextRoundMatchIsFedByExactlyTwoSlots() {
        // For a full power-of-two bracket every match above round 1 is fed by two
        // distinct feeders, one into slot 0 and one into slot 1.
        for (n in intArrayOf(4, 8)) {
            val t = bracket(n)
            val feedersByTarget = t.matches
                .filter { it.feedsMatchId != null }
                .groupBy { it.feedsMatchId }
            for ((target, feeders) in feedersByTarget) {
                assertEquals(2, feeders.size, "n=$n: $target is fed by two matches")
                assertEquals(setOf(0, 1), feeders.map { it.feedsSlot }.toSet(), "n=$n: slots 0 and 1")
            }
        }
    }

    @Test
    fun shape_round1Seeding_topMatchPairsSeed0VsLast() {
        // n=8: the first round-1 match must be the 0-vs-7 pairing (seed order top).
        val t = bracket(8)
        val firstR1 = t.matches.first { it.round == 1 }
        assertEquals(0, minOf(firstR1.homeIndex, firstR1.awayIndex), "top match contains seed 0")
        assertEquals(7, maxOf(firstR1.homeIndex, firstR1.awayIndex), "seed 0 plays the last seed")
    }

    @Test
    fun shape_stableIds_seR_roundIndex() {
        val t = bracket(4)
        // Ids follow se-r<round>-<index>; the final is se-r2-0.
        assertTrue(t.matches.all { it.id.startsWith("se-r${it.round}-") }, "ids encode the round")
        assertEquals("se-r2-0", finalOf(t).id, "final id is se-r2-0")
        assertEquals(t.matches.size, t.matches.map { it.id }.toSet().size, "unique ids")
    }

    @Test
    fun shape_powerOfTwo_round1FullyRealNoTbdNoPrePlacement() {
        // A perfect power of two has no byes: every round-1 match has two real
        // sides and every later match starts TBD vs TBD.
        for (n in intArrayOf(2, 4, 8)) {
            val t = bracket(n)
            t.matches.filter { it.round == 1 }.forEach {
                assertTrue(it.homeIndex != TBD && it.awayIndex != TBD, "n=$n: round 1 fully seated")
            }
            t.matches.filter { it.round > 1 }.forEach {
                assertEquals(TBD, it.homeIndex, "n=$n: later round home starts TBD")
                assertEquals(TBD, it.awayIndex, "n=$n: later round away starts TBD")
            }
        }
    }

    // ---- byes (n = 3, 5, 6) --------------------------------------------------

    @Test
    fun byes_count_equalsNextPowerOfTwoMinusN() {
        // byes = P - n. The number of "missing" round-1 matches equals byes,
        // because each bye removes one round-1 pairing (a free pass).
        data class Case(val n: Int, val p: Int, val r1: Int)
        // n=3 -> P=4, byes=1, round-1 matches = 2-1 = 1.
        // n=5 -> P=8, byes=3, round-1 matches = 4-3 = 1.
        // n=6 -> P=8, byes=2, round-1 matches = 4-2 = 2.
        for (c in listOf(Case(3, 4, 1), Case(5, 8, 1), Case(6, 8, 2))) {
            val t = bracket(c.n)
            val byes = c.p - c.n
            assertEquals(c.r1, t.matches.count { it.round == 1 }, "n=${c.n}: ${c.r1} real round-1 matches")
            // Every real round-1 match has two real competitors (no phantom byes).
            t.matches.filter { it.round == 1 }.forEach {
                assertTrue(it.homeIndex != TBD && it.awayIndex != TBD, "n=${c.n}: no bye inside a match")
            }
            // The number of round-2 slots pre-filled by byes equals the bye count.
            val prePlaced = t.matches.filter { it.round == 2 }
                .sumOf { listOf(it.homeIndex, it.awayIndex).count { i -> i != TBD } }
            assertEquals(byes, prePlaced, "n=${c.n}: $byes seeds pre-placed into round 2")
        }
    }

    @Test
    fun byes_topSeedsArePrePlacedIntoRound2() {
        // n=3: seed 0 gets a bye and must appear pre-placed in a round-2 (final) slot;
        // seeds 1 and 2 play the single round-1 match.
        val t = bracket(3)
        val r1 = t.matches.filter { it.round == 1 }
        assertEquals(1, r1.size, "n=3: one real round-1 match")
        assertEquals(setOf(1, 2), setOf(r1.single().homeIndex, r1.single().awayIndex), "1 vs 2 play")
        // Seed 0 is already seated in the final.
        val fin = finalOf(t)
        assertTrue(0 in listOf(fin.homeIndex, fin.awayIndex), "seed 0 pre-placed into the final")
    }

    @Test
    fun byes_noPhantomPlayedMatches() {
        // A bye must never create a played match or a match referencing TBD as a
        // real competitor; nothing is played in a fresh bracket.
        for (n in intArrayOf(3, 5, 6)) {
            val t = bracket(n)
            assertTrue(t.matches.none { it.played }, "n=$n: nothing played on creation")
            // No emitted match has a bye sitting inside it as a competitor.
            t.matches.forEach {
                if (it.round == 1) {
                    assertTrue(it.homeIndex != TBD && it.awayIndex != TBD, "n=$n: round 1 has no TBD")
                }
            }
        }
    }

    @Test
    fun byes_n5_seedsPrePlacedAreLowSeeds() {
        // n=5 -> P=8, byes for seeds 0,1,2 (the three lowest); seed 3 vs 4 is the
        // only real round-1 match.
        val t = bracket(5)
        val r1 = t.matches.filter { it.round == 1 }
        assertEquals(1, r1.size, "n=5: a single round-1 match")
        assertEquals(setOf(3, 4), setOf(r1.single().homeIndex, r1.single().awayIndex), "3 vs 4")
        val prePlacedSeeds = t.matches.filter { it.round == 2 }
            .flatMap { listOf(it.homeIndex, it.awayIndex) }
            .filter { it != TBD }
            .toSet()
        assertEquals(setOf(0, 1, 2), prePlacedSeeds, "seeds 0,1,2 byed into round 2")
    }

    // ---- advanceBracket: placement + full play -------------------------------

    @Test
    fun advanceBracket_placesWinnerIntoFedSlot() {
        // n=4: play both semis, check the winners land in the correct final slots.
        val t = bracket(4)
        val semis = t.matches.filter { it.round == 1 }
        val s0 = semis[0] // feedsSlot for each is its own slot into the final
        val s1 = semis[1]

        var st = advanceBracket(t, s0.id, minOf(s0.homeIndex, s0.awayIndex), gameId = null)
        st = advanceBracket(st, s1.id, minOf(s1.homeIndex, s1.awayIndex), gameId = null)

        val fin = finalOf(st)
        val expectHome = if (s0.feedsSlot == 0) minOf(s0.homeIndex, s0.awayIndex) else minOf(s1.homeIndex, s1.awayIndex)
        val expectAway = if (s0.feedsSlot == 1) minOf(s0.homeIndex, s0.awayIndex) else minOf(s1.homeIndex, s1.awayIndex)
        assertEquals(expectHome, fin.homeIndex, "semi feeding slot 0 placed home")
        assertEquals(expectAway, fin.awayIndex, "semi feeding slot 1 placed away")
    }

    @Test
    fun advanceBracket_marksPlayedAndKeepsGameId() {
        val t = bracket(2)
        val m = t.matches.single()
        val out = advanceBracket(t, m.id, m.homeIndex, gameId = "g-1")
        val played = out.matches.single()
        assertTrue(played.played, "match marked played")
        assertEquals(m.homeIndex, played.winnerIndex, "winner recorded")
        assertEquals("g-1", played.gameId, "game id attached")
    }

    @Test
    fun advanceBracket_fullPlay_yieldsOneChampion() {
        for (n in intArrayOf(2, 3, 4, 5, 6, 8)) {
            val played = playOutLowestWins(bracket(n))
            assertNull(nextPlayableBracketMatch(played), "n=$n: bracket fully resolved")
            // Lowest index always advances -> seed 0 is the champion.
            assertEquals(0, bracketChampion(played), "n=$n: seed 0 wins the bracket")
            assertTrue(finalOf(played).played, "n=$n: the final was played")
        }
    }

    @Test
    fun advanceBracket_rejectsBogusWinner() {
        val t = bracket(4)
        val semi = t.matches.first { it.round == 1 }
        // 99 is not one of this match's two competitors.
        assertFailsWith<IllegalArgumentException> {
            advanceBracket(t, semi.id, winnerIndex = 99, gameId = null)
        }
        // The final's slots are TBD, so even "TBD" is its only "competitor" and a
        // real seed is rejected before its feeders resolve.
        assertFailsWith<IllegalArgumentException> {
            advanceBracket(t, finalOf(t).id, winnerIndex = 0, gameId = null)
        }
    }

    @Test
    fun advanceBracket_unknownMatchIsNoOp_andIdempotent() {
        var t = bracket(4)
        assertEquals(t, advanceBracket(t, "no-such", 0, null), "unknown id -> unchanged")
        val semi = t.matches.first { it.round == 1 }
        val w = minOf(semi.homeIndex, semi.awayIndex)
        val once = advanceBracket(t, semi.id, w, "g")
        val twice = advanceBracket(once, semi.id, w, "g")
        assertEquals(once, twice, "re-advancing the same winner is idempotent")
    }

    // ---- nextPlayableBracketMatch -------------------------------------------

    @Test
    fun nextPlayable_skipsTbdMatches() {
        val t = bracket(4)
        // The final has TBD slots and must be skipped in favour of a round-1 match.
        val next = nextPlayableBracketMatch(t)
        assertNotNull(next, "a fresh bracket has a playable match")
        assertEquals(1, next!!.round, "round-1 served before the TBD final")
        assertTrue(next.homeIndex != TBD && next.awayIndex != TBD, "playable match is fully seated")
    }

    @Test
    fun nextPlayable_returnsByeSeededFinalOnceItsOtherFeederResolves() {
        // n=3: after the single semi (1 vs 2) is played, both final slots are real
        // (seed 0 by bye + the semi winner) so the final becomes playable.
        var t = bracket(3)
        val semi = t.matches.single { it.round == 1 }
        assertEquals(semi.id, nextPlayableBracketMatch(t)!!.id, "the semi is first to play")
        t = advanceBracket(t, semi.id, minOf(semi.homeIndex, semi.awayIndex), null)
        val next = nextPlayableBracketMatch(t)
        assertEquals(finalOf(t).id, next!!.id, "the final is now playable")
        assertTrue(next.homeIndex != TBD && next.awayIndex != TBD, "final fully seated")
    }

    // ---- bracketChampion -----------------------------------------------------

    @Test
    fun bracketChampion_nullUntilFinalPlayed() {
        var t = bracket(4)
        assertNull(bracketChampion(t), "no champion on creation")
        // Play just the semis: the final is seated but unplayed -> still no champion.
        for (semi in t.matches.filter { it.round == 1 }) {
            t = advanceBracket(t, semi.id, minOf(semi.homeIndex, semi.awayIndex), null)
        }
        assertNull(bracketChampion(t), "no champion until the final is played")
        val fin = finalOf(t)
        t = advanceBracket(t, fin.id, minOf(fin.homeIndex, fin.awayIndex), null)
        assertEquals(0, bracketChampion(t), "champion appears once the final is played")
    }

    // ---- bracketStandings ----------------------------------------------------

    @Test
    fun bracketStandings_championDeepestAndNotEliminated_firstRoundLosersOutEarly() {
        // n=4: seed 0 wins it all; the two round-1 losers go out at round 1.
        val t = playOutLowestWins(bracket(4))
        val rows = bracketStandings(t).associateBy { it.competitorIndex }

        val champ = rows.getValue(0)
        assertFalse(champ.eliminated, "champion is not eliminated")
        assertEquals(finalOf(t).round, champ.roundReached, "champion reached the final round")
        // Highest roundReached in the field belongs to the champion.
        assertEquals(champ.roundReached, rows.values.maxOf { it.roundReached }, "champion advanced furthest")

        // In a 4-bracket the round-1 pairings are (0v3) and (1v2): seeds 2 and 3 lose
        // in round 1 and are eliminated having never won (roundReached 0).
        for (loser in intArrayOf(2, 3)) {
            assertTrue(rows.getValue(loser).eliminated, "seed $loser eliminated")
            assertEquals(0, rows.getValue(loser).roundReached, "first-round loser never advanced")
        }
        // Seed 1 reached the final (won its semi) before losing to seed 0.
        assertTrue(rows.getValue(1).eliminated, "runner-up is eliminated")
        assertEquals(1, rows.getValue(1).roundReached, "runner-up won round 1")
    }

    @Test
    fun bracketStandings_freshBracketHasNobodyEliminated() {
        val rows = bracketStandings(bracket(8))
        assertTrue(rows.all { !it.eliminated && it.roundReached == 0 }, "nothing decided yet")
        assertEquals(8, rows.size, "one row per competitor")
    }

    // ---- reconcileBracketMatch (via real finished games) ---------------------

    @Test
    fun reconcileBracketMatch_homeSeatWin_advancesHomeCompetitor() {
        val t = bracket(4)
        val semi = t.matches.first { it.round == 1 }.copy(gameId = "g-1")
        val state = t.copy(matches = t.matches.toMutableList().also { it[t.matches.indexOf(t.matches.first { m -> m.round == 1 })] = semi })
        val game = finishedRecord(state, semi, "g-1", seats = listOf(0)) // home seat wins

        val out = reconcileBracketMatch(state, semi.id, game)
        val played = out.matches.first { it.id == semi.id }
        assertTrue(played.played, "match recorded")
        assertEquals(semi.homeIndex, played.winnerIndex, "seat 0 -> home competitor")
        // The winner is promoted into the final slot the semi feeds.
        val fin = finalOf(out)
        assertTrue(semi.homeIndex in listOf(fin.homeIndex, fin.awayIndex), "home competitor advanced to final")
    }

    @Test
    fun reconcileBracketMatch_awaySeatWin_advancesAwayCompetitor() {
        val t = bracket(2)
        val m = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(m))
        val game = finishedRecord(state, m, "g-1", seats = listOf(1)) // away seat wins

        val out = reconcileBracketMatch(state, m.id, game)
        assertEquals(m.awayIndex, out.matches.single().winnerIndex, "seat 1 -> away competitor")
        assertEquals(m.awayIndex, bracketChampion(out), "away competitor is champion")
    }

    @Test
    fun reconcileBracketMatch_naturalGotchaWin_advancesHome() {
        // Drive a real Gotcha game to a home-seat win and fold it in.
        val t = bracket(2, mode = GameMode.GOTCHA)
        val m = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(m))
        var g = buildMatchGameRecord(state, m, "g-1", 1L).state as GotchaState
        g = g.applyTurn(180) // seat 0 -> 180
        g = g.applyTurn(0)   // seat 1 -> 0
        g = g.applyTurn(121) // seat 0 -> 301 -> win
        assertEquals(listOf(0), g.winnerIndices, "seat 0 reaches the target")
        val game = buildMatchGameRecord(state, m, "g-1", 1L).copy(state = g)

        val out = reconcileBracketMatch(state, m.id, game)
        assertEquals(m.homeIndex, out.matches.single().winnerIndex, "home advances")
    }

    @Test
    fun reconcileBracketMatch_knockoutDraw_leavesMatchUnplayed() {
        // A Golf tie reports both seats; a knockout cannot decide a winner, so the
        // match stays UNPLAYED (no advancement) — the documented draw handling.
        val t = bracket(4, mode = GameMode.GOLF)
        val semi = t.matches.first { it.round == 1 }.copy(gameId = "g-1")
        val state = t.copy(matches = t.matches.toMutableList().also { list ->
            list[list.indexOfFirst { it.round == 1 }] = semi
        })
        val game = finishedRecord(state, semi, "g-1", seats = listOf(0, 1)) // tie

        val out = reconcileBracketMatch(state, semi.id, game)
        assertEquals(state, out, "a knockout draw records nothing")
        assertFalse(out.matches.first { it.id == semi.id }.played, "the match remains unplayed")
        // The fed final slot is untouched (still TBD on both sides of nobody advanced).
        assertNull(bracketChampion(out), "no progress toward a champion")
    }

    @Test
    fun reconcileBracketMatch_naturalGolfTie_leavesMatchUnplayed() {
        // Same draw rule, but via the engine's own tie path (identical play).
        val t = bracket(2, mode = GameMode.GOLF)
        val m = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(m))
        var golf = buildMatchGameRecord(state, m, "g-1", 1L).state as GolfState
        repeat(com.dartrack.model.GOLF_HOLES * 2) {
            golf = golf.applyResult(com.dartrack.model.GolfResult.SINGLE)
        }
        assertEquals(listOf(0, 1), golf.winnerIndices, "identical play ties both seats")
        val game = buildMatchGameRecord(state, m, "g-1", 1L).copy(state = golf)

        val out = reconcileBracketMatch(state, m.id, game)
        assertEquals(state, out, "engine tie -> match left unplayed in a knockout")
    }

    @Test
    fun reconcileBracketMatch_unfinishedOrMismatchedOrUnknown_noChange() {
        val t = bracket(2)
        val m = t.matches.single().copy(gameId = "g-1")
        val state = t.copy(matches = listOf(m))

        val unfinished = buildMatchGameRecord(state, m, "g-1", 1L)
        assertEquals(state, reconcileBracketMatch(state, m.id, unfinished), "unfinished -> no change")

        val mismatched = finishedRecord(state, m, gameId = "OTHER", seats = listOf(0))
        assertEquals(state, reconcileBracketMatch(state, m.id, mismatched), "unlinked game -> no change")

        val ok = finishedRecord(state, m, "g-1", seats = listOf(0))
        assertEquals(state, reconcileBracketMatch(state, "no-such", ok), "unknown match -> no change")
    }

    // ---- syncedBracketWith ---------------------------------------------------

    @Test
    fun syncedBracketWith_cascadesAcrossRoundsInOnePass() {
        // n=4: link all three matches to finished games. Even though the final's
        // feeders only resolve mid-fold, the natural round order means the final is
        // reconciled in the same pass.
        val t = bracket(4)
        val ms = t.matches
        val s0 = ms.first { it.round == 1 }
        val s1 = ms.filter { it.round == 1 }[1]
        val fin = finalOf(t)
        val linked = t.copy(matches = ms.map {
            when (it.id) {
                s0.id -> it.copy(gameId = "g-${s0.id}")
                s1.id -> it.copy(gameId = "g-${s1.id}")
                fin.id -> it.copy(gameId = "g-${fin.id}")
                else -> it
            }
        })
        // Home seat wins both semis; the resulting final is decided by its home seat.
        val gS0 = finishedRecord(linked, linked.matches.first { it.id == s0.id }, "g-${s0.id}", listOf(0))
        val gS1 = finishedRecord(linked, linked.matches.first { it.id == s1.id }, "g-${s1.id}", listOf(0))
        // The final's slots are still TBD here, so build its record against a
        // temporarily-seated copy (any real seats work — reconcile reports the
        // winner by SEAT and maps it onto the final's LIVE competitors mid-fold).
        val seatedFinal = fin.copy(homeIndex = 0, awayIndex = 1, gameId = "g-${fin.id}")
        val gFin = finishedRecord(linked, seatedFinal, "g-${fin.id}", listOf(0))

        val out = linked.syncedBracketWith(listOf(gS0, gS1, gFin))
        assertTrue(out.matches.all { it.played }, "all three matches reconciled in one pass")
        assertNotNull(bracketChampion(out), "a champion emerges from the fold")
    }

    @Test
    fun syncedBracketWith_isIdempotent_andSkipsUnfinished() {
        val t = bracket(4)
        val s0 = t.matches.first { it.round == 1 }
        val s1 = t.matches.filter { it.round == 1 }[1]
        val linked = t.copy(matches = t.matches.map {
            when (it.id) {
                s0.id -> it.copy(gameId = "g0")
                s1.id -> it.copy(gameId = "g1")
                else -> it
            }
        })
        val gFinished = finishedRecord(linked, linked.matches.first { it.id == s0.id }, "g0", listOf(0))
        val gInProgress = buildMatchGameRecord(linked, linked.matches.first { it.id == s1.id }, "g1", 1L)

        val once = linked.syncedBracketWith(listOf(gFinished, gInProgress))
        assertTrue(once.matches.first { it.id == s0.id }.played, "finished semi reconciled")
        assertFalse(once.matches.first { it.id == s1.id }.played, "in-progress semi skipped")
        val twice = once.syncedBracketWith(listOf(gFinished, gInProgress))
        assertEquals(once, twice, "syncedBracketWith is idempotent")
    }

    // ---- createSingleEliminationTournament guards ----------------------------

    @Test
    fun createSingleElimination_setsFormatAndRejectsTooFew() {
        val t = createSingleEliminationTournament("se", "Cup", named("A", "B"), GameMode.X01)
        assertEquals(TournamentFormat.SINGLE_ELIMINATION, t.format, "format is knockout")
        assertFailsWith<IllegalArgumentException> {
            createSingleEliminationTournament("se", "Cup", listOf(human("Solo")), GameMode.X01)
        }
        assertFailsWith<IllegalArgumentException> { singleEliminationMatches(1) }
    }

    // ---- JSON round-trip (format + feeds fields) -----------------------------

    @Test
    fun jsonRoundTrip_preservesBracketIncludingFormatAndFeeds() {
        var t = createSingleEliminationTournament(
            "se-json", "Persisted",
            listOf(human("Alice", "alice"), cpu("CPU 1 (Hard)", BotLevel.HARD), human("Cy"), human("Dee")),
            GameMode.CRICKET,
            createdAtEpochMs = 42L,
        )
        // Record one result so a winnerIndex + gameId + an advanced slot all persist.
        val semi = t.matches.first { it.round == 1 }
        t = advanceBracket(t, semi.id, minOf(semi.homeIndex, semi.awayIndex), gameId = "g-1")

        val text = encodeTournamentStore(listOf(t))
        val decoded = decodeTournamentStore(text)
        assertEquals(1, decoded.size, "one tournament survives the round-trip")
        assertEquals(t, decoded.single(), "state is byte-for-byte equal after decode")

        val back = decoded.single()
        assertEquals(TournamentFormat.SINGLE_ELIMINATION, back.format, "format preserved")
        // Feeder wiring survives.
        assertNull(finalOf(back).feedsMatchId, "final still feeds null")
        assertTrue(back.matches.any { it.feedsMatchId != null }, "feeder links preserved")
        val playedBack = back.matches.first { it.id == semi.id }
        assertEquals(minOf(semi.homeIndex, semi.awayIndex), playedBack.winnerIndex, "winner preserved")
        assertEquals("g-1", playedBack.gameId, "game id preserved")
    }

    // ---- back-compat: old (no-format) tournament decodes as a league ---------

    @Test
    fun backCompat_oldTournamentJson_decodesAsRoundRobinWithDefaults() {
        // A pre-feature state: no `format` on the state, no `feeds*` on the match.
        // It must decode with format=ROUND_ROBIN and feeds defaults, unchanged.
        val legacy = """
            {
              "schemaVersion": ${TournamentStore.SCHEMA_VERSION},
              "tournaments": [
                {
                  "id": "old",
                  "name": "Legacy League",
                  "competitors": [
                    {"name": "Ann", "playerId": "ann"},
                    {"name": "Bob", "playerId": "bob"}
                  ],
                  "mode": "X01",
                  "matches": [
                    {"id": "m-0-0-1", "round": 0, "homeIndex": 0, "awayIndex": 1,
                     "winnerIndex": 0, "played": true}
                  ]
                }
              ]
            }
        """.trimIndent()

        val decoded = decodeTournamentStore(legacy)
        assertEquals(1, decoded.size, "legacy store decodes")
        val t = decoded.single()
        assertEquals(TournamentFormat.ROUND_ROBIN, t.format, "missing format -> ROUND_ROBIN default")
        assertEquals(3, t.pointsForWin, "missing points -> league default")
        val m = t.matches.single()
        assertNull(m.feedsMatchId, "missing feedsMatchId -> null default")
        assertEquals(0, m.feedsSlot, "missing feedsSlot -> 0 default")
        assertTrue(m.played, "recorded result preserved")
        assertEquals(0, m.winnerIndex, "winner preserved")
        // And the league functions still operate on it unchanged.
        assertEquals(listOf(0), champions(t), "round-robin champion still computed")
    }

    @Test
    fun backCompat_oldMatchShape_defaultsFeedsFields() {
        // A TournamentMatch JSON with none of the new fields decodes with defaults.
        val json = """{"id":"m-0-0-1","round":0,"homeIndex":0,"awayIndex":1}"""
        val m = GameJson.format.decodeFromString(TournamentMatch.serializer(), json)
        assertNull(m.feedsMatchId, "feedsMatchId defaults to null")
        assertEquals(0, m.feedsSlot, "feedsSlot defaults to 0")
        assertFalse(m.played, "played defaults to false")
    }
}
